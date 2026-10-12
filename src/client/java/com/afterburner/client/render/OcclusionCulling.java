package com.afterburner.client.render;

import com.afterburner.Afterburner;
import com.afterburner.Features;
import com.afterburner.client.Addons;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.opengl.GL45C.*;

/**
 * Leaves out the chunk sections hidden behind nearer terrain.
 * <p>
 * Every frame, after the solid and cutout terrain is drawn, the graphics card tests every section's box against the
 * depth drawn so far (through a small depth pyramid), and what it finds is read back a frame or two later. A section is
 * then left out while its latest answer is "hidden", that answer is at most {@link #SECTION_MAX_AGE} frames old, and the
 * camera hasn't moved a block since. So a section coming into view (over a hill top, through a freshly dug hole) can
 * show up a frame or two late. To keep that rare, the test is done with a few pixels to spare, sections not entirely on
 * the screen count as seen (turning around never reveals a hole), and the sections right around the camera are always
 * drawn.
 * <p>
 * Needs OpenGL 4.5, or an older context with the extensions that bring the same (compute shaders, storage buffers,
 * direct state access); without them (macOS) everything is drawn, as in vanilla. Everything here runs on the render
 * thread, between the chunk draws, and puts back the OpenGL state it touches.
 */
public final class OcclusionCulling {
	public static final boolean ENABLED = Features.OCCLUSION_CULLING.enabled() && Features.CHUNK_BATCHING.enabled();
	/** Words per section test: box corner relative to the camera (3 floats, 1 unused). */
	static final int TEST_WORDS = 4;
	private static final int RING = 3;
	/** Section answers older than this many frames don't count any more: the section is drawn. */
	private static final int SECTION_MAX_AGE = 5;
	/**
	 * Share of the tested sections the last tests found hidden. When it's small (flying high, open land) the test costs
	 * the graphics chip about what it saves, so it runs every other frame only; answers stay young enough to be used.
	 */
	private static float hiddenShare = 1;
	private static final float SPARSE_BELOW = 0.1F;
	/** Below this almost nothing is hidden; the test is then run one frame in {@link #MAX_TEST_EVERY}. */
	private static final float VERY_SPARSE_BELOW = 0.02F;
	/**
	 * Frames at most between tests when little is hidden. Kept so an answer is never older than
	 * {@link #SECTION_MAX_AGE} even after the read-back delay, so no section is left out for longer than before.
	 */
	private static final int MAX_TEST_EVERY = 3;
	/** Texture units the compute shaders read from; the game's own state tracking only covers units 0-11. */
	private static final int DEPTH_UNIT = 14, PYRAMID_UNIT = 15;

	private static final String PYRAMID_SOURCE = """
			layout(local_size_x = 8, local_size_y = 8) in;
			#ifdef FIRST
			layout(binding = 14) uniform sampler2D Source;
			#else
			layout(r32f, binding = 1) readonly uniform image2D Source;
			#endif
			layout(r32f, binding = 0) writeonly uniform image2D Target;
			uniform ivec2 SourceSize;
			uniform ivec2 TargetSize;

			float read(ivec2 p) {
			    p = min(p, SourceSize - 1);
			#ifdef FIRST
			    return texelFetch(Source, p, 0).r;
			#else
			    return imageLoad(Source, p).r;
			#endif
			}

			// 1 is far, so the largest value is the farthest thing in the 2x2 block.
			void main() {
			    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
			    if (p.x >= TargetSize.x || p.y >= TargetSize.y) return;
			    ivec2 s = p * 2;
			    float d = max(max(read(s), read(s + ivec2(1, 0))), max(read(s + ivec2(0, 1)), read(s + ivec2(1, 1))));
			    imageStore(Target, p, vec4(d));
			}
			""";

