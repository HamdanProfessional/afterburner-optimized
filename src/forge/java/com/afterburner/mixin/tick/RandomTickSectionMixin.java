package com.afterburner.mixin.tick;

import com.afterburner.tick.TickingSections;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A server chunk's section keeps its place in the chunk's random tick array right whenever its counts change, see {@link TickingSections}. */
@Mixin(LevelChunkSection.class)
public abstract class RandomTickSectionMixin implements TickingSections.Owned {
	@Unique
	private LevelChunkSection @Nullable [] afterburner$ticking;
	@Unique
	private int afterburner$index;
	@Unique
	private @Nullable LevelChunkSection afterburner$quiet;

	@Shadow
	public abstract boolean isRandomlyTicking();

	@Override
	public void afterburner$own(LevelChunkSection[] ticking, int index, LevelChunkSection quiet) {
		afterburner$ticking = ticking;
		afterburner$index = index;
		afterburner$quiet = quiet;
		afterburner$update(ticking);
	}

	/** The only two places a section's counts change after it's made. */
	@ModifyReturnValue(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
			at = @At("RETURN"))
	private BlockState afterburner$changed(BlockState previous) {
		LevelChunkSection[] ticking = afterburner$ticking;
		if (ticking != null) afterburner$update(ticking);
		return previous;
	}

	@Inject(method = "recalcBlockCounts", at = @At("TAIL"))
	private void afterburner$recounted(CallbackInfo ci) {
		LevelChunkSection[] ticking = afterburner$ticking;
		if (ticking != null) afterburner$update(ticking);
	}

	@Unique
	private void afterburner$update(LevelChunkSection[] ticking) {
		ticking[afterburner$index] = isRandomlyTicking() ? (LevelChunkSection) (Object) this : afterburner$quiet;
	}
}
