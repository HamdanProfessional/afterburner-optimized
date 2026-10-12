package com.afterburner.client.render;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Where a measuring tool hears what the renderer is doing. The benchmark tools (src/bench, mod {@code afterburner_bench},
 * not released) set one with {@link #set}; without one, every call here does nothing.
 */
public interface Probe {
	/** The GPU work from here until the next mark is this part of the frame. */
	default void mark(String label) {
	}

	/** Before the frame's chunk draws: the sections in view, the camera and its matrices (the view's rotation only). */
	default void chunksInView(List<SectionRenderDispatcher.RenderSection> sections, double camX, double camY, double camZ, Matrix4f projection,
			Matrix4f modelView) {
	}

	/** The solid and cut-out terrain is in the depth buffer now. */
	default void opaqueDrawn() {
	}

	/** Whether the off-screen build scans are timed ({@link #offscreenScan}). */
	default boolean timesScans() {
		return false;
	}

	/** An off-screen build scan took this long and picked this many sections to build. */
	default void offscreenScan(long nanos, int picked) {
	}

	/** Whether a benchmark is measuring now, when nothing else should run (the memory trim waits). */
	default boolean busy() {
		return false;
	}

	static Probe get() {
		return Holder.probe;
	}

	/** At startup, before the first frame. */
	static void set(Probe probe) {
		Holder.probe = probe;
	}

	final class Holder {
		private static Probe probe = new Probe() {
		};

		private Holder() {
		}
	}
}
