package com.afterburner.labs.shaderpack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A pack's options, found as OptiFine finds them in its sources, and how its settings screens show them (shaders.properties
 * and the lang files):
 * <ul>
 * <li>{@code #define NAME VALUE // [V1 V2 ...]}: one of those values.</li>
 * <li>{@code #define NAME} or {@code //#define NAME}, where the pack tests NAME with #ifdef, #ifndef or defined: on or off.</li>
 * <li>{@code const int|float NAME = VALUE; // [V1 V2 ...]} and {@code const bool NAME = true|false;}: like the first two.</li>
 * </ul>
 * The player's choices are kept as OptiFine and Iris keep them, "NAME=VALUE" lines in shaderpacks/&lt;pack&gt;.txt, and applied
 * by rewriting those lines as the pack's files are read ({@link #rewriter}). No Minecraft classes.
 */
public final class PackOptions {
	public enum Type {
		/** A #define that's there or not; values "true" and "false". */
		TOGGLE,
		/** A #define's value. */
		VALUE,
		/** A const's value (a bool's is "true" or "false"). */
		CONST
	}

	public static final class Option {
		public final String name;
		public final Type type;
		public final String defaultValue;
		/** The values to choose from, in order. */
		public final List<String> values;

		Option(String name, Type type, String defaultValue, List<String> values) {
			this.name = name;
			this.type = type;
			this.defaultValue = defaultValue;
			this.values = values;
		}

		/** Whether it's on/off (a toggle, or a bool const). */
		public boolean isBoolean() {
			return this.type == Type.TOGGLE || this.values.size() == 2 && this.values.contains("true") && this.values.contains("false");
		}
	}

	private static final Pattern VALUE = Pattern.compile("^\\s*#define\\s+(\\w+)\\s+([^\\s/]+)\\s*//.*?\\[([^\\]]*)\\]");
	private static final Pattern TOGGLE = Pattern.compile("^\\s*(//)?\\s*#define\\s+(\\w+)\\s*(//.*)?$");
	private static final Pattern CONST = Pattern.compile("^\\s*const\\s+(?:int|float)\\s+(\\w+)\\s*=\\s*([^;\\s]+)\\s*;\\s*//.*?\\[([^\\]]*)\\]");
	private static final Pattern CONST_BOOL = Pattern.compile("^\\s*const\\s+bool\\s+(\\w+)\\s*=\\s*(true|false)\\s*;");
	private static final Pattern TESTED = Pattern.compile("#\\s*(?:ifdef|ifndef)\\s+(\\w+)|defined\\s*\\(?\\s*(\\w+)");
	private static final Set<String> SOURCES = Set.of("glsl", "vsh", "fsh", "gsh", "csh", "tcs", "tes", "inc", "vert", "frag", "geom", "comp");

	/** By name, in the order they were found. */
	public final Map<String, Option> options;
	/** Each screen's entries: "" is the main screen; entries are option names, "[SCREEN]", "&lt;empty&gt;", "&lt;profile&gt;", "*". */
	public final Map<String, List<String>> screens;
	/** Options shown as sliders. */
	public final Set<String> sliders;
	/** Each profile's settings ("NAME=VALUE", "NAME", "!NAME", "profile.OTHER"), in order. */
	public final Map<String, List<String>> profiles;
	/** The pack's English names and descriptions (option.NAME, option.NAME.comment, value.NAME.VALUE, screen.NAME, ...). */
	public final Map<String, String> lang;
	/** Where each option is defined: path to line index to option name. */
	private final Map<String, Map<Integer, String>> definitions;

	private PackOptions(Map<String, Option> options, Map<String, List<String>> screens, Set<String> sliders, Map<String, List<String>> profiles,
			Map<String, String> lang, Map<String, Map<Integer, String>> definitions) {
		this.options = options;
		this.screens = screens;
		this.sliders = sliders;
		this.profiles = profiles;
		this.lang = lang;
		this.definitions = definitions;
	}

	/** Finds the options of a pack. {@code macros} are the standard ones (see {@link StandardMacros}), for shaders.properties. */
	public static PackOptions read(PackFiles pack, Map<String, String> macros) throws IOException {
		List<String> files = pack.list();
		Map<String, String[]> lines = new LinkedHashMap<>();
		Set<String> tested = new HashSet<>();
		for (String path : files) {
			int dot = path.lastIndexOf('.');
			if (dot < 0 || !SOURCES.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT))) continue;
			String text = pack.readRaw(path);
			if (text == null) continue;
			String[] split = text.split("\n", -1);
			lines.put(path, split);
			for (String line : split) {
				if (line.indexOf('#') < 0 && !line.contains("defined")) continue;
				Matcher m = TESTED.matcher(line);
				while (m.find()) tested.add(m.group(1) != null ? m.group(1) : m.group(2));
			}
		}

		Map<String, Option> options = new LinkedHashMap<>();
		Map<String, Map<Integer, String>> definitions = new HashMap<>();
		for (Map.Entry<String, String[]> file : lines.entrySet()) {
			String[] split = file.getValue();
			for (int i = 0; i < split.length; i++) {
				String line = strip(split[i]);
				if (!line.contains("#define") && !line.contains("const")) continue;
				Option found = null;
				Matcher m;
				if ((m = VALUE.matcher(line)).find()) {
					found = new Option(m.group(1), Type.VALUE, m.group(2), values(m.group(3), m.group(2)));
				} else if ((m = TOGGLE.matcher(line)).find()) {
					if (tested.contains(m.group(2))) found = new Option(m.group(2), Type.TOGGLE, m.group(1) == null ? "true" : "false", List.of("true", "false"));
				} else if ((m = CONST.matcher(line)).find()) {
					found = new Option(m.group(1), Type.CONST, m.group(2), values(m.group(3), m.group(2)));
				} else if ((m = CONST_BOOL.matcher(line)).find()) {
					found = new Option(m.group(1), Type.CONST, m.group(2), List.of("true", "false"));
				}
				if (found == null) continue;
				Option known = options.get(found.name);
				// The same option defined again (in another file) is rewritten too; something else by that name isn't.
				if (known != null && known.type != found.type) continue;
				if (known == null) options.put(found.name, found);
				definitions.computeIfAbsent(file.getKey(), k -> new HashMap<>()).put(i, found.name);
			}
		}

		Map<String, List<String>> screens = new LinkedHashMap<>();
		Set<String> sliders = new HashSet<>();
		Map<String, List<String>> profiles = new LinkedHashMap<>();
		if (pack.exists("/shaders.properties")) {
			try {
				GlslPreprocessor pp = new GlslPreprocessor(pack::readRaw, GlslPreprocessor.Mode.PROPERTIES);
				StandardMacros.apply(pp, macros);
				ShaderProperties properties = ShaderProperties.parse(pp.process("/shaders.properties"));
				for (ShaderProperties.Entry e : properties.withPrefix("screen")) {
					String key = e.key();
					if (key.equals("screen")) screens.put("", words(e.value()));
					else if (key.startsWith("screen.") && key.indexOf('.', "screen.".length()) < 0 && !key.equals("screen.columns")) {
						screens.put(key.substring("screen.".length()), words(e.value()));
					}
				}
				String slider = properties.get("sliders");
				if (slider != null) sliders.addAll(words(slider));
				for (ShaderProperties.Entry e : properties.withPrefix("profile.")) {
					profiles.put(e.key().substring("profile.".length()), words(e.value()));
				}
			} catch (GlslPreprocessor.PreprocessException e) {
				// No screens: every option is listed.
			}
		}

		Map<String, String> lang = new HashMap<>();
		for (String path : files) {
			if (!path.toLowerCase(Locale.ROOT).equals("/lang/en_us.lang")) continue;
			String text = pack.readRaw(path);
			if (text == null) break;
			for (String line : text.split("\n")) {
				line = line.strip();
				int eq = line.indexOf('=');
				if (line.isEmpty() || line.startsWith("#") || eq <= 0) continue;
				lang.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
			}
			break;
		}
		return new PackOptions(options, screens, sliders, profiles, lang, definitions);
	}

	private static String strip(String line) {
		return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
	}

	private static List<String> values(String list, String defaultValue) {
		List<String> out = new ArrayList<>(words(list));
		if (!out.contains(defaultValue)) out.addFirst(defaultValue);
		return List.copyOf(out);
	}

	private static List<String> words(String text) {
		List<String> out = new ArrayList<>();
		for (String w : text.strip().split("\\s+")) if (!w.isEmpty()) out.add(w);
		return out;
	}

	/** The option's value with the player's {@code chosen} values (only those not at their default are in there). */
	public String value(Option option, Map<String, String> chosen) {
		String v = chosen.get(option.name);
		return v != null && option.values.contains(v) ? v : option.defaultValue;
	}

	/** Sets an option in {@code chosen}; the default is kept by leaving it out. */
	public static void set(Option option, String value, Map<String, String> chosen) {
		if (value.equals(option.defaultValue)) chosen.remove(option.name);
		else chosen.put(option.name, value);
	}

	/** Applies a profile's settings to {@code chosen}. */
	public void applyProfile(String profile, Map<String, String> chosen) {
		this.applyProfile(profile, chosen, new HashSet<>());
	}

	private void applyProfile(String profile, Map<String, String> chosen, Set<String> seen) {
		List<String> settings = this.profiles.get(profile);
		if (settings == null || !seen.add(profile)) return;
		for (String s : settings) {
			if (s.startsWith("profile.")) {
				this.applyProfile(s.substring("profile.".length()), chosen, seen);
				continue;
			}
			int eq = s.indexOf('=');
			boolean off = s.startsWith("!");
			String name = eq >= 0 ? s.substring(0, eq) : off ? s.substring(1) : s;
			Option o = this.options.get(name);
			if (o == null) continue;
			String value = eq >= 0 ? s.substring(eq + 1) : off ? "false" : "true";
			if (o.values.contains(value)) set(o, value, chosen);
		}
	}

	/** The first profile all of whose settings are what {@code chosen} says, or null. */
	public @Nullable String currentProfile(Map<String, String> chosen) {
		for (String profile : this.profiles.keySet()) {
			Map<String, String> applied = new HashMap<>(chosen);
			this.applyProfile(profile, applied);
			if (applied.equals(chosen)) return profile;
		}
		return null;
	}

	/** Rewrites the pack's files for the {@code chosen} values; null if they're all at their defaults. */
	public PackFiles.@Nullable Rewriter rewriter(Map<String, String> chosen) {
		Map<String, String> active = new HashMap<>();
		for (Map.Entry<String, String> e : chosen.entrySet()) {
			Option o = this.options.get(e.getKey());
			if (o != null && !e.getValue().equals(o.defaultValue) && o.values.contains(e.getValue())) active.put(o.name, e.getValue());
		}
		if (active.isEmpty()) return null;
		return (path, text) -> {
			Map<Integer, String> here = this.definitions.get(path);
			if (here == null) return text;
			String[] split = text.split("\n", -1);
			boolean changed = false;
			for (Map.Entry<Integer, String> d : here.entrySet()) {
				String value = active.get(d.getValue());
				int i = d.getKey();
				if (value == null || i >= split.length) continue;
				String line = split[i];
				String cr = line.endsWith("\r") ? "\r" : "";
				String body = strip(line);
				String rewritten = rewrite(this.options.get(d.getValue()), body, value);
				if (rewritten != null && !rewritten.equals(body)) {
					split[i] = rewritten + cr;
					changed = true;
				}
			}
			return changed ? String.join("\n", split) : text;
		};
	}

	private static @Nullable String rewrite(Option option, String line, String value) {
		String name = Pattern.quote(option.name);
		return switch (option.type) {
			case TOGGLE -> {
				Matcher m = Pattern.compile("^(\\s*)(?://)?\\s*(#define\\s+" + name + "\\b.*)$").matcher(line);
				if (!m.find()) yield null;
				yield m.group(1) + (value.equals("true") ? "" : "//") + m.group(2);
			}
			case VALUE -> {
				Matcher m = Pattern.compile("^(\\s*#define\\s+" + name + "\\s+)([^\\s/]+)").matcher(line);
				yield m.find() ? m.group(1) + value + line.substring(m.end()) : null;
			}
			case CONST -> {
				Matcher m = Pattern.compile("^(\\s*const\\s+\\w+\\s+" + name + "\\s*=\\s*)([^;]+?)(\\s*;)").matcher(line);
				yield m.find() ? m.group(1) + value + line.substring(m.end(2)) : null;
			}
		};
	}

	/** The player's values for a pack: "NAME=VALUE" lines in {@code file} (missing: none). */
	public static Map<String, String> readValues(Path file) {
		Map<String, String> out = new LinkedHashMap<>();
		if (!Files.isRegularFile(file)) return out;
		Properties p = new Properties();
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			p.load(in);
		} catch (IOException | IllegalArgumentException e) {
			return out;
		}
		for (String key : p.stringPropertyNames()) out.put(key, p.getProperty(key).strip());
		return out;
	}

	/** Writes the values, sorted, or deletes the file when there are none. */
	public static void writeValues(Path file, Map<String, String> values) throws IOException {
		if (values.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}
		try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			for (Map.Entry<String, String> e : new TreeMap<>(values).entrySet()) out.write(e.getKey() + "=" + e.getValue() + "\n");
		}
	}
}
