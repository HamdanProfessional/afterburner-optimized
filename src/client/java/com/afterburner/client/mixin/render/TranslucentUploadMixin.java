package com.afterburner.client.mixin.render;

import com.afterburner.client.render.TranslucentCulling;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;

/**
 * Lets a section's translucent sorting know where the camera is, and hands the split of each sorted index buffer to
 * the mesh when the buffer reaches the graphics card (see {@link TranslucentCulling}).
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class TranslucentUploadMixin {
	@ModifyReturnValue(method = "createVertexSorting", at = @At("RETURN"))
	private VertexSorting afterburner$relative(VertexSorting sorting, SectionPos sectionPos, Vec3 cameraPos) {
		return new TranslucentCulling.RelativeSorting(sorting, (float) (cameraPos.x - sectionPos.minBlockX()), (float) (cameraPos.y - sectionPos.minBlockY()),
				(float) (cameraPos.z - sectionPos.minBlockZ()));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@WrapOperation(method = "addSectionBuffersToUberBuffer", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/UberGpuBuffer;addAllocation(Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/UberGpuBuffer$UploadCallback;Ljava/nio/ByteBuffer;)Z",
			ordinal = 1))
	private boolean afterburner$keepSplit(UberGpuBuffer buffers, Object key, UberGpuBuffer.UploadCallback callback, ByteBuffer indices, Operation<Boolean> original,
			@Local(argsOnly = true) ChunkSectionLayer layer) {
		if (layer != ChunkSectionLayer.TRANSLUCENT) return original.call(buffers, key, callback, indices);
		long split = TranslucentCulling.take(indices);
		int front = split < 0 ? 0 : (int) (split >>> 32), view = split < 0 ? TranslucentCulling.NO_PARTITION : (int) split;
		UberGpuBuffer.UploadCallback<CompiledSectionMesh> withSplit = mesh -> {
			((TranslucentCulling.Partitioned) mesh).afterburner$setPartition(front, view);
			callback.bufferHasBeenUploaded(mesh);
		};
		return original.call(buffers, key, withSplit, indices);
	}
}
