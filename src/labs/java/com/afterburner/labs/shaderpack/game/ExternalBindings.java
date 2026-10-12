package com.afterburner.labs.shaderpack.game;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL43C;

/**
 * A pack's custom images (and the samplers that read them) and its storage buffers, by their names in its programs: each is
 * set to its unit (a texture unit, an image unit, or a storage buffer binding) when a program is linked. {@link PackStorage}
 * keeps those units bound.
 */
public final class ExternalBindings {
	public enum Kind { SAMPLER, IMAGE, BUFFER }

	/** {@code unit} is the texture unit, image unit or storage buffer binding the name reads. */
	public record Slot(Kind kind, int unit) {}

	private static volatile Map<String, Slot> slots = Map.of();

	private ExternalBindings() {
	}

	/** Set while a pack is loaded, before its programs are compiled. */
	static void set(Map<String, Slot> map) {
		slots = Map.copyOf(map);
	}

	static void clear() {
		slots = Map.of();
	}

	static @Nullable Slot get(String name) {
		return slots.get(name);
	}

	/** A program was linked and is in use: samplers and images to their units, storage buffers to their bindings. */
	static void setup(Gl.Program program) {
		Map<String, Slot> map = slots;
		if (map.isEmpty()) return;
		for (Map.Entry<String, Slot> e : map.entrySet()) {
			Slot slot = e.getValue();
			if (slot.kind == Kind.BUFFER) {
				int index = GL43C.glGetProgramResourceIndex(program.id, GL43C.GL_SHADER_STORAGE_BLOCK, e.getKey());
				if (index != GL43C.GL_INVALID_INDEX) GL43C.glShaderStorageBlockBinding(program.id, index, slot.unit);
			} else {
				program.uniform(e.getKey(), slot.unit);
			}
		}
	}
}