	private static final String TEST_SOURCE = """
			layout(local_size_x = 64) in;
			layout(binding = 15) uniform sampler2D Pyramid;
			#ifdef BOXES
			// An entity's or block entity's box, relative to the camera: lowest corner, then highest corner.
			struct Box {
			    vec4 lo;
			    vec4 hi;
			};
			layout(std430, binding = 0) readonly buffer Boxes { Box boxes[]; };
			#else
			layout(std430, binding = 0) readonly buffer Tests { vec4 corners[]; };
			#endif
			layout(std430, binding = 2) writeonly buffer Results { uint results[]; };
			uniform mat4 ViewProjection;
			uniform ivec2 ScreenSize;
			uniform int Levels;
			uniform uint Count;
			uniform uint Base;
			// Pixels to spare around each box.
			uniform int Margin;
			// Block models may reach a block past their section, and the depth buffer rounds.
			const float GROW = 1.25;

			// The box only counts as hidden if all of it is on the screen: answers are used a few frames later, when the
			// camera may have turned towards a part that was off the screen.
			bool visible(vec3 lo, vec3 hi) {
			    vec2 low = vec2(1e30), high = vec2(-1e30);
			    float nearest = 1e30;
			    for (int i = 0; i < 8; i++) {
			        vec4 clip = ViewProjection * vec4((i & 1) != 0 ? hi.x : lo.x, (i & 2) != 0 ? hi.y : lo.y, (i & 4) != 0 ? hi.z : lo.z, 1.0);
			        // A corner (nearly) behind the camera: the box can't be put on the screen, so it counts as seen.
			        if (clip.w < 1.0) return true;
			        vec3 ndc = clip.xyz / clip.w;
			        low = min(low, ndc.xy);
			        high = max(high, ndc.xy);
			        nearest = min(nearest, ndc.z * 0.5 + 0.5);
			    }
			    vec2 size = vec2(ScreenSize);
			    ivec2 p0 = ivec2(floor((low * 0.5 + 0.5) * size)) - Margin;
			    ivec2 p1 = ivec2(floor((high * 0.5 + 0.5) * size)) + Margin;
			    if (any(lessThan(p0, ivec2(0))) || any(greaterThan(p1, ScreenSize - 1))) return true;
			    // The finest pyramid level where the box covers at most 2x2 texels; level k texels cover 2^(k+1) pixels.
			    int level = 0;
			    while (level < Levels - 1 && any(greaterThan((p1 >> (level + 1)) - (p0 >> (level + 1)), ivec2(1)))) level++;
			    ivec2 t0 = p0 >> (level + 1), t1 = p1 >> (level + 1);
			    float farthest = max(max(texelFetch(Pyramid, t0, level).r, texelFetch(Pyramid, ivec2(t1.x, t0.y), level).r),
			            max(texelFetch(Pyramid, ivec2(t0.x, t1.y), level).r, texelFetch(Pyramid, t1, level).r));
			    // Hidden only if everything already drawn there is nearer than the nearest corner of the box.
			    return farthest >= nearest;
			}

			#ifdef BOXES
			void main() {
			    uint id = gl_GlobalInvocationID.x;
			    if (id >= Count) return;
			    results[Base + id] = visible(boxes[id].lo.xyz - 0.25, boxes[id].hi.xyz + 0.25) ? 1u : 0u;
			}
			#else
			void main() {
			    uint id = gl_GlobalInvocationID.x;
			    if (id >= Count) return;
			    vec3 corner = corners[id].xyz;
			    results[id] = visible(corner - GROW, corner + (16.0 + GROW)) ? 1u : 0u;
			}
			#endif
			""";

	/** 0 not checked yet, 1 working, -1 not available here. */
	private static int state;
	private static int firstProgram, downProgram, testProgram, boxProgram, depthSampler, pyramidSampler;
	/** Uniform locations, looked up once: the string lookups are not cheap enough to do every frame. */
	private static int firstSourceLoc, firstTargetLoc, downSourceLoc, downTargetLoc;
	private static int testViewProjectionLoc, testScreenSizeLoc, testLevelsLoc, testCountLoc, testMarginLoc;
	private static int boxViewProjectionLoc, boxScreenSizeLoc, boxLevelsLoc, boxCountLoc, boxMarginLoc, boxBaseLoc;
	private static int pyramid, pyramidWidth, pyramidHeight, pyramidLevels, sourceWidth, sourceHeight;
	private static final Matrix4f viewProjection = new Matrix4f();
	/** Why the last test couldn't run, logged once per reason. */
	private static final Set<String> skipped = new HashSet<>();

