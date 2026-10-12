package com.afterburner.labs.shaderpack.game;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;

/**
 * The texture units of a pack's samplers: one per sampler name (colortex0, shadowtex1, an image's sampler, ...) for all its
 * programs, from {@link Gl#FIRST_UNIT} on, so a texture bound for one program is bound for the next that reads it.
 */
final class PackSlots {
	private final Object2IntOpenHashMap<String> slots = new Object2IntOpenHashMap<>();
	private final List<String> names = new ArrayList<>();

	PackSlots() {
		this.slots.defaultReturnValue(-1);
	}

	/** The slot of a sampler name, given one if it has none yet. */
	int slot(String name) {
		int s = this.slots.getInt(name);
		if (s < 0) {
			s = this.names.size();
			this.slots.put(name, s);
			this.names.add(name);
		}
		return s;
	}

	String name(int slot) {
		return this.names.get(slot);
	}

	int size() {
		return this.names.size();
	}

	static int unit(int slot) {
		return Gl.FIRST_UNIT + slot;
	}

	/** Whether the slot has a texture unit: past the last one, the program can't be drawn. */
	static boolean fits(int slot) {
		return unit(slot) < Gl.units();
	}
}
