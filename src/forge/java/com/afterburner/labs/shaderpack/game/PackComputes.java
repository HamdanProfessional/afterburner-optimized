package com.afterburner.labs.shaderpack.game;

import com.afterburner.labs.shaderpack.GlslTranslator;
import com.afterburner.labs.shaderpack.PackLoader;
import com.afterburner.labs.shaderpack.UniformLayout;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

/**
 * A pack's compute programs (.csh), compiled by the driver as the translator writes them (GLSL 430: the game's shader
 * compiler takes vertex and fragment shaders only) and run outside the game's render passes, each followed by a memory barrier.
 * They read the same uniform blocks as the pack's other programs, its buffers and textures by sampler name, and its images and
 * storage buffers (see {@link PackStorage}).
 * <p>
 * How many work groups: {@code const ivec3 workGroups}, or {@code const vec2 workGroupsRender} (a part of the render size, in
 * invocations), which is the whole render size if the program gives neither.
 */
final class PackComputes implements AutoCloseable {
	/** Uniform buffer bindings of the frame and draw blocks: past those the game's programs use (one per block, from 0). */
	private static final int FRAME_BINDING = 20, DRAW_BINDING = 21;
	/** Texture units of the textures a compute program reads by name: below {@link PackStorage#FIRST_SAMPLER_UNIT}. */
	private static final int FIRST_UNIT = 32, UNITS = 16;
	private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");
	private static final Pattern LOCAL_SIZE = Pattern.compile("local_size_([xyz])\\s*=\\s*(\\d+)");
	private static final Pattern LINE = Pattern.compile("(?:^|\\D)0[:(](\\d+)");

	private record Program(String label, int id, int @Nullable [] groups, float[] renderScale, int[] local, List<String> textures) {}

	private final Map<String, List<Program>> passes = new LinkedHashMap<>();

	PackComputes(PackLoader.Loaded pack, List<String> warnings) {
		for (Map.Entry<String, List<PackLoader.ComputeProgram>> e : pack.computes.entrySet()) {
			List<Program> list = new ArrayList<>();
			for (PackLoader.ComputeProgram cp : e.getValue()) {
				Program p = compile(pack, cp, warnings);
				if (p != null) list.add(p);
			}
			if (!list.isEmpty()) this.passes.put(e.getKey(), list);
		}
	}

