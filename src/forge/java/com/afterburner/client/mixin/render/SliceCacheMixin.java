package com.afterburner.client.mixin.render;

import com.afterburner.client.render.ChunkBatcher;
import com.afterburner.client.render.RegionMesh;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Whenever a mesh's place in a chunk buffer changes, it forgets the slices it remembered ({@link RegionMesh#afterburner$slice})
 * and the chunk batcher patches it into its next frame. */
@Mixin(UberGpuBuffer.class)
public class SliceCacheMixin {
	@Shadow
	@Final
	private Map<Object, ?> allocationMap;

	@WrapOperation(method = "uploadStagedAllocations", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
	private Object afterburner$forgetOnUpload(Map<Object, Object> map, Object key, Object value, Operation<Object> original) {
		moved(key);
		return original.call(map, key, value);
	}

	@Inject(method = "freeAllocation", at = @At("HEAD"))
	private void afterburner$forgetOnFree(Object key, CallbackInfo ci) {
		// Nothing moves for what isn't there (an empty section's mesh, one not uploaded yet).
		if (allocationMap.containsKey(key)) moved(key);
	}

	@Unique
	private static void moved(Object key) {
		if (key instanceof RegionMesh mesh) {
			mesh.afterburner$forgetSlices();
			// Its section's draws in the last frame are out of date: the next build looks them up again.
			if (mesh.afterburner$owner() != null) mesh.afterburner$owner().afterburner$meshChanged();
			ChunkBatcher.moved(mesh);
		} else {
			ChunkBatcher.changed();
		}
	}
}
