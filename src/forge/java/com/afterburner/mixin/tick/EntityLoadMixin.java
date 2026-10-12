package com.afterburner.mixin.tick;

import com.afterburner.tick.ChunkListener;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.LongConsumer;

/** Reports each chunk whose mobs have been loaded, see {@link ServerLevelTicksMixin}. */
@Mixin(PersistentEntitySectionManager.class)
public abstract class EntityLoadMixin implements ChunkListener {
	@Unique
	private @Nullable LongConsumer afterburner$listener;

	@Override
	public void afterburner$listen(LongConsumer listener) {
		afterburner$listener = listener;
	}

	/** The one place a chunk's mobs become LOADED. */
	@WrapOperation(method = "processPendingLoads", at = @At(value = "INVOKE",
			target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;put(JLjava/lang/Object;)Ljava/lang/Object;"))
	private Object afterburner$loaded(Long2ObjectMap<Object> statuses, long chunk, Object status, Operation<Object> original) {
		Object old = original.call(statuses, chunk, status);
		if (afterburner$listener != null) afterburner$listener.accept(chunk);
		return old;
	}
}
