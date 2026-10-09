package com.afterburner.mixin.tick;

import com.afterburner.tick.TickingSections;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** The random tick loop walks the chunk's array with the quiet sections replaced, see {@link TickingSections}. */
@Mixin(ServerLevel.class)
public abstract class RandomTickLevelMixin implements TickingSections.Quiet {
	@Unique
	private @Nullable LevelChunkSection afterburner$quiet;

	@ModifyExpressionValue(method = "tickChunk",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
	private LevelChunkSection[] afterburner$tickingSections(LevelChunkSection[] sections, @Local(argsOnly = true) LevelChunk chunk) {
		return TickingSections.pick(sections, chunk);
	}

	/** Never written to: nothing in it random-ticks, so the loop passes it by. */
	@Override
	public LevelChunkSection afterburner$quietSection() {
		LevelChunkSection quiet = afterburner$quiet;
		if (quiet == null) afterburner$quiet = quiet = new LevelChunkSection(((ServerLevel) (Object) this).palettedContainerFactory());
		return quiet;
	}
}