	/** Whether this frame's chunk draws leave out hidden sections and test them, and whether the last frame's did. */
	private static boolean on, wasOn, tested;
	private static int slot;
	private static long frame;
	private static final int[] testBuffers = new int[RING], boxBuffers = new int[RING];
	private static final long[] testCapacity = new long[RING], boxCapacity = new long[RING];
	/** Camera position of the last frames, by frame number: answers only count while the camera stays near. */
	private static final int CAMERAS = 16;
	private static final double[] cameras = new double[CAMERAS * 3];
	/** Entity answers older than this many frames don't count any more. */
	private static final int MAX_AGE = 10;
	private static int resultBuffer;
	private static long resultCapacity;
	private static final Readback[] readbacks = new Readback[RING];
	private static ByteBuffer testData = MemoryUtil.memAlloc(64 * 1024);
	/** Read-back answers, reused between tests (a fresh direct buffer per test added up). */
	private static IntBuffer resultData = MemoryUtil.memAllocInt(16 * 1024);
	private static SectionRenderDispatcher.RenderSection[] testedSectionList = new SectionRenderDispatcher.RenderSection[256];

	/** Benchmark numbers: frames tested, sections tested and found hidden (from the read-back results), same for entities and block entities. */
	public static long testedFrames, testedSections, hiddenSections, leftOutSections, testedObjects, hiddenObjects;
	public static boolean measuring;

	private OcclusionCulling() {
	}

	private static final class Readback {
		int buffer;
		long capacity;
		long fence;
		long frame;
		SectionRenderDispatcher.RenderSection[] sections = new SectionRenderDispatcher.RenderSection[0];
		int count;
		/** The entities and block entities tested, and where each was then. */
		Object[] owners = new Object[0];
		double[] positions = new double[0];
		int boxCount;
	}

	/**
	 * Start of a frame's chunk draws (the solid layer): whether hidden sections are left out and tested this frame. Also
	 * takes in the test results that have arrived since.
	 */
	public static boolean beginFrame(Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ) {
		// The occlusion test reads the game's depth, which a shader pack draws elsewhere.
		boolean want = ENABLED && Features.OCCLUSION_CULLING.active() && !Addons.shaderPackActive();
		// Switched back on in the settings: the answers from before it was off are too old to trust.
		if (want && !wasOn) frame += MAX_AGE + SECTION_MAX_AGE + 1;
		wasOn = want;
		on = want && begin(modelView, projection, camX, camY, camZ);
		// The last frame's test didn't run (its chunk draws stopped short): what was gathered for it is let go.
		if (!tested) EntityCulling.clearBoxes();
		tested = false;
		EntityCulling.gather(on);
		return on;
	}

	private static boolean begin(Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ) {
		if (state == 0) init();
		if (state < 0) return false;
		collect();
		viewProjection.set(projection).mul(modelView);
		slot = (int) (++frame % RING);
		int c = (int) (frame % CAMERAS) * 3;
		cameras[c] = camX;
		cameras[c + 1] = camY;
		cameras[c + 2] = camZ;
		return true;
	}

	/** Whether this frame's chunk draws leave out hidden sections. */
	public static boolean on() {
		return on;
	}

