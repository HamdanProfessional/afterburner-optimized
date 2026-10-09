package com.afterburner.labs.shaderpack.game;

import com.afterburner.labs.lod.Lod;
import com.afterburner.labs.shaderpack.PackUniforms;
import com.afterburner.labs.shaderpack.TranslateTarget;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.EndFlashState;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * OptiFine's standard uniforms for one frame (worldTime, sunPosition, gbufferModelView, eyeBrightnessSmooth, ...), as
 * {@link PackUniforms.Inputs}: what custom uniforms read and what the uniform blocks are filled with.
 * <p>
 * Matrices follow OptiFine: gbufferModelView is the camera's rotation (world geometry is drawn camera-relative), gbufferProjection
 * a normal OpenGL projection (near -1, far 1), not the game's reversed one.
 */
public final class FrameUniforms implements PackUniforms.Inputs {
	/** Turns the game's reversed-depth projection into an OpenGL one; the CPU side of the translator's ab_ToGl. */
	public static Matrix4f toGl(boolean zeroToOne) {
		return zeroToOne
			? new Matrix4f(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -2, 0, 0, 0, 1, 1)
			: new Matrix4f(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1);
	}

	/** The steps a kept shadow map turns in, per turn of the sky (a day). */
	private static final double SUN_STEPS = 3600.0;

	private final Map<String, double[]> values = new HashMap<>();
	private final Map<String, String> consts;
	private int frameCounter;
	private double frameTimeCounter;
	private long lastFrameNanos;
	private double wetness;
	private double eyeBlockSmooth;
	private double eyeSkySmooth;
	private boolean smoothStarted;
	private double endFlash;
	private final Matrix4f previousModelView = new Matrix4f();
	private final Matrix4f previousProjection = new Matrix4f();
	/** Distant Horizons' projection (the far terrain's): gbufferProjection with its own near and far planes. */
	final Matrix4f dhProjection = new Matrix4f();
	private final Matrix4f previousDhProjection = new Matrix4f();
	private double[] previousCamera = {0, 0, 0};
	private boolean hasPrevious;
	/** The shadow map's view and projection this frame (shadowModelView, shadowProjection); set while there's a world. */
	final Matrix4f shadowView = new Matrix4f();
	final Matrix4f shadowProjection = new Matrix4f();
	boolean hasShadow;

	/** {@code consts} are the pack's settings (eyeBrightnessHalflife, wetnessHalflife, ...). */
	public FrameUniforms(Map<String, String> consts) {
		this.consts = consts;
	}

	@Override
	public double @Nullable [] get(String name) {
		return this.values.get(name);
	}

	/** Sets a value for this frame (the shadow pass's matrices, per-draw values, ...). */
	public void put(String name, double... value) {
		this.values.put(name, value);
	}

	public void putMatrix(String name, Matrix4fc m) {
		this.values.put(name, matrix(m));
	}

