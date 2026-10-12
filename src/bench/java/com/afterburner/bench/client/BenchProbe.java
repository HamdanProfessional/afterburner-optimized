package com.afterburner.bench.client;

import com.afterburner.client.render.Probe;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.joml.Matrix4f;

import java.util.List;

/** Hands what the renderer reports to the measuring tools: GPU timers, the occlusion probe, the build log, the benchmark. */
final class BenchProbe implements Probe {
	@Override
	public void mark(String label) {
		GpuTimers.mark(label);
	}

	@Override
	public void chunksInView(List<SectionRenderDispatcher.RenderSection> sections, double camX, double camY, double camZ, Matrix4f projection,
			Matrix4f modelView) {
		if (OcclusionProbe.ENABLED) OcclusionProbe.prepare(sections, camX, camY, camZ, projection, modelView);
	}

	@Override
	public void opaqueDrawn() {
		if (OcclusionProbe.ENABLED) OcclusionProbe.afterOpaque();
	}

	@Override
	public boolean timesScans() {
		return BuildLog.ENABLED;
	}

	@Override
	public void offscreenScan(long nanos, int picked) {
		BuildLog.scans++;
		BuildLog.scanNanos += nanos;
		BuildLog.offscreen += picked;
	}

	@Override
	public boolean busy() {
		return AutoBench.busy();
	}
}
