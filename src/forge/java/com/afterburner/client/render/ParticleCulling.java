package com.afterburner.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import com.afterburner.client.mixin.particle.OcclusionGraphAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

/**
 * Two things that make particles cheaper to draw, for {@link com.afterburner.client.mixin.particle}:
 * <ul>
 * <li>A particle drawn only where the chunk sections in view leave out (behind terrain, as the game's own visibility
 * update finds) is skipped, as the terrain there is. One that reaches into a section in view, a section of only air (which
 * the update never lists) or past the sections the game keeps, is drawn.</li>
 * <li>A particle's light is looked up in the world once and kept while it stays in the same block, until any block, light
 * or loaded chunk changes on the client ({@link #lightEpoch}): the same light, without 4 chunk lookups per particle per
 * frame.</li>
 * </ul>
 * Render thread only.
 */
public final class ParticleCulling {
	/** Counts every change on the client that can change a block's light: a kept light from before is looked up again. */
	public static int lightEpoch;

	private static int frame, markedFrame = -1;
	private static boolean anyVisible;
	private static final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

	private ParticleCulling() {
	}

	/** Before the particles of a frame are gathered. */
	public static void beginFrame() {
		frame++;
	}

	/** Whether everything between these corners is in chunk sections the game finds hidden this frame. */
	public static boolean hidden(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
		ViewArea area = renderer.viewArea();
		if (area == null) return false;
		if (markedFrame != frame) {
			markedFrame = frame;
			// Before the first visibility update (joining, reloading) nothing is listed: then nothing is left out.
			anyVisible = !renderer.visibleSections().isEmpty();
			for (SectionRenderDispatcher.RenderSection section : renderer.visibleSections()) ((SeenSection) section).afterburner$setSeen(frame);
		}
		if (!anyVisible) return false;
		int x0 = Mth.floor(minX) >> 4, y0 = Mth.floor(minY) >> 4, z0 = Mth.floor(minZ) >> 4;
		int x1 = Mth.floor(maxX) >> 4, y1 = Mth.floor(maxY) >> 4, z1 = Mth.floor(maxZ) >> 4;
		// Big ones (a firework's flash) are drawn.
		if (x1 - x0 > 1 || y1 - y0 > 1 || z1 - z0 > 1) return false;
		OcclusionGraphAccessor graph = (OcclusionGraphAccessor) renderer.sectionOcclusionGraph();
		for (int sx = x0; sx <= x1; sx++) {
			for (int sy = y0; sy <= y1; sy++) {
				for (int sz = z0; sz <= z1; sz++) {
					SectionRenderDispatcher.RenderSection section = area.getRenderSectionAt(cursor.set(sx << 4, sy << 4, sz << 4));
					if (section == null || ((SeenSection) section).afterburner$seen() == frame) return false;
					// Sections of only air (the sky) and those of chunks it waits for are never listed, seen or not.
					if (graph.afterburner$emptySections().contains(SectionPos.asLong(sx, sy, sz))
							|| !graph.afterburner$loadedChunks().contains(ChunkPos.pack(sx, sz))) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** A chunk section that remembers the last frame it was in view. */
	public interface SeenSection {
		int afterburner$seen();

		void afterburner$setSeen(int frame);
	}
}
