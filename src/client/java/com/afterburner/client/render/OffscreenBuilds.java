package com.afterburner.client.render;

import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

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
	/** A frustum that takes in everything, to walk the whole visibility graph. */
	private static final Frustum EVERYWHERE = new Frustum(new Matrix4f(), new Matrix4f()) {
		@Override
		public int cubeInFrustum(BoundingBox box) {
			return FrustumIntersection.INSIDE;
		}

		@Override
		public boolean isVisible(AABB box) {
			return true;
		}
	};

	/** The nearest sections of one look, nearest first. Render thread only. */
	private static final SectionRenderDispatcher.RenderSection[] PICKED = new SectionRenderDispatcher.RenderSection[PER_FRAME];
	private static final double[] PICKED_DISTANCE = new double[PER_FRAME];
	private static int picked, wait;
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
	public static void schedule(LevelRenderer renderer, ClientLevel level, SectionUpdateTracker tracker, Vec3 camera) {
		if (wait > 0) {
			wait--;
			return;
		}
		// The last look found nothing, and nothing happened since that could change that.
		if (changes == changesAtLastLook) return;
		SectionRenderDispatcher dispatcher = renderer.sectionRenderDispatcher();
		if (dispatcher == null || dispatcher.getCompileQueueSize() >= QUEUE) return;
		int changesAtStart = changes;
		picked = 0;
		boolean timed = Probe.get().timesScans();
		long start = timed ? System.nanoTime() : 0;
		renderer.sectionOcclusionGraph().getOctree().visitNodes((node, fullyVisible, depth, isClose) -> {
			SectionRenderDispatcher.RenderSection section = node.getSection();
			if (section == null || section.sectionMesh.get() != CompiledSectionMesh.UNCOMPILED) return;
			SectionUpdateTracker.SectionDirtyState dirty = tracker.getDirtyState(section.getSectionNode());
			if (dirty == null || !dirty.isDirty()) return;
			BlockPos origin = section.getRenderOrigin();
			double dx = origin.getX() + 8 - camera.x, dy = origin.getY() + 8 - camera.y, dz = origin.getZ() + 8 - camera.z;
			double distance = dx * dx + dy * dy + dz * dz;
			if (picked == PER_FRAME && distance >= PICKED_DISTANCE[PER_FRAME - 1]) return;
			// Like vanilla: a section's first build waits for the chunks around it.
			if (!tracker.hasAllNeighbors(level, section.getSectionNode())) return;
			pick(section, distance);
		}, EVERYWHERE, 0);
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
			SectionUpdateTracker.SectionDirtyState dirty = tracker.getDirtyState(section.getSectionNode());
			if (dirty == null || !dirty.isDirty()) continue;
			section.compileAsync(cache.createRegion(level, section.getSectionNode()));
			dirty.setNotDirty();
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
