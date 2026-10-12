package com.afterburner.client.mixin.perf;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * The F3 screen's text (some 40 lines of formatting, counts and lookups) and the two looks for the block and liquid aimed
 * at, made 10 times a second instead of every frame; the lines drawn in between are the last ones made. Other mods'
 * lines are made with the game's, so they come along.
 */
@Mixin(DebugScreenOverlay.class)
public class DebugScreenMixin {
	@Unique
	private static final long REFRESH_NANOS = 100_000_000L;

	@Shadow
	private HitResult block;
	@Shadow
	private HitResult liquid;

	@Unique
	private long afterburner$madeAt = Long.MIN_VALUE;
	@Unique
	private boolean afterburner$due;
	@Unique
	private List<String> afterburner$game, afterburner$system;

	@Inject(method = "render", at = @At("HEAD"))
	private void afterburner$frame(CallbackInfo ci) {
		long now = System.nanoTime();
		afterburner$due = afterburner$game == null || afterburner$system == null || block == null || liquid == null
				|| now - afterburner$madeAt >= REFRESH_NANOS;
		if (afterburner$due) afterburner$madeAt = now;
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"))
	private HitResult afterburner$pick(Entity entity, double reach, float partialTick, boolean fluids, Operation<HitResult> original) {
		if (afterburner$due) return original.call(entity, reach, partialTick, fluids);
		return fluids ? liquid : block;
	}

	@WrapOperation(method = "drawGameInformation", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;getGameInformation()Ljava/util/List;"))
	private List<String> afterburner$game(DebugScreenOverlay overlay, Operation<List<String>> original) {
		if (afterburner$due || afterburner$game == null) afterburner$game = original.call(overlay);
		// The caller adds lines to it.
		return new ArrayList<>(afterburner$game);
	}

	@WrapOperation(method = "drawSystemInformation", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;getSystemInformation()Ljava/util/List;"))
	private List<String> afterburner$system(DebugScreenOverlay overlay, Operation<List<String>> original) {
		if (afterburner$due || afterburner$system == null) afterburner$system = original.call(overlay);
		return new ArrayList<>(afterburner$system);
	}
}
