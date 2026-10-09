package com.afterburner.labs.shaderpack;

import com.afterburner.labs.shaderpack.GlslPreprocessor.Kind;
import com.afterburner.labs.shaderpack.GlslPreprocessor.Token;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The {@code uniform.<type>.<name>} and {@code variable.<type>.<name>} entries of shaders.properties: expressions over the
 * game's uniforms (worldTime, sunPosition.y, gbufferModelView.0.1, ...), biome parameters, entity flags and earlier entries,
 * evaluated in file order once a frame.
 * <p>
 * Values are double arrays: one element for float/int/bool (bool as 0 or 1), 2 to 4 for vectors, 16 (column-major) for matrices.
 */
public final class PackUniforms {
	/** The values expressions can read: game uniforms and parameters, or null if unknown. */
	public interface Inputs {
		double @Nullable [] get(String name);
	}

	public record Definition(boolean uniform, String type, String name, String source) {}

	private record Compiled(Definition definition, Expr expr) {}

	private final List<Compiled> compiled = new ArrayList<>();
	private final Map<String, double[]> values = new LinkedHashMap<>();
	private final List<String> warnings = new ArrayList<>();
	private final Map<Integer, double[]> smoothState = new HashMap<>();
	private long lastNanos;
	private double deltaSeconds;
	private int nextSmoothId = 100000;

	private PackUniforms() {
	}

	/** Reads and compiles the entries; ones that don't compile are skipped with a warning. */
	public static PackUniforms parse(PackProperties properties, Map<String, String> constants) {
		PackUniforms out = new PackUniforms();
		for (PackProperties.Entry e : properties.entries()) {
			boolean uniform = e.key().startsWith("uniform.");
			if (!uniform && !e.key().startsWith("variable.")) continue;
			String[] parts = e.key().split("\\.", 3);
			if (parts.length != 3 || !Set.of("float", "int", "bool", "vec2", "vec3", "vec4").contains(parts[1])) {
				out.warnings.add(e.origin() + ": bad custom uniform " + e.key());
				continue;
			}
			Definition definition = new Definition(uniform, parts[1], parts[2], e.value());
			try {
				Expr expr = new Parser(e.value(), constants, out).parse();
				out.compiled.add(new Compiled(definition, expr));
			} catch (RuntimeException ex) {
				out.warnings.add(e.origin() + ": " + e.key() + ": " + ex);
			}
		}
		return out;
	}

	public List<String> warnings() {
		return this.warnings;
	}

	/** The uniforms and variables, in file order. */
	public List<Definition> definitions() {
		List<Definition> out = new ArrayList<>();
		for (Compiled c : this.compiled) out.add(c.definition);
		return out;
	}

	/** The uniforms (not the variables), in file order. */
	public List<Definition> uniforms() {
		List<Definition> out = new ArrayList<>();
		for (Compiled c : this.compiled) if (c.definition.uniform) out.add(c.definition);
		return out;
	}

	/** Evaluates everything for this frame. */
	public void update(Inputs inputs) {
		long now = System.nanoTime();
		this.deltaSeconds = this.lastNanos == 0 ? 0.0 : (now - this.lastNanos) / 1e9;
		this.lastNanos = now;
		Inputs scope = name -> {
			double[] own = this.values.get(name);
			return own != null ? own : inputs.get(name);
		};
		for (Compiled c : this.compiled) {
			double[] v;
			try {
				v = c.expr.eval(scope);
			} catch (RuntimeException ex) {
				v = new double[] {0.0};
			}
			this.values.put(c.definition.name, convert(v, c.definition.type));
		}
	}

	/** A uniform's or variable's value from the last {@link #update}, or null. */
	public double @Nullable [] value(String name) {
		return this.values.get(name);
	}

	private static double[] convert(double[] v, String type) {
		return switch (type) {
			case "float" -> new double[] {v[0]};
			case "int" -> new double[] {(double) (long) v[0]};
			case "bool" -> new double[] {v[0] != 0.0 ? 1.0 : 0.0};
			default -> {
				int n = type.charAt(3) - '0';
				double[] out = new double[n];
				for (int i = 0; i < n; i++) out[i] = v.length == 1 ? v[0] : i < v.length ? v[i] : 0.0;
				yield out;
			}
		};
	}

	// ---- Expressions ----

