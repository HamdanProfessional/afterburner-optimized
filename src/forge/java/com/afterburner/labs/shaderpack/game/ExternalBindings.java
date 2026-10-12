package com.afterburner.labs.shaderpack.game;

import com.afterburner.labs.shaderpack.DriverGlsl;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;

/**
 * What the game's pipelines can't bind for a pack, bound by us: its custom images (and the samplers that read them) and its
 * storage buffers. The game's pipeline builder takes a pipeline's uniforms from its declared layout, which has no images or
 * storage buffers, and throws on anything else a shader declares; so ours are taken out of the shaders' descriptors (into
 * set 1, where the game puts nothing), and set by name when the program is linked: a sampler or image to a unit of ours, a
 * storage buffer to its bufferObject index. {@link PackStorage} keeps those units bound.
 */
public final class ExternalBindings {
	public enum Kind { SAMPLER, IMAGE, BUFFER }

	/**
	 * {@code binding} is unique to the name and gives its name in the program ({@code _uniform_01_NN}); {@code unit} is the texture
	 * unit, image unit or storage buffer binding it reads.
	 */
	public record Slot(Kind kind, int binding, int unit) {}

	/** Descriptor set 1: the game's are all in set 0. */
	private static final int SET = 1;

	private static volatile Map<String, Slot> slots = Map.of();

	private ExternalBindings() {
	}

	/** Set while a pack is loaded, before its pipelines are compiled. */
	static void set(Map<String, Slot> map) {
		slots = Map.copyOf(map);
	}

	static void clear() {
		slots = Map.of();
	}

	static @Nullable Slot get(String name) {
		return slots.get(name);
	}

	/** Whether a pipeline is a pack's (ours to change). */
	private static boolean isPack(RenderPipeline pipeline) {
		var location = pipeline.getLocation();
		return location.getNamespace().equals("afterburner") && location.getPath().startsWith("shaderpack/");
	}

	/**
	 * The pipeline builder's look at a shader's descriptors: ours moved to set 1 at their own binding, and left out of what the
	 * builder checks against the pipeline's layout.
	 */
	public static List<SpvModule.Reflection.Descriptor> descriptors(RenderPipeline pipeline, List<SpvModule.Reflection.Descriptor> descriptors) {
		Map<String, Slot> map = slots;
		if (map.isEmpty() || !isPack(pipeline)) return descriptors;
		List<SpvModule.Reflection.Descriptor> out = null;
		for (int i = 0; i < descriptors.size(); i++) {
			SpvModule.Reflection.Descriptor d = descriptors.get(i);
			Slot slot = map.get(d.name());
			if (slot == null) {
				if (out != null) out.add(d);
				continue;
			}
			if (out == null) out = new ArrayList<>(descriptors.subList(0, i));
			d.descriptorSetIndex(SET);
			d.binding(slot.binding);
		}
		return out == null ? descriptors : out;
	}

	/** A program was linked and is in use: our samplers and images to their units, storage buffers to their bindings. */
	public static void setup(int program) {
		Map<String, Slot> map = slots;
		if (map.isEmpty()) return;
		for (Slot slot : map.values()) {
			String name = String.format(Locale.ROOT, "_uniform_%02d_%02d", SET, slot.binding);
			if (slot.kind == Kind.BUFFER) {
				int index = GL43C.glGetProgramResourceIndex(program, GL43C.GL_SHADER_STORAGE_BLOCK, name);
				if (index != GL43C.GL_INVALID_INDEX) GL43C.glShaderStorageBlockBinding(program, index, slot.unit);
			} else {
				int location = GL20C.glGetUniformLocation(program, name);
				if (location != -1) GL20C.glUniform1i(location, slot.unit);
			}
		}
	}

	/** A shader's GLSL as the game made it for the driver, with the extensions it needs (see {@link DriverGlsl}). */
	public static String source(String source) {
		return DriverGlsl.source(source);
	}
}
