package com.afterburner.labs.shaderpack.game;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Locale;
import java.util.Map;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * The pack's screen-sized buffers: colortex0-15, each twice so a pass can read one while writing the other, and the depth
 * buffers (with the far terrain's, dhDepthTex). The depth here is a normal one (cleared to 1, nearer is smaller), not the
 * game's reversed one.
 */
final class PackTargets implements AutoCloseable {
	static final int BUFFERS = 16;
	private static final int COLOR_USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING
		| GpuTexture.USAGE_RENDER_ATTACHMENT;
	/** The old names of colortex0-7, as buffer settings may use them. */
	private static final String[] LEGACY = {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};

	private final boolean[] used;
	private final GpuFormat[] formats = new GpuFormat[BUFFERS];
	private final boolean[] clear = new boolean[BUFFERS];
	private final Vector4f[] clearColors = new Vector4f[BUFFERS];
	private final GpuTexture[][] textures = new GpuTexture[BUFFERS][];
	private final GpuTextureView[][] views = new GpuTextureView[BUFFERS][];
	/** Which of a buffer's two textures holds its latest contents. */
	private final int[] current = new int[BUFFERS];
	int width;
	int height;
	private @Nullable GpuTexture depth;
	private @Nullable GpuTextureView depthView;
	private @Nullable GpuTexture depthNoTranslucents;
	private @Nullable GpuTextureView depthNoTranslucentsView;
	private @Nullable GpuTexture depthNoHand;
	private @Nullable GpuTextureView depthNoHandView;
	/** The far terrain's depth (Distant Horizons' dhDepthTex), with its own projection. */
	private @Nullable GpuTexture farDepth;
	private @Nullable GpuTextureView farDepthView;
	/**
	 * Its copy without the water a pack draws on its own (dhDepthTex1), and that with a floor under the water (what the
	 * pack sees under it, from its water on: see PackRenderer#drawFarWater).
	 */
	private @Nullable GpuTexture farDepthSolid, farDepthFloor;
	private @Nullable GpuTextureView farDepthSolidView, farDepthFloorView;

	/** {@code used} says which buffers the pack draws to or reads; {@code consts} has their settings. */
	PackTargets(boolean[] used, Map<String, String> consts, int width, int height) {
		this.used = used;
		for (int b = 0; b < BUFFERS; b++) {
			if (!used[b]) continue;
			String format = setting(consts, b, "Format");
			this.formats[b] = format == null ? GpuFormat.RGBA8_UNORM : format(format);
			this.clear[b] = !"false".equals(setting(consts, b, "Clear"));
			String color = setting(consts, b, "ClearColor");
			this.clearColors[b] = color != null ? vec4(color) : b == 1 ? new Vector4f(1, 1, 1, 1) : new Vector4f(0, 0, 0, 0);
		}
		this.create(width, height);
	}

	private static @Nullable String setting(Map<String, String> consts, int buffer, String what) {
		String value = consts.get("colortex" + buffer + what);
		if (value == null && buffer < LEGACY.length) value = consts.get(LEGACY[buffer] + what);
		return value;
	}

	/** An OptiFine format name as the game's: R11F_G11F_B10F is RG11B10_FLOAT. Three-channel ones get four. */
	static GpuFormat format(String name) {
		return switch (name.strip().toUpperCase(Locale.ROOT)) {
			case "R8" -> GpuFormat.R8_UNORM;
			case "RG8" -> GpuFormat.RG8_UNORM;
			case "R8_SNORM" -> GpuFormat.R8_SNORM;
			case "RG8_SNORM" -> GpuFormat.RG8_SNORM;
			case "RGB8_SNORM", "RGBA8_SNORM" -> GpuFormat.RGBA8_SNORM;
			case "R16" -> GpuFormat.R16_UNORM;
			case "RG16" -> GpuFormat.RG16_UNORM;
			case "RGB16", "RGBA16" -> GpuFormat.RGBA16_UNORM;
			case "R16F" -> GpuFormat.R16_FLOAT;
			case "RG16F" -> GpuFormat.RG16_FLOAT;
			case "RGB16F", "RGBA16F" -> GpuFormat.RGBA16_FLOAT;
			case "R32F" -> GpuFormat.R32_FLOAT;
			case "RG32F" -> GpuFormat.RG32_FLOAT;
			case "RGB32F", "RGBA32F" -> GpuFormat.RGBA32_FLOAT;
			case "R11F_G11F_B10F" -> GpuFormat.RG11B10_FLOAT;
			case "RGB10_A2" -> GpuFormat.RGB10A2_UNORM;
			case "R32I" -> GpuFormat.R32_SINT;
			case "R32UI" -> GpuFormat.R32_UINT;
			default -> GpuFormat.RGBA8_UNORM;
		};
	}

	/** "vec4(0.0, 0.0, 0.0, 1.0)" or one number; anything else is black. */
	static Vector4f vec4(String text) {
		String inner = text.replaceAll("^\\s*vec4\\s*\\(|\\)\\s*$", "");
		String[] parts = inner.split(",");
		float[] v = new float[4];
		try {
			for (int i = 0; i < 4; i++) v[i] = Float.parseFloat(parts[parts.length == 1 ? 0 : i].strip().replaceAll("[fF]$", ""));
		} catch (RuntimeException e) {
			return new Vector4f();
		}
		return new Vector4f(v[0], v[1], v[2], v[3]);
	}

	private void create(int width, int height) {
		GpuDevice device = RenderSystem.getDevice();
		this.width = width;
		this.height = height;
		for (int b = 0; b < BUFFERS; b++) {
			if (!this.used[b]) continue;
			this.textures[b] = new GpuTexture[2];
			this.views[b] = new GpuTextureView[2];
			for (int i = 0; i < 2; i++) {
				this.textures[b][i] = device.createTexture("Afterburner colortex" + b + (i == 0 ? "" : " (alt)"), COLOR_USAGE, this.formats[b], width,
					height, 1, 1);
				this.views[b][i] = device.createTextureView(this.textures[b][i]);
			}
			this.current[b] = 0;
		}
		this.depth = device.createTexture("Afterburner depthtex0", COLOR_USAGE, GpuFormat.D32_FLOAT, width, height, 1, 1);
		this.depthView = device.createTextureView(this.depth);
		this.depthNoTranslucents = device.createTexture("Afterburner depthtex1", COLOR_USAGE, GpuFormat.D32_FLOAT, width, height, 1, 1);
		this.depthNoTranslucentsView = device.createTextureView(this.depthNoTranslucents);
		this.depthNoHand = device.createTexture("Afterburner depthtex2", COLOR_USAGE, GpuFormat.D32_FLOAT, width, height, 1, 1);
		this.depthNoHandView = device.createTextureView(this.depthNoHand);
		this.farDepth = device.createTexture("Afterburner dhDepthTex", COLOR_USAGE, GpuFormat.D32_FLOAT, width, height, 1, 1);
		this.farDepthView = device.createTextureView(this.farDepth);
		this.farDepthSolid = device.createTexture("Afterburner dhDepthTex1", COLOR_USAGE, GpuFormat.D32_FLOAT, width, height, 1, 1);
		this.farDepthSolidView = device.createTextureView(this.farDepthSolid);
		this.farDepthFloor = device.createTexture("Afterburner dhDepthTex1 (floor)", COLOR_USAGE, GpuFormat.D32_FLOAT, width, height, 1, 1);
		this.farDepthFloorView = device.createTextureView(this.farDepthFloor);
		// Buffers that aren't cleared every frame start out cleared too.
		CommandEncoder encoder = device.createCommandEncoder();
		for (int b = 0; b < BUFFERS; b++) {
			if (!this.used[b]) continue;
			for (GpuTexture t : this.textures[b]) encoder.clearColorTexture(t, this.clearColors[b]);
		}
		encoder.clearDepthTexture(this.depthNoTranslucents, 1.0);
		encoder.clearDepthTexture(this.depthNoHand, 1.0);
		encoder.clearDepthTexture(this.farDepth, 1.0);
		encoder.clearDepthTexture(this.farDepthSolid, 1.0);
		encoder.clearDepthTexture(this.farDepthFloor, 1.0);
	}

	/** Makes them screen-sized again; their contents are lost. */
	void resize(int width, int height) {
		if (width == this.width && height == this.height) return;
		this.release();
		this.create(width, height);
	}

	boolean has(int buffer) {
		return buffer >= 0 && buffer < BUFFERS && this.used[buffer];
	}

	GpuFormat format(int buffer) {
		return this.formats[buffer];
	}

	/** The texture with the buffer's latest contents: what passes read, and what the world is drawn into. */
	GpuTextureView read(int buffer) {
		return this.views[buffer][this.current[buffer]];
	}

	/** The other texture: what a fullscreen pass writing the buffer draws into before {@link #flip}. */
	GpuTextureView write(int buffer) {
		return this.views[buffer][1 - this.current[buffer]];
	}

	void flip(int buffer) {
		this.current[buffer] = 1 - this.current[buffer];
	}

	boolean owns(GpuTextureView view) {
		if (view == this.depthView) return true;
		for (int b = 0; b < BUFFERS; b++) {
			if (this.used[b] && (view == this.views[b][0] || view == this.views[b][1])) return true;
		}
		return false;
	}

	GpuTextureView depth() {
		return this.depthView;
	}

	GpuTextureView depthNoTranslucents() {
		return this.depthNoTranslucentsView;
	}

	GpuTextureView depthNoHand() {
		return this.depthNoHandView;
	}

	GpuTextureView farDepth() {
		return this.farDepthView;
	}

	GpuTextureView farDepthSolid() {
		return this.farDepthSolidView;
	}

	GpuTextureView farDepthFloor() {
		return this.farDepthFloorView;
	}

	/** The start of a frame: clears the depth, and the buffers that are cleared ({@code fog} is colortex0's default color). */
	void clear(CommandEncoder encoder, Vector4f fog) {
		encoder.clearDepthTexture(this.depth, 1.0);
		encoder.clearDepthTexture(this.farDepth, 1.0);
		encoder.clearDepthTexture(this.farDepthSolid, 1.0);
		for (int b = 0; b < BUFFERS; b++) {
			if (!this.used[b] || !this.clear[b]) continue;
			encoder.clearColorTexture(this.textures[b][this.current[b]], b == 0 && this.clearColors[b].lengthSquared() == 0 ? fog : this.clearColors[b]);
		}
	}

	/** Copies depthtex0 to depthtex1 (before translucents) or depthtex2 (before the hand). */
	void copyDepth(CommandEncoder encoder, boolean toNoHand) {
		GpuTexture target = toNoHand ? this.depthNoHand : this.depthNoTranslucents;
		encoder.copyTextureToTexture(this.depth, target, 0, 0, 0, 0, 0, this.width, this.height);
	}

	/** Copies the far terrain's depth to {@link #farDepthSolid} (after it's drawn), or that to {@link #farDepthFloor} (before its floor). */
	void copyFarDepth(CommandEncoder encoder, boolean toFloor) {
		GpuTexture from = toFloor ? this.farDepthSolid : this.farDepth;
		encoder.copyTextureToTexture(from, toFloor ? this.farDepthFloor : this.farDepthSolid, 0, 0, 0, 0, 0, this.width, this.height);
	}

	private void release() {
		for (int b = 0; b < BUFFERS; b++) {
			if (this.textures[b] == null) continue;
			for (int i = 0; i < 2; i++) {
				this.views[b][i].close();
				this.textures[b][i].close();
			}
			this.textures[b] = null;
			this.views[b] = null;
		}
		for (GpuTextureView v : new GpuTextureView[] {this.depthView, this.depthNoTranslucentsView, this.depthNoHandView, this.farDepthView,
				this.farDepthSolidView, this.farDepthFloorView}) {
			if (v != null) v.close();
		}
		for (GpuTexture t : new GpuTexture[] {this.depth, this.depthNoTranslucents, this.depthNoHand, this.farDepth, this.farDepthSolid,
				this.farDepthFloor}) {
			if (t != null) t.close();
		}
	}

	@Override
	public void close() {
		this.release();
	}
}