	/** Whether to leave out this section this frame: hidden at a recent test, and not around the camera. */
	public static boolean leftOut(SectionRenderDispatcher.RenderSection section, double camX, double camY, double camZ) {
		BlockPos origin = section.getOrigin();
		double dx = Math.max(Math.max(origin.getX() - camX, camX - origin.getX() - 16), 0);
		double dy = Math.max(Math.max(origin.getY() - camY, camY - origin.getY() - 16), 0);
		double dz = Math.max(Math.max(origin.getZ() - camZ, camZ - origin.getZ() - 16), 0);
		if (dx < 16 && dy < 16 && dz < 16) return false;
		return recent(((OccludedSection) section).afterburner$hiddenAt(), SECTION_MAX_AGE, camX, camY, camZ);
	}

	/** Whether an answer from the test of that frame still holds for a camera here: it's recent and the camera hasn't moved far since. */
	static boolean recent(long testFrame, double x, double y, double z) {
		return recent(testFrame, MAX_AGE, x, y, z);
	}

	private static boolean recent(long testFrame, int maxAge, double x, double y, double z) {
		if (testFrame < 0 || frame - testFrame > maxAge) return false;
		int c = (int) (testFrame % CAMERAS) * 3;
		double dx = x - cameras[c], dy = y - cameras[c + 1], dz = z - cameras[c + 2];
		return dx * dx + dy * dy + dz * dz < 1.0;
	}

	public static void resetStats() {
		testedFrames = testedSections = hiddenSections = leftOutSections = testedObjects = hiddenObjects = 0;
	}

	private static void init() {
		state = -1;
		GLCapabilities caps = GL.getCapabilities();
		// The game asks for OpenGL 3.2, and some drivers (Intel's) then report just that, with the newer parts as extensions.
		boolean compute = caps.OpenGL43 || caps.GL_ARB_compute_shader && caps.GL_ARB_shader_storage_buffer_object
				&& caps.GL_ARB_shader_image_load_store && caps.GL_ARB_shading_language_420pack;
		boolean directAccess = (caps.OpenGL45 || caps.GL_ARB_direct_state_access) && (caps.OpenGL44 || caps.GL_ARB_buffer_storage)
				&& (caps.OpenGL42 || caps.GL_ARB_texture_storage);
		if (!compute || !directAccess) {
			Afterburner.LOGGER.info("Occlusion culling is off: the graphics driver lacks compute shaders or direct state access");
			return;
		}
		String header = caps.OpenGL43 ? "#version 430\n" : """
				#version 330
				#extension GL_ARB_compute_shader : require
				#extension GL_ARB_shader_storage_buffer_object : require
				#extension GL_ARB_shader_image_load_store : require
				#extension GL_ARB_shading_language_420pack : require
				""";
		try {
			firstProgram = program(header + "#define FIRST\n", PYRAMID_SOURCE);
			downProgram = program(header, PYRAMID_SOURCE);
			testProgram = program(header, TEST_SOURCE);
			boxProgram = program(header + "#define BOXES\n", TEST_SOURCE);
		} catch (IllegalStateException e) {
			Afterburner.LOGGER.warn("Occlusion culling is off: compute shaders failed, {}", e.getMessage());
			return;
		}
		depthSampler = sampler(GL_NEAREST);
		pyramidSampler = sampler(GL_NEAREST_MIPMAP_NEAREST);
		firstSourceLoc = glGetUniformLocation(firstProgram, "SourceSize");
		firstTargetLoc = glGetUniformLocation(firstProgram, "TargetSize");
		downSourceLoc = glGetUniformLocation(downProgram, "SourceSize");
		downTargetLoc = glGetUniformLocation(downProgram, "TargetSize");
		testViewProjectionLoc = glGetUniformLocation(testProgram, "ViewProjection");
		testScreenSizeLoc = glGetUniformLocation(testProgram, "ScreenSize");
		testLevelsLoc = glGetUniformLocation(testProgram, "Levels");
		testCountLoc = glGetUniformLocation(testProgram, "Count");
		testMarginLoc = glGetUniformLocation(testProgram, "Margin");
		boxViewProjectionLoc = glGetUniformLocation(boxProgram, "ViewProjection");
		boxScreenSizeLoc = glGetUniformLocation(boxProgram, "ScreenSize");
		boxLevelsLoc = glGetUniformLocation(boxProgram, "Levels");
		boxCountLoc = glGetUniformLocation(boxProgram, "Count");
		boxMarginLoc = glGetUniformLocation(boxProgram, "Margin");
		boxBaseLoc = glGetUniformLocation(boxProgram, "Base");
		for (int i = 0; i < RING; i++) readbacks[i] = new Readback();
		resultBuffer = glCreateBuffers();
		state = 1;
		Afterburner.LOGGER.info("Occlusion culling is on");
	}

