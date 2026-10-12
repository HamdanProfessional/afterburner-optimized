package com.afterburner.client.mixin.render;

import com.afterburner.Afterburner;
import com.afterburner.client.render.LightMemo;
import it.unimi.dsi.fastutil.longs.Long2FloatLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Vanilla's light cache for one builder thread also keeps the whole section being built, see {@link LightMemo}. Vanilla's
 * own 100 entries are still filled in as before; only the lookups are answered from here first.
 */
@Mixin(targets = "net.minecraft.client.renderer.block.ModelBlockRenderer$Cache")
public class LightMemoMixin implements LightMemo {
	/** The section and one block around it. */
	@Unique
	private static final int SIZE = 18;
	/** Vanilla's "not in the cache" for light. */
	@Unique
	private static final int NO_LIGHT = Integer.MAX_VALUE;

	@Shadow
	private boolean enabled;

	@Unique
	private final int[] afterburner$light = new int[SIZE * SIZE * SIZE], afterburner$lightAt = new int[SIZE * SIZE * SIZE];
	@Unique
	private final float[] afterburner$shade = new float[SIZE * SIZE * SIZE];
	@Unique
	private final int[] afterburner$shadeAt = new int[SIZE * SIZE * SIZE];
	@Unique
	private int afterburner$stamp;
	@Unique
	private boolean afterburner$anchored;
	@Unique
	private int afterburner$minX, afterburner$minY, afterburner$minZ;
	@Unique
	private long afterburner$lookups, afterburner$remembered, afterburner$checked, afterburner$wrong;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void afterburner$register(CallbackInfo ci) {
		ALL.add(this);
	}

	@Override
	public void afterburner$anchor(SectionPos section) {
		if (++afterburner$stamp == 0) {
			Arrays.fill(afterburner$lightAt, 0);
			Arrays.fill(afterburner$shadeAt, 0);
			afterburner$stamp = 1;
		}
		afterburner$minX = section.minBlockX() - 1;
		afterburner$minY = section.minBlockY() - 1;
		afterburner$minZ = section.minBlockZ() - 1;
		afterburner$anchored = true;
	}

	@Inject(method = "disable", at = @At("HEAD"))
	private void afterburner$unanchor(CallbackInfo ci) {
		afterburner$anchored = false;
	}

	/** Where a position is kept, or -1 outside the section and the block around it. */
	@Unique
	private int afterburner$index(int x, int y, int z) {
		x -= afterburner$minX;
		y -= afterburner$minY;
		z -= afterburner$minZ;
		return x >= 0 && x < SIZE && y >= 0 && y < SIZE && z >= 0 && z < SIZE ? (y * SIZE + z) * SIZE + x : -1;
	}

	/** Only reached while caching is on. */
	@Redirect(method = "getLightColor", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2IntLinkedOpenHashMap;get(J)I"))
	private int afterburner$rememberedLight(Long2IntLinkedOpenHashMap cache, long key) {
		if (afterburner$anchored) {
			int i = afterburner$index(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
			if (i >= 0) {
				afterburner$lookups++;
				if (afterburner$lightAt[i] != afterburner$stamp) return NO_LIGHT;
				afterburner$remembered++;
				// Check mode reads it again (below), which compares.
				return CHECK ? NO_LIGHT : afterburner$light[i];
			}
		}
		return cache.get(key);
	}

	@Redirect(method = "getLightColor", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I"))
	private int afterburner$light(BlockAndTintGetter level, BlockState state, BlockPos pos) {
		int light = LevelRenderer.getLightColor(level, state, pos);
		if (enabled && afterburner$anchored) {
			int i = afterburner$index(pos.getX(), pos.getY(), pos.getZ());
			if (i >= 0) {
				if (CHECK && afterburner$lightAt[i] == afterburner$stamp) {
					afterburner$checked++;
					if (afterburner$light[i] != light && ++afterburner$wrong <= 20) {
						Afterburner.LOGGER.error("Smooth lighting check failed at {}: remembered {}, now {}", pos.immutable(), afterburner$light[i], light);
					}
				}
				afterburner$light[i] = light;
				afterburner$lightAt[i] = afterburner$stamp;
			}
		}
		return light;
	}

	/** Only reached while caching is on. */
	@Redirect(method = "getShadeBrightness", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2FloatLinkedOpenHashMap;get(J)F"))
	private float afterburner$rememberedShade(Long2FloatLinkedOpenHashMap cache, long key) {
		if (afterburner$anchored) {
			int i = afterburner$index(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
			if (i >= 0) return afterburner$shadeAt[i] == afterburner$stamp ? afterburner$shade[i] : Float.NaN;
		}
		return cache.get(key);
	}

	@Redirect(method = "getShadeBrightness", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;getShadeBrightness(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"))
	private float afterburner$shade(BlockState state, BlockGetter level, BlockPos pos) {
		float shade = state.getShadeBrightness(level, pos);
		if (enabled && afterburner$anchored) {
			int i = afterburner$index(pos.getX(), pos.getY(), pos.getZ());
			if (i >= 0) {
				afterburner$shade[i] = shade;
				afterburner$shadeAt[i] = afterburner$stamp;
			}
		}
		return shade;
	}

	@Override
	public long[] afterburner$stats() {
		return new long[] {afterburner$lookups, afterburner$remembered, afterburner$checked, afterburner$wrong};
	}

	@Override
	public void afterburner$resetStats() {
		afterburner$lookups = afterburner$remembered = afterburner$checked = afterburner$wrong = 0;
	}
}
