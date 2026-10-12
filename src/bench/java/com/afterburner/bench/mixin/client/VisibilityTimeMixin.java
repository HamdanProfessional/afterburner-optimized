package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.VisibilityTimes;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Queue;
import java.util.function.Consumer;

/** Times visibility updates, for {@link VisibilityTimes}. */
@Mixin(SectionOcclusionGraph.class)
public class VisibilityTimeMixin {
	// The queue holds the graph's nodes, a class only the graph can name.
	@WrapMethod(method = "runUpdates")
	private void afterburner$time(SectionOcclusionGraph.GraphStorage storage, Vec3 cameraPos, Queue<?> queue, boolean smartCull,
			Consumer<SectionRenderDispatcher.RenderSection> onSectionAdded, Operation<Void> original) {
		long start = System.nanoTime();
		try {
			original.call(storage, cameraPos, queue, smartCull, onSectionAdded);
		} finally {
			VisibilityTimes.ran(!Minecraft.getInstance().isSameThread(), System.nanoTime() - start);
		}
	}
}