	@FunctionalInterface
	interface Expr {
		double[] eval(Inputs in);
	}

	private static double[] scalar(double v) {
		return new double[] {v};
	}

	/** A number written in the expression. */
	private record Constant(double value) implements Expr {
		@Override
		public double[] eval(Inputs in) {
			return scalar(this.value);
		}
	}

	/** A component of a value; 0 past its end (a vector the game doesn't give reads as 0, as an unknown name does). */
	private static double[] component(double[] v, int index) {
		return scalar(index < v.length ? v[index] : 0.0);
	}

	private static boolean truth(double[] v) {
		return v[0] != 0.0;
	}

	/** Smooths toward the target, about 99% of the way after the fade time. */
	private double smooth(int id, double target, double fadeIn, double fadeOut) {
		double[] state = this.smoothState.get(id);
		if (state == null) {
			this.smoothState.put(id, new double[] {target});
			return target;
		}
		double previous = state[0];
		double fade = target >= previous ? fadeIn : fadeOut;
		double value;
		if (fade <= 0.0 || this.deltaSeconds <= 0.0) {
			value = fade <= 0.0 ? target : previous;
		} else {
			value = previous + (target - previous) * (1.0 - Math.exp(-4.6 * this.deltaSeconds / fade));
		}
		state[0] = value;
		return value;
	}

	private static final class Parser {
		private final List<Token> tokens = new ArrayList<>();
		private final Map<String, String> constants;
		private final PackUniforms owner;
		private int pos;

		Parser(String text, Map<String, String> constants, PackUniforms owner) {
			for (Token t : GlslPreprocessor.tokenize(text)) if (t.kind() != Kind.SPACE) this.tokens.add(t);
			this.constants = constants;
			this.owner = owner;
		}

		Expr parse() {
			Expr e = this.or();
			if (this.pos < this.tokens.size()) throw new IllegalArgumentException("unexpected '" + this.tokens.get(this.pos).text() + "'");
			return e;
		}

		private @Nullable Token peek() {
			return this.pos < this.tokens.size() ? this.tokens.get(this.pos) : null;
		}

		private boolean accept(String text) {
			Token t = this.peek();
			if (t != null && t.kind() == Kind.OTHER && t.text().equals(text)) {
				this.pos++;
				return true;
			}
			return false;
		}

		/** Accepts a two-character operator (the tokenizer gives single characters). */
		private boolean accept2(String op) {
			if (this.pos + 1 < this.tokens.size() && this.tokens.get(this.pos).text().equals(op.substring(0, 1))
					&& this.tokens.get(this.pos + 1).text().equals(op.substring(1))) {
				this.pos += 2;
				return true;
			}
			return false;
		}

		private void expect(String text) {
			if (!this.accept(text)) throw new IllegalArgumentException("expected '" + text + "'");
		}

		private Expr or() {
			Expr left = this.and();
			while (this.accept2("||")) {
				Expr a = left;
				Expr b = this.and();
				left = in -> scalar(truth(a.eval(in)) || truth(b.eval(in)) ? 1 : 0);
			}
			return left;
		}

		private Expr and() {
			Expr left = this.equality();
			while (this.accept2("&&")) {
				Expr a = left;
				Expr b = this.equality();
				left = in -> scalar(truth(a.eval(in)) && truth(b.eval(in)) ? 1 : 0);
			}
			return left;
		}

		private Expr equality() {
			Expr left = this.relational();
			while (true) {
				Expr a = left;
				if (this.accept2("==")) {
					Expr b = this.relational();
					left = in -> scalar(a.eval(in)[0] == b.eval(in)[0] ? 1 : 0);
				} else if (this.accept2("!=")) {
					Expr b = this.relational();
					left = in -> scalar(a.eval(in)[0] != b.eval(in)[0] ? 1 : 0);
				} else {
					return left;
				}
			}
		}

		private Expr relational() {
			Expr left = this.additive();
			while (true) {
				Expr a = left;
				if (this.accept2(">=")) {
					Expr b = this.additive();
					left = in -> scalar(a.eval(in)[0] >= b.eval(in)[0] ? 1 : 0);
				} else if (this.accept2("<=")) {
					Expr b = this.additive();
					left = in -> scalar(a.eval(in)[0] <= b.eval(in)[0] ? 1 : 0);
				} else if (this.accept(">")) {
					Expr b = this.additive();
					left = in -> scalar(a.eval(in)[0] > b.eval(in)[0] ? 1 : 0);
				} else if (this.accept("<")) {
					Expr b = this.additive();
					left = in -> scalar(a.eval(in)[0] < b.eval(in)[0] ? 1 : 0);
				} else {
					return left;
				}
			}
		}

