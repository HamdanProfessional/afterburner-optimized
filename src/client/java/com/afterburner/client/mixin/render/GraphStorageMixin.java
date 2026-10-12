package com.afterburner.client.mixin.render;

import com.afterburner.client.render.RegionFrustum;
import com.afterburner.client.render.RegionList;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;

/** The graph's sections in flat arrays ({@link RegionList}). */
@Mixin(SectionOcclusionGraph.GraphStorage.class)
public class GraphStorageMixin implements RegionList {
	@Unique
	private SectionRenderDispatcher.RenderSection[] afterburner$sections = new SectionRenderDispatcher.RenderSection[1024];
	@Unique
	private long[] afterburner$keys = new long[1024];
	/** Each section's corner, x y z. */
	@Unique
	private int[] afterburner$origins = new int[1024 * 3];
	@Unique
	private int afterburner$count;
	/** The camera move the corners were read at; a section added after another move makes it stale too. */
	@Unique
	private int afterburner$moves = Integer.MIN_VALUE;

	@Override
	public void afterburner$append(SectionRenderDispatcher.RenderSection section) {
		int moves = RegionFrustum.moves();
		if (afterburner$moves == Integer.MIN_VALUE) afterburner$moves = moves;
		else if (afterburner$moves != moves) afterburner$moves = Integer.MIN_VALUE + 1;
		int n = afterburner$count;
		if (n == afterburner$sections.length) {
			afterburner$sections = Arrays.copyOf(afterburner$sections, n * 2);
			afterburner$keys = Arrays.copyOf(afterburner$keys, n * 2);
			afterburner$origins = Arrays.copyOf(afterburner$origins, n * 2 * 3);
		}
		afterburner$sections[n] = section;
		afterburner$place(n);
		afterburner$count = n + 1;
	}

	@Unique
	private void afterburner$place(int n) {
		BlockPos origin = afterburner$sections[n].getOrigin();
		afterburner$keys[n] = RegionFrustum.key(origin.getX(), origin.getY(), origin.getZ());
		afterburner$origins[n * 3] = origin.getX();
		afterburner$origins[n * 3 + 1] = origin.getY();
		afterburner$origins[n * 3 + 2] = origin.getZ();
	}

	@Override
	public void afterburner$refresh() {
		int moves = RegionFrustum.moves();
		if (afterburner$moves == moves) return;
		for (int i = 0; i < afterburner$count; i++) afterburner$place(i);
		afterburner$moves = moves;
	}

	@Override
	public int afterburner$count() {
		return afterburner$count;
	}

	@Override
	public SectionRenderDispatcher.RenderSection[] afterburner$sections() {
		return afterburner$sections;
	}

	@Override
	public int[] afterburner$origins() {
		return afterburner$origins;
	}

	@Override
	public long[] afterburner$keys() {
		return afterburner$keys;
	}

}
