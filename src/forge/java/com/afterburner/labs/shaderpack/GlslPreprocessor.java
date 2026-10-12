package com.afterburner.labs.shaderpack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A C preprocessor for shader pack sources: #include, object and function-like #define, #undef, #if/#ifdef/#ifndef/#elif/
 * #else/#endif with full expressions, plus #version and #extension, which are collected rather than kept. The output has no
 * directives and no comments; comments on active lines are kept aside, because packs put settings in them
 * ({@code DRAWBUFFERS}, {@code const int colortex0Format = ...}).
 * <p>
 * In {@link Mode#PROPERTIES} only the conditionals are run (shaders.properties, block.properties): other lines starting with
 * '#' are comments and nothing is expanded.
 */
public final class GlslPreprocessor {
	public enum Mode { GLSL, PROPERTIES }

	/** Reads pack files by absolute path; null if missing. */
	public interface FileSource {
		@Nullable String read(String path) throws IOException;
	}

	public static final class Macro {
		final String name;
		/** Null for an object-like macro. */
		final @Nullable List<String> params;
		final boolean variadic;
		final List<Token> body;

		Macro(String name, @Nullable List<String> params, boolean variadic, List<Token> body) {
			this.name = name;
			this.params = params;
			this.variadic = variadic;
			this.body = body;
		}

		/** The body as written, for tools. */
		public String body() {
			return join(this.body).trim();
		}
	}

	/** A comment on an active line; {@code line} is the index of the output line it sits on or before. */
	public record Comment(int line, String text) {}

	public record Extension(String name, String behavior) {}

	public static final class Result {
		/** Output lines, without newlines. */
		public final List<String> lines;
		/** Where each output line came from, "file:line". */
		public final List<String> origins;
		public final List<Comment> comments;
		/** The first #version's text ("120", "330 compatibility"), or null. */
		public final @Nullable String version;
		public final List<Extension> extensions;
		public final Map<String, Macro> macros;
		public final List<String> warnings;

		Result(List<String> lines, List<String> origins, List<Comment> comments, @Nullable String version, List<Extension> extensions,
				Map<String, Macro> macros, List<String> warnings) {
			this.lines = lines;
			this.origins = origins;
			this.comments = comments;
			this.version = version;
			this.extensions = extensions;
			this.macros = macros;
			this.warnings = warnings;
		}

		public String text() {
			StringBuilder out = new StringBuilder();
			for (String line : this.lines) out.append(line).append('\n');
			return out.toString();
		}
	}

	public static final class PreprocessException extends Exception {
		public PreprocessException(String message) {
			super(message);
		}
	}

	private static final int MAX_INCLUDE_DEPTH = 64;

	private final FileSource files;
	private final Mode mode;
	private final Map<String, Macro> macros = new HashMap<>();
	private final List<String> lines = new ArrayList<>();
	private final List<String> origins = new ArrayList<>();
	private final List<Comment> comments = new ArrayList<>();
	private final List<Extension> extensions = new ArrayList<>();
	private final List<String> warnings = new ArrayList<>();
	private final List<Cond> conds = new ArrayList<>();
	private @Nullable String version;
	private String file = "";
	private int lineNo;

	public GlslPreprocessor(FileSource files, Mode mode) {
		this.files = files;
		this.mode = mode;
	}

	/** Defines a macro as if by "#define name value". */
	public void define(String name, String value) {
		this.macros.put(name, new Macro(name, null, false, tokenize(value)));
	}

	/** Takes over macros another run ended with (the pack's options, for its .properties files). */
	public void defineAll(Map<String, Macro> macros) {
		this.macros.putAll(macros);
	}

	public boolean isDefined(String name) {
		return this.macros.containsKey(name);
	}

	/** Runs a file. One preprocessor runs one file. */
	public Result process(String path) throws PreprocessException {
		String text;
		try {
			text = this.files.read(path);
		} catch (IOException e) {
			throw new PreprocessException("Can't read " + path + ": " + e);
		}
		if (text == null) throw new PreprocessException("Missing file " + path);
		return this.processText(path, text);
	}

	/** Runs the given text as if it were the file at {@code path}. */
	public Result processText(String path, String text) throws PreprocessException {
		this.runFile(path, text, 0);
		return new Result(this.lines, this.origins, this.comments, this.version, this.extensions, this.macros, this.warnings);
	}

	// ---- Files and lines ----

	private record Cond(boolean parentActive, boolean active, boolean taken, boolean sawElse) {}

	private boolean active() {
		return this.conds.isEmpty() || this.conds.get(this.conds.size() - 1).active;
	}

	private void runFile(String path, String text, int depth) throws PreprocessException {
		if (depth > MAX_INCLUDE_DEPTH) throw new PreprocessException("Includes nested too deep at " + path);
		String outerFile = this.file;
		int outerLine = this.lineNo;
		int condDepth = this.conds.size();
		this.file = path;

		List<String> raw = splitLines(text);
		boolean inBlockComment = false;
		StringBuilder pending = null;
		String pendingOrigin = null;
		for (int i = 0; i < raw.size(); i++) {
			this.lineNo = i + 1;
			String line = raw.get(i);
			// Backslash at the end of a line joins the next one; only a line's own backslash counts (Bliss's comment ending in
			// three, then an empty line, joins just that line, as drivers read it).
			while (raw.get(i).endsWith("\\") && i + 1 < raw.size()) {
				line = line.substring(0, line.length() - 1) + raw.get(++i);
			}

			String code;
			List<String> lineComments = new ArrayList<>();
			if (this.mode == Mode.GLSL) {
				StringBuilder codeOut = new StringBuilder();
				inBlockComment = stripComments(line, inBlockComment, codeOut, lineComments);
				code = codeOut.toString();
			} else {
				code = line;
			}

			String trimmed = code.strip();
			if (trimmed.startsWith("#")) {
				if (pending != null) throw this.error("Directive inside a macro call");
				boolean wasActive = this.active();
				this.directive(trimmed.substring(1).strip(), depth);
				if (wasActive) this.addComments(lineComments);
				continue;
			}
			if (!this.active()) continue;
			this.addComments(lineComments);
			if (this.mode == Mode.PROPERTIES) {
				this.emit(code, path + ":" + this.lineNo);
				continue;
			}
			if (trimmed.isEmpty() && pending == null) continue;

			String origin = pending != null ? pendingOrigin : path + ":" + this.lineNo;
			String source = pending != null ? pending.append(' ').append(code).toString() : code;
			try {
				String expanded = join(this.expand(tokenize(source), Set.of(), false));
				pending = null;
				if (!expanded.isBlank()) this.emit(expanded, origin);
			} catch (Incomplete e) {
				pending = new StringBuilder(source);
				pendingOrigin = origin;
			}
		}
		if (pending != null) {
			// Last line ended in a function-like macro's name with no call: keep it as it is.
			this.emit(join(this.expand(tokenize(pending.toString()), Set.of(), true)), pendingOrigin);
		}
		if (this.conds.size() != condDepth) throw this.error("#if without #endif");
		this.file = outerFile;
		this.lineNo = outerLine;
	}

	private void emit(String line, String origin) {
		this.lines.add(line);
		this.origins.add(origin);
	}

	private void addComments(List<String> lineComments) {
		for (String comment : lineComments) this.comments.add(new Comment(this.lines.size(), comment));
	}

	private static List<String> splitLines(String text) {
		List<String> out = new ArrayList<>();
		int start = 0;
		int n = text.length();
		for (int i = 0; i < n; i++) {
			char c = text.charAt(i);
			if (c == '\n' || c == '\r') {
				out.add(text.substring(start, i));
				if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
				start = i + 1;
			}
		}
		if (start < n) out.add(text.substring(start));
		return out;
	}

	/** Copies a line's code to {@code code}, comments become a space; returns whether a block comment is still open. */
	private static boolean stripComments(String line, boolean inBlock, StringBuilder code, List<String> comments) {
		int n = line.length();
		int i = 0;
		boolean directive = !inBlock && line.stripLeading().startsWith("#");
		StringBuilder comment = inBlock ? new StringBuilder() : null;
		while (i < n) {
			char c = line.charAt(i);
			if (comment != null) {
				if (c == '*' && i + 1 < n && line.charAt(i + 1) == '/') {
					comments.add(comment.toString());
					comment = null;
					code.append(' ');
					i += 2;
				} else {
					comment.append(c);
					i++;
				}
				continue;
			}
			if (directive && c == '"') {
				int end = line.indexOf('"', i + 1);
				if (end < 0) end = n - 1;
				code.append(line, i, end + 1);
				i = end + 1;
				continue;
			}
			if (c == '/' && i + 1 < n) {
				char d = line.charAt(i + 1);
				if (d == '/') {
					comments.add(line.substring(i + 2));
					code.append(' ');
					return false;
				}
				if (d == '*') {
					comment = new StringBuilder();
					i += 2;
					continue;
				}
			}
			code.append(c);
			i++;
		}
		if (comment != null) {
			comments.add(comment.toString());
			return true;
		}
		return false;
	}

	// ---- Directives ----

	private void directive(String text, int depth) throws PreprocessException {
		int nameEnd = 0;
		while (nameEnd < text.length() && Character.isLetter(text.charAt(nameEnd))) nameEnd++;
		String name = text.substring(0, nameEnd);
		String rest = text.substring(nameEnd).strip();
		boolean active = this.active();

		switch (name) {
			case "if", "ifdef", "ifndef" -> {
				boolean value = false;
				if (active) {
					value = switch (name) {
						case "ifdef" -> this.macros.containsKey(firstIdentifier(rest));
						case "ifndef" -> !this.macros.containsKey(firstIdentifier(rest));
						default -> this.evaluate(rest);
					};
				}
				this.conds.add(new Cond(active, active && value, active && value, false));
			}
			case "elif" -> {
				Cond top = this.top("#elif");
				if (top.sawElse) throw this.error("#elif after #else");
				boolean value = top.parentActive && !top.taken && this.evaluate(rest);
				this.conds.set(this.conds.size() - 1, new Cond(top.parentActive, value, top.taken || value, false));
			}
			case "else" -> {
				Cond top = this.top("#else");
				if (top.sawElse) throw this.error("Second #else");
				boolean value = top.parentActive && !top.taken;
				this.conds.set(this.conds.size() - 1, new Cond(top.parentActive, value, true, true));
			}
			case "endif" -> {
				this.top("#endif");
				this.conds.remove(this.conds.size() - 1);
			}
			default -> {
				if (!active) return;
				switch (name) {
					case "define" -> this.defineDirective(rest);
					case "undef" -> this.macros.remove(firstIdentifier(rest));
					case "include" -> this.include(rest, depth);
					case "version" -> {
						if (this.mode == Mode.GLSL && this.version == null) {
							this.version = rest;
							String number = firstNumber(rest);
							if (number != null) this.define("__VERSION__", number);
						}
					}
					case "extension" -> {
						if (this.mode == Mode.GLSL) {
							int colon = rest.indexOf(':');
							String ext = (colon < 0 ? rest : rest.substring(0, colon)).strip();
							this.extensions.add(new Extension(ext, colon < 0 ? "enable" : rest.substring(colon + 1).strip()));
						}
					}
					case "error" -> {
						if (this.mode == Mode.GLSL) throw this.error("#error " + rest);
					}
					case "pragma", "line", "" -> {
					}
					default -> {
						if (this.mode == Mode.GLSL) this.warnings.add(this.where() + ": unknown directive #" + name);
					}
				}
			}
		}
	}

	private Cond top(String directive) throws PreprocessException {
		if (this.conds.isEmpty()) throw this.error(directive + " without #if");
		return this.conds.get(this.conds.size() - 1);
	}

	private void defineDirective(String rest) throws PreprocessException {
		int i = 0;
		int n = rest.length();
		while (i < n && isIdentPart(rest.charAt(i))) i++;
		if (i == 0) throw this.error("#define without a name");
		String name = rest.substring(0, i);
		List<String> params = null;
		boolean variadic = false;
		if (i < n && rest.charAt(i) == '(') {
			int close = rest.indexOf(')', i);
			if (close < 0) throw this.error("Unclosed parameter list for " + name);
			params = new ArrayList<>();
			for (String param : rest.substring(i + 1, close).split(",")) {
				String p = param.strip();
				if (p.isEmpty()) continue;
				if (p.equals("...")) {
					variadic = true;
					p = "__VA_ARGS__";
				}
				params.add(p);
			}
			i = close + 1;
		}
		this.macros.put(name, new Macro(name, params, variadic, tokenize(rest.substring(i).strip())));
	}

	private void include(String rest, int depth) throws PreprocessException {
		String target;
		if (rest.startsWith("\"") && rest.indexOf('"', 1) > 0) {
			target = rest.substring(1, rest.indexOf('"', 1));
		} else if (rest.startsWith("<") && rest.indexOf('>') > 0) {
			target = rest.substring(1, rest.indexOf('>'));
		} else {
			throw this.error("Bad #include " + rest);
		}
		String path = PackFiles.resolve(this.file, target);
		if (path == null) throw this.error("Include outside the pack: " + target);
		String text;
		try {
			text = this.files.read(path);
		} catch (IOException e) {
			throw this.error("Can't read " + path + ": " + e);
		}
		if (text == null) throw this.error("Missing include " + path);
		this.runFile(path, text, depth + 1);
	}

	private String where() {
		return this.file + ":" + this.lineNo;
	}

	private PreprocessException error(String message) {
		return new PreprocessException(this.where() + ": " + message);
	}

	private static String firstIdentifier(String text) {
		int i = 0;
		while (i < text.length() && !isIdentStart(text.charAt(i))) i++;
		int start = i;
		while (i < text.length() && isIdentPart(text.charAt(i))) i++;
		return text.substring(start, i);
	}

	private static @Nullable String firstNumber(String text) {
		int i = 0;
		while (i < text.length() && !Character.isDigit(text.charAt(i))) i++;
		int start = i;
		while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
		return start == i ? null : text.substring(start, i);
	}

	// ---- Tokens and expansion ----

	enum Kind { IDENT, NUMBER, SPACE, OTHER }

	record Token(Kind kind, String text) {
		boolean is(String s) {
			return this.kind == Kind.OTHER && this.text.equals(s);
		}
	}

	private static final Token SPACE = new Token(Kind.SPACE, " ");

	static boolean isIdentStart(char c) {
		return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	static boolean isIdentPart(char c) {
		return isIdentStart(c) || (c >= '0' && c <= '9');
	}

	static List<Token> tokenize(String text) {
		List<Token> out = new ArrayList<>();
		int n = text.length();
		int i = 0;
		while (i < n) {
			char c = text.charAt(i);
			int start = i;
			if (isIdentStart(c)) {
				while (i < n && isIdentPart(text.charAt(i))) i++;
				out.add(new Token(Kind.IDENT, text.substring(start, i)));
			} else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(text.charAt(i + 1)))) {
				// A preprocessing number: digits, letters, '.', and a sign right after an exponent.
				i++;
				while (i < n) {
					char d = text.charAt(i);
					if (isIdentPart(d) || d == '.') {
						i++;
					} else if ((d == '+' || d == '-') && (text.charAt(i - 1) == 'e' || text.charAt(i - 1) == 'E') && !isHex(text, start)) {
						i++;
					} else {
						break;
					}
				}
				out.add(new Token(Kind.NUMBER, text.substring(start, i)));
			} else if (Character.isWhitespace(c)) {
				while (i < n && Character.isWhitespace(text.charAt(i))) i++;
				out.add(new Token(Kind.SPACE, text.substring(start, i)));
			} else if (c == '#' && i + 1 < n && text.charAt(i + 1) == '#') {
				i += 2;
				out.add(new Token(Kind.OTHER, "##"));
			} else {
				i++;
				out.add(new Token(Kind.OTHER, String.valueOf(c)));
			}
		}
		return out;
	}

	private static boolean isHex(String text, int start) {
		return start + 1 < text.length() && text.charAt(start) == '0' && (text.charAt(start + 1) == 'x' || text.charAt(start + 1) == 'X');
	}

	static String join(List<Token> tokens) {
		StringBuilder out = new StringBuilder();
		for (Token token : tokens) out.append(token.text);
		return out.toString();
	}

	/** Thrown when a function-like macro's call runs past the end of the line. */
	private static final class Incomplete extends RuntimeException {
		Incomplete() {
			super(null, null, false, false);
		}
	}

	private List<Token> expand(List<Token> tokens, Set<String> hidden, boolean atEnd) throws PreprocessException {
		List<Token> out = new ArrayList<>();
		int n = tokens.size();
		int i = 0;
		while (i < n) {
			Token token = tokens.get(i);
			Macro macro = token.kind == Kind.IDENT && !hidden.contains(token.text) ? this.macros.get(token.text) : null;
			if (macro == null) {
				if (token.kind == Kind.IDENT && token.text.equals("__LINE__")) {
					out.add(new Token(Kind.NUMBER, Integer.toString(this.lineNo)));
				} else if (token.kind == Kind.IDENT && token.text.equals("__FILE__")) {
					out.add(new Token(Kind.NUMBER, "0"));
				} else {
					out.add(token);
				}
				i++;
				continue;
			}
			Set<String> inner = new HashSet<>(hidden);
			inner.add(macro.name);
			if (macro.params == null) {
				List<Token> replaced = this.expand(this.substitute(macro, List.of(), inner), inner, true);
				i++;
				// "#define F G" with G function-like: F(1) calls G with the '(' that follows.
				int last = replaced.size() - 1;
				while (last >= 0 && replaced.get(last).kind == Kind.SPACE) last--;
				if (last >= 0 && this.callsNext(replaced.get(last), hidden, tokens, i)) {
					out.addAll(replaced.subList(0, last));
					List<Token> rest = new ArrayList<>();
					rest.add(replaced.get(last));
					rest.addAll(tokens.subList(i, n));
					tokens = rest;
					n = tokens.size();
					i = 0;
				} else {
					out.addAll(replaced);
				}
				continue;
			}
			int j = i + 1;
			while (j < n && tokens.get(j).kind == Kind.SPACE) j++;
			if (j >= n && !atEnd) throw new Incomplete();
			if (j >= n || !tokens.get(j).is("(")) {
				out.add(token);
				i++;
				continue;
			}
			// Gather the arguments up to the matching ')'.
			List<List<Token>> args = new ArrayList<>();
			List<Token> arg = new ArrayList<>();
			int parens = 0;
			int k = j + 1;
			for (; k < n; k++) {
				Token t = tokens.get(k);
				if (t.is("(")) {
					parens++;
				} else if (t.is(")")) {
					if (parens == 0) break;
					parens--;
				} else if (t.is(",") && parens == 0 && !(macro.variadic && args.size() == macro.params.size() - 1)) {
					args.add(arg);
					arg = new ArrayList<>();
					continue;
				}
				arg.add(t);
			}
			if (k >= n) {
				if (atEnd) throw this.error("Unclosed call to macro " + macro.name);
				throw new Incomplete();
			}
			args.add(arg);
			if (macro.params.isEmpty() && args.size() == 1 && join(args.get(0)).isBlank()) args.clear();
			if (args.size() != macro.params.size()) {
				throw this.error("Macro " + macro.name + " takes " + macro.params.size() + " arguments, got " + args.size());
			}
			List<Token> replaced = this.expand(this.substitute(macro, args, hidden), inner, true);
			i = k + 1;
			int last = replaced.size() - 1;
			while (last >= 0 && replaced.get(last).kind == Kind.SPACE) last--;
			if (last >= 0 && this.callsNext(replaced.get(last), hidden, tokens, i)) {
				out.addAll(replaced.subList(0, last));
				List<Token> rest = new ArrayList<>();
				rest.add(replaced.get(last));
				rest.addAll(tokens.subList(i, n));
				tokens = rest;
				n = tokens.size();
				i = 0;
			} else {
				out.addAll(replaced);
			}
		}
		return out;
	}

	/** Whether {@code name} is a function-like macro that the '(' after position {@code from} would call. */
	private boolean callsNext(Token name, Set<String> hidden, List<Token> tokens, int from) {
		if (name.kind != Kind.IDENT || hidden.contains(name.text)) return false;
		Macro macro = this.macros.get(name.text);
		if (macro == null || macro.params == null) return false;
		for (int j = from; j < tokens.size(); j++) {
			if (tokens.get(j).kind == Kind.SPACE) continue;
			return tokens.get(j).is("(");
		}
		return false;
	}

	/** The macro's body with its parameters replaced and '##' applied. */
	private List<Token> substitute(Macro macro, List<List<Token>> args, Set<String> hidden) throws PreprocessException {
		List<Token> body = macro.body;
		List<Token> out = new ArrayList<>();
		for (int i = 0; i < body.size(); i++) {
			Token token = body.get(i);
			int param = token.kind == Kind.IDENT && macro.params != null ? macro.params.indexOf(token.text) : -1;
			if (param < 0) {
				out.add(token);
				continue;
			}
			List<Token> arg = trim(args.get(param));
			if (nextTo(body, i, "##")) {
				out.addAll(arg);
			} else {
				out.addAll(this.expand(arg, hidden, true));
			}
		}
		if (out.stream().noneMatch(t -> t.is("##"))) return out;
		// Token pasting: join the tokens on both sides of each '##'.
		List<Token> pasted = new ArrayList<>();
		for (int i = 0; i < out.size(); i++) {
			Token token = out.get(i);
			if (!token.is("##")) {
				pasted.add(token);
				continue;
			}
			while (!pasted.isEmpty() && pasted.get(pasted.size() - 1).kind == Kind.SPACE) pasted.remove(pasted.size() - 1);
			int j = i + 1;
			while (j < out.size() && out.get(j).kind == Kind.SPACE) j++;
			String left = pasted.isEmpty() ? "" : pasted.remove(pasted.size() - 1).text;
			String right = j < out.size() ? out.get(j).text : "";
			pasted.addAll(tokenize(left + right));
			i = j;
		}
		return pasted;
	}

	private static boolean nextTo(List<Token> body, int i, String op) {
		for (int j = i - 1; j >= 0; j--) {
			if (body.get(j).kind == Kind.SPACE) continue;
			if (body.get(j).is(op)) return true;
			break;
		}
		for (int j = i + 1; j < body.size(); j++) {
			if (body.get(j).kind == Kind.SPACE) continue;
			return body.get(j).is(op);
		}
		return false;
	}

	private static List<Token> trim(List<Token> tokens) {
		int start = 0;
		int end = tokens.size();
		while (start < end && tokens.get(start).kind == Kind.SPACE) start++;
		while (end > start && tokens.get(end - 1).kind == Kind.SPACE) end--;
		return tokens.subList(start, end);
	}

	// ---- #if expressions ----

	/** Evaluates an #if expression with the macros defined so far (program.X.enabled in shaders.properties). */
	public boolean evaluate(String expression) throws PreprocessException {
		List<Token> tokens = tokenize(expression);
		// "defined X" and "defined(X)" first, so the names aren't expanded.
		List<Token> resolved = new ArrayList<>();
		for (int i = 0; i < tokens.size(); i++) {
			Token token = tokens.get(i);
			if (token.kind != Kind.IDENT || !token.text.equals("defined")) {
				resolved.add(token);
				continue;
			}
			int j = i + 1;
			while (j < tokens.size() && tokens.get(j).kind == Kind.SPACE) j++;
			boolean paren = j < tokens.size() && tokens.get(j).is("(");
			if (paren) {
				j++;
				while (j < tokens.size() && tokens.get(j).kind == Kind.SPACE) j++;
			}
			if (j >= tokens.size() || tokens.get(j).kind != Kind.IDENT) throw this.error("Bad 'defined' in #if " + expression);
			boolean defined = this.macros.containsKey(tokens.get(j).text);
			if (paren) {
				j++;
				while (j < tokens.size() && tokens.get(j).kind == Kind.SPACE) j++;
				if (j >= tokens.size() || !tokens.get(j).is(")")) throw this.error("Bad 'defined' in #if " + expression);
			}
			resolved.add(new Token(Kind.NUMBER, defined ? "1" : "0"));
			i = j;
		}
		List<Token> expanded = this.expand(resolved, Set.of(), true);
		List<Token> clean = new ArrayList<>();
		for (Token token : expanded) if (token.kind != Kind.SPACE) clean.add(token);
		if (clean.isEmpty()) {
			this.warnings.add(this.where() + ": empty #if");
			return false;
		}
		try {
			ExprParser parser = new ExprParser(clean);
			double value = parser.ternary();
			if (parser.at < clean.size()) throw new PreprocessException("unexpected '" + clean.get(parser.at).text + "'");
			return value != 0;
		} catch (PreprocessException | RuntimeException e) {
			throw this.error("Can't evaluate #if " + expression + " (" + e.getMessage() + ")");
		}
	}

	/** Precedence climbing over doubles; '/' and '%' on two whole numbers work as integers. */
	private static final class ExprParser {
		private final List<Token> tokens;
		int at;

		ExprParser(List<Token> tokens) {
			this.tokens = tokens;
		}

		private boolean peek(String op) {
			if (this.at >= this.tokens.size()) return false;
			int len = op.length();
			for (int k = 0; k < len; k++) {
				if (this.at + k >= this.tokens.size() || !this.tokens.get(this.at + k).is(String.valueOf(op.charAt(k)))) return false;
			}
			// "<" must not match "<=" or "<<", "&" not "&&", and so on.
			if (len == 1 && this.at + 1 < this.tokens.size()) {
				String next = this.tokens.get(this.at + 1).text;
				String both = op + next;
				if (this.tokens.get(this.at + 1).kind == Kind.OTHER
						&& (both.equals("<=") || both.equals(">=") || both.equals("==") || both.equals("!=") || both.equals("<<")
						|| both.equals(">>") || both.equals("&&") || both.equals("||"))) {
					return false;
				}
			}
			return true;
		}

		private boolean take(String op) {
			if (!this.peek(op)) return false;
			this.at += op.length();
			return true;
		}

		double ternary() throws PreprocessException {
			double cond = this.binary(0);
			if (this.take("?")) {
				double a = this.ternary();
				if (!this.take(":")) throw new PreprocessException("missing ':'");
				double b = this.ternary();
				return cond != 0 ? a : b;
			}
			return cond;
		}

		private static final String[][] LEVELS = {
			{"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<=", ">=", "<", ">"}, {"<<", ">>"}, {"+", "-"}, {"*", "/", "%"}
		};

		private double binary(int level) throws PreprocessException {
			if (level == LEVELS.length) return this.unary();
			double left = this.binary(level + 1);
			outer:
			while (true) {
				for (String op : LEVELS[level]) {
					if (this.take(op)) {
						double right = this.binary(level + 1);
						left = apply(op, left, right);
						continue outer;
					}
				}
				return left;
			}
		}

		private static double apply(String op, double a, double b) throws PreprocessException {
			boolean whole = a == Math.rint(a) && b == Math.rint(b);
			return switch (op) {
				case "||" -> (a != 0 || b != 0) ? 1 : 0;
				case "&&" -> (a != 0 && b != 0) ? 1 : 0;
				case "|" -> (long) a | (long) b;
				case "^" -> (long) a ^ (long) b;
				case "&" -> (long) a & (long) b;
				case "==" -> a == b ? 1 : 0;
				case "!=" -> a != b ? 1 : 0;
				case "<=" -> a <= b ? 1 : 0;
				case ">=" -> a >= b ? 1 : 0;
				case "<" -> a < b ? 1 : 0;
				case ">" -> a > b ? 1 : 0;
				case "<<" -> (long) a << (long) b;
				case ">>" -> (long) a >> (long) b;
				case "+" -> a + b;
				case "-" -> a - b;
				case "*" -> a * b;
				case "/" -> {
					if (b == 0) throw new PreprocessException("division by zero");
					yield whole ? (double) ((long) a / (long) b) : a / b;
				}
				case "%" -> {
					if (b == 0) throw new PreprocessException("division by zero");
					yield whole ? (double) ((long) a % (long) b) : a % b;
				}
				default -> throw new PreprocessException("bad operator " + op);
			};
		}

		private double unary() throws PreprocessException {
			if (this.take("!")) return this.unary() == 0 ? 1 : 0;
			if (this.take("-")) return -this.unary();
			if (this.take("+")) return this.unary();
			if (this.take("~")) return ~(long) this.unary();
			if (this.take("(")) {
				double value = this.ternary();
				if (!this.take(")")) throw new PreprocessException("missing ')'");
				return value;
			}
			if (this.at >= this.tokens.size()) throw new PreprocessException("expression ends early");
			Token token = this.tokens.get(this.at++);
			if (token.kind == Kind.NUMBER) return parseNumber(token.text);
			if (token.kind == Kind.IDENT) return token.text.equals("true") ? 1 : 0;
			throw new PreprocessException("unexpected '" + token.text + "'");
		}

		static double parseNumber(String text) throws PreprocessException {
			String t = text;
			try {
				if (t.startsWith("0x") || t.startsWith("0X")) {
					t = t.replaceAll("[uUlL]+$", "");
					return Long.parseLong(t.substring(2), 16);
				}
				if (t.matches("[0-9]+[uUlL]*")) {
					t = t.replaceAll("[uUlL]+$", "");
					return t.length() > 1 && t.startsWith("0") ? Long.parseLong(t, 8) : Long.parseLong(t);
				}
				return Double.parseDouble(t.replaceAll("(?i)(lf|f)$", ""));
			} catch (NumberFormatException e) {
				throw new PreprocessException("bad number " + text);
			}
		}
	}
}
