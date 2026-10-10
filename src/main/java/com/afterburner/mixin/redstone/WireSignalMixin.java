package com.afterburner.mixin.redstone;

import com.afterburner.redstone.WireReads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The signal a powered wire gives to the side, without working out how it connects on all four sides. Vanilla works out
 * the whole connection state (2 to 4 blocks read for each side) to look at one side: here only the sides that can change
 * the answer are worked out. Same answer.
 */
@Mixin(RedstoneWireBlock.class)
public abstract class WireSignalMixin {
	@Shadow
	private boolean shouldSignal;

	@Shadow
	private RedstoneSide getConnectingSide(BlockGetter level, BlockPos pos, Direction direction, boolean canConnectUp) {
		throw new AssertionError();
	}

	@Shadow
	private BlockState getConnectionState(BlockGetter level, BlockState state, BlockPos pos) {
		throw new AssertionError();
	}

	@Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
	private void afterburner$oneSide(BlockState state, BlockGetter level, BlockPos pos, Direction direction, CallbackInfoReturnable<Integer> cir) {
		// Another mod's wire may connect differently.
		if (((Object) this).getClass() != RedstoneWireBlock.class || !this.shouldSignal || direction == Direction.DOWN) return;
		int power = state.getValue(RedstoneWireBlock.POWER);
		if (power == 0 || direction == Direction.UP) return;
		Direction side = direction.getOpposite();
		boolean connected = this.afterburner$connected(level, state, pos, side);
		if (WireReads.CHECK) {
			boolean vanilla = this.getConnectionState(level, state, pos).getValue(RedstoneWireBlock.PROPERTY_BY_DIRECTION.get(side)).isConnected();
			WireReads.checked(pos, "connected " + side, connected ? 1 : 0, vanilla ? 1 : 0);
		}
		cir.setReturnValue(connected ? power : 0);
	}

	/**
	 * Whether {@code getConnectionState} connects the wire on this side. A side connects if what's there connects; one that
	 * doesn't is drawn as a line through anyway if neither side across connects, unless the wire was a dot and stays one.
	 */
	@Unique
	private boolean afterburner$connected(BlockGetter level, BlockState state, BlockPos pos, Direction side) {
		boolean canConnectUp = !level.getBlockState(pos.above()).isRedstoneConductor(level, pos);
		if (this.getConnectingSide(level, pos, side, canConnectUp).isConnected()) return true;
		if (this.getConnectingSide(level, pos, side.getClockWise(), canConnectUp).isConnected()
				|| this.getConnectingSide(level, pos, side.getCounterClockWise(), canConnectUp).isConnected()) {
			return false;
		}
		// Only the side opposite can connect now: with it, the wire is a line through this side; without, a dot if it was one.
		return !afterburner$isDot(state) || this.getConnectingSide(level, pos, side.getOpposite(), canConnectUp).isConnected();
	}

	@Unique
	private static boolean afterburner$isDot(BlockState state) {
		return !state.getValue(RedstoneWireBlock.NORTH).isConnected() && !state.getValue(RedstoneWireBlock.SOUTH).isConnected()
				&& !state.getValue(RedstoneWireBlock.EAST).isConnected() && !state.getValue(RedstoneWireBlock.WEST).isConnected();
	}
}
