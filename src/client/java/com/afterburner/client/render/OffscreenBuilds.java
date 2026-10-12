package com.afterburner.client.render;

import com.afterburner.client.perf.FpsTarget;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;

/**
 * Builds the chunk sections the camera could see from where it is but isn't looking at, while the section builders
 * have little else to do. Vanilla builds a section only once it's on screen, so after joining or flying somewhere new,
 * turning around shows the world still building. Only sections the visibility graph reaches get built, so caves sealed
 * off from the camera are left alone, as before. And only first builds: a section off screen that changed is rebuilt
 * once it's looked at, as in vanilla, so once everything around is built this costs nothing.
 */
public final class OffscreenBuilds {
	/** Waiting section builds from this many on, nothing more is added: the builders are busy with what's on screen. */
	private static final int QUEUE = 8;
	/** Sections added per frame, at most. Each one has its blocks copied on the render thread first. */
	private static final int PER_FRAME = 8;
	/** Frames to skip after a look that found nothing to build. */
	private static final int IDLE_FRAMES = 20;
	/**
	 * At most one look at the visibility graph every this many frames. The look walks every node every time (vanilla's
	 * graph has no cheaper "which are dirty" query here), so running it every frame cost about 1% for nothing when the
	 * builders were already busy; work found a frame late is still picked up by the next look.
	 */
	private static final int SCAN_INTERVAL = 2;
	/** A frustum that takes in everything, to get every section the visibility graph reaches. */
	private static final Frustum EVERYWHERE = new Frustum(new Matrix4f(), new Matrix4f()) {
		@Override
		public boolean isVisible(AABB box) {
			return true;
		}
	};

	/** The sections the graph reaches, for one look. Render thread only. */
	private static final ArrayList<SectionRenderDispatcher.RenderSection> REACHED = new ArrayList<>();
	/** The nearest sections of one look, nearest first. Render thread only. */
	private static final SectionRenderDispatcher.RenderSection[] PICKED = new SectionRenderDispatcher.RenderSection[PER_FRAME];
	private static final double[] PICKED_DISTANCE = new double[PER_FRAME];
	private static int picked, wait;
	/** Frames since the last full-graph look, to hold the looks to one per {@link #SCAN_INTERVAL}. */
	private static int sinceScan = SCAN_INTERVAL;
	/** Bumped by whatever can give a section its first build: chunks and light data arriving, sections built (on any thread). */
	private static volatile int changes;
	private static int changesAtLastLook = -1;

	/** Sections built this way, for the benchmark report. Render thread only. */
	public static long built;

	private OffscreenBuilds() {
	}

	/** Something happened that may let more sections be built for the first time. */
	public static void changed() {
		changes++;
	}

	/** Once a frame, after the sections on screen were given to the builders. */
	public static void schedule(SectionOcclusionGraph graph, SectionRenderDispatcher dispatcher, ClientLevel level, Vec3 camera) {
		if (wait > 0) {
			wait--;
			return;
		}
		// The last look found nothing, and nothing happened since that could change that.
		if (changes == changesAtLastLook) return;
		// The visibility graph is walked in full each look, so do at most one every SCAN_INTERVAL frames.
		if (++sinceScan < SCAN_INTERVAL) return;
		// The builders are busy with what's on screen: don't look, but keep the interval elapsed so the next free frame looks.
		if (dispatcher.getToBatchCount() >= QUEUE) return;
		sinceScan = 0;
		int changesAtStart = changes;
		picked = 0;
		boolean timed = Probe.get().timesScans();
		long start = timed ? System.nanoTime() : 0;
		LevelLightEngine light = level.getLightEngine();
		int cameraX = SectionPos.posToSectionCoord(camera.x), cameraZ = SectionPos.posToSectionCoord(camera.z);
		graph.addSectionsInFrustum(EVERYWHERE, REACHED);
		for (int i = 0, n = REACHED.size(); i < n; i++) {
			SectionRenderDispatcher.RenderSection section = REACHED.get(i);
			if (section.getCompiled() != SectionRenderDispatcher.CompiledSection.UNCOMPILED || !section.isDirty()) continue;
			BlockPos origin = section.getOrigin();
			// Past a shorter draw distance the FPS target set, it wouldn't be drawn.
			if (!FpsTarget.drawn(cameraX, cameraZ, origin.getX() >> 4, origin.getZ() >> 4)) continue;
			double dx = origin.getX() + 8 - camera.x, dy = origin.getY() + 8 - camera.y, dz = origin.getZ() + 8 - camera.z;
			double distance = dx * dx + dy * dy + dz * dz;
			if (picked == PER_FRAME && distance >= PICKED_DISTANCE[PER_FRAME - 1]) continue;
			// Like vanilla: a section is built once its light is there.
			if (!light.lightOnInSection(SectionPos.of(origin))) continue;
			pick(section, distance);
		}
		REACHED.clear();
		if (timed) Probe.get().offscreenScan(System.nanoTime() - start, picked);
		if (picked == 0) {
			changesAtLastLook = changesAtStart;
			wait = IDLE_FRAMES;
			return;
		}
		RenderRegionCache cache = new RenderRegionCache();
		for (int i = 0; i < picked; i++) {
			SectionRenderDispatcher.RenderSection section = PICKED[i];
			PICKED[i] = null;
			if (!section.isDirty()) continue;
			section.rebuildSectionAsync(dispatcher, cache);
			section.setNotDirty();
			built++;
		}
	}

	/** Puts the section in the sorted picks, dropping the farthest when they're full. */
	private static void pick(SectionRenderDispatcher.RenderSection section, double distance) {
		int at = picked < PER_FRAME ? picked++ : PER_FRAME - 1;
		while (at > 0 && PICKED_DISTANCE[at - 1] > distance) {
			PICKED[at] = PICKED[at - 1];
			PICKED_DISTANCE[at] = PICKED_DISTANCE[at - 1];
			at--;
		}
		PICKED[at] = section;
		PICKED_DISTANCE[at] = distance;
	}
}
