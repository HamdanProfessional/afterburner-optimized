package com.afterburner.client.render;

import com.afterburner.client.mixin.particle.FrustumInvoker;
import com.afterburner.client.mixin.particle.ParticleAccessor;
import com.afterburner.client.mixin.particle.ParticleViewAccessor;
import com.afterburner.client.mixin.render.ViewAreaAccessor;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Three things that make particles cheaper to draw, for {@link com.afterburner.client.mixin.particle}:
 * <ul>
 * <li>A particle out of view isn't drawn. The game draws every one, even those behind the camera.</li>
 * <li>A particle drawn only where the chunk sections in view leave out (behind terrain, as the game's own visibility
 * update finds) is skipped, as the terrain there is. One that reaches into a section in view, or past the sections the
 * game keeps, is drawn.</li>
 * <li>A particle's light is looked up in the world once and kept while it stays in the same block, until any block, light
 * or loaded chunk changes on the client ({@link #lightEpoch}): the same light, without 4 chunk lookups per particle per
 * frame.</li>
 * </ul>
 * Only the game's own flat particles are culled: they're drawn around where they are, as big as they say. Other mods'
 * particles may draw anything anywhere. Render thread only.
 */
public final class ParticleCulling {
	/** Counts every change on the client that can change a block's light: a kept light from before is looked up again. */
	public static int lightEpoch;
	private static final ClassValue<Boolean> CULLED = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			return SingleQuadParticle.class.isAssignableFrom(type) && type.getName().startsWith("net.minecraft.");
		}
	};

	/** The particles drawn in the world this frame are culled; null outside of that. */
	private static @Nullable Frustum frustum;
	private static @Nullable ViewArea area;
	private static int frame;
	private static boolean anyVisible;
	private static final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
	/** Particles left out, for the benchmark report. */
	public static long culled;

	private ParticleCulling() {
	}

	/** Before the particles of the world are drawn this frame. */
	public static void beginFrame(LevelRenderer renderer) {
		ParticleViewAccessor view = (ParticleViewAccessor) renderer;
		Frustum captured = view.afterburner$capturedFrustum();
		frustum = captured != null ? captured : view.afterburner$cullingFrustum();
		area = view.afterburner$viewArea();
		frame++;
		List<SectionRenderDispatcher.RenderSection> visible = view.afterburner$visibleSections();
		// Before the first visibility update (joining, reloading) nothing is listed: then no section is left out.
		anyVisible = !visible.isEmpty();
		for (int i = 0, n = visible.size(); i < n; i++) ((SeenSection) visible.get(i)).afterburner$setSeen(frame);
	}

	public static void endFrame() {
		frustum = null;
		area = null;
	}

	/** Whether to leave out this particle, about to be drawn. */
	public static boolean skip(Particle particle, float partialTick) {
		Frustum frustum = ParticleCulling.frustum;
		if (frustum == null || !CULLED.get(particle.getClass())) return false;
		ParticleAccessor p = (ParticleAccessor) particle;
		double x = Mth.lerp(partialTick, p.afterburner$xo(), p.afterburner$x());
		double y = Mth.lerp(partialTick, p.afterburner$yo(), p.afterburner$y());
		double z = Mth.lerp(partialTick, p.afterburner$zo(), p.afterburner$z());
		double r = ((SingleQuadParticle) particle).getQuadSize(partialTick) * 1.5;
		boolean skip = !((FrustumInvoker) frustum).afterburner$cubeInFrustum(x - r, y - r, z - r, x + r, y + r, z + r)
				|| hidden(x - r, y - r, z - r, x + r, y + r, z + r);
		if (skip) culled++;
		return skip;
	}

	/** Whether everything between these corners is in chunk sections the game finds hidden this frame. */
	private static boolean hidden(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		ViewArea area = ParticleCulling.area;
		if (area == null || !anyVisible) return false;
		int x0 = Mth.floor(minX) >> 4, y0 = Mth.floor(minY) >> 4, z0 = Mth.floor(minZ) >> 4;
		int x1 = Mth.floor(maxX) >> 4, y1 = Mth.floor(maxY) >> 4, z1 = Mth.floor(maxZ) >> 4;
		// Big ones (a firework's flash) are drawn.
		if (x1 - x0 > 1 || y1 - y0 > 1 || z1 - z0 > 1) return false;
		for (int sx = x0; sx <= x1; sx++) {
			for (int sy = y0; sy <= y1; sy++) {
				for (int sz = z0; sz <= z1; sz++) {
					SectionRenderDispatcher.RenderSection section = ((ViewAreaAccessor) area).afterburner$sectionAt(cursor.set(sx << 4, sy << 4, sz << 4));
					// Past the sections the game keeps (above the world, past the view distance), or in view.
					if (section == null || !section.getOrigin().equals(cursor) || ((SeenSection) section).afterburner$seen() == frame) return false;
					// A section of a chunk whose neighbours aren't all loaded is never listed, seen or not: it isn't hidden, just not ready.
					if (!section.hasAllNeighbors()) return false;
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