		private Expr additive() {
			Expr left = this.multiplicative();
			while (true) {
				Expr a = left;
				if (this.accept("+")) {
					Expr b = this.multiplicative();
					left = in -> zip(a.eval(in), b.eval(in), Double::sum);
				} else if (this.accept("-")) {
					Expr b = this.multiplicative();
					left = in -> zip(a.eval(in), b.eval(in), (x, y) -> x - y);
				} else {
					return left;
				}
			}
		}

		private Expr multiplicative() {
			Expr left = this.unary();
			while (true) {
				Expr a = left;
				if (this.accept("*")) {
					Expr b = this.unary();
					left = in -> zip(a.eval(in), b.eval(in), (x, y) -> x * y);
				} else if (this.accept("/")) {
					Expr b = this.unary();
					left = in -> zip(a.eval(in), b.eval(in), (x, y) -> x / y);
				} else if (this.accept("%")) {
					Expr b = this.unary();
					left = in -> zip(a.eval(in), b.eval(in), (x, y) -> x % y);
				} else {
					return left;
				}
			}
		}

		private Expr unary() {
			if (this.accept("-")) {
				Expr a = this.unary();
				return in -> map(a.eval(in), x -> -x);
			}
			if (this.accept("+")) return this.unary();
			if (this.accept("!")) {
				Expr a = this.unary();
				return in -> scalar(truth(a.eval(in)) ? 0 : 1);
			}
			return this.postfix(this.primary());
		}

		/** Component access: {@code .x}, {@code .r}, {@code .0.1} (matrix row, column). */
		private Expr postfix(Expr base) {
			Expr e = base;
			while (true) {
				Token t = this.peek();
				if (t == null) return e;
				if (t.kind() == Kind.NUMBER && t.text().startsWith(".")) {
					// ".0.1" is one pp-number: matrix element row 0, column 1.
					this.pos++;
					String[] parts = t.text().substring(1).split("\\.");
					if (parts.length == 2) {
						int row = Integer.parseInt(parts[0]);
						int column = Integer.parseInt(parts[1]);
						Expr m = e;
						e = in -> component(m.eval(in), column * 4 + row);
					} else {
						int index = Integer.parseInt(parts[0]);
						Expr v = e;
						e = in -> component(v.eval(in), index);
					}
					continue;
				}
				if (t.kind() == Kind.OTHER && t.text().equals(".") && this.pos + 1 < this.tokens.size()) {
					Token c = this.tokens.get(this.pos + 1);
					int index = switch (c.text()) {
						case "x", "r", "s" -> 0;
						case "y", "g", "t" -> 1;
						case "z", "b", "p" -> 2;
						case "w", "a", "q" -> 3;
						default -> -1;
					};
					if (index < 0) throw new IllegalArgumentException("bad component ." + c.text());
					this.pos += 2;
					Expr v = e;
					e = in -> component(v.eval(in), index);
					continue;
				}
				return e;
			}
		}

		private Expr primary() {
			Token t = this.peek();
			if (t == null) throw new IllegalArgumentException("unexpected end");
			this.pos++;
			if (t.kind() == Kind.NUMBER) return new Constant(number(t.text()));
			if (t.kind() == Kind.OTHER && t.text().equals("(")) {
				Expr e = this.or();
				this.expect(")");
				return e;
			}
			if (t.kind() != Kind.IDENT) throw new IllegalArgumentException("unexpected '" + t.text() + "'");
			String name = t.text();
			if (this.accept("(")) {
				List<Expr> args = new ArrayList<>();
				if (!this.accept(")")) {
					do {
						args.add(this.or());
					} while (this.accept(","));
					this.expect(")");
				}
				return this.function(name, args);
			}
			switch (name) {
				case "pi":
					return in -> scalar(Math.PI);
				case "true":
					return in -> scalar(1);
				case "false":
					return in -> scalar(0);
				default:
					break;
			}
			String constant = this.constants.get(name);
			if (constant != null) {
				double v = number(constant);
				return in -> scalar(v);
			}
			return in -> {
				double[] v = in.get(name);
				return v != null ? v : scalar(0);
			};
		}

