package com.afterburner.bench;

import com.afterburner.Afterburner;
import com.afterburner.Features;
import com.afterburner.explosion.ExplosionRays;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /afterburner-explosions [count]}: blows up {@code count} cubes of mixed blocks (stone, dirt, obsidian, bedrock,
 * water, lava, glass, planks, rails...) far from spawn, by turns with no source (a bed, a command), TNT, a creeper, a TNT
 * minecart and a wither skull, at different sizes. Each time it works out which blocks blow up both Afterburner's way and
 * vanilla's from the same random draws, compares them and times both, then lets the explosion really happen. Op only, and
 * it builds over whatever is there, so use a test world.
 */
public final class ExplosionCommand {
	private static final int X = 4000, Y = 120, Z = 4000, HALF = 7;
	private static final int WARMUP = 30;
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	private ExplosionCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context,
			Commands.CommandSelection selection) {
		dispatcher.register(Commands.literal("afterburner-explosions")
				.requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> run(c.getSource(), 300))
				.then(Commands.argument("count", IntegerArgumentType.integer(1, 2000))
						.executes(c -> run(c.getSource(), IntegerArgumentType.getInteger(c, "count")))));
	}

	private static int run(CommandSourceStack source, int count) {
		ServerLevel level = source.getServer().overworld();
		List<CompletableFuture<?>> loads = new ArrayList<>();
		for (int cz = (Z - 16) >> 4; cz <= (Z + 16) >> 4; cz++) {
			for (int cx = (X - 16) >> 4; cx <= (X + 16) >> 4; cx++) {
				loads.add(ChunkCommand.load(level.getChunkSource(), new ChunkPos(cx, cz)));
			}
		}
		source.sendSuccess(() -> Component.literal("Blowing up " + count + " cubes...").withStyle(ChatFormatting.GRAY), true);
		CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenRunAsync(() -> {
			List<String> lines = blowUp(level, count);
			String saved = Reports.write("explosions", lines);
			for (String line : lines) source.sendSuccess(() -> Component.literal(line), true);
			if (saved != null) source.sendSuccess(() -> Component.literal("Saved to " + saved).withStyle(ChatFormatting.GRAY), true);
			Afterburner.LOGGER.info("Explosion check: {}", String.join(" | ", lines));
		}, source.getServer()).exceptionally(e -> {
			Afterburner.LOGGER.error("Explosion check failed", e);
			source.sendFailure(Component.literal("Explosion check failed: " + e));
			return null;
		});
		return 1;
	}

	private static List<String> blowUp(ServerLevel level, int count) {
		Random random = new Random(26);
		Vec3 center = new Vec3(X + 0.5, Y + 0.5, Z + 0.5);
		long ours = 0, vanilla = 0;
		int timed = 0, different = 0;
		for (int i = 0; i < WARMUP + count; i++) {
			build(level, random);
			Entity source = source(level, i % 5, center);
			float radius = i % 3 == 0 ? 7.0F : i % 5 == 4 ? 1.0F : 4.0F;
			ExplosionDamageCalculator calculator = source == null ? new ExplosionDamageCalculator() : new EntityBasedExplosionDamageCalculator(source);
			Explosion explosion = new Explosion(level, source, center.x, center.y, center.z, radius, false, Explosion.BlockInteraction.DESTROY);
			long[] result = ExplosionRays.compare(explosion, level, center, radius, calculator, random, i % 2 == 0);
			if (result[2] == 0) different++;
			if (i >= WARMUP) {
				ours += result[0];
				vanilla += result[1];
				timed++;
			}
			// Then for real, through the game's own explosion (and Afterburner's if it's on).
			level.explode(source, center.x, center.y, center.z, radius, Level.ExplosionInteraction.TNT);
			AABB area = new AABB(X - HALF - 8, Y - HALF - 8, Z - HALF - 8, X + HALF + 8, Y + HALF + 8, Z + HALF + 8);
			for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area)) item.discard();
		}
		List<String> lines = new ArrayList<>();
		lines.add("Explosions: " + (WARMUP + count) + " compared with vanilla's way, " + different + " different");
		lines.add("Which blocks blow up, average: Afterburner " + String.format("%.3f", ours / 1e6 / timed) + " ms, vanilla "
				+ String.format("%.3f", vanilla / 1e6 / timed) + " ms (" + String.format("%.1f", (double) vanilla / ours) + "x)");
		lines.add("Faster explosions " + (Features.FAST_EXPLOSIONS.enabled() ? "on" : "off") + (ExplosionRays.CHECK ? "; " + ExplosionRays.summary() : ""));
		lines.add(Reports.modsLine());
		return lines;
	}

	/** By turns: none, TNT, a creeper, a TNT minecart, a wither skull (that breaks what withers break). */
	private static Entity source(ServerLevel level, int kind, Vec3 at) {
		EntityType<?> type = switch (kind) {
			case 1 -> EntityType.TNT;
			case 2 -> EntityType.CREEPER;
			case 3 -> EntityType.TNT_MINECART;
			case 4 -> EntityType.WITHER_SKULL;
			default -> null;
		};
		if (type == null) return null;
		Entity entity = type.create(level);
		entity.setPos(at);
		if (entity instanceof WitherSkull skull) skull.setDangerous(true);
		return entity;
	}

	/** A cube of mixed blocks around the center, set without updates so sand and water stay where they're put. */
	private static void build(ServerLevel level, Random random) {
		BlockState[] palette = {Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(),
				Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState(),
				Blocks.DIRT.defaultBlockState(), Blocks.SAND.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState(),
				Blocks.BEDROCK.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
				Blocks.GLASS.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(), Blocks.RAIL.defaultBlockState(),
				Blocks.COBBLESTONE_WALL.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(), Blocks.END_STONE.defaultBlockState()};
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = -HALF - 2; x <= HALF + 2; x++) {
			for (int y = -HALF - 2; y <= HALF + 2; y++) {
				for (int z = -HALF - 2; z <= HALF + 2; z++) {
					boolean inside = Math.abs(x) <= HALF && Math.abs(y) <= HALF && Math.abs(z) <= HALF;
					BlockState state = inside ? palette[random.nextInt(palette.length)] : Blocks.AIR.defaultBlockState();
					level.setBlock(pos.set(X + x, Y + y, Z + z), state, FLAGS);
				}
			}
		}
	}
}
