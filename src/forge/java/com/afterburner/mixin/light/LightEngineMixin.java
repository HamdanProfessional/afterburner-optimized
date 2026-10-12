package com.afterburner.mixin.light;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.lighting.LightEngine;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Looking up the block at a spot goes from the chunk map to the chunk to its section every time, and vanilla only
 * remembers the last two chunks. Light spreading from one spot touches blocks in up to 27 sections, so this remembers
 * the sections themselves, one per slot of a 4x4x4 grid, for one round of light updates (as long as vanilla keeps its
 * chunks). Chunks of an unusual kind, and spots above or below the world, still go the vanilla way.
 */
@Mixin(LightEngine.class)
public abstract class LightEngineMixin {
	@Unique
	private static final int SLOTS = 64;
	@Unique
	private static final long NO_KEY = Long.MAX_VALUE;

	/** Created on first use: the vanilla constructor clears the cache before this mixin's fields would be set. */
	@Unique
	private long @Nullable [] afterburner$keys;
	@Unique
	private LevelChunkSection @Nullable [] afterburner$sections;

	@Shadow
	protected abstract @Nullable LightChunk getChunk(int chunkX, int chunkZ);

	/**
	 * @author Afterburner
	 * @reason Remember the sections looked at during a round of light updates.
	 */
	@Overwrite
	protected BlockState getState(BlockPos pos) {
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		int sx = SectionPos.blockToSectionCoord(x), sy = SectionPos.blockToSectionCoord(y), sz = SectionPos.blockToSectionCoord(z);
		long key = SectionPos.asLong(sx, sy, sz);
		int slot = (sx & 3) << 4 | (sz & 3) << 2 | (sy & 3);
		if (afterburner$keys == null) afterburner$clear();
		LevelChunkSection section;
		if (afterburner$keys[slot] == key) {
			section = afterburner$sections[slot];
		} else {
			LightChunk chunk = getChunk(sx, sz);
			if (chunk == null) return Blocks.BEDROCK.defaultBlockState();
			section = afterburner$section(chunk, sy);
			if (section == null) return chunk.getBlockState(pos);
			afterburner$keys[slot] = key;
			afterburner$sections[slot] = section;
		}
		// What LevelChunk and ProtoChunk do for a spot inside the world.
		return section.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : section.getBlockState(x & 15, y & 15, z & 15);
	}

	/** The section, or null if the chunk should be asked itself. */
	@Unique
	private static @Nullable LevelChunkSection afterburner$section(LightChunk chunk, int sectionY) {
		LevelChunkSection[] sections;
		int minSectionY;
		if (chunk.getClass() == LevelChunk.class) {
			LevelChunk levelChunk = (LevelChunk) chunk;
			if (levelChunk.getLevel().isDebug()) return null;
			sections = levelChunk.getSections();
			minSectionY = levelChunk.getMinSectionY();
		} else if (chunk.getClass() == ImposterProtoChunk.class) {
			LevelChunk wrapped = ((ImposterProtoChunk) chunk).getWrapped();
			if (wrapped.getClass() != LevelChunk.class || wrapped.getLevel().isDebug()) return null;
			sections = wrapped.getSections();
			minSectionY = wrapped.getMinSectionY();
		} else if (chunk.getClass() == ProtoChunk.class) {
			ProtoChunk protoChunk = (ProtoChunk) chunk;
			sections = protoChunk.getSections();
			minSectionY = protoChunk.getMinSectionY();
		} else {
			return null;
		}
		int index = sectionY - minSectionY;
		return index >= 0 && index < sections.length ? sections[index] : null;
	}

	@Inject(method = "clearChunkCache", at = @At("TAIL"))
	private void afterburner$onClearChunks(CallbackInfo ci) {
		afterburner$clear();
	}

	@Unique
	private void afterburner$clear() {
		if (afterburner$keys == null) {
			afterburner$keys = new long[SLOTS];
			afterburner$sections = new LevelChunkSection[SLOTS];
		}
		Arrays.fill(afterburner$keys, NO_KEY);
		Arrays.fill(afterburner$sections, null);
	}
}
