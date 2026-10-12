package com.afterburner.labs.mixin.shader;

import com.mojang.blaze3d.shaders.Uniform;
import java.util.List;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A game shader's samplers and uniforms, which a pack's program for it takes over (see PackPipelines). */
@Mixin(ShaderInstance.class)
public interface ShaderInstanceAccessor {
	/** The program the game last used for a shader: forgotten when ours is used, so the game's binds its own again. */
	@Accessor("lastProgramId")
	static void afterburner$setLastProgramId(int id) {
		throw new AssertionError();
	}

	@Accessor("samplerNames")
	List<String> afterburner$samplerNames();

	@Accessor("uniforms")
	List<Uniform> afterburner$uniforms();
}
