package com.afterburner.mixin.ai;

import com.afterburner.ai.PoiColumns;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Keeps a second copy of the section map grouped by chunk column, so a search over many columns looks each one up once
 * instead of looking up every section in it. Vanilla only ever adds to or replaces entries in the map (in the two
 * methods below), so the copy is updated in the same places.
 */
@Mixin(SectionStorage.class)
public abstract class SectionStorageMixin<R, P> implements PoiColumns {
	@Shadow
	@Final
	protected LevelHeightAccessor levelHeightAccessor;

	/** Made on first use. */
	@Unique
	private @Nullable Long2ObjectOpenHashMap<Optional<?>[]> afterburner$columns;

	@Shadow
	protected abstract Optional<R> getOrLoad(long sectionPos);

	@WrapOperation(method = {"getOrCreate", "unpackChunk(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/storage/SectionStorage$PackedChunk;)V"},
			at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;put(JLjava/lang/Object;)Ljava/lang/Object;"))
	private Object afterburner$onPut(Long2ObjectMap<Object> storage, long sectionKey, Object section, Operation<Object> original) {
		Object old = original.call(storage, sectionKey, section);
		int index = SectionPos.y(sectionKey) - levelHeightAccessor.getMinSectionY();
		int count = levelHeightAccessor.getSectionsCount();
		if (index >= 0 && index < count) {
			Long2ObjectOpenHashMap<Optional<?>[]> columns = afterburner$columns;
			if (columns == null) columns = afterburner$columns = new Long2ObjectOpenHashMap<>();
			long chunkKey = ChunkPos.pack(SectionPos.x(sectionKey), SectionPos.z(sectionKey));
			Optional<?>[] column = columns.get(chunkKey);
			if (column == null) columns.put(chunkKey, column = new Optional<?>[count]);
			column[index] = (Optional<?>) section;
		}
		return old;
	}

	@Override
	public Optional<?> afterburner$getOrLoad(long sectionKey) {
		return getOrLoad(sectionKey);
	}

	@Override
	public Optional<?> @Nullable [] afterburner$column(long chunkKey) {
		Long2ObjectOpenHashMap<Optional<?>[]> columns = afterburner$columns;
		return columns == null ? null : columns.get(chunkKey);
	}

	@Override
	public int afterburner$minSection() {
		return levelHeightAccessor.getMinSectionY();
	}

	@Override
	public int afterburner$sectionCount() {
		return levelHeightAccessor.getSectionsCount();
	}
}
