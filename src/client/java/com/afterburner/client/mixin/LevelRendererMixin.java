package com.afterburner.client.mixin;

import com.afterburner.client.render.ChunkRegions;
import com.afterburner.client.render.FaceSortedMesh;
import com.afterburner.client.render.RegionMesh;
import com.afterburner.client.render.SectionFaces;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** Draws only the face groups of each section that the camera can see (see {@link SectionFaces}). */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;
	@Shadow
	private boolean usingMultiDrawIndirectForTerrain;

	@WrapOperation(method = "extractSectionDrawGroups", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
	private boolean afterburner$drawVisibleFaces(List<Object> list, Object element, Operation<Boolean> original,
			@Local SectionRenderDispatcher.RenderSection section, @Local SectionMesh mesh, @Local ChunkSectionLayer layer) {
		if (element instanceof DynamicGpuData.IndexedDraw draw && mesh instanceof FaceSortedMesh sorted) {
			SectionFaces faces = sorted.afterburner$faces(layer);
			if (faces != null) {
				Vec3 camera = levelRenderState.cameraRenderState.pos;
				BlockPos origin = section.getRenderOrigin();
				boolean inRegion = mesh instanceof RegionMesh regional && regional.afterburner$inRegion();
				int ox = inRegion ? ChunkRegions.originX(origin.getX()) : origin.getX();
				int oy = inRegion ? ChunkRegions.originY(origin.getY()) : origin.getY();
				int oz = inRegion ? ChunkRegions.originZ(origin.getZ()) : origin.getZ();
				if (faces.addVisible(list, draw, camera.x - ox, camera.y - oy, camera.z - oz, usingMultiDrawIndirectForTerrain)) return true;
			}
		}
		return original.call(list, element);
	}
}
