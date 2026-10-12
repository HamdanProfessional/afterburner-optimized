package com.afterburner.client.mixin.render;

import com.afterburner.Afterburner;
import com.afterburner.client.render.OcclusionRays;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
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
 * {@code while (camera.subtract(point).lengthSqr() > 3600) { point = point.add(step); ... }}. The first step of a line is
 * answered from {@link OcclusionRays}: for a line that gets through, the step lands above the world, which ends vanilla's
 * loop with the line through; for one that is stopped, it lands where the stopping step does, so vanilla stops it there.
 */
@Mixin(SectionOcclusionGraph.class)
public class OcclusionRayMixin {
	/** Above any world: vanilla's loop takes the line as through. */
	@Unique
	private static final Vec3 ABOVE = new Vec3(0.0, Double.POSITIVE_INFINITY, 0.0);

	@Shadow
	private @Nullable ViewArea viewArea;

	@Inject(method = "runUpdates", at = @At("HEAD"))
	private void afterburner$beginLines(SectionOcclusionGraph.GraphStorage storage, Vec3 cameraPos, Queue<?> queue, boolean smartCull,
			Consumer<SectionRenderDispatcher.RenderSection> onSectionAdded, CallbackInfo ci, @Share("lines") LocalRef<OcclusionRays> lines) {
		ViewArea area = viewArea;
		lines.set(area == null ? null : OcclusionRays.begin(area, storage.sectionToNodeMap, cameraPos));
	}

	@Inject(method = "runUpdates", at = @At("RETURN"))
	private void afterburner$endLines(SectionOcclusionGraph.GraphStorage storage, Vec3 cameraPos, Queue<?> queue, boolean smartCull,
			Consumer<SectionRenderDispatcher.RenderSection> onSectionAdded, CallbackInfo ci, @Share("lines") LocalRef<OcclusionRays> lines) {
		OcclusionRays rays = lines.get();
		if (rays != null) rays.end();
	}

	@WrapOperation(method = "runUpdates", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;add(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 afterburner$rememberedLine(Vec3 point, Vec3 step, Operation<Vec3> original, @Local(argsOnly = true) Vec3 camera,
			@Share("lines") LocalRef<OcclusionRays> share) {
		OcclusionRays lines = share.get();
		if (lines == null || step == lines.last) return original.call(point, step);
		lines.last = step;
		lines.lines++;
		// The check walks it in full, looking each section up through the view area as vanilla does.
		int full = OcclusionRays.CHECK ? lines.walk(point, step, camera, 0, true) : 0;
		int index = lines.index(point.x, point.y, point.z);
		int known = lines.known(index);
		int result;
		if (known == OcclusionRays.THROUGH) {
			lines.remembered++;
			result = OcclusionRays.THROUGH;
		} else {
			if (known > 0) lines.resumed++;
			result = lines.walk(point, step, camera, known, false);
			lines.remember(index, result);
		}
		if (OcclusionRays.CHECK) {
			lines.checked++;
			if (full != result && ++lines.different <= 20) {
				Afterburner.LOGGER.error("Visibility check failed for the line from {}: remembered {}, now {}", point, result, full);
			}
		}
		if (result == OcclusionRays.THROUGH) return ABOVE;
		// A stopped line was walked (from where it was known to get), which left the point where its stopping step lands.
		return new Vec3(lines.x, lines.y, lines.z);
	}
}
