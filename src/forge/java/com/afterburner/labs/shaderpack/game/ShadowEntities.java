package com.afterburner.labs.shaderpack.game;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Entities that cast a pack's shadow without being drawn for the camera, as Iris draws its shadow map's own: those out of view
 * but near enough to throw a shadow into it, and the camera's entity in first person (your own shadow). They're extracted
 * after the game's (PackLevelExtractorMixin) and submitted at orders of their own ({@link #BASE} on), so the game prepares
 * their draws with the rest; every pass but the shadow map's leaves those orders out (PackPreparedFrameMixin).
 */
public final class ShadowEntities {
	/** Submit orders from here on are the shadow's only; the game's own are small (0, 1, 2, ...). */
	private static final int FIRST = 1 << 23;
	/** Where the shadow's orders start: the game's order n is BASE + n. */
	private static final int BASE = 1 << 24;
	/** How far out of view an entity still casts a shadow, at most, in blocks (the pack's entity shadow distance if less). */
	private static final double OUT_OF_VIEW = 48.0;

	private static final Set<Entity> FOR_CAMERA = Collections.newSetFromMap(new IdentityHashMap<>());
	private static final List<EntityRenderState> STATES = new ArrayList<>();
	private static double range;
	private static boolean drawing;
	private static @Nullable SubmitNodeStorage storage;
	private static @Nullable SubmitNodeCollector collector;

	private ShadowEntities() {
	}

	/** The game starts picking the entities it draws for the camera. */
	public static void begin() {
		FOR_CAMERA.clear();
		STATES.clear();
		range = Math.min(Shaderpacks.entityShadowRange(), OUT_OF_VIEW);
	}

	/** The game draws this entity for the camera. */
	public static void forCamera(Entity entity) {
		if (range > 0.0) FOR_CAMERA.add(entity);
	}

	/** The game picked the camera's entities: the shadow's own are those it left out, within range. */
	public static void extract(Minecraft mc, @Nullable ClientLevel level, Camera camera, DeltaTracker delta) {
		if (range <= 0.0 || level == null) return;
		Vec3 at = camera.position();
		double reach = range * range;
		TickRateManager ticks = level.tickRateManager();
		EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		for (Entity entity : level.entitiesForRendering()) {
			if (FOR_CAMERA.contains(entity) || entity.tickCount == 0 || entity.distanceToSqr(at) > reach) continue;
			// Other players of this client are never drawn, as by the game.
			if (entity instanceof LocalPlayer && camera.entity() != entity) continue;
			if (entity != camera.entity() && !entity.shouldRender(at.x, at.y, at.z)) continue;
			STATES.add(dispatcher.extractEntity(entity, delta.getGameTimeDeltaPartialTick(!ticks.isEntityFrozen(entity))));
		}
		FOR_CAMERA.clear();
	}

	/** After the game submitted its entities: the shadow's, at their own orders. */
	public static void submit(EntityRenderDispatcher dispatcher, PoseStack poseStack, CameraRenderState camera, SubmitNodeCollector output) {
		if (STATES.isEmpty() || !(output instanceof SubmitNodeStorage nodes)) {
			STATES.clear();
			return;
		}
		SubmitNodeCollector shadowOnly = collector(nodes);
		Vec3 at = camera.pos;
		for (EntityRenderState state : STATES) {
			dispatcher.submit(state, camera, state.x - at.x(), state.y - at.y(), state.z - at.z(), poseStack, shadowOnly);
		}
		STATES.clear();
	}

	/** Submits at the shadow's orders: what the game would submit at order n goes to BASE + n. */
	private static SubmitNodeCollector collector(SubmitNodeStorage nodes) {
		SubmitNodeCollector c = collector;
		if (c != null && storage == nodes) return c;
		c = (SubmitNodeCollector) Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(), new Class<?>[] {SubmitNodeCollector.class},
			(proxy, method, args) -> {
				if (method.getDeclaringClass() == Object.class) {
					return switch (method.getName()) {
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == args[0];
						default -> "ShadowEntities collector";
					};
				}
				if (method.getName().equals("order") && args != null && args.length == 1 && args[0] instanceof Integer order) {
					return nodes.order(BASE + order);
				}
				try {
					return method.invoke(nodes.order(BASE), args);
				} catch (InvocationTargetException e) {
					throw e.getCause();
				}
			});
		storage = nodes;
		collector = c;
		return c;
	}

	/** The orders a pass draws ({@code all}, of {@code orders}): all in the shadow map, the game's own anywhere else. */
	public static ObjectCollection<SubmitNodeCollection> orders(Int2ObjectAVLTreeMap<SubmitNodeCollection> orders, ObjectCollection<SubmitNodeCollection> all) {
		if (drawing || orders.isEmpty() || orders.lastIntKey() < FIRST) return all;
		return orders.headMap(FIRST).values();
	}

	/** The shadow map draws the entities (ShadowMap#renderEntities): the shadow's own orders too. */
	static void drawing(boolean on) {
		drawing = on;
	}
}
