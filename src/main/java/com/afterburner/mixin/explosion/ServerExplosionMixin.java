package com.afterburner.mixin.explosion;

import com.afterburner.explosion.ExplosionRays;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Works out which blocks an explosion blows up looking each block up once ({@link ExplosionRays}). */
@Mixin(ServerExplosion.class)
public class ServerExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;
	@Shadow
	@Final
	private Vec3 center;
	@Shadow
	@Final
	private float radius;
	@Shadow
	@Final
	private ExplosionDamageCalculator damageCalculator;

	@Inject(method = "calculateExplodedPositions", at = @At("HEAD"), cancellable = true)
	private void afterburner$cachedRays(CallbackInfoReturnable<List<BlockPos>> cir) {
		if (ExplosionRays.cacheable(this.damageCalculator)) {
			cir.setReturnValue(ExplosionRays.explodedPositions((Explosion) (Object) this, this.level, this.center, this.radius, this.damageCalculator));
		}
	}
}
