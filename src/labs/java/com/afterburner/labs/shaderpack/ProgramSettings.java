package com.afterburner.labs.shaderpack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a program's source tells the loader besides its code: the buffers it draws to ({@code /* DRAWBUFFERS:017 *}{@code /} or
 * {@code /* RENDERTARGETS: 0,1,7 *}{@code /}) and the {@code const} settings OptiFine reads (colortex0Format, shadowMapResolution,
 * sunPathRotation, ...). Like OptiFine, settings count in code and in comments, but only in active (not #if'd out) lines; the last
 * one wins.
 */
public final class ProgramSettings {
	private static final Pattern DRAW_BUFFERS = Pattern.compile("DRAWBUFFERS\\s*:\\s*([0-9A-Fa-f]+)");
	private static final Pattern RENDER_TARGETS = Pattern.compile("RENDERTARGETS\\s*:\\s*([0-9]+(?:\\s*,\\s*[0-9]+)*)");
	private static final Pattern CONST = Pattern.compile("\\bconst\\s+(int|float|bool|vec4|ivec3|vec3|vec2)\\s+(\\w+)\\s*=\\s*([^;]+);");

	/** The buffers outputs 0, 1, ... write, or null if the program doesn't say. */
	public final int @Nullable [] drawBuffers;
	/** Setting name to its value as written ("RGBA16F", "2048", "true", "vec4(0.0, 0.0, 0.0, 1.0)"). */
	public final Map<String, String> consts;

	private ProgramSettings(int @Nullable [] drawBuffers, Map<String, String> consts) {
		this.drawBuffers = drawBuffers;
		this.consts = consts;
	}

	/** Reads a preprocessed stage. */
	public static ProgramSettings of(GlslPreprocessor.Result source) {
		int[] drawBuffers = null;
		Map<String, String> consts = new LinkedHashMap<>();
		// Code and comments in line order, so "last one wins" is by position in the file.
		int comment = 0;
		for (int line = 0; line < source.lines.size(); line++) {
			readConsts(source.lines.get(line), consts);
			while (comment < source.comments.size() && source.comments.get(comment).line() <= line) {
				String text = source.comments.get(comment++).text();
				int[] found = drawBuffers(text);
				if (found != null) drawBuffers = found;
				readConsts(text, consts);
			}
		}
		while (comment < source.comments.size()) {
			String text = source.comments.get(comment++).text();
			int[] found = drawBuffers(text);
			if (found != null) drawBuffers = found;
			readConsts(text, consts);
		}
		return new ProgramSettings(drawBuffers, consts);
	}

	private static int @Nullable [] drawBuffers(String text) {
		int[] out = null;
		Matcher m = DRAW_BUFFERS.matcher(text);
		while (m.find()) {
			String digits = m.group(1);
			out = new int[digits.length()];
			for (int i = 0; i < digits.length(); i++) out[i] = Character.digit(digits.charAt(i), 16);
		}
		m = RENDER_TARGETS.matcher(text);
		while (m.find()) {
			String[] parts = m.group(1).split(",");
			out = new int[parts.length];
			for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].strip());
		}
		return out;
	}

	private static void readConsts(String text, Map<String, String> consts) {
		if (!text.contains("const")) return;
		Matcher m = CONST.matcher(text);
		while (m.find()) {
			consts.remove(m.group(2));
			consts.put(m.group(2), m.group(3).strip());
		}
	}
}
