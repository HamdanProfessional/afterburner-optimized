package com.afterburner.labs.mixin.shader;

import com.mojang.renderpearl.backend.opengl.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A program linked elsewhere (see the program link mixin), as the game's. */
@Mixin(GlProgram.class)
public interface GlProgramAccessor {
	@Invoker("<init>")
	static GlProgram afterburner$create(int programId, String debugLabel) {
		throw new AssertionError();
	}
}