	private static @Nullable Program compile(PackLoader.Loaded pack, PackLoader.ComputeProgram cp, List<String> warnings) {
		String label = cp.path();
		GlslTranslator.Compute c;
		try {
			c = pack.translate(cp);
		} catch (GlslTranslator.TranslateException e) {
			warnings.add(label + ": " + e.getMessage());
			return null;
		}
		for (String w : c.warnings) warnings.add(label + ": " + w);

		int shader = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
		GL20C.glShaderSource(shader, c.source);
		GL20C.glCompileShader(shader);
		if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
			warnings.add(label + " didn't compile: " + withOrigins(GL20C.glGetShaderInfoLog(shader), c));
			GL20C.glDeleteShader(shader);
			return null;
		}
		int program = GL20C.glCreateProgram();
		GL20C.glAttachShader(program, shader);
		GL20C.glLinkProgram(program);
		GL20C.glDetachShader(program, shader);
		GL20C.glDeleteShader(shader);
		if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
			warnings.add(label + " didn't link: " + GL20C.glGetProgramInfoLog(program));
			GL20C.glDeleteProgram(program);
			return null;
		}

		GlStateManager._glUseProgram(program);
		block(program, UniformLayout.FRAME_BLOCK, FRAME_BINDING);
		block(program, UniformLayout.DRAW_BLOCK, DRAW_BINDING);
		List<String> textures = new ArrayList<>();
		for (String sampler : c.samplers) {
			int location = GL20C.glGetUniformLocation(program, sampler);
			if (location == -1) continue;
			ExternalBindings.Slot slot = ExternalBindings.get(sampler);
			if (slot != null && slot.kind() == ExternalBindings.Kind.SAMPLER) {
				GL20C.glUniform1i(location, slot.unit());
			} else if (textures.size() >= UNITS) {
				warnings.add(label + ": more than " + UNITS + " textures, " + sampler + " reads nothing");
			} else {
				GL20C.glUniform1i(location, FIRST_UNIT + textures.size());
				textures.add(sampler);
			}
		}
		for (String image : c.images) {
			int location = GL20C.glGetUniformLocation(program, image);
			if (location == -1) continue;
			ExternalBindings.Slot slot = ExternalBindings.get(image);
			if (slot != null && slot.kind() == ExternalBindings.Kind.IMAGE) GL20C.glUniform1i(location, slot.unit());
			else warnings.add(label + ": image " + image + " isn't one of the pack's images");
		}
		for (Map.Entry<String, Integer> b : c.buffers.entrySet()) {
			int index = GL43C.glGetProgramResourceIndex(program, GL43C.GL_SHADER_STORAGE_BLOCK, b.getKey());
			if (index != GL43C.GL_INVALID_INDEX && b.getValue() >= 0) GL43C.glShaderStorageBlockBinding(program, index, b.getValue());
		}
		GlStateManager._glUseProgram(0);

		int[] local = {1, 1, 1};
		Matcher m = LOCAL_SIZE.matcher(c.source);
		while (m.find()) local[m.group(1).charAt(0) - 'x'] = Math.max(1, Integer.parseInt(m.group(2)));
		int[] groups = null;
		float[] scale = {1, 1};
		String fixed = cp.directives().consts.get("workGroups");
		String render = cp.directives().consts.get("workGroupsRender");
		if (fixed != null) {
			double[] v = numbers(fixed);
			if (v.length == 3) groups = new int[] {(int) v[0], (int) v[1], (int) v[2]};
			else warnings.add(label + ": can't read workGroups = " + fixed);
		} else if (render != null) {
			double[] v = numbers(render);
			if (v.length == 2) scale = new float[] {(float) v[0], (float) v[1]};
			else warnings.add(label + ": can't read workGroupsRender = " + render);
		}
		return new Program(label, program, groups, scale, local, textures);
	}

	private static void block(int program, String name, int binding) {
		int index = GL31C.glGetUniformBlockIndex(program, name);
		if (index != GL31C.GL_INVALID_INDEX) GL31C.glUniformBlockBinding(program, index, binding);
	}

	private static double[] numbers(String text) {
		List<Double> out = new ArrayList<>();
		Matcher m = NUMBER.matcher(text.replaceAll("^\\s*[a-z0-9]+\\s*\\(", "("));
		while (m.find()) out.add(Double.parseDouble(m.group()));
		return out.stream().mapToDouble(Double::doubleValue).toArray();
	}

	/** A driver's compile log with "(file:line)" after the line numbers it names, where the pack's source says. */
	private static String withOrigins(String log, GlslTranslator.Compute c) {
		StringBuilder out = new StringBuilder();
		for (String line : log.strip().split("\n")) {
			out.append("\n  ").append(line.strip());
			Matcher m = LINE.matcher(line);
			if (m.find()) out.append(" (").append(c.origin(Integer.parseInt(m.group(1)))).append(')');
		}
		return out.toString();
	}

	boolean has(String pass) {
		return this.passes.containsKey(pass);
	}

	/**
	 * Runs a pass's compute programs. {@code textures} gives what a sampler name reads; {@code frame} and {@code draw} are the
	 * uniform blocks' buffers; the render size is for workGroupsRender.
	 */
	void run(String pass, Function<String, PackTextures.@Nullable Bound> textures, GpuBuffer frame, GpuBuffer draw, int width, int height) {
		List<Program> list = this.passes.get(pass);
		if (list == null) return;
		GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, FRAME_BINDING, ((GlBuffer) frame).handle());
		GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, DRAW_BINDING, ((GlBuffer) draw).handle());
		for (Program p : list) {
			GlStateManager._glUseProgram(p.id);
			for (int i = 0; i < p.textures.size(); i++) {
				PackTextures.Bound bound = textures.apply(p.textures.get(i));
				GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + FIRST_UNIT + i);
				if (bound == null) {
					GlStateManager._bindTexture(0);
					GL33C.glBindSampler(FIRST_UNIT + i, 0);
				} else {
					GlStateManager._bindTexture(((GlTextureView) bound.view()).glId());
					GL33C.glBindSampler(FIRST_UNIT + i, ((GlSampler) bound.sampler()).getId());
				}
			}
			GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
			int x, y, z;
			if (p.groups != null) {
				x = p.groups[0];
				y = p.groups[1];
				z = p.groups[2];
			} else {
				x = ceilDiv(Math.max(1, (int) Math.ceil(width * p.renderScale[0])), p.local[0]);
				y = ceilDiv(Math.max(1, (int) Math.ceil(height * p.renderScale[1])), p.local[1]);
				z = 1;
			}
			if (x > 0 && y > 0 && z > 0) GL43C.glDispatchCompute(x, y, z);
			GL42C.glMemoryBarrier(GL42C.GL_ALL_BARRIER_BITS);
		}
		GlStateManager._glUseProgram(0);
		GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, FRAME_BINDING, 0);
		GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, DRAW_BINDING, 0);
	}

	private static int ceilDiv(int a, int b) {
		return (a + b - 1) / b;
	}

	@Override
	public void close() {
		for (List<Program> list : this.passes.values()) for (Program p : list) GL20C.glDeleteProgram(p.id);
		this.passes.clear();
	}
}