		private static double number(String text) {
			String t = text.toLowerCase(Locale.ROOT);
			if (t.startsWith("0x")) return Long.parseLong(t.substring(2).replaceAll("[ul]+$", ""), 16);
			t = t.replaceAll("[fdul]+$", "");
			try {
				return Double.parseDouble(t);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("bad number " + text);
			}
		}

		private Expr function(String name, List<Expr> args) {
			int n = args.size();
			Expr a = n > 0 ? args.get(0) : null;
			Expr b = n > 1 ? args.get(1) : null;
			Expr c = n > 2 ? args.get(2) : null;
			switch (name) {
				case "sin": return unaryFn(name, args, Math::sin);
				case "cos": return unaryFn(name, args, Math::cos);
				case "asin": return unaryFn(name, args, Math::asin);
				case "acos": return unaryFn(name, args, Math::acos);
				case "tan": return unaryFn(name, args, Math::tan);
				case "atan": return unaryFn(name, args, Math::atan);
				case "torad", "radians": return unaryFn(name, args, Math::toRadians);
				case "todeg", "degrees": return unaryFn(name, args, Math::toDegrees);
				case "abs": return unaryFn(name, args, Math::abs);
				case "floor": return unaryFn(name, args, Math::floor);
				case "ceil": return unaryFn(name, args, Math::ceil);
				case "exp": return unaryFn(name, args, Math::exp);
				case "exp2": return unaryFn(name, args, x -> Math.pow(2.0, x));
				case "exp10": return unaryFn(name, args, x -> Math.pow(10.0, x));
				case "frac": return unaryFn(name, args, x -> x - Math.floor(x));
				case "log": return unaryFn(name, args, Math::log);
				case "log2": return unaryFn(name, args, x -> Math.log(x) / Math.log(2.0));
				case "log10": return unaryFn(name, args, Math::log10);
				case "round": return unaryFn(name, args, x -> (double) Math.round(x));
				case "signum", "sign": return unaryFn(name, args, Math::signum);
				case "sqrt": return unaryFn(name, args, Math::sqrt);
				case "atan2":
					count(name, n, 2);
					return in -> zip(a.eval(in), b.eval(in), Math::atan2);
				case "pow":
					count(name, n, 2);
					return in -> zip(a.eval(in), b.eval(in), Math::pow);
				case "fmod":
					count(name, n, 2);
					return in -> zip(a.eval(in), b.eval(in), (x, y) -> x - y * Math.floor(x / y));
				case "min":
				case "max": {
					if (n < 1) throw new IllegalArgumentException(name + " needs arguments");
					boolean max = name.equals("max");
					return in -> {
						double[] best = args.get(0).eval(in);
						for (int i = 1; i < n; i++) {
							double[] v = args.get(i).eval(in);
							best = zip(best, v, max ? Math::max : Math::min);
						}
						return best;
					};
				}
				case "clamp":
					count(name, n, 3);
					return in -> {
						double[] x = a.eval(in);
						return zip(zip(x, b.eval(in), Math::max), c.eval(in), Math::min);
					};
				case "lerp":
					count(name, n, 3);
					return in -> {
						double k = a.eval(in)[0];
						return zip(b.eval(in), c.eval(in), (x, y) -> x + (y - x) * k);
					};
				case "mix":
					// Iris's (GLSL's): mix(from, to, amount), the amount last, per component.
					count(name, n, 3);
					return in -> {
						double[] from = a.eval(in);
						return zip(from, zip(zip(b.eval(in), from, (y, x) -> y - x), c.eval(in), (d, k) -> d * k), Double::sum);
					};
				case "edge":
					// Iris's: 1 from the edge on (GLSL's step), edge(edge, x).
					count(name, n, 2);
					return in -> zip(a.eval(in), b.eval(in), (e, x) -> x >= e ? 1.0 : 0.0);
				case "random":
					if (n == 2) return in -> scalar(a.eval(in)[0] + Math.random() * (b.eval(in)[0] - a.eval(in)[0]));
					return in -> scalar(Math.random());
				case "randomInt":
					count(name, n, 2);
					return in -> {
						double from = a.eval(in)[0];
						return scalar(from + Math.floor(Math.random() * (b.eval(in)[0] - from)));
					};
				case "if":
				case "ifb":
					if (n < 3 || n % 2 == 0) throw new IllegalArgumentException(name + " needs cond, value, ..., else");
					return in -> {
						for (int i = 0; i + 1 < n; i += 2) {
							if (truth(args.get(i).eval(in))) return args.get(i + 1).eval(in);
						}
						return args.get(n - 1).eval(in);
					};
				case "between":
					count(name, n, 3);
					return in -> {
						double x = a.eval(in)[0];
						return scalar(x >= b.eval(in)[0] && x <= c.eval(in)[0] ? 1 : 0);
					};
				case "equals":
					count(name, n, 3);
					return in -> scalar(Math.abs(a.eval(in)[0] - b.eval(in)[0]) <= c.eval(in)[0] ? 1 : 0);
				case "in":
					if (n < 1) throw new IllegalArgumentException("in needs arguments");
					return in -> {
						double x = a.eval(in)[0];
						for (int i = 1; i < n; i++) if (args.get(i).eval(in)[0] == x) return scalar(1);
						return scalar(0);
					};
				case "vec2":
				case "vec3":
				case "vec4": {
					int size = name.charAt(3) - '0';
					return in -> {
						double[] out = new double[size];
						int k = 0;
						for (Expr arg : args) {
							for (double v : arg.eval(in)) if (k < size) out[k++] = v;
						}
						if (k == 1) java.util.Arrays.fill(out, out[0]);
						return out;
					};
				}
				case "smooth": {
					// smooth([id], value, [fadeIn, [fadeOut]]): the first argument is the id with 4 arguments, or with 2 or 3 when
					// it's a number (as OptiFine tells them apart: "smooth(if(...), 0.0, 0.0)" is a value and its fades); without
					// one, an id is made.
					int id;
					List<Expr> rest;
					if (n == 4 || n >= 2 && args.get(0) instanceof Constant) {
						id = args.get(0) instanceof Constant constant ? (int) constant.value() : this.owner.nextSmoothId++;
						rest = args.subList(1, n);
					} else {
						id = this.owner.nextSmoothId++;
						rest = args;
					}
					if (rest.isEmpty()) throw new IllegalArgumentException("smooth needs a value");
					Expr value = rest.get(0);
					Expr fadeIn = rest.size() > 1 ? rest.get(1) : null;
					Expr fadeOut = rest.size() > 2 ? rest.get(2) : fadeIn;
					PackUniforms owner = this.owner;
					return in -> {
						double up = fadeIn != null ? fadeIn.eval(in)[0] : 1.0;
						double down = fadeOut != null ? fadeOut.eval(in)[0] : up;
						return scalar(owner.smooth(id, value.eval(in)[0], up, down));
					};
				}
				case "print":
					count(name, n, 3);
					return in -> c.eval(in);
				default:
					throw new IllegalArgumentException("unknown function " + name);
			}
		}

