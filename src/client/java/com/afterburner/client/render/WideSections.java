package com.afterburner.client.render;

import com.afterburner.client.perf.FpsTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * The sections the chunk batcher builds its frames from: those in a frustum {@link #MARGIN} wider than the camera's on every
 * side. Vanilla picks its sections anew every 2 degrees the camera turns, which built the frame anew each time (a few
 * milliseconds at a long render distance, so turning was the 1% lows); from these, a frame holds all a small turn can show,
 * and vanilla's sections pick which of them are drawn ({@link ChunkBatcher.Frame#markInView}).
 * <p>
 * They're picked again when the occlusion graph changes (when vanilla picks its own again for that), and when vanilla's list
 * has a section these don't: turned past the margin. The octree hands out sections in an order that depends on the camera's
 * place, not on the frustum, so those drawn come in vanilla's order (near to far, which water and glass need).
 */
public final class WideSections {
	/** {@code -Dafterburner.wideSections=false} builds frames from vanilla's sections, as before. */
	public static final boolean ENABLED = !"false".equals(System.getProperty("afterburner.wideSections"));
	/** How much wider than the camera's view, on each side. */
	private static final double MARGIN = Math.toRadians(15.0);
	/** The widest half-angle the frustum is made wider to (a perspective projection ends at 90). */
	private static final double MAX_HALF = Math.toRadians(85.0);
	/** Frames vanilla's own sections are used for after the wide ones twice in a row didn't have them all. */
	private static final int BACK_OFF = 20;

	private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> sections = new ObjectArrayList<>();
	private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> nearby = new ObjectArrayList<>();
	/** Set when the occlusion graph tells vanilla to pick its sections again. */
	private static volatile boolean graphChanged = true;
	private static boolean picked;
	private static int backOff;

	private WideSections() {
	}

	/** The occlusion graph changed (vanilla picks its sections again). */
	public static void graphChanged() {
		graphChanged = true;
	}

	/** Picked again next time (the world or the chunk renderer changed). */
	static void invalidate() {
		picked = false;
	}

	/** Vanilla's sections for a while: the wide ones didn't have them all, even picked anew. */
	static void backOff() {
		picked = false;
		backOff = BACK_OFF;
	}

	/** The sections to build this frame from, or null for vanilla's (turned off, or backing off). */
	static @Nullable List<SectionRenderDispatcher.RenderSection> get(CameraRenderState state) {
		if (!ENABLED) return null;
		if (backOff > 0) {
			backOff--;
			return null;
		}
		if (!picked || graphChanged) pick(state);
		return sections;
	}

	/** Picks them anew, for the camera as it is now. */
	static List<SectionRenderDispatcher.RenderSection> pick(CameraRenderState state) {
		graphChanged = false;
		picked = true;
		sections.clear();
		nearby.clear();
		Minecraft mc = Minecraft.getInstance();
		Camera camera = mc.gameRenderer.mainCamera();
		Window window = mc.getWindow();
		// The camera's culling projection (Camera.createProjectionMatrixForCulling), each half-angle made MARGIN wider.
		double halfY = Math.toRadians(Math.max(camera.getFov(), mc.options.fov().get().intValue())) / 2.0;
		double halfX = Math.atan((double) window.getWidth() / Math.max(1, window.getHeight()) * Math.tan(halfY));
		double wideY = Math.max(halfY, Math.min(halfY + MARGIN, MAX_HALF));
		double wideX = Math.max(halfX, Math.min(halfX + MARGIN, MAX_HALF));
		Matrix4f projection = new Matrix4f().perspective((float) (wideY * 2.0), (float) (Math.tan(wideX) / Math.tan(wideY)), 0.05F, state.depthFar,
				RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
		Frustum frustum = new Frustum(state.viewRotationMatrix, projection);
		frustum.prepare(state.pos.x, state.pos.y, state.pos.z);
		mc.levelRenderer.sectionOcclusionGraph().addSectionsInFrustum(frustum, sections, nearby);
		nearby.clear();
		FpsTarget.filter(sections, state.pos.x, state.pos.z);
		return sections;
	}
}
