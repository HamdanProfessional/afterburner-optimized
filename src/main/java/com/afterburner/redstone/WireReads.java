package com.afterburner.redstone;

import com.afterburner.Afterburner;
import com.afterburner.mixin.redstone.RedstoneWireAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The power a redstone wire should have, worked out as vanilla's wire block does it but reading each block around it once.
 * Vanilla asks each of the 6 neighbours for its signal and each solid one for what powers it through its own 6 neighbours,
 * then reads the 4 beside it, the one above (4 times) and those above or below them again for the wires next to it: up to
 * about 56 block reads where 25 different blocks are read at most. The same signals are asked for in the same order.
 * <p>
 * One per wire block, used on the server thread only (wire power is never worked out on the client). With {@code -Dafterburner.checkRedstone=true} vanilla's way runs too and
 * is compared (slow, only for making sure the results are the same).
 */
public final class WireReads {
	public static final boolean CHECK = Boolean.getBoolean("afterburner.checkRedstone");
	private static final Direction[] DIRECTIONS = Direction.values();
	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
	private static final AtomicLong CHECKED = new AtomicLong();
	private static final AtomicLong WRONG = new AtomicLong();

	/** What was read 2 blocks or less from the wire, by offset; only the entries stamped with this call's number are for it. */
	private final BlockState[] states = new BlockState[125];
	private final BlockPos[] positions = new BlockPos[125];
	private final int[] stamps = new int[125];
	/** Whether the block is a redstone conductor: 0 not looked at this call, 1 no, 2 yes. */
	private final byte[] conductors = new byte[125];
	private int stamp;
	private Level level;
	private BlockPos origin;

	/** Whether it's working one out now: a block's signal that changed blocks and updated a wire would need vanilla's way. */
	public boolean busy() {
		return this.level != null;
	}

	/** What {@code RedStoneWireBlock.calculateTargetStrength} returns. */
	public int targetStrength(Level level, BlockPos pos, RedStoneWireBlock wire) {
		this.level = level;
		this.origin = pos;
		if (++this.stamp == 0) {
			Arrays.fill(this.stamps, 0);
			this.stamp = 1;
		}
		try {
			// wire.getBlockSignal: the neighbours' best signal, with wires giving none.
			RedstoneWireAccessor signals = (RedstoneWireAccessor) wire;
			signals.afterburner$setShouldSignal(false);
			int blockSignal = this.bestNeighborSignal();
			signals.afterburner$setShouldSignal(true);
			return blockSignal == 15 ? blockSignal : Math.max(blockSignal, this.incomingWireSignal(wire));
		} finally {
			this.level = null;
		}
	}

	/** {@code SignalGetter.getBestNeighborSignal}. */
	private int bestNeighborSignal() {
		int best = 0;
		for (Direction direction : DIRECTIONS) {
			int signal = this.signal(direction.getStepX(), direction.getStepY(), direction.getStepZ(), direction);
			if (signal >= 15) {
				return 15;
			}
			if (signal > best) {
				best = signal;
			}
		}
		return best;
	}

	/** {@code SignalGetter.getSignal} of the block at the offset. */
	private int signal(int dx, int dy, int dz, Direction direction) {
		int signal = this.state(dx, dy, dz).getSignal(this.level, this.pos(dx, dy, dz), direction);
		return this.conductor(dx, dy, dz) ? Math.max(signal, this.directSignalTo(dx, dy, dz)) : signal;
	}

	/** {@code SignalGetter.getDirectSignalTo} of the block at the offset (its neighbours in the same order). */
	private int directSignalTo(int dx, int dy, int dz) {
		int result = 0;
		for (Direction direction : DIRECTIONS) {
			int ax = dx + direction.getStepX(), ay = dy + direction.getStepY(), az = dz + direction.getStepZ();
			result = Math.max(result, this.state(ax, ay, az).getDirectSignal(this.level, this.pos(ax, ay, az), direction));
			if (result >= 15) {
				return result;
			}
		}
		return result;
	}

	/** The incoming wire signal part of {@code RedStoneWireBlock.calculateTargetStrength}. */
	private int incomingWireSignal(RedStoneWireBlock wire) {
		int wireSignal = 0;
		for (Direction direction : HORIZONTAL) {
			int dx = direction.getStepX(), dz = direction.getStepZ();
			wireSignal = Math.max(wireSignal, wireSignal(wire, this.state(dx, 0, dz)));
			if (this.conductor(dx, 0, dz) && !this.conductor(0, 1, 0)) {
				wireSignal = Math.max(wireSignal, wireSignal(wire, this.state(dx, 1, dz)));
			} else if (!this.conductor(dx, 0, dz)) {
				wireSignal = Math.max(wireSignal, wireSignal(wire, this.state(dx, -1, dz)));
			}
		}
		return Math.max(0, wireSignal - 1);
	}

	private static int wireSignal(RedStoneWireBlock wire, BlockState state) {
		return state.is(wire) ? state.getValue(RedStoneWireBlock.POWER) : 0;
	}

	private int index(int dx, int dy, int dz) {
		int i = (dx + 2) * 25 + (dy + 2) * 5 + dz + 2;
		if (this.stamps[i] != this.stamp) {
			this.stamps[i] = this.stamp;
			BlockPos pos = this.origin.offset(dx, dy, dz);
			this.positions[i] = pos;
			this.states[i] = this.level.getBlockState(pos);
			this.conductors[i] = 0;
		}
		return i;
	}

	private BlockState state(int dx, int dy, int dz) {
		return this.states[this.index(dx, dy, dz)];
	}

	private BlockPos pos(int dx, int dy, int dz) {
		return this.positions[this.index(dx, dy, dz)];
	}

	private boolean conductor(int dx, int dy, int dz) {
		int i = this.index(dx, dy, dz);
		if (this.conductors[i] == 0) this.conductors[i] = (byte) (this.states[i].isRedstoneConductor(this.level, this.positions[i]) ? 2 : 1);
		return this.conductors[i] == 2;
	}

	/** Counts a comparison with vanilla's way (check mode). */
	public static void checked(BlockPos pos, String what, int ours, int vanilla) {
		CHECKED.incrementAndGet();
		if (ours != vanilla && WRONG.incrementAndGet() <= 20) {
			Afterburner.LOGGER.error("Redstone check failed at {}: {} {}, vanilla {}", pos, what, ours, vanilla);
		}
	}

	/** For benchmark reports. */
	public static String summary() {
		return "Redstone check: " + String.format("%,d", CHECKED.get()) + " compared, " + WRONG.get() + " different";
	}
}
