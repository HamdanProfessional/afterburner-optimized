package com.afterburner.explosion;

import com.afterburner.Afterburner;
import com.afterburner.mixin.explosion.EntityCalculatorAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Which blocks an explosion blows up, as vanilla works it out: 1352 rays from the center, each stepping 0.3 blocks at a
 * time and losing power to every block it goes through. Vanilla looks the block and fluid up again at every step, though
 * most steps land in a block the same ray or another one already looked at (a TNT explosion takes about 30,000 steps
 * through about 1,500 blocks). Here each block is looked up once per explosion, with its resistance; the rays, their
 * random power and the order blocks are found in stay the same, so the same blocks blow up in the same order.
 * <p>
 * Only for the game's own resistance rules, which depend on nothing but the block: another mod's may not. With
 * {@code -Dafterburner.checkExplosions=true} every explosion is also worked out vanilla's way and compared (slow, only for
 * making sure the results are the same).
 */
public final class ExplosionRays {
	public static final boolean CHECK = Boolean.getBoolean("afterburner.checkExplosions");
	private static final int RAYS = 1352;
	/** A block outside the world, where rays stop. */
	private static final Spot OUTSIDE = new Spot(null, null, false, 0.0F);
	private static final AtomicLong CHECKED = new AtomicLong();
	private static final AtomicLong WRONG = new AtomicLong();

	private ExplosionRays() {
	}

	/** Whether the resistance rules depend only on the block (all of the game's own do). */
	public static boolean cacheable(ExplosionDamageCalculator calculator) {
		if (!calculator.getClass().getName().startsWith("net.minecraft.")) return false;
		return !(calculator instanceof EntityBasedExplosionDamageCalculator)
				|| ((EntityCalculatorAccessor) calculator).afterburner$source().getClass().getName().startsWith("net.minecraft.");
	}

	public static List<BlockPos> explodedPositions(Explosion explosion, ServerLevel level, Vec3 center, float radius, ExplosionDamageCalculator calculator) {
		if (!CHECK) return cached(explosion, level, center, radius, calculator, null);
		// Each ray's power drawn up front, for both ways (the rays draw nothing else).
		float[] chance = new float[RAYS];
		for (int i = 0; i < RAYS; i++) chance[i] = level.getRandom().nextFloat();
		List<BlockPos> blown = cached(explosion, level, center, radius, calculator, chance);
		List<BlockPos> vanilla = vanilla(explosion, level, center, radius, calculator, chance);
		CHECKED.incrementAndGet();
		// The same blocks in the same order: the order they're blown up in is shuffled from it.
		if (!vanilla.equals(blown) && WRONG.incrementAndGet() <= 20) {
			Afterburner.LOGGER.error("Explosion check failed at {}: {} blocks, vanilla {}", center, blown.size(), vanilla.size());
		}
		return blown;
	}

	/** @param chance each ray's random draw, or null to draw them from the level as vanilla does, one as each ray starts */
	private static List<BlockPos> cached(Explosion explosion, ServerLevel level, Vec3 center, float radius, ExplosionDamageCalculator calculator, float @Nullable [] chance) {
		Set<BlockPos> toBlowSet = new HashSet<>();
		Long2ObjectOpenHashMap<Spot> spots = new Long2ObjectOpenHashMap<>(2048);
		int ray = 0;
		for (int xx = 0; xx < 16; xx++) {
			for (int yy = 0; yy < 16; yy++) {
				for (int zz = 0; zz < 16; zz++) {
					if (xx == 0 || xx == 15 || yy == 0 || yy == 15 || zz == 0 || zz == 15) {
						double xd = xx / 15.0F * 2.0F - 1.0F;
						double yd = yy / 15.0F * 2.0F - 1.0F;
						double zd = zz / 15.0F * 2.0F - 1.0F;
						double d = Math.sqrt(xd * xd + yd * yd + zd * zd);
						xd /= d;
						yd /= d;
						zd /= d;
						float remainingPower = radius * (0.7F + (chance != null ? chance[ray++] : level.getRandom().nextFloat()) * 0.6F);
						double xp = center.x;
						double yp = center.y;
						double zp = center.z;

						while (remainingPower > 0.0F) {
							int x = Mth.floor(xp), y = Mth.floor(yp), z = Mth.floor(zp);
							long key = BlockPos.asLong(x, y, z);
							Spot spot = spots.get(key);
							if (spot == null) {
								// Read in the order vanilla reads them, the first time any ray gets here.
								BlockPos pos = new BlockPos(x, y, z);
								BlockState block = level.getBlockState(pos);
								FluidState fluid = level.getFluidState(pos);
								if (!level.isInWorldBounds(pos)) {
									spot = OUTSIDE;
								} else {
									Optional<Float> resistance = calculator.getBlockExplosionResistance(explosion, level, pos, block, fluid);
									spot = new Spot(pos, block, resistance.isPresent(), resistance.orElse(0.0F));
								}
								spots.put(key, spot);
							}
							if (spot == OUTSIDE) {
								break;
							}

							if (spot.resists) {
								remainingPower -= (spot.resistance + 0.3F) * 0.3F;
							}

							// One already blown up can't be added again: whether it would be needn't be asked.
							if (remainingPower > 0.0F && !spot.blown && calculator.shouldBlockExplode(explosion, level, spot.pos, spot.block, remainingPower)) {
								toBlowSet.add(spot.pos);
								spot.blown = true;
							}

							xp += xd * 0.3F;
							yp += yd * 0.3F;
							zp += zd * 0.3F;
							remainingPower -= 0.22500001F;
						}
					}
				}
			}
		}
		return new ObjectArrayList<>(toBlowSet);
	}