	private static int sampler(int minFilter) {
		int sampler = glCreateSamplers();
		glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, minFilter);
		glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glSamplerParameteri(sampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
		glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glSamplerParameteri(sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		return sampler;
	}

	private static int program(String header, String source) {
		int shader = glCreateShader(GL_COMPUTE_SHADER);
		glShaderSource(shader, header + source);
		glCompileShader(shader);
		if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
			String log = glGetShaderInfoLog(shader);
			glDeleteShader(shader);
			throw new IllegalStateException("shader didn't compile: " + log);
		}
		int program = glCreateProgram();
		glAttachShader(program, shader);
		glLinkProgram(program);
		glDeleteShader(shader);
		if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
			String log = glGetProgramInfoLog(program);
			glDeleteProgram(program);
			throw new IllegalStateException("shader didn't link: " + log);
		}
		return program;
	}

	/**
	 * Whether the depth attachment is a whole plain 2D texture: not a cube map face or layered. Asked of the framebuffer,
	 * not the texture: Intel's driver refuses GL_TEXTURE_TARGET even in a 4.6 context.
	 */
	private static boolean flat(int fbo) {
		return glGetNamedFramebufferAttachmentParameteri(fbo, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE) == 0
				&& glGetNamedFramebufferAttachmentParameteri(fbo, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_LAYERED) == GL_FALSE;
	}

	/** The test can't run this frame. */
	private static void skip(String why) {
		if (skipped.add(why)) Afterburner.LOGGER.info("Occlusion test skipped: {}", why);
	}

	/**
	 * Once a frame, after the solid and cutout terrain is drawn: tests the frame's sections (and the entities and block
	 * entities gathered since the last test) against the depth drawn so far.
	 */
	public static void test(List<SectionRenderDispatcher.RenderSection> visible, double camX, double camY, double camZ) {
		if (!on || tested) return;
		tested = true;
		// Throttle before any per-section work: when little is hidden the test costs about what it saves, and nothing
		// waits on this frame's answers, so the last ones are used a little longer (never past SECTION_MAX_AGE).
		if (!testThisFrame()) {
			EntityCulling.clearBoxes();
			return;
		}
		int count = 0, leftOut = 0;
		testData = room(testData, (long) visible.size() * TEST_WORDS * 4);
		if (testedSectionList.length < visible.size()) testedSectionList = new SectionRenderDispatcher.RenderSection[Math.max(visible.size(), testedSectionList.length * 2)];
		for (int i = 0, n = visible.size(); i < n; i++) {
			SectionRenderDispatcher.RenderSection section = visible.get(i);
			if (section.getCompiled().hasNoRenderableLayers()) continue;
			if (measuring && leftOut(section, camX, camY, camZ)) leftOut++;
			BlockPos origin = section.getOrigin();
			// The sections around the camera are always drawn, whatever the test would say ({@link #leftOut}): testing them
			// only spends graphics work to learn nothing.
			if (nearCamera(origin, camX, camY, camZ)) continue;
			int b = count * TEST_WORDS * 4;
			testData.putFloat(b, (float) (origin.getX() - camX));
			testData.putFloat(b + 4, (float) (origin.getY() - camY));
			testData.putFloat(b + 8, (float) (origin.getZ() - camZ));
			testData.putFloat(b + 12, 0);
			testedSectionList[count++] = section;
		}
		if (measuring) leftOutSections += leftOut;
		int boxes = EntityCulling.takeBoxes(camX, camY, camZ);
		try {
			if (count == 0) return;
			run(count, boxes);
		} finally {
			Arrays.fill(testedSectionList, 0, count, null);
			EntityCulling.clearBoxes();
		}
	}

	/** Whether to test this frame: every frame, or every {@link #MAX_TEST_EVERY} while little is hidden. */
	private static boolean testThisFrame() {
		int every = hiddenShare < VERY_SPARSE_BELOW ? MAX_TEST_EVERY : hiddenShare < SPARSE_BELOW ? 2 : 1;
		return every == 1 || frame % every == 0;
	}

	/** Whether the camera is within the 16 blocks of this section's box that {@link #leftOut} keeps always drawn. */
	private static boolean nearCamera(BlockPos origin, double camX, double camY, double camZ) {
		return camX > origin.getX() - 16 && camX < origin.getX() + 32
				&& camY > origin.getY() - 16 && camY < origin.getY() + 32
				&& camZ > origin.getZ() - 16 && camZ < origin.getZ() + 32;
	}

	private static void run(int count, int boxes) {
		int fbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		if (fbo == 0 || glGetNamedFramebufferAttachmentParameteri(fbo, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_TEXTURE) {
			skip("the terrain isn't drawn into a depth texture");
			return;
		}
		int depth = glGetNamedFramebufferAttachmentParameteri(fbo, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
		if (glGetNamedFramebufferAttachmentParameteri(fbo, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL) != 0 || !flat(fbo)) {
			skip("the depth attachment isn't a plain 2D texture");
			return;
		}
		int width = glGetTextureLevelParameteri(depth, 0, GL_TEXTURE_WIDTH), height = glGetTextureLevelParameteri(depth, 0, GL_TEXTURE_HEIGHT);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer viewport = stack.mallocInt(4);
			glGetIntegerv(GL_VIEWPORT, viewport);
			if (viewport.get(0) != 0 || viewport.get(1) != 0 || viewport.get(2) != width || viewport.get(3) != height) {
				skip("the viewport doesn't cover the depth texture");
				return;
			}
		}
		Probe.get().mark("terrain occlusion test");
		int program = glGetInteger(GL_CURRENT_PROGRAM);
		upload(testBuffers, testCapacity, testData, (long) count * TEST_WORDS * 4);
		buildPyramid(depth, width, height);

		glUseProgram(testProgram);
		glBindTextureUnit(PYRAMID_UNIT, pyramid);
		glBindSampler(PYRAMID_UNIT, pyramidSampler);
		long resultBytes = (long) (count + boxes) * 4;
		if (resultCapacity < resultBytes) {
			glDeleteBuffers(resultBuffer);
			resultCapacity = Math.max(resultBytes * 3 / 2, 16 * 1024);
			resultBuffer = glCreateBuffers();
			glNamedBufferStorage(resultBuffer, resultCapacity, 0);
		}
		glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, testBuffers[slot], 0, (long) count * TEST_WORDS * 4);
		glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 2, resultBuffer, 0, resultBytes);
		uniforms(testViewProjectionLoc, testScreenSizeLoc, testLevelsLoc, testCountLoc, testMarginLoc, width, height, count);
		glDispatchCompute((count + 63) / 64, 1, 1);
		if (boxes > 0) {
			upload(boxBuffers, boxCapacity, EntityCulling.boxData(), (long) boxes * EntityCulling.BOX_BYTES);
			glUseProgram(boxProgram);
			glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, boxBuffers[slot], 0, (long) boxes * EntityCulling.BOX_BYTES);
			uniforms(boxViewProjectionLoc, boxScreenSizeLoc, boxLevelsLoc, boxCountLoc, boxMarginLoc, width, height, boxes);
			glUniform1ui(boxBaseLoc, count);
			glDispatchCompute((boxes + 63) / 64, 1, 1);
		}
		glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
		readBack(count, boxes, resultBytes);

		glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, 0);
		glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, 0);
		glBindTextureUnit(PYRAMID_UNIT, 0);
		glBindSampler(PYRAMID_UNIT, 0);
		glUseProgram(program);
		if (measuring) testedFrames++;
	}

	private static ByteBuffer room(ByteBuffer buffer, long bytes) {
		if (buffer.capacity() >= bytes) return buffer;
		MemoryUtil.memFree(buffer);
		return MemoryUtil.memAlloc((int) Math.max(bytes, buffer.capacity() * 2L));
	}

	private static IntBuffer roomInt(IntBuffer buffer, int ints) {
		if (buffer.capacity() >= ints) return buffer;
		MemoryUtil.memFree(buffer);
		return MemoryUtil.memAllocInt(Math.max(ints, buffer.capacity() * 2));
	}

	/** Sends {@code bytes} of data to this frame's buffer of the ring, making it bigger if needed. */
	private static void upload(int[] buffers, long[] capacity, ByteBuffer data, long bytes) {
		if (capacity[slot] < bytes) {
			if (buffers[slot] != 0) glDeleteBuffers(buffers[slot]);
			capacity[slot] = Math.max(bytes * 3 / 2, 64 * 1024);
			buffers[slot] = glCreateBuffers();
			glNamedBufferStorage(buffers[slot], capacity[slot], GL_DYNAMIC_STORAGE_BIT);
		}
		glNamedBufferSubData(buffers[slot], 0, data.limit((int) bytes).position(0));
		data.clear();
	}

	private static void uniforms(int viewProjectionLoc, int screenSizeLoc, int levelsLoc, int countLoc, int marginLoc, int width, int height, int count) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			glUniformMatrix4fv(viewProjectionLoc, false, viewProjection.get(stack.mallocFloat(16)));
		}
		glUniform2i(screenSizeLoc, width, height);
		glUniform1i(levelsLoc, pyramidLevels);
		glUniform1ui(countLoc, count);
		// Answers are used a frame or two later: room for the camera moving meanwhile, about 4 pixels at 720p.
		glUniform1i(marginLoc, Math.max(2, (height + 179) / 180));
	}

	private static void buildPyramid(int depth, int width, int height) {
		if (width != sourceWidth || height != sourceHeight) {
			if (pyramid != 0) glDeleteTextures(pyramid);
			sourceWidth = width;
			sourceHeight = height;
			pyramidWidth = (width + 1) / 2;
			pyramidHeight = (height + 1) / 2;
			pyramidLevels = 32 - Integer.numberOfLeadingZeros(Math.max(pyramidWidth, pyramidHeight));
			pyramid = glCreateTextures(GL_TEXTURE_2D);
			glTextureStorage2D(pyramid, pyramidLevels, GL_R32F, pyramidWidth, pyramidHeight);
		}
		// What was drawn must be in the depth texture before the compute shader reads it.
		glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
		glUseProgram(firstProgram);
		glBindTextureUnit(DEPTH_UNIT, depth);
		glBindSampler(DEPTH_UNIT, depthSampler);
		glBindImageTexture(0, pyramid, 0, false, 0, GL_WRITE_ONLY, GL_R32F);
		glUniform2i(firstSourceLoc, width, height);
		glUniform2i(firstTargetLoc, pyramidWidth, pyramidHeight);
		glDispatchCompute((pyramidWidth + 7) / 8, (pyramidHeight + 7) / 8, 1);
		glBindTextureUnit(DEPTH_UNIT, 0);
		glBindSampler(DEPTH_UNIT, 0);

		glUseProgram(downProgram);
		int w = pyramidWidth, h = pyramidHeight;
		for (int level = 1; level < pyramidLevels; level++) {
			int sw = w, sh = h;
			w = (w + 1) / 2;
			h = (h + 1) / 2;
			glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
			glBindImageTexture(1, pyramid, level - 1, false, 0, GL_READ_ONLY, GL_R32F);
			glBindImageTexture(0, pyramid, level, false, 0, GL_WRITE_ONLY, GL_R32F);
			glUniform2i(downSourceLoc, sw, sh);
			glUniform2i(downTargetLoc, w, h);
			glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
		}
		glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);
		glBindImageTexture(0, 0, 0, false, 0, GL_READ_ONLY, GL_R32F);
		glBindImageTexture(1, 0, 0, false, 0, GL_READ_ONLY, GL_R32F);
	}

	/** Copies the results for reading a few frames later, unless every read-back buffer is still waiting. */
	private static void readBack(int count, int boxes, long bytes) {
		Readback free = null;
		for (Readback r : readbacks) {
			if (r.fence == 0) {
				free = r;
				break;
			}
		}
		if (free == null) return;
		if (free.capacity < bytes) {
			if (free.buffer != 0) glDeleteBuffers(free.buffer);
			free.capacity = Math.max(bytes * 3 / 2, 16 * 1024);
			free.buffer = glCreateBuffers();
			glNamedBufferStorage(free.buffer, free.capacity, 0);
		}
		glCopyNamedBufferSubData(resultBuffer, free.buffer, 0, 0, bytes);
		free.fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
		free.frame = frame;
		if (free.sections.length < count) free.sections = new SectionRenderDispatcher.RenderSection[Math.max(count, free.sections.length * 2)];
		System.arraycopy(testedSectionList, 0, free.sections, 0, count);
		free.count = count;
		if (free.owners.length < boxes) {
			free.owners = new Object[Math.max(boxes, free.owners.length * 2)];
			free.positions = new double[free.owners.length * 3];
		}
		System.arraycopy(EntityCulling.owners(), 0, free.owners, 0, boxes);
		System.arraycopy(EntityCulling.positions(), 0, free.positions, 0, boxes * 3);
		free.boxCount = boxes;
	}

	/** Takes in the results the graphics card has finished, oldest first, without waiting for any. */
	private static void collect() {
		while (true) {
			Readback oldest = null;
			for (Readback r : readbacks) {
				if (r.fence != 0 && (oldest == null || r.frame < oldest.frame)) oldest = r;
			}
			if (oldest == null) return;
			int status = glClientWaitSync(oldest.fence, 0, 0);
			if (status != GL_ALREADY_SIGNALED && status != GL_CONDITION_SATISFIED) return;
			glDeleteSync(oldest.fence);
			oldest.fence = 0;
			int answers = oldest.count + oldest.boxCount;
			resultData = roomInt(resultData, answers);
			resultData.clear();
			resultData.limit(answers);
			glGetNamedBufferSubData(oldest.buffer, 0, resultData);
			apply(oldest, resultData);
			Arrays.fill(oldest.sections, 0, oldest.count, null);
			Arrays.fill(oldest.owners, 0, oldest.boxCount, null);
			oldest.boxCount = 0;
		}
	}

	/** Takes in the answers of one test: per section, then per entity or block entity box, whether it was seen. */
	private static void apply(Readback done, IntBuffer seen) {
		int count = done.count, hidden = 0;
		for (int i = 0; i < count; i++) {
			boolean h = seen.get(i) == 0;
			((OccludedSection) done.sections[i]).afterburner$setHiddenAt(h ? done.frame : -1);
			if (h) hidden++;
		}
		if (count > 0) hiddenShare = hiddenShare * 0.9F + 0.1F * hidden / count;
		if (measuring) {
			testedSections += count;
			hiddenSections += hidden;
		}
		for (int i = 0; i < done.boxCount; i++) {
			OccludedObject o = (OccludedObject) done.owners[i];
			if (seen.get(count + i) == 0) {
				o.afterburner$setHiddenAt(done.frame, done.positions[i * 3], done.positions[i * 3 + 1], done.positions[i * 3 + 2]);
				if (measuring) hiddenObjects++;
			} else {
				o.afterburner$setHiddenAt(-1, 0, 0, 0);
			}
		}
		if (measuring) testedObjects += done.boxCount;
	}
}
