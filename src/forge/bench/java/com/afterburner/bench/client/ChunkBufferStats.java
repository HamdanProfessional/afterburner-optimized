package com.afterburner.bench.client;

import com.afterburner.client.mixin.memory.HeapAccessor;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/** How much of the chunk renderer's GPU buffers (one set per block layer) is reserved, and how much is really used. */
public final class ChunkBufferStats {
	private ChunkBufferStats() {
	}

	public static String line(Minecraft mc) {
		try {
			Object dispatcher = mc.levelRenderer.sectionRenderDispatcher();
			Map<ChunkSectionLayer, ?> buffers = get(dispatcher, "chunkUberBuffers");
			StringBuilder out = new StringBuilder("Chunk GPU buffers (reserved/used MB):");
			long reserved = 0, used = 0;
			for (Map.Entry<ChunkSectionLayer, ?> e : buffers.entrySet()) {
				for (String kind : new String[]{"vertexBuffer", "indexBuffer"}) {
					UberGpuBuffer<?> buffer = get(e.getValue(), kind);
					List<Pair<TlsfAllocator, ?>> nodes = get(buffer, "nodes");
					Map<?, TlsfAllocator.Allocation> allocations = get(buffer, "allocationMap");
					long r = 0;
					for (Pair<TlsfAllocator, ?> node : nodes) r += ((HeapAccessor) node.getSecond()).afterburner$size();
					long u = 0;
					for (TlsfAllocator.Allocation a : allocations.values()) u += a.getSize();
					reserved += r;
					used += u;
					out.append(' ').append(e.getKey().label()).append(kind.startsWith("v") ? " vertices " : " indices ")
							.append(r >> 20).append('/').append(u >> 20).append(" (").append(nodes.size()).append(')');
				}
			}
			return out.append(" | total ").append(reserved >> 20).append('/').append(used >> 20).toString();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "Chunk GPU buffers: couldn't read (" + e + ")";
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T get(Object owner, String name) throws ReflectiveOperationException {
		Class<?> c = owner.getClass();
		while (c != null) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return (T) f.get(owner);
			} catch (NoSuchFieldException e) {
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}
}
