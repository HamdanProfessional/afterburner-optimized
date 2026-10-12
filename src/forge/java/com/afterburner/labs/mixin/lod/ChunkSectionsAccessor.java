package com.afterburner.labs.mixin.lod;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The far terrain draws with the same view matrix as the chunks. */
@Mixin(ChunkSectionsToRender.class)
public interface ChunkSectionsAccessor {
	@Accessor("terrainTransformUBO")
	GpuBufferSlice afterburner$terrainTransformUBO();
}