	/** Vanilla's way, for the check. */
	private static List<BlockPos> vanilla(Explosion explosion, ServerLevel level, Vec3 center, float radius, ExplosionDamageCalculator calculator, float[] chance) {
		Set<BlockPos> toBlowSet = new HashSet<>();
		int ray = 0;
		for (int xx = 0; xx < 16; xx++) {
			for (int yy = 0; yy < 16; yy++) {
				for (int zz = 0; zz < 16; zz++) {
					if (xx == 0 || xx == 15 || yy == 0 || yy == 15 || zz == 0 || zz == 15) {
						double xd = xx / 15.0F * 2.0F - 1.0F;
						double yd = yy / 15.0F * 2.0F - 1.0F;
						double zd = zz / 15.0F * 2.0F - 1.0F;
						double d = Math.sqrt(xd * xd + yd * yd + zd * zd);
						xd /= d;
						yd /= d;
						zd /= d;
						float remainingPower = radius * (0.7F + chance[ray++] * 0.6F);
						double xp = center.x;
						double yp = center.y;
						double zp = center.z;

						while (remainingPower > 0.0F) {
							BlockPos pos = BlockPos.containing(xp, yp, zp);
							BlockState block = level.getBlockState(pos);
							FluidState fluid = level.getFluidState(pos);
							if (!level.isInWorldBounds(pos)) {
								break;
							}

							Optional<Float> resistance = calculator.getBlockExplosionResistance(explosion, level, pos, block, fluid);
							if (resistance.isPresent()) {
								remainingPower -= (resistance.get() + 0.3F) * 0.3F;
							}

							if (remainingPower > 0.0F && calculator.shouldBlockExplode(explosion, level, pos, block, remainingPower)) {
								toBlowSet.add(pos);
							}

							xp += xd * 0.3F;
							yp += yd * 0.3F;
							zp += zd * 0.3F;
							remainingPower -= 0.22500001F;
						}
					}
				}
			}
		}
		return new ObjectArrayList<>(toBlowSet);
	}

	/**
	 * For the bench: works the same explosion out both ways from the same random draws (drawn from {@code random}), the
	 * one named first first, and returns {nanoseconds this way, nanoseconds vanilla's way, 1 if both found the same blocks
	 * in the same order}.
	 */
	public static long[] compare(Explosion explosion, ServerLevel level, Vec3 center, float radius, ExplosionDamageCalculator calculator,
			java.util.Random random, boolean vanillaFirst) {
		float[] chance = new float[RAYS];
		for (int i = 0; i < RAYS; i++) chance[i] = random.nextFloat();
		long[] nanos = new long[2];
		List<BlockPos> blown = null, vanilla = null;
		for (int pass = 0; pass < 2; pass++) {
			boolean ours = (pass == 0) != vanillaFirst;
			long start = System.nanoTime();
			if (ours) blown = cached(explosion, level, center, radius, calculator, chance);
			else vanilla = vanilla(explosion, level, center, radius, calculator, chance);
			nanos[ours ? 0 : 1] = System.nanoTime() - start;
		}
		return new long[] {nanos[0], nanos[1], blown.equals(vanilla) ? 1 : 0};
	}

	/** For benchmark reports. */
	public static String summary() {
		return "Explosion check: " + String.format("%,d", CHECKED.get()) + " compared, " + WRONG.get() + " different";
	}

	/** A block the rays went through: what it is, what it takes from a ray going through, and whether it's blown up yet. */
	private static final class Spot {
		final BlockPos pos;
		final BlockState block;
		final boolean resists;
		final float resistance;
		boolean blown;

		Spot(BlockPos pos, BlockState block, boolean resists, float resistance) {
			this.pos = pos;
			this.block = block;
			this.resists = resists;
			this.resistance = resistance;
		}
	}
}
