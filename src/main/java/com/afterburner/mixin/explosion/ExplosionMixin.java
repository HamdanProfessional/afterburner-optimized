package com.afterburner.mixin.explosion;

import com.afterburner.explosion.ExplosionRays;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.List;

/** Works out which blocks an explosion blows up looking each block up once ({@link ExplosionRays}). */
@Mixin(Explosion.class)
public class ExplosionMixin {
	@Shadow
	@Final
	private Level level;
	@Shadow
	@Final
	private float radius;
	@Shadow
	@Final
	private ExplosionDamageCalculator damageCalculator;

	/** The positions worked out up front, or null when this explosion uses somebody else's resistance rules. */
	@Unique
	private @Nullable List<BlockPos> afterburner$positions;

	@Inject(method = "explode", at = @At("HEAD"))
	private void afterburner$blocksOnce(CallbackInfo ci) {
		Explosion self = (Explosion) (Object) this;
		if (this.level instanceof ServerLevel server && ExplosionRays.cacheable(this.damageCalculator)) {
			this.afterburner$positions = ExplosionRays.explodedPositions(self, server, self.center(), this.radius, this.damageCalculator);
		}
	}

	/** Vanilla walks each ray block by block; with the positions already worked out the walk can stop at its first step. */
	@WrapOperation(method = "explode", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"))
	private BlockPos afterburner$skipWalk(double x, double y, double z, Operation<BlockPos> original) {
		if (this.afterburner$positions == null) return original.call(x, y, z);
		// Outside the world: vanilla's walk stops before it asks for the block's resistance.
		return new BlockPos(0, Integer.MAX_VALUE, 0);
	}

	@WrapOperation(method = "explode", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;addAll(Ljava/util/Collection;)Z"))
	private boolean afterburner$addOnce(ObjectArrayList<BlockPos> toBlow, Collection<? extends BlockPos> positions, Operation<Boolean> original) {
		List<BlockPos> workedOut = this.afterburner$positions;
		return original.call(toBlow, workedOut != null ? workedOut : positions);
	}
}
