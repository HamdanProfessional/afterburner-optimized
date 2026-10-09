package com.afterburner.mixin.memory;

import com.afterburner.memory.SectionLocks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Every block and biome container gets the one-field thread check of {@link SectionLocks} instead of a ThreadingDetector. */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerMixin {
	/** See {@link SectionLocks}, which reads and writes it by name. */
	@Unique
	private volatile @Nullable Object afterburner$user;

	/** Leaves the detector field empty; {@link #acquire} and {@link #release} were its only users. */
	@WrapOperation(method = "<init>*", at = @At(value = "NEW", target = "net/minecraft/util/ThreadingDetector"))
	private @Nullable ThreadingDetector afterburner$noDetector(String name, Operation<ThreadingDetector> original) {
		return null;
	}

	/**
	 * @author Afterburner
	 * @reason The same check without the detector's four objects.
	 */
	@Overwrite
	public void acquire() {
		SectionLocks.acquire((PalettedContainer<?>) (Object) this);
	}

	/**
	 * @author Afterburner
	 * @reason The same check without the detector's four objects.
	 */
	@Overwrite
	public void release() {
		SectionLocks.release((PalettedContainer<?>) (Object) this);
	}
}
