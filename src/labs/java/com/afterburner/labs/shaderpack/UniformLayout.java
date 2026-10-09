package com.afterburner.labs.shaderpack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The pack's plain (non-sampler) uniforms, as two std140 blocks shared by all its programs: {@value #FRAME_BLOCK} (filled once a
 * frame) and {@value #DRAW_BLOCK} (values that change between draws, like entityId). Every program declares both blocks whole,
 * so one buffer of each serves them all.
 * <p>
 * Two programs may declare the same name with different types; the first type keeps the name and the others get
 * {@code ab_<type>_<name>}.
 */
public final class UniformLayout {
	public static final String FRAME_BLOCK = "AbFrame";
	public static final String DRAW_BLOCK = "AbDraw";

	/** Uniforms whose value changes from draw to draw. */
	public static final Set<String> PER_DRAW = Set.of(
		"entityId", "blockEntityId", "entityColor", "renderStage", "blendFunc", "instanceId", "atlasSize", "spriteBounds",
		"currentRenderedItemId");

	public record Member(String name, String glslName, String type, int arraySize, boolean perDraw, int offset, int size) {}

	private final Map<String, String> firstType = new HashMap<>();
	private final Map<String, Member> members = new LinkedHashMap<>();
	private boolean finished;
	private int frameSize;
	private int drawSize;

	/** Adds a uniform a program declares; returns the name it has in the blocks. */
	public String add(String name, String type, int arraySize) {
		if (this.finished) throw new IllegalStateException("Layout already finished");
		String first = this.firstType.putIfAbsent(name, type);
		String glslName = first == null || first.equals(type) ? name : "ab_" + type + "_" + name;
		String key = glslName;
		Member old = this.members.get(key);
		if (old == null || arraySize > old.arraySize) {
			this.members.put(key, new Member(name, glslName, type, arraySize, PER_DRAW.contains(name), 0, 0));
		}
		return glslName;
	}

	/** The name a uniform of this type has in the blocks (after {@link #add}). */
	public String glslName(String name, String type) {
		String first = this.firstType.get(name);
		return first == null || first.equals(type) ? name : "ab_" + type + "_" + name;
	}

	/** Lays out both blocks. */
	public void finish() {
		if (this.finished) return;
		this.finished = true;
		List<Member> sorted = new ArrayList<>(this.members.values());
		sorted.sort(Comparator.comparingInt((Member m) -> -align(m.type, m.arraySize)).thenComparing(Member::glslName));
		int frame = 0;
		int draw = 0;
		Map<String, Member> laidOut = new LinkedHashMap<>();
		for (Member m : sorted) {
			int align = align(m.type, m.arraySize);
			int size = size(m.type, m.arraySize);
			int offset;
			if (m.perDraw) {
				offset = roundUp(draw, align);
				draw = offset + size;
			} else {
				offset = roundUp(frame, align);
				frame = offset + size;
			}
			laidOut.put(m.glslName, new Member(m.name, m.glslName, m.type, m.arraySize, m.perDraw, offset, size));
		}
		this.members.clear();
		this.members.putAll(laidOut);
		this.frameSize = Math.max(16, roundUp(frame, 16));
		this.drawSize = Math.max(16, roundUp(draw, 16));
	}

	public List<Member> members() {
		return List.copyOf(this.members.values());
	}

	public @Nullable Member member(String glslName) {
		return this.members.get(glslName);
	}

	public int frameSize() {
		return this.frameSize;
	}

	public int drawSize() {
		return this.drawSize;
	}

	/** The GLSL declaration of one block, members in offset order. Each block has at least one member. */
	public String declaration(boolean perDraw) {
		if (!this.finished) throw new IllegalStateException("Layout not finished");
		StringBuilder out = new StringBuilder("layout(std140) uniform ").append(perDraw ? DRAW_BLOCK : FRAME_BLOCK).append(" {\n");
		boolean any = false;
		for (Member m : this.members.values()) {
			if (m.perDraw != perDraw) continue;
			out.append("\t").append(m.type).append(' ').append(m.glslName);
			if (m.arraySize > 0) out.append('[').append(m.arraySize).append(']');
			out.append(";\n");
			any = true;
		}
		if (!any) out.append("\tvec4 ab_unused").append(perDraw ? "Draw" : "Frame").append(";\n");
		return out.append("};\n").toString();
	}

	/**
	 * Writes one block's values at the buffer's position (which isn't moved). Values are as in {@link PackUniforms}: matrices
	 * column-major, arrays one element after another; members without a value are zero.
	 */
	public void write(ByteBuffer buffer, boolean perDraw, PackUniforms.Inputs values) {
		int base = buffer.position();
		int size = perDraw ? this.drawSize : this.frameSize;
		for (int i = 0; i < size; i += 4) buffer.putInt(base + i, 0);
		for (Member m : this.members.values()) {
			if (m.perDraw != perDraw) continue;
			double[] v = values.get(m.name);
			if (v == null) continue;
			int[] mat = matrix(m.type);
			boolean integer = m.type.startsWith("i") || m.type.startsWith("u") || m.type.startsWith("b") || m.type.equals("int")
				|| m.type.equals("uint") || m.type.equals("bool");
			int count = Math.max(1, m.arraySize);
			int stride = m.arraySize > 0 ? roundUp(size(m.type, 0), 16) : 0;
			int k = 0;
			for (int element = 0; element < count; element++) {
				int at = base + m.offset + element * stride;
				if (mat != null) {
					for (int c = 0; c < mat[0]; c++) {
						for (int r = 0; r < mat[1]; r++) {
							if (k < v.length) buffer.putFloat(at + c * 16 + r * 4, (float) v[k]);
							k++;
						}
					}
				} else {
					int n = components(m.type);
					for (int j = 0; j < n; j++) {
						double d = k < v.length ? v[k] : 0.0;
						if (integer) buffer.putInt(at + j * 4, (int) d);
						else buffer.putFloat(at + j * 4, (float) d);
						k++;
					}
				}
			}
		}
	}

	// ---- std140 ----

	static int roundUp(int value, int to) {
		return (value + to - 1) / to * to;
	}

	/** Components of a scalar or vector type, or 0. */
	static int components(String type) {
		return switch (type) {
			case "float", "int", "uint", "bool" -> 1;
			case "vec2", "ivec2", "uvec2", "bvec2" -> 2;
			case "vec3", "ivec3", "uvec3", "bvec3" -> 3;
			case "vec4", "ivec4", "uvec4", "bvec4" -> 4;
			default -> 0;
		};
	}

	/** Columns and rows of a matrix type, or null. */
	static int @Nullable [] matrix(String type) {
		if (!type.startsWith("mat")) return null;
		String dims = type.substring(3);
		if (dims.length() == 1) {
			int n = dims.charAt(0) - '0';
			return n >= 2 && n <= 4 ? new int[] {n, n} : null;
		}
		if (dims.length() == 3 && dims.charAt(1) == 'x') {
			int c = dims.charAt(0) - '0';
			int r = dims.charAt(2) - '0';
			return c >= 2 && c <= 4 && r >= 2 && r <= 4 ? new int[] {c, r} : null;
		}
		return null;
	}

	static boolean isKnownType(String type) {
		return components(type) > 0 || matrix(type) != null;
	}

	static int align(String type, int arraySize) {
		if (arraySize > 0 || matrix(type) != null) return 16;
		int n = components(type);
		return n == 1 ? 4 : n == 2 ? 8 : 16;
	}

	static int size(String type, int arraySize) {
		int[] mat = matrix(type);
		int one = mat != null ? mat[0] * 16 : components(type) * 4;
		if (arraySize <= 0) return one;
		return roundUp(one, 16) * arraySize;
	}
}
