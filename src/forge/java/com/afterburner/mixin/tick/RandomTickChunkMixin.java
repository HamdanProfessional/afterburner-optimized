package com.afterburner.mixin.tick;

import com.afterburner.tick.TickingSections;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A server chunk gets its random tick array when it's made (every other constructor calls this one), see {@link TickingSections}. */
@Mixin(LevelChunk.class)
public abstract class RandomTickChunkMixin implements TickingSections.Owner {
	@Shadow
	@Final
	private Level level;
	@Unique
	private LevelChunkSection @Nullable [] afterburner$ticking;

	@Inject(method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;Lnet/minecraft/world/ticks/LevelChunkTicks;Lnet/minecraft/world/ticks/LevelChunkTicks;J[Lnet/minecraft/world/level/chunk/LevelChunkSection;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V",
			at = @At("RETURN"))
	private void afterburner$tickingSections(CallbackInfo ci) {
		if (!(level instanceof ServerLevel server)) return;
		LevelChunkSection quiet = ((TickingSections.Quiet) server).afterburner$quietSection();
		LevelChunkSection[] sections = ((LevelChunk) (Object) this).getSections();
		LevelChunkSection[] ticking = new LevelChunkSection[sections.length];
		for (int i = 0; i < sections.length; i++) {
			((TickingSections.Owned) sections[i]).afterburner$own(ticking, i, quiet);
		}
		afterburner$ticking = ticking;
	}

	@Override
	public LevelChunkSection @Nullable [] afterburner$tickingSections() {
		return afterburner$ticking;
	}
}