	/**
	 * Computes the frame's values. {@code projection} is the game's world projection (reversed depth), {@code viewRotation}
	 * the camera's rotation matrix, {@code width}/{@code height} the size of the world's render target.
	 */
	public void update(Minecraft mc, Camera camera, float partialTick, Matrix4fc projection, Matrix4fc viewRotation, boolean zeroToOne,
			int width, int height) {
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		this.hasShadow = false;
		long now = System.nanoTime();
		double frameTime = this.lastFrameNanos == 0 ? 0.0 : Math.min((now - this.lastFrameNanos) / 1e9, 1.0);
		this.lastFrameNanos = now;
		this.frameCounter = (this.frameCounter + 1) % 720720;
		this.frameTimeCounter = (this.frameTimeCounter + frameTime) % 3600.0;
		this.put("frameCounter", this.frameCounter);
		this.put("frameTime", frameTime);
		this.put("frameTimeCounter", this.frameTimeCounter);
		this.put("viewWidth", width);
		this.put("viewHeight", height);
		this.put("aspectRatio", (double) width / Math.max(1, height));
		this.put("near", 0.05);
		this.put("far", mc.options.getEffectiveRenderDistance() * 16.0);
		this.put("screenBrightness", mc.options.gamma().get());
		this.put("hideGUI", mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden ? 1 : 0);

		// Matrices.
		Matrix4f modelView = new Matrix4f(viewRotation);
		Matrix4f glProjection = toGl(zeroToOne).mul(projection);
		this.putMatrix("gbufferModelView", modelView);
		this.putMatrix("gbufferModelViewInverse", new Matrix4f(modelView).invert());
		this.putMatrix("gbufferProjection", glProjection);
		this.putMatrix("gbufferProjectionInverse", new Matrix4f(glProjection).invert());
		this.putMatrix("gbufferPreviousModelView", this.hasPrevious ? this.previousModelView : modelView);
		this.putMatrix("gbufferPreviousProjection", this.hasPrevious ? this.previousProjection : glProjection);
		this.previousModelView.set(modelView);
		this.previousProjection.set(glProjection);
		this.farTerrain(mc, glProjection);

		var position = camera.position();
		double[] cameraPosition = {position.x, position.y, position.z};
		double[] previousCamera = this.hasPrevious ? this.previousCamera : cameraPosition;
		this.put("cameraPosition", cameraPosition);
		this.put("previousCameraPosition", previousCamera);
		// Iris's split of the camera position into whole blocks and the rest, exact however far out the camera is.
		this.put("cameraPositionInt", Math.floor(cameraPosition[0]), Math.floor(cameraPosition[1]), Math.floor(cameraPosition[2]));
		this.put("cameraPositionFract", frac(cameraPosition[0]), frac(cameraPosition[1]), frac(cameraPosition[2]));
		this.put("previousCameraPositionInt", Math.floor(previousCamera[0]), Math.floor(previousCamera[1]), Math.floor(previousCamera[2]));
		this.put("previousCameraPositionFract", frac(previousCamera[0]), frac(previousCamera[1]), frac(previousCamera[2]));
		this.previousCamera = cameraPosition;
		this.put("eyeAltitude", position.y);
		this.hasPrevious = true;

		if (level == null || player == null) return;
		var eyePosition = player.getEyePosition(partialTick);
		this.put("eyePosition", eyePosition.x, eyePosition.y, eyePosition.z);
		this.put("relativeEyePosition", position.x - eyePosition.x, position.y - eyePosition.y, position.z - eyePosition.z);
		EnvironmentAttributeProbe probe = camera.attributeProbe();

		// Time and sky.
		long clock = level.getDefaultClockTime();
		if (clock == 0) clock = level.getOverworldClockTime();
		this.put("worldTime", Math.floorMod(clock, 24000L));
		this.put("worldDay", Math.floorDiv(clock, 24000L));
		MoonPhase moonPhase = probe.getValue(EnvironmentAttributes.MOON_PHASE, partialTick);
		this.put("moonPhase", moonPhase.index());
		double skyAngle = frac(probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTick) / 360.0);
		double sunAngle = skyAngle < 0.75 ? skyAngle + 0.25 : skyAngle - 0.75;
		this.put("sunAngle", sunAngle);
		this.put("shadowAngle", sunAngle <= 0.5 ? sunAngle : sunAngle - 0.5);
		float sunPath = (float) number("sunPathRotation", 0.0);
		double[] sun = celestial(modelView, sunPath, probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTick));
		double[] moon = celestial(modelView, sunPath, probe.getValue(EnvironmentAttributes.MOON_ANGLE, partialTick));
		this.put("sunPosition", sun);
		this.put("moonPosition", moon);
		this.put("shadowLightPosition", sunAngle <= 0.5 ? sun : moon);

		// The shadow map's view, as OptiFine sets it up: from 100 blocks toward the sun or moon, looking back at the camera,
		// moved in steps of shadowIntervalSize so it doesn't shimmer; seen orthographically out to shadowDistance.
		// It turns by the sky's angle (0 at noon: looking straight down), not shadowAngle (0.25 at noon), which would light
		// from the horizon at noon and stretch every shadow out of sight. At night, the sky's angle half a turn on: the moon.
		double shadowAngle = sunAngle <= 0.5 ? sunAngle : sunAngle - 0.5;
		double shadowSky = shadowAngle < 0.25 ? shadowAngle + 0.75 : shadowAngle - 0.25;
		// A kept shadow map turns in steps (a tenth of a degree, a third of a second apart) so it's drawn again only then.
		if (Shaderpacks.shadowUpdates() != Shaderpacks.ShadowUpdates.EVERY_FRAME) shadowSky = Math.floor(shadowSky * SUN_STEPS) / SUN_STEPS;
		float interval = (float) number("shadowIntervalSize", 2.0);
		float distance = (float) number("shadowDistance", 160.0);
		Matrix4f shadowView = new Matrix4f().translate(0, 0, -100).rotateX((float) Math.toRadians(90.0))
			.rotateZ((float) Math.toRadians(shadowSky * -360.0)).rotateX((float) Math.toRadians(number("sunPathRotation", 0.0)));
		if (interval > 0) {
			shadowView.translate((float) (camera.position().x % interval) - interval / 2, (float) (camera.position().y % interval) - interval / 2,
				(float) (camera.position().z % interval) - interval / 2);
		}
		Matrix4f shadowProjection = new Matrix4f().setOrtho(-distance, distance, -distance, distance, 0.05F, 256.0F);
		this.putMatrix("shadowModelView", shadowView);
		this.putMatrix("shadowModelViewInverse", new Matrix4f(shadowView).invert());
		this.putMatrix("shadowProjection", shadowProjection);
		this.putMatrix("shadowProjectionInverse", new Matrix4f(shadowProjection).invert());
		this.putMatrix(TranslateTarget.SHADOW_FROM_VIEW, new Matrix4f(shadowView).mul(new Matrix4f(modelView).invert()));
		this.putMatrix(TranslateTarget.SHADOW_PROJECTION, shadowProjection);
		this.shadowView.set(shadowView);
		this.shadowProjection.set(shadowProjection);
		this.hasShadow = true;
		Vector4f up = modelView.transform(new Vector4f(0, 100, 0, 0));
		this.put("upPosition", up.x, up.y, up.z);
		Vector3fc skyColor = probe.getValue(EnvironmentAttributes.SKY_COLOR, partialTick);
		this.put("skyColor", skyColor.x(), skyColor.y(), skyColor.z());
		Vector3fc fogColor = probe.getValue(EnvironmentAttributes.FOG_COLOR, partialTick);
		this.put("fogColor", fogColor.x(), fogColor.y(), fogColor.z());
		double far = mc.options.getEffectiveRenderDistance() * 16.0;
		this.put("fogMode", 9729);
		this.put("fogShape", 0);
		this.put("fogStart", far * 0.9);
		this.put("fogEnd", far);
		this.put("fogDensity", 1.0);
		// gl_Fog outside the world passes (GlslTranslator.collectUniforms).
		this.put("ab_fogColor", fogColor.x(), fogColor.y(), fogColor.z());
		this.put("ab_fogStart", far * 0.9);
		this.put("ab_fogEnd", far);
		this.put("ab_fogDensity", 1.0);

		// Weather.
		double rain = level.getRainLevel(partialTick);
		this.put("rainStrength", rain);
		double halflife = rain > this.wetness ? number("wetnessHalflife", 600.0) : number("drynessHalflife", 200.0);
		this.wetness = smooth(this.wetness, rain, frameTime, halflife / 20.0);
		this.put("wetness", this.wetness);

		// The camera's entity.
		FogType fluid = camera.getFluidInCamera();
		this.put("isEyeInWater", switch (fluid) {
			case WATER -> 1;
			case LAVA -> 2;
			case POWDER_SNOW -> 3;
			default -> 0;
		});
		this.put("nightVision", player.hasEffect(MobEffects.NIGHT_VISION) ? GameRenderer.nightVisionScale(player, partialTick) : 0.0);
		this.put("blindness", player.getEffectBlendFactor(MobEffects.BLINDNESS, partialTick));
		double darknessScale = mc.options.darknessEffectScale().get();
		double darkness = player.getEffectBlendFactor(MobEffects.DARKNESS, partialTick) * darknessScale;
		this.put("darknessFactor", darkness);
		this.put("darknessLightFactor",
			Math.max(0.0, Mth.cos((player.tickCount - partialTick) * (float) Math.PI * 0.025F) * 0.45 * darkness) * darknessScale);
		this.put("playerMood", 0.0);
		this.put("bossBattle", 0);

		BlockPos eye = camera.blockPosition();
		int blockLight = level.getBrightness(LightLayer.BLOCK, eye);
		int skyLight = level.getBrightness(LightLayer.SKY, eye);
		this.put("eyeBrightness", blockLight * 16, skyLight * 16);
		if (!this.smoothStarted) {
			this.eyeBlockSmooth = blockLight * 16;
			this.eyeSkySmooth = skyLight * 16;
			this.smoothStarted = true;
		}
		double eyeHalflife = number("eyeBrightnessHalflife", 10.0);
		this.eyeBlockSmooth = smooth(this.eyeBlockSmooth, blockLight * 16, frameTime, eyeHalflife);
		this.eyeSkySmooth = smooth(this.eyeSkySmooth, skyLight * 16, frameTime, eyeHalflife);
		this.put("eyeBrightnessSmooth", Math.round(this.eyeBlockSmooth), Math.round(this.eyeSkySmooth));
		this.put("centerDepthSmooth", 0.0);

		this.put("heldItemId", -1);
		this.put("heldItemId2", -1);
		this.put("heldBlockLightValue", lightValue(player.getMainHandItem()));
		this.put("heldBlockLightValue2", lightValue(player.getOffhandItem()));
		this.world(level, player, partialTick, position, modelView, probe);
		this.player(mc, player, partialTick);
	}

	/** Iris's uniforms for the dimension, the weather, the biome the player is in, lightning and the End's flashes. */
	private void world(ClientLevel level, LocalPlayer player, float partialTick, Vec3 camera, Matrix4fc modelView, EnvironmentAttributeProbe probe) {
		DimensionType dimension = level.dimensionType();
		this.put("bedrockLevel", dimension.minY());
		this.put("heightLimit", dimension.height());
		this.put("logicalHeightLimit", dimension.logicalHeight());
		this.put("hasCeiling", flag(dimension.hasCeiling()));
		this.put("hasSkylight", flag(dimension.hasSkyLight()));
		this.put("ambientLight", dimension.ambientLight());
		this.put("seaLevel", level.getSeaLevel());
		this.put("cloudHeight", probe.getValue(EnvironmentAttributes.CLOUD_HEIGHT, partialTick));
		// How far the game's clouds have moved (its cloud texture is 256 cells wide; they wrap around after 400 ticks a cell).
		this.put("cloudTime", (Math.floorMod(level.getGameTime(), 256L * 400L) + partialTick) * 0.03);
		this.put("thunderStrength", Mth.clamp(level.getThunderLevel(partialTick), 0.0F, 1.0F));

		// A lightning bolt's position, from the camera, with w 1; all 0 while there's none.
		double[] bolt = {0, 0, 0, 0};
		for (Entity entity : level.entitiesForRendering()) {
			if (entity instanceof LightningBolt) {
				Vec3 p = entity.getPosition(partialTick);
				bolt = new double[] {p.x - camera.x, p.y - camera.y, p.z - camera.z, 1};
				break;
			}
		}
		this.put("lightningBoltPosition", bolt);

		// The End's flashes: how bright, and where in view space (100 blocks out, where the sky draws them).
		EndFlashState flash = level.endFlashState();
		double intensity = flash != null ? flash.getIntensity(partialTick) : 0.0;
		this.put("previousEndFlashIntensity", this.endFlash);
		this.put("endFlashIntensity", intensity);
		this.endFlash = intensity;
		if (flash != null) {
			double x = Math.toRadians(flash.getXAngle()), y = Math.toRadians(flash.getYAngle());
			Vector4f p = modelView.transform(new Vector4f((float) (-100.0 * Math.cos(x) * Math.sin(y)), (float) (-100.0 * Math.sin(x)),
				(float) (100.0 * Math.cos(x) * Math.cos(y)), 0.0F), new Vector4f());
			this.put("endFlashPosition", p.x, p.y, p.z);
		} else {
			this.put("endFlashPosition", 0, 0, 0);
		}

		// The biome at the player's feet.
		BlockPos feet = player.blockPosition();
		Holder<Biome> biome = level.getBiome(feet);
		this.put("biome", PackBiomes.id(biome));
		this.put("biome_category", PackBiomes.category(biome));
		this.put("biome_precipitation", switch (biome.value().getPrecipitationAt(feet, level.getSeaLevel())) {
			case NONE -> 0;
			case RAIN -> 1;
			case SNOW -> 2;
		});
		this.put("temperature", biome.value().getBaseTemperature());
		this.put("rainfall", biome.value().climateSettings.downfall());
	}

	/** Iris's uniforms for the player: health, hunger, air and armor (-1 outside survival and adventure), the camera, their state. */
	private void player(Minecraft mc, LocalPlayer player, float partialTick) {
		GameType mode = mc.gameMode != null ? mc.gameMode.getPlayerMode() : null;
		boolean survival = mode != null && mode.isSurvival();
		this.put("currentPlayerHealth", survival ? player.getHealth() / player.getMaxHealth() : -1.0);
		this.put("maxPlayerHealth", survival ? player.getMaxHealth() : -1.0);
		this.put("currentPlayerHunger", survival ? player.getFoodData().getFoodLevel() / 20.0 : -1.0);
		this.put("maxPlayerHunger", 20.0);
		this.put("currentPlayerAir", survival ? (double) player.getAirSupply() / player.getMaxAirSupply() : -1.0);
		this.put("maxPlayerAir", survival ? player.getMaxAirSupply() : -1.0);
		this.put("currentPlayerArmor", survival ? player.getArmorValue() / 50.0 : -1.0);
		this.put("maxPlayerArmor", 50.0);

		this.put("firstPersonCamera", flag(mc.options.getCameraType().isFirstPerson()));
		this.put("isSpectator", flag(mode == GameType.SPECTATOR));
		this.put("isRightHanded", flag(mc.options.mainHand().get() == HumanoidArm.RIGHT));
		Entity camera = mc.getCameraEntity();
		Vec3 look = camera instanceof LivingEntity ? camera.getViewVector(partialTick) : Vec3.ZERO;
		this.put("playerLookVector", look.x, look.y, look.z);
		Vec3 body = camera != null ? camera.getForward() : Vec3.ZERO;
		this.put("playerBodyVector", body.x, body.y, body.z);

		this.put("is_alive", flag(player.isAlive()));
		this.put("is_sneaking", flag(player.isCrouching()));
		this.put("is_sprinting", flag(player.isSprinting()));
		this.put("is_hurt", flag(player.hurtTime > 0));
		this.put("is_invisible", flag(player.isInvisible()));
		this.put("is_burning", flag(player.isOnFire()));
		this.put("is_on_ground", flag(player.onGround()));
		this.put("isElytraFlying", flag(player.isFallFlying()));
		this.put("isRiding", flag(player.isPassenger()));
		this.put("feetInWater", flag(player.isInShallowWater()));
	}

	private static double flag(boolean b) {
		return b ? 1.0 : 0.0;
	}

	/**
	 * Distant Horizons' uniforms, for the far terrain: its projection is the game's with the near plane further out and the far
	 * one past the far terrain's edge (in a depth of its own, dhDepthTex); dhRenderDistance is how far it reaches, in blocks.
	 */
	private void farTerrain(Minecraft mc, Matrix4f glProjection) {
		float view = mc.options.getEffectiveRenderDistance() * 16.0F;
		float lod = Lod.packDistance();
		float near = 16.0F, far = Math.max(lod * 1.5F, lod + 512.0F);
		// Only the depth row changes. The game's projection may have the view's bobbing after it (P * B, B turning and moving):
		// its w row is -(B's z row), so the depth row of P' * B is -a * (that w row) + (0, 0, 0, b).
		float a = -(far + near) / (far - near), b = -2.0F * far * near / (far - near);
		this.dhProjection.set(glProjection).m02(-a * glProjection.m03()).m12(-a * glProjection.m13()).m22(-a * glProjection.m23())
			.m32(-a * glProjection.m33() + b).determineProperties();
		// With none drawn (Target FPS turned it off) the pack still has Distant Horizons' macros: one that fades its own terrain out
		// where the far terrain takes over (MakeUp: from dhNearPlane + 90% of the way to far) would show sky there. The near plane
		// at far gives that fade no width. (dhDepthTex is all 1.0 then, which reads as dhFarPlane whatever the near plane is.)
		this.put("dhNearPlane", lod > 0.0F ? near : view);
		this.put("dhFarPlane", far);
		this.put("dhRenderDistance", Math.round(Math.max(view, lod)));
		this.putMatrix("dhProjection", this.dhProjection);
		this.putMatrix("dhProjectionInverse", new Matrix4f(this.dhProjection).invert());
		this.putMatrix("dhPreviousProjection", this.hasPrevious ? this.previousDhProjection : this.dhProjection);
		this.previousDhProjection.set(this.dhProjection);
	}

	/** A number setting of the pack (a const in its programs), or {@code fallback}. */
	double number(String name, double fallback) {
		String value = this.consts.get(name);
		if (value == null) return fallback;
		try {
			return Double.parseDouble(value.replaceAll("[fF]$", ""));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** Moves toward the target so half the distance is covered every {@code halflife} seconds. */
	private static double smooth(double current, double target, double seconds, double halflife) {
		if (halflife <= 0.0) return target;
		return target + (current - target) * Math.pow(0.5, seconds / halflife);
	}

	private static int lightValue(ItemStack stack) {
		return stack.getItem() instanceof BlockItem item ? item.getBlock().defaultBlockState().getLightEmission() : 0;
	}

	/** A celestial body 100 blocks out, in eye space: the sky renderer's transform applied to (0, 100, 0). */
	/** Where the sun or moon is in view space, its path tilted by the pack's sunPathRotation as the shadow's view is. */
	private static double[] celestial(Matrix4fc modelView, float sunPathRotation, float angleDegrees) {
		Matrix4f m = new Matrix4f(modelView).rotateY((float) Math.toRadians(-90.0)).rotateZ((float) Math.toRadians(sunPathRotation))
			.rotateX((float) Math.toRadians(angleDegrees));
		Vector4f p = m.transform(new Vector4f(0, 100, 0, 0));
		return new double[] {p.x, p.y, p.z};
	}

	private static double frac(double x) {
		return x - Math.floor(x);
	}

	static double[] matrix(Matrix4fc m) {
		return new double[] {
			m.m00(), m.m01(), m.m02(), m.m03(),
			m.m10(), m.m11(), m.m12(), m.m13(),
			m.m20(), m.m21(), m.m22(), m.m23(),
			m.m30(), m.m31(), m.m32(), m.m33()};
	}
}
