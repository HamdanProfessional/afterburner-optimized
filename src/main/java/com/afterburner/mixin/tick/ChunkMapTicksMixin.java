package com.afterburner.mixin.tick;

import com.afterburner.tick.ChunkListener;
import com.afterburner.tick.TickWaker;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells the world's tick lists when a chunk comes into simulation distance, and when a chunk that was being unloaded is
 * taken back: the tick check finds it again once the visible chunk list is next copied.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapTicksMixin {
	@Shadow
	@Final
	private ServerLevel level;

	/** Chunks taken back from the unload list since the visible chunk list was last copied. */
	@Unique
	private final LongArrayList afterburner$revived = new LongArrayList();

	@Inject(method = "<init>", at = @At("RETURN"))
	private void afterburner$listen(CallbackInfo ci) {
		TickWaker waker = (TickWaker) level;
		DistanceManagerAccessor distances = (DistanceManagerAccessor) ((ChunkMap) (Object) this).getDistanceManager();
		((ChunkListener) distances.afterburner$simulation()).afterburner$listen(waker::afterburner$wakeTicks);
	}

	@WrapOperation(method = "updateChunkScheduling", at = @At(value = "INVOKE",
			target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;remove(J)Ljava/lang/Object;"))
	private Object afterburner$takenBack(Long2ObjectLinkedOpenHashMap<?> pendingUnloads, long chunk, Operation<Object> original) {
		Object holder = original.call(pendingUnloads, chunk);
		if (holder != null) afterburner$revived.add(chunk);
		return holder;
	}

	@Inject(method = "promoteChunkMap", at = @At("TAIL"))
	private void afterburner$visible(CallbackInfoReturnable<Boolean> cir) {
		if (afterburner$revived.isEmpty()) return;
		TickWaker waker = (TickWaker) level;
		for (int i = 0, n = afterburner$revived.size(); i < n; i++) waker.afterburner$wakeTicks(afterburner$revived.getLong(i));
		afterburner$revived.clear();
	}
}
