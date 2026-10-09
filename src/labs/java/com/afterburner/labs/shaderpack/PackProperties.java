package com.afterburner.labs.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A pack's .properties file (shaders.properties, block.properties, ...) after {@link GlslPreprocessor.Mode#PROPERTIES}: Java
 * properties syntax, but keys keep their file order (custom uniforms and variables are defined in order) and a key given twice
 * keeps its last value in its first place.
 */
public final class PackProperties {
	public record Entry(String key, String value, String origin) {}

	private final Map<String, Entry> entries = new LinkedHashMap<>();

	private PackProperties() {
	}

	/** Parses preprocessed lines (continuations already joined). */
	public static PackProperties parse(GlslPreprocessor.Result source) {
		PackProperties out = new PackProperties();
		for (int i = 0; i < source.lines.size(); i++) {
			String line = source.lines.get(i).strip();
			if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
			int split = -1;
			for (int j = 0; j < line.length(); j++) {
				char c = line.charAt(j);
				if (c == '\\') {
					j++;
				} else if (c == '=' || c == ':' || Character.isWhitespace(c)) {
					split = j;
					break;
				}
			}
			String key;
			String value;
			if (split < 0) {
				key = line;
				value = "";
			} else {
				key = line.substring(0, split);
				int v = split;
				while (v < line.length() && Character.isWhitespace(line.charAt(v))) v++;
				if (v < line.length() && (line.charAt(v) == '=' || line.charAt(v) == ':')) v++;
				while (v < line.length() && Character.isWhitespace(line.charAt(v))) v++;
				value = line.substring(v);
			}
			key = unescape(key);
			value = unescape(value).strip();
			String origin = i < source.origins.size() ? source.origins.get(i) : "?";
			Entry old = out.entries.get(key);
			out.entries.put(key, new Entry(key, value, old != null ? old.origin : origin));
		}
		return out;
	}

	private static String unescape(String text) {
		if (text.indexOf('\\') < 0) return text;
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c != '\\' || i + 1 >= text.length()) {
				out.append(c);
				continue;
			}
			char e = text.charAt(++i);
			switch (e) {
				case 't' -> out.append('\t');
				case 'n' -> out.append('\n');
				case 'r' -> out.append('\r');
				case 'f' -> out.append('\f');
				case 'u' -> {
					if (i + 4 < text.length()) {
						try {
							out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
							i += 4;
						} catch (NumberFormatException ex) {
							out.append('u');
						}
					} else {
						out.append('u');
					}
				}
				default -> out.append(e);
			}
		}
		return out.toString();
	}

	public @Nullable String get(String key) {
		Entry e = this.entries.get(key);
		return e == null ? null : e.value;
	}

	public String get(String key, String fallback) {
		String value = this.get(key);
		return value == null ? fallback : value;
	}

	/** OptiFine's booleans: "true"/"on" are true, "false"/"off" false, anything else the fallback. */
	public boolean getBoolean(String key, boolean fallback) {
		String value = this.get(key);
		if (value == null) return fallback;
		return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
			case "true", "on" -> true;
			case "false", "off" -> false;
			default -> fallback;
		};
	}

	public List<Entry> entries() {
		return List.copyOf(this.entries.values());
	}

	/** Entries whose key starts with {@code prefix}, in file order. */
	public List<Entry> withPrefix(String prefix) {
		List<Entry> out = new ArrayList<>();
		for (Entry e : this.entries.values()) if (e.key.startsWith(prefix)) out.add(e);
		return out;
	}
}
