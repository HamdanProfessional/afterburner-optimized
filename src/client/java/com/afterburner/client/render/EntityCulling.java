package com.afterburner.client.render;

import com.afterburner.Features;
import com.afterburner.client.Addons;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Leaves out the entities and block entities (chests, signs, banners, ...) hidden behind terrain.
 * <p>
 * Everything the game is about to draw goes into the frame's {@link OcclusionCulling} test as a box, and what the test
 * finds comes back a frame or two later. An entity is left out while its latest answer was "hidden", that answer is
 * recent, and neither the camera nor the entity has moved much since. So something stepping out from behind a wall can
 * show up a frame or two late (like with other entity culling mods); to keep that rare, fast things, the area right
 * around the camera, and boxes that weren't entirely on the screen at the test are always drawn.
 * <p>
 * Never left out: players (their names show through walls), glowing entities (their outline shows through walls),
 * entities with a visible name, text displays, fishing hooks (their line), and leashed animals whose rope is in view.
 */
public final class EntityCulling {
	public static final boolean ENABLED = Features.ENTITY_CULLING.enabled() && OcclusionCulling.ENABLED;
	/** Bytes per box: lowest corner relative to the camera (3 floats, 1 unused), then the highest corner. */
	static final int BOX_BYTES = 32;
	/** Faster than this (blocks per tick) and it's always drawn: the answer would be out of date by the time it's used. */
	private static final double MAX_SPEED = 0.5;
	/** Entities that moved more than this (blocks) since they were found hidden get drawn again until the next answer. */
	private static final double MAX_MOVE = 0.5;
	public static final int NONE = 0, ENTITIES = 1, BLOCK_ENTITIES = 2;

	/** What the game is gathering for drawing right now; only those calls are culled (not, say, the hitbox debug view). */
	private static int phase;
	/** Whether boxes are gathered this frame. */
	private static boolean gathering;
	private static ByteBuffer boxData = MemoryUtil.memAlloc(256 * BOX_BYTES);
	private static Object[] owners = new Object[256];
	private static double[] positions = new double[256 * 3];
	private static int count;
	/** Benchmark numbers: entities and block entities left out. */
	public static long culledEntities, culledBlockEntities;
	/** Box growth (blocks) of each block entity class, see {@link #reach}. */
	private static final ClassValue<Double> REACH = new ClassValue<>() {
		@Override
		protected Double computeValue(Class<?> type) {
			return reach(type);
		}
	};

	private EntityCulling() {
	}

	/** Start of gathering what to draw this frame. */
	public static void startFrame() {
		Arrays.fill(owners, 0, count, null);
		count = 0;
		boxData.clear();
		gathering = ENABLED && Features.ENTITY_CULLING.active() && Features.OCCLUSION_CULLING.active() && !Addons.shaderPackActive() && OcclusionCulling.working();
		if (gathering) OcclusionCulling.poll();
	}

	public static void phase(int phase) {
		EntityCulling.phase = phase;
	}

	/** Whether to leave out this entity, which vanilla would draw because this box of it is in view. */
	public static boolean hide(Entity entity, AABB box, double camX, double camY, double camZ) {
		if (phase != ENTITIES || !gathering || !cullable(entity) || near(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, camX, camY, camZ)) {
			return false;
		}
		add(entity, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, entity.getX(), entity.getY(), entity.getZ(), camX, camY, camZ);
		OccludedObject o = (OccludedObject) entity;
		boolean hidden = OcclusionCulling.recent(o.afterburner$hiddenAt(), camX, camY, camZ)
				&& o.afterburner$movedSq(entity.getX(), entity.getY(), entity.getZ()) < MAX_MOVE * MAX_MOVE;
		if (hidden && OcclusionCulling.measuring) culledEntities++;
		return hidden;
	}

	private static boolean cullable(Entity entity) {
		if (entity instanceof Player || entity instanceof Display.TextDisplay || entity instanceof FishingHook) return false;
		if (entity.shouldShowName() || Minecraft.getInstance().shouldEntityAppearGlowing(entity)) return false;
		double dx = entity.getX() - entity.xOld, dy = entity.getY() - entity.yOld, dz = entity.getZ() - entity.zOld;
		return dx * dx + dy * dy + dz * dz <= MAX_SPEED * MAX_SPEED;
	}