		private static void count(String name, int n, int expected) {
			if (n != expected) throw new IllegalArgumentException(name + " needs " + expected + " arguments, got " + n);
		}

		private static Expr unaryFn(String name, List<Expr> args, java.util.function.DoubleUnaryOperator op) {
			count(name, args.size(), 1);
			Expr a = args.get(0);
			return in -> map(a.eval(in), op);
		}
	}

	private static double[] map(double[] v, java.util.function.DoubleUnaryOperator op) {
		double[] out = new double[v.length];
		for (int i = 0; i < v.length; i++) out[i] = op.applyAsDouble(v[i]);
		return out;
	}

	/** Component-wise; a scalar pairs with every component of a vector. */
	private static double[] zip(double[] a, double[] b, java.util.function.DoubleBinaryOperator op) {
		if (a.length == 1 && b.length == 1) return new double[] {op.applyAsDouble(a[0], b[0])};
		int n = Math.max(a.length, b.length);
		double[] out = new double[n];
		for (int i = 0; i < n; i++) {
			double x = a.length == 1 ? a[0] : i < a.length ? a[i] : 0.0;
			double y = b.length == 1 ? b[0] : i < b.length ? b[i] : 0.0;
			out[i] = op.applyAsDouble(x, y);
		}
		return out;
	}
}
