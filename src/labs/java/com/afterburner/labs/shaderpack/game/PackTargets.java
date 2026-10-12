package com.afterburner.labs.shaderpack.game;

import java.util.Map;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * The pack's screen-sized buffers: colortex0-15, each twice so a pass can read one while writing the other, and the depth
 * buffers (with the far terrain's, dhDepthTex). Depth is cleared to 1, nearer is smaller, as the game's.
 */
final class PackTargets implements AutoCloseable {
	static final int BUFFERS = 16;
	/** The old names of colortex0-7, as buffer settings may use them. */
	private static final String[] LEGACY = {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};

	private final boolean[] used;
	private final Gl.Format[] formats = new Gl.Format[BUFFERS];
	private final boolean[] clear = new boolean[BUFFERS];
	private final Vector4f[] clearColors = new Vector4f[BUFFERS];
	private final int[][] textures = new int[BUFFERS][];
	/** Which of a buffer's two textures holds its latest contents. */
	private final int[] current = new int[BUFFERS];
	int width;
	int height;
	private int depth, depthNoTranslucents, depthNoHand;
	/** The far terrain's depth (Distant Horizons' dhDepthTex), with its own projection. */
	private int farDepth;
	/**
	 * Its copy without the water a pack draws on its own (dhDepthTex1), and that with a floor under the water (what the
	 * pack sees under it, from its water on: see PackRenderer#drawFarWater).
	 */
	private int farDepthSolid, farDepthFloor;

	/** {@code used} says which buffers the pack draws to or reads; {@code consts} has their settings. */
	PackTargets(boolean[] used, Map<String, String> consts, int width, int height) {
		this.used = used;
		for (int b = 0; b < BUFFERS; b++) {
			if (!used[b]) continue;
			String format = setting(consts, b, "Format");
			this.formats[b] = format == null ? Gl.Format.RGBA8 : Gl.format(format);
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
		this.width = width;
		this.height = height;
		for (int b = 0; b < BUFFERS; b++) {
			if (!this.used[b]) continue;
			this.textures[b] = new int[2];
			for (int i = 0; i < 2; i++) this.textures[b][i] = Gl.texture(this.formats[b], width, height, false, false);
			this.current[b] = 0;
		}
		this.depth = Gl.texture(Gl.Format.DEPTH, width, height, true, false);
		this.depthNoTranslucents = Gl.texture(Gl.Format.DEPTH, width, height, true, false);
		this.depthNoHand = Gl.texture(Gl.Format.DEPTH, width, height, true, false);
		this.farDepth = Gl.texture(Gl.Format.DEPTH, width, height, true, false);
		this.farDepthSolid = Gl.texture(Gl.Format.DEPTH, width, height, true, false);
		this.farDepthFloor = Gl.texture(Gl.Format.DEPTH, width, height, true, false);
		// Buffers that aren't cleared every frame start out cleared too.
		for (int b = 0; b < BUFFERS; b++) {
			if (!this.used[b]) continue;
			Vector4f c = this.clearColors[b];
			for (int t : this.textures[b]) Gl.clear(t, this.formats[b], c.x, c.y, c.z, c.w);
		}
		for (int t : new int[] {this.depth, this.depthNoTranslucents, this.depthNoHand, this.farDepth, this.farDepthSolid, this.farDepthFloor}) {
			Gl.clearDepth(t, 1.0F);
		}
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

	Gl.Format format(int buffer) {
		return this.formats[buffer];
	}

	/** The texture with the buffer's latest contents: what passes read, and what the world is drawn into. */
	int read(int buffer) {
		return this.textures[buffer][this.current[buffer]];
	}

	/** The other texture: what a fullscreen pass writing the buffer draws into before {@link #flip}. */
	int write(int buffer) {
		return this.textures[buffer][1 - this.current[buffer]];
	}

	void flip(int buffer) {
		this.current[buffer] = 1 - this.current[buffer];
	}

	int depth() {
		return this.depth;
	}

	int depthNoTranslucents() {
		return this.depthNoTranslucents;
	}

	int depthNoHand() {
		return this.depthNoHand;
	}

	int farDepth() {
		return this.farDepth;
	}

	int farDepthSolid() {
		return this.farDepthSolid;
	}

	int farDepthFloor() {
		return this.farDepthFloor;
	}

	/** The start of a frame: clears the depth, and the buffers that are cleared ({@code fog} is colortex0's default color). */
	void clear(Vector4f fog) {
		Gl.clearDepth(this.depth, 1.0F);
		Gl.clearDepth(this.farDepth, 1.0F);
		Gl.clearDepth(this.farDepthSolid, 1.0F);
		for (int b = 0; b < BUFFERS; b++) {
			if (!this.used[b] || !this.clear[b]) continue;
			Vector4f c = b == 0 && this.clearColors[b].lengthSquared() == 0 ? fog : this.clearColors[b];
			Gl.clear(this.textures[b][this.current[b]], this.formats[b], c.x, c.y, c.z, c.w);
		}
	}

	/** Copies depthtex0 to depthtex1 (before translucents) or depthtex2 (before the hand). */
	void copyDepth(boolean toNoHand) {
		Gl.copy(this.depth, toNoHand ? this.depthNoHand : this.depthNoTranslucents, this.width, this.height, true);
	}

	/** Copies the far terrain's depth to {@link #farDepthSolid} (after it's drawn), or that to {@link #farDepthFloor} (before its floor). */
	void copyFarDepth(boolean toFloor) {
		if (toFloor) Gl.copy(this.farDepthSolid, this.farDepthFloor, this.width, this.height, true);
		else Gl.copy(this.farDepth, this.farDepthSolid, this.width, this.height, true);
	}

	private void release() {
		for (int b = 0; b < BUFFERS; b++) {
			if (this.textures[b] == null) continue;
			for (int t : this.textures[b]) Gl.deleteTexture(t);
			this.textures[b] = null;
		}
		for (int t : new int[] {this.depth, this.depthNoTranslucents, this.depthNoHand, this.farDepth, this.farDepthSolid, this.farDepthFloor}) {
			Gl.deleteTexture(t);
		}
		this.depth = this.depthNoTranslucents = this.depthNoHand = this.farDepth = this.farDepthSolid = this.farDepthFloor = 0;
	}

	@Override
	public void close() {
		this.release();
	}
}