	/**
	 * Whether to leave out this block entity, which vanilla would draw. Renderers seen from further than usual draw
	 * something big (an end gateway's beam), so those are always drawn.
	 */
	public static boolean hide(BlockEntity blockEntity, Vec3 camera, int viewDistance) {
		if (phase != BLOCK_ENTITIES || !gathering || viewDistance > 64) return false;
		BlockPos pos = blockEntity.getBlockPos();
		double g = REACH.get(blockEntity.getClass());
		double x0 = pos.getX() - g, y0 = pos.getY() - g, z0 = pos.getZ() - g, x1 = pos.getX() + 1 + g, y1 = pos.getY() + 1 + g, z1 = pos.getZ() + 1 + g;
		if (blockEntity instanceof LidBlockEntity) {
			// The open lid reaches up, and a double chest's model also covers its other half, which is beside it.
			y1 += 1;
			BlockState state = blockEntity.getBlockState();
			Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : null;
			if (facing == null || facing.getAxis() == Direction.Axis.X) {
				z0 -= 0.5;
				z1 += 0.5;
			}
			if (facing == null || facing.getAxis() == Direction.Axis.Z) {
				x0 -= 0.5;
				x1 += 0.5;
			}
		}
		if (near(x0, y0, z0, x1, y1, z1, camera.x, camera.y, camera.z)) return false;
		add(blockEntity, x0, y0, z0, x1, y1, z1, pos.getX(), pos.getY(), pos.getZ(), camera.x, camera.y, camera.z);
		boolean hidden = OcclusionCulling.recent(((OccludedObject) blockEntity).afterburner$hiddenAt(), camera.x, camera.y, camera.z);
		if (hidden && OcclusionCulling.measuring) culledBlockEntities++;
		return hidden;
	}

	/**
	 * How far (blocks) past its own block a block entity of this class may draw. Vanilla ones stay within half a block
	 * (chest lids, books, items), except the ones listed; other mods' ones could be anything, so they get more room.
	 */
	private static double reach(Class<?> type) {
		if (!type.getName().startsWith("net.minecraft.")) return 1.5;
		if (BannerBlockEntity.class.isAssignableFrom(type) || PistonMovingBlockEntity.class.isAssignableFrom(type)
				|| SkullBlockEntity.class.isAssignableFrom(type) || ShulkerBoxBlockEntity.class.isAssignableFrom(type)
				|| SpawnerBlockEntity.class.isAssignableFrom(type) || TrialSpawnerBlockEntity.class.isAssignableFrom(type)
				|| VaultBlockEntity.class.isAssignableFrom(type)) {
			return 1.0;
		}
		return 0.5;
	}

	/** Boxes the camera is in or right next to are always drawn (the test can't see them properly anyway). */
	private static boolean near(double x0, double y0, double z0, double x1, double y1, double z1, double camX, double camY, double camZ) {
		return camX > x0 - 1 && camX < x1 + 1 && camY > y0 - 1 && camY < y1 + 1 && camZ > z0 - 1 && camZ < z1 + 1;
	}

	private static void add(Object owner, double x0, double y0, double z0, double x1, double y1, double z1, double x, double y, double z,
			double camX, double camY, double camZ) {
		if (count == owners.length) {
			owners = Arrays.copyOf(owners, count * 2);
			positions = Arrays.copyOf(positions, count * 6);
		}
		if (boxData.capacity() < (count + 1) * BOX_BYTES) {
			ByteBuffer bigger = MemoryUtil.memAlloc(boxData.capacity() * 2);
			MemoryUtil.memCopy(MemoryUtil.memAddress(boxData), MemoryUtil.memAddress(bigger), (long) count * BOX_BYTES);
			MemoryUtil.memFree(boxData);
			boxData = bigger;
		}
		int b = count * BOX_BYTES;
		boxData.putFloat(b, (float) (x0 - camX));
		boxData.putFloat(b + 4, (float) (y0 - camY));
		boxData.putFloat(b + 8, (float) (z0 - camZ));
		boxData.putFloat(b + 12, 0);
		boxData.putFloat(b + 16, (float) (x1 - camX));
		boxData.putFloat(b + 20, (float) (y1 - camY));
		boxData.putFloat(b + 24, (float) (z1 - camZ));
		boxData.putFloat(b + 28, 0);
		owners[count] = owner;
		positions[count * 3] = x;
		positions[count * 3 + 1] = y;
		positions[count * 3 + 2] = z;
		count++;
	}

	/** The boxes gathered this frame, for the test (once): how many there are. */
	static int takeBoxes() {
		int n = gathering ? count : 0;
		gathering = false;
		return n;
	}

	static ByteBuffer boxData() {
		return boxData;
	}

	static Object[] owners() {
		return owners;
	}

	static double[] positions() {
		return positions;
	}

	public static void resetStats() {
		culledEntities = culledBlockEntities = 0;
	}
}
