package com.afterburner.client.mixin.render;

import com.afterburner.Afterburner;
import com.afterburner.client.render.OcclusionRays;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;
import java.util.function.Consumer;

/**
 * Vanilla's visibility update walks a line from a section corner to the camera in its loop
 * {@code while (checkPos.distanceSquared(camera) > 3600) { checkPos.add(step); ... }}. The first time that check comes up
 * for a line, this answers it from {@link OcclusionRays}: a line that gets through ends the loop at once, and for one
 * that is stopped, the line is moved to just before the stopping step, so vanilla takes that step and stops itself.
 */
@Mixin(SectionOcclusionGraph.class)
public class OcclusionRayMixin {
	@Shadow
	private @Nullable ViewArea viewArea;

	@Inject(method = "runUpdates", at = @At("HEAD"))
	private void afterburner$beginLines(SectionOcclusionGraph.GraphStorage storage, Vec3 cameraPos, Queue<SectionOcclusionGraph.Node> queue,
			boolean smartCull, Consumer<SectionRenderDispatcher.RenderSection> onSectionAdded, LongOpenHashSet emptySections,
			LongOpenHashSet loadedChunks, CallbackInfo ci, @Share("lines") LocalRef<OcclusionRays> lines) {
		ViewArea area = viewArea;
		lines.set(area == null ? null : OcclusionRays.begin(((ViewAreaAccessor) area).afterburner$sections(),
				((SectionToNodeMapAccessor) storage.sectionToNodeMap).afterburner$nodes(), cameraPos));
	}

	@Inject(method = "runUpdates", at = @At("RETURN"))
	private void afterburner$endLines(SectionOcclusionGraph.GraphStorage storage, Vec3 cameraPos, Queue<SectionOcclusionGraph.Node> queue,
			boolean smartCull, Consumer<SectionRenderDispatcher.RenderSection> onSectionAdded, LongOpenHashSet emptySections,
			LongOpenHashSet loadedChunks, CallbackInfo ci, @Share("lines") LocalRef<OcclusionRays> lines) {
		OcclusionRays rays = lines.get();
		if (rays != null) rays.end();
	}

	@WrapOperation(method = "runUpdates", at = @At(value = "INVOKE", target = "Lorg/joml/Vector3d;distanceSquared(DDD)D"))
	private double afterburner$rememberedLine(Vector3d checkPos, double camX, double camY, double camZ, Operation<Double> original,
			@Local(argsOnly = true) SectionOcclusionGraph.GraphStorage storage, @Share("lines") LocalRef<OcclusionRays> share) {
		OcclusionRays lines = share.get();
		ViewArea area = viewArea;
		if (lines == null || area == null || checkPos == lines.last) return original.call(checkPos, camX, camY, camZ);
		lines.last = checkPos;
		lines.lines++;
		double x = checkPos.x, y = checkPos.y, z = checkPos.z;
		// The check walks it vanilla's way, looking each section up in the view area and the node map.
		int full = OcclusionRays.CHECK ? afterburner$walk(lines, area, storage, x, y, z, camX, camY, camZ, 0, true) : 0;
		int index = lines.index(x, y, z);
		int known = lines.known(index);
		int result;
		if (known == OcclusionRays.THROUGH) {
			lines.remembered++;
			result = OcclusionRays.THROUGH;
		} else {
			if (known > 0) lines.resumed++;
			result = afterburner$walk(lines, area, storage, x, y, z, camX, camY, camZ, known, false);
			lines.remember(index, result);
		}
		if (OcclusionRays.CHECK) {
			lines.checked++;
			if (full != result && ++lines.different <= 20) {
				Afterburner.LOGGER.error("Visibility check failed for the line from {} {} {}: remembered {}, now {}", x, y, z, result, full);
			}
		}
		if (result == OcclusionRays.THROUGH) return 0.0;
		// Walking left the line just before the step that is stopped.
		checkPos.set(lines.pos);
		return original.call(checkPos, camX, camY, camZ);
	}

	/**
	 * Vanilla's walk from a corner, skipping the checks of the first {@code from} steps, which got through before. Returns
	 * {@link OcclusionRays#THROUGH}, or how many steps get through, with {@code lines.pos} left where they end. Sections are
	 * looked up {@link OcclusionRays#inView directly}, or the way vanilla does.
	 */
	@Unique
	private static int afterburner$walk(OcclusionRays lines, ViewArea area, SectionOcclusionGraph.GraphStorage storage,
			double x, double y, double z, double camX, double camY, double camZ, int from, boolean vanilla) {
		Vector3d pos = lines.pos.set(x, y, z);
		Vector3d step = lines.step.set(camX, camY, camZ).sub(pos).normalize().mul(OcclusionRays.STEP);
		for (int i = 0; i < from; i++) pos.add(step);
		int through = from, maxY = area.maxY(), minY = area.minY();
		while (pos.distanceSquared(camX, camY, camZ) > 3600.0) {
			double px = pos.x, py = pos.y, pz = pos.z;
			pos.add(step);
			lines.walked++;
			if (pos.y > maxY || pos.y < minY) return OcclusionRays.THROUGH;
			int bx = Mth.floor(pos.x), by = Mth.floor(pos.y), bz = Mth.floor(pos.z);
			boolean inView;
			if (vanilla) {
				SectionRenderDispatcher.RenderSection section = area.getRenderSectionAt(lines.block.set(bx, by, bz));
				inView = section != null && storage.sectionToNodeMap.get(section) != null;
			} else {
				inView = lines.inView(SectionPos.blockToSectionCoord(bx), SectionPos.blockToSectionCoord(by), SectionPos.blockToSectionCoord(bz));
			}
			if (!inView) {
				pos.set(px, py, pz);
				return through;
			}
			through++;
		}
		return OcclusionRays.THROUGH;
	}
}
