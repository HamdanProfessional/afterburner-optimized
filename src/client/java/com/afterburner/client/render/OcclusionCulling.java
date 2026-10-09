package com.afterburner.client.render;

import com.afterburner.Afterburner;
import com.afterburner.Features;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL45C.*;

/**
 * Leaves out the chunk sections hidden behind nearer terrain.
 * <p>
 * Every frame, after the solid terrain is drawn, the graphics card tests every section's box against the depth drawn
 * so far (through a small depth pyramid), and what it finds is read back a frame or two later. By default a section
 * is then left out while its latest answer is "hidden", that answer is at most {@link #SECTION_MAX_AGE} frames old,
 * and the camera hasn't moved a block since. So a section coming into view (over a hill top, through a freshly dug
 * hole) can show up a frame or two late. To keep that rare, the test is done with a few pixels to spare, sections not
 * entirely on the screen count as seen (turning around never reveals a hole), and the sections right around the
 * camera are always drawn.
 * <p>
 * With {@code -Dafterburner.occlusionSameFrame=true} it never leaves a hole instead: the sections hidden at the last
 * test are held back, and those the test sees are drawn in the same frame from draw commands it switches on. That
 * needs no waiting on the graphics card's side, but Intel's driver turns those commands back into CPU work, and on
 * the laptop it was tested on it cost as much as it saved.
 * <p>
 * Needs OpenGL 4.5, or an older context with the extensions that bring the same (compute shaders, storage buffers,
 * indirect multi-draw, direct state access). Everything here runs
 * on the render thread, in the middle of the frame's main render pass, and puts back the OpenGL state it touches.
 */
public final class OcclusionCulling {
	public static final boolean ENABLED = Features.OCCLUSION_CULLING.enabled() && Features.CHUNK_BATCHING.enabled();
	/** The projection the world is drawn with this frame, view bobbing included (render thread). */
	public static final Matrix4f projection = new Matrix4f();
	/** Words per section test: box corner relative to the camera (3 floats, 1 unused), then solid and cutout command ranges. */
	static final int TEST_WORDS = 8;
	/** Words per indirect draw command: index count, instance count, first index, base vertex, base instance. */
	static final int COMMAND_WORDS = 5;
	private static final int RING = 3;
	/** Hold back the hidden sections and draw those the test sees in the same frame (see the class comment). */
	static boolean SAME_FRAME = Boolean.getBoolean("afterburner.occlusionSameFrame");
	/** Test with drawn passes ({@link OcclusionPortable}) instead of compute shaders: on Vulkan, or with this switch. */
	private static final boolean FORCE_PORTABLE = Boolean.getBoolean("afterburner.occlusionPortable");
	private static boolean portable;
	/** Section answers older than this many frames don't count any more: the section is drawn. */
	private static final int SECTION_MAX_AGE = 5;
	/**
	 * Share of the tested sections the last tests found hidden. When it's small (flying high, open land) the drawn test
	 * costs the graphics chip about what it saves, so it runs every other frame only; answers stay young enough to be used.
	 * Measured flying at render distance 32 (5% hidden): 1% lows 42-43 FPS testing every frame, 47-48 every other one.
	 */
	private static float hiddenShare = 1;
	private static final float SPARSE_BELOW = 0.1F;
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

			// Depth is reversed (1 is near), so the smallest value is the farthest thing in the 2x2 block.
			void main() {
			    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
			    if (p.x >= TargetSize.x || p.y >= TargetSize.y) return;
			    ivec2 s = p * 2;
			    float d = min(min(read(s), read(s + ivec2(1, 0))), min(read(s + ivec2(0, 1)), read(s + ivec2(1, 1))));
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
			struct Test {
			    vec4 corner;
			    uvec4 commands;
			};
			layout(std430, binding = 0) readonly buffer Tests { Test tests[]; };
			layout(std430, binding = 1) buffer Commands { uint commands[]; };
			#endif
			layout(std430, binding = 2) writeonly buffer Results { uint results[]; };
			uniform mat4 ViewProjection;
			uniform ivec2 ScreenSize;
			uniform int Levels;
			uniform uint Count;
			uniform uint Base;
			uniform int ZeroToOne;
			// Pixels to spare around each box, and whether a box partly off the screen counts as seen.
			uniform int Margin;
			uniform int Whole;
			// Block models may reach a block past their section, and the depth buffer rounds.
			const float GROW = 1.25;

			// whole: the box only counts as hidden if all of it is on the screen. Entity answers are used a few frames
			// later, when the camera may have turned towards a part that was off the screen.
			bool visible(vec3 lo, vec3 hi, bool whole) {
			    vec2 low = vec2(1e30), high = vec2(-1e30);
			    float nearest = -1e30;
			    for (int i = 0; i < 8; i++) {
			        vec4 clip = ViewProjection * vec4((i & 1) != 0 ? hi.x : lo.x, (i & 2) != 0 ? hi.y : lo.y, (i & 4) != 0 ? hi.z : lo.z, 1.0);
			        // A corner (nearly) behind the camera: the box can't be put on the screen, so it counts as seen.
			        if (clip.w < 1.0) return true;
			        vec3 ndc = clip.xyz / clip.w;
			        low = min(low, ndc.xy);
			        high = max(high, ndc.xy);
			        nearest = max(nearest, ZeroToOne != 0 ? ndc.z : ndc.z * 0.5 + 0.5);
			    }
			    vec2 size = vec2(ScreenSize);
			    ivec2 p0 = ivec2(floor((low * 0.5 + 0.5) * size)) - Margin;
			    ivec2 p1 = ivec2(floor((high * 0.5 + 0.5) * size)) + Margin;
			    if (whole && (any(lessThan(p0, ivec2(0))) || any(greaterThan(p1, ScreenSize - 1)))) return true;
			    p0 = max(p0, ivec2(0));
			    p1 = min(p1, ScreenSize - 1);
			    if (any(greaterThan(p0, p1))) return true;
			    // The finest pyramid level where the box covers at most 2x2 texels; level k texels cover 2^(k+1) pixels.
			    int level = 0;
			    while (level < Levels - 1 && any(greaterThan((p1 >> (level + 1)) - (p0 >> (level + 1)), ivec2(1)))) level++;
			    ivec2 t0 = p0 >> (level + 1), t1 = p1 >> (level + 1);
			    float farthest = min(min(texelFetch(Pyramid, t0, level).r, texelFetch(Pyramid, ivec2(t1.x, t0.y), level).r),
			            min(texelFetch(Pyramid, ivec2(t0.x, t1.y), level).r, texelFetch(Pyramid, t1, level).r));
			    // Hidden only if everything already drawn there is nearer than the nearest corner of the box.
			    return farthest <= nearest;
			}

			#ifdef BOXES
			void main() {
			    uint id = gl_GlobalInvocationID.x;
			    if (id >= Count) return;
			    results[Base + id] = visible(boxes[id].lo.xyz - 0.25, boxes[id].hi.xyz + 0.25, true) ? 1u : 0u;
			}
			#else
			void main() {
			    uint id = gl_GlobalInvocationID.x;
			    if (id >= Count) return;
			    Test test = tests[id];
			    bool seen = visible(test.corner.xyz - GROW, test.corner.xyz + (16.0 + GROW), Whole != 0);
			    results[id] = seen ? 1u : 0u;
			    if (!seen) return;
			    for (uint c = test.commands.x; c < test.commands.x + test.commands.y; c++) commands[c * 5u + 1u] = 1u;
			    for (uint c = test.commands.z; c < test.commands.z + test.commands.w; c++) commands[c * 5u + 1u] = 1u;
			}
			#endif
			""";

	/** 0 not checked yet, 1 working, -1 not available here. */
	private static int state;
	private static int firstProgram, downProgram, testProgram, boxProgram, depthSampler, pyramidSampler;
	private static int pyramid, pyramidWidth, pyramidHeight, pyramidLevels, sourceWidth, sourceHeight;
	private static final Matrix4f viewProjection = new Matrix4f();
	private static boolean zeroToOne;
	/** Why the last test couldn't run, logged once per reason. */
	private static final java.util.Set<String> skipped = new java.util.HashSet<>();

	private static int slot;
	private static long frame;
	private static final @Nullable GpuBuffer[] commandBuffers = new GpuBuffer[RING];
	private static final int[] testBuffers = new int[RING], boxBuffers = new int[RING];
	private static final long[] testCapacity = new long[RING], boxCapacity = new long[RING];
	/** Camera position of the last frames, by frame number: entity answers only count while the camera stays near. */
	private static final int CAMERAS = 16;
	private static final double[] cameras = new double[CAMERAS * 3];
	/** Entity answers older than this many frames don't count any more. */
	private static final int MAX_AGE = 10;
	private static int resultBuffer;
	private static long resultCapacity;
	private static final Readback[] readbacks = new Readback[RING];
	private static ByteBuffer commandData = MemoryUtil.memAlloc(64 * 1024), testData = MemoryUtil.memAlloc(64 * 1024);

	/** Benchmark numbers: frames tested, sections tested and found hidden (from the read-back results), same for entities and block entities. */
	public static long testedFrames, testedSections, hiddenSections, lateCommands, leftOutSections, testedObjects, hiddenObjects;
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
	 * Start of a frame's chunk draws: whether occlusion culling runs this frame. Also takes in the test results that
	 * have arrived since.
	 */
	static boolean begin(Matrix4fc modelView, Vec3 camera) {
		if (state == 0) init();
		if (state < 0) return false;
		collect();
		viewProjection.set(projection).mul(modelView);
		slot = (int) (++frame % RING);
		int c = (int) (frame % CAMERAS) * 3;
		cameras[c] = camera.x;
		cameras[c + 1] = camera.y;
		cameras[c + 2] = camera.z;
		return true;
	}

	/** Makes every answer so far too old to use, as if the frames since had passed: after being switched off for a while. */
	static void skipAhead() {
		frame += MAX_AGE + SECTION_MAX_AGE + 1;
	}

	/** Whether the tests run (they start with the first frame of chunk draws). */
	static boolean working() {
		return state > 0;
	}

	/** Takes in the test results that have arrived, without waiting. */
	static void poll() {
		if (state > 0) collect();
	}

	/** Whether an answer from the test of that frame still holds for a camera here: it's recent and the camera hasn't moved far since. */
	static boolean recent(long testFrame, double x, double y, double z) {
		return recent(testFrame, MAX_AGE, x, y, z);
	}

	/** Whether a section found hidden at the test of that frame (-1: seen there) is left out for a camera here. */
	static boolean hidden(long testFrame, double x, double y, double z) {
		return SAME_FRAME ? testFrame >= 0 : recent(testFrame, SECTION_MAX_AGE, x, y, z);
	}

	private static boolean recent(long testFrame, int maxAge, double x, double y, double z) {
		if (testFrame < 0 || frame - testFrame > maxAge) return false;
		int c = (int) (testFrame % CAMERAS) * 3;
		double dx = x - cameras[c], dy = y - cameras[c + 1], dz = z - cameras[c + 2];
		return dx * dx + dy * dy + dz * dz < 1.0;
	}

	public static void resetStats() {
		testedFrames = testedSections = hiddenSections = lateCommands = leftOutSections = testedObjects = hiddenObjects = 0;
	}

	private static void init() {
		state = -1;
		DeviceInfo device = RenderSystem.getDevice().getDeviceInfo();
		zeroToOne = device.isZZeroToOne();
		if (!FORCE_PORTABLE && device.backendName().toLowerCase().contains("gl") && initCompute(device)) return;
		// Vulkan, or OpenGL without compute shaders: the test is drawn, and its answers are always used a frame or two later.
		portable = true;
		SAME_FRAME = false;
		state = 1;
		Afterburner.LOGGER.info("Occlusion culling is on (drawn test, {})", device.backendName());
	}

	private static boolean initCompute(DeviceInfo device) {
		GLCapabilities caps = GL.getCapabilities();
		// The game asks for OpenGL 3.3, and some drivers (Intel's) then report just that, with the newer parts as extensions.
		boolean compute = caps.OpenGL43 || caps.GL_ARB_compute_shader && caps.GL_ARB_shader_storage_buffer_object
				&& caps.GL_ARB_shader_image_load_store && caps.GL_ARB_shading_language_420pack;
		boolean directAccess = (caps.OpenGL45 || caps.GL_ARB_direct_state_access) && (caps.OpenGL44 || caps.GL_ARB_buffer_storage)
				&& (caps.OpenGL42 || caps.GL_ARB_texture_storage);
		if (!compute || !directAccess || !device.features().multiDrawIndirect() || !device.features().drawIndirect()) {
			Afterburner.LOGGER.info("Occlusion culling: the graphics driver lacks compute shaders, direct state access or indirect multi-draw");
			return false;
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
			Afterburner.LOGGER.warn("Occlusion culling: compute shaders failed, {}", e.getMessage());
			return false;
		}
		depthSampler = sampler(GL_NEAREST);
		pyramidSampler = sampler(GL_NEAREST_MIPMAP_NEAREST);
		for (int i = 0; i < RING; i++) readbacks[i] = new Readback();
		resultBuffer = glCreateBuffers();
		state = 1;
		Afterburner.LOGGER.info("Occlusion culling is on");
		return true;
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

	/** Room for this frame's commands, filled by the caller from position 0; {@link #upload} sends them. */
	static ByteBuffer commandData(int commands) {
		commandData = room(commandData, (long) commands * COMMAND_WORDS * 4);
		return commandData;
	}

	static ByteBuffer testData(int tests) {
		testData = room(testData, (long) tests * TEST_WORDS * 4);
		return testData;
	}

	private static ByteBuffer room(ByteBuffer buffer, long bytes) {
		if (buffer.capacity() >= bytes) return buffer;
		MemoryUtil.memFree(buffer);
		return MemoryUtil.memAlloc((int) Math.max(bytes, buffer.capacity() * 2L));
	}

	/** Sends this frame's draw commands and section tests to the graphics card. */
	static void upload(int commands, int tests) {
		if (portable) {
			// The drawn test sends the tests itself, and has no draw commands to switch on.
			commandData.clear();
			return;
		}
		long commandBytes = Math.max((long) commands * COMMAND_WORDS * 4, 4);
		GpuBuffer buffer = commandBuffers[slot];
		if (buffer == null || buffer.size() < commandBytes) {
			if (buffer != null) buffer.close();
			long size = Math.max(commandBytes * 3 / 2, 64 * 1024);
			buffer = commandBuffers[slot] = RenderSystem.getDevice().createBuffer(() -> "Afterburner hidden chunk draws",
					GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_COPY_DST, size);
		}
		if (commands > 0) glNamedBufferSubData(handle(buffer), 0, commandData.limit((int) ((long) commands * COMMAND_WORDS * 4)).position(0));
		long testBytes = Math.max((long) tests * TEST_WORDS * 4, 4);
		if (testCapacity[slot] < testBytes) {
			if (testBuffers[slot] != 0) glDeleteBuffers(testBuffers[slot]);
			testCapacity[slot] = Math.max(testBytes * 3 / 2, 64 * 1024);
			testBuffers[slot] = glCreateBuffers();
			glNamedBufferStorage(testBuffers[slot], testCapacity[slot], GL_DYNAMIC_STORAGE_BIT);
		}
		if (tests > 0) glNamedBufferSubData(testBuffers[slot], 0, testData.limit((int) ((long) tests * TEST_WORDS * 4)).position(0));
		commandData.clear();
		testData.clear();
	}

	/** The draw commands of this frame's hidden-at-first sections, switched on by {@link #test}. */
	static GpuBuffer commands() {
		return commandBuffers[slot];
	}

	/**
	 * Whether the depth attachment is a whole plain 2D texture: not a cube map face or layered (the game only makes 2D and
	 * cube map textures). Asked of the framebuffer, not the texture: Intel's driver refuses GL_TEXTURE_TARGET even in a
	 * 4.6 context (an error every frame, and no occlusion culling).
	 */
	private static boolean flat(int fbo) {
		return glGetNamedFramebufferAttachmentParameteri(fbo, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE) == 0
				&& glGetNamedFramebufferAttachmentParameteri(fbo, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_LAYERED) == GL_FALSE;
	}

	/** The test can't run this frame: everything gets drawn. */
	static boolean skip(String why) {
		if (skipped.add(why)) Afterburner.LOGGER.info("Occlusion test skipped: {}", why);
		return false;
	}

	private static int handle(GpuBuffer buffer) {
		return ((GlBuffer) buffer).handle();
	}

	/**
	 * Tests every section of the frame against the depth drawn so far, switching on the commands of those seen. Must be
	 * called inside the render pass that draws the solid terrain, after it. Returns false if it couldn't test (then
	 * nothing was switched on).
	 */
	static boolean test(SectionRenderDispatcher.RenderSection[] sections, int count, RenderPass pass) {
		int boxes = EntityCulling.takeBoxes();
		if (portable) {
			boolean tested = true, ran = count > 0 && (hiddenShare >= SPARSE_BELOW || frame % 2 == 0);
			if (ran) {
				Probe.get().mark("terrain occlusion test");
				tested = OcclusionPortable.test(pass, sections, count, testData, boxes, EntityCulling.boxData(), viewProjection, zeroToOne);
			}
			EntityCulling.boxData().clear();
			if (tested && ran && measuring) testedFrames++;
			return tested;
		}
		if (count == 0) return true;
		if (!SAME_FRAME && hiddenShare < SPARSE_BELOW && frame % 2 != 0) {
			// Nothing waits for this frame's answers (no held-back draws): the last ones are used a frame longer.
			EntityCulling.boxData().clear();
			return true;
		}
		int fbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		if (fbo == 0 || glGetNamedFramebufferAttachmentParameteri(fbo, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_TEXTURE) {
			return skip("the terrain isn't drawn into a depth texture");
		}
		int depth = glGetNamedFramebufferAttachmentParameteri(fbo, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
		if (glGetNamedFramebufferAttachmentParameteri(fbo, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL) != 0 || !flat(fbo)) {
			return skip("the depth attachment isn't a plain 2D texture");
		}
		int width = glGetTextureLevelParameteri(depth, 0, GL_TEXTURE_WIDTH), height = glGetTextureLevelParameteri(depth, 0, GL_TEXTURE_HEIGHT);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer viewport = stack.mallocInt(4);
			glGetIntegerv(GL_VIEWPORT, viewport);
			if (viewport.get(0) != 0 || viewport.get(1) != 0 || viewport.get(2) != width || viewport.get(3) != height) {
				return skip("the viewport doesn't cover the depth texture");
			}
		}
		Probe.get().mark("terrain occlusion test");
		int program = glGetInteger(GL_CURRENT_PROGRAM);
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
		glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, handle(commandBuffers[slot]));
		glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 2, resultBuffer, 0, resultBytes);
		uniforms(testProgram, width, height, count);
		glDispatchCompute((count + 63) / 64, 1, 1);
		if (boxes > 0) {
			uploadBoxes(boxes);
			glUseProgram(boxProgram);
			glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, boxBuffers[slot], 0, (long) boxes * EntityCulling.BOX_BYTES);
			uniforms(boxProgram, width, height, boxes);
			glUniform1ui(glGetUniformLocation(boxProgram, "Base"), count);
			glDispatchCompute((boxes + 63) / 64, 1, 1);
		}
		glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
		readBack(sections, count, boxes, resultBytes);

		for (int i = 0; i < 3; i++) glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, 0);
		glBindTextureUnit(PYRAMID_UNIT, 0);
		glBindSampler(PYRAMID_UNIT, 0);
		glUseProgram(program);
		if (measuring) testedFrames++;
		return true;
	}

	private static void uniforms(int program, int width, int height, int count) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			glUniformMatrix4fv(glGetUniformLocation(program, "ViewProjection"), false, viewProjection.get(stack.mallocFloat(16)));
		}
		glUniform2i(glGetUniformLocation(program, "ScreenSize"), width, height);
		glUniform1i(glGetUniformLocation(program, "Levels"), pyramidLevels);
		glUniform1ui(glGetUniformLocation(program, "Count"), count);
		glUniform1i(glGetUniformLocation(program, "ZeroToOne"), zeroToOne ? 1 : 0);
		// Answers used a frame or two later get room for the camera moving meanwhile: about 4 pixels at 720p.
		glUniform1i(glGetUniformLocation(program, "Margin"), SAME_FRAME ? 1 : Math.max(2, (height + 179) / 180));
		glUniform1i(glGetUniformLocation(program, "Whole"), SAME_FRAME ? 0 : 1);
	}

	private static void uploadBoxes(int boxes) {
		long bytes = (long) boxes * EntityCulling.BOX_BYTES;
		if (boxCapacity[slot] < bytes) {
			if (boxBuffers[slot] != 0) glDeleteBuffers(boxBuffers[slot]);
			boxCapacity[slot] = Math.max(bytes * 3 / 2, 16 * 1024);
			boxBuffers[slot] = glCreateBuffers();
			glNamedBufferStorage(boxBuffers[slot], boxCapacity[slot], GL_DYNAMIC_STORAGE_BIT);
		}
		glNamedBufferSubData(boxBuffers[slot], 0, EntityCulling.boxData().limit((int) bytes).position(0));
		EntityCulling.boxData().clear();
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
		// What the render pass drew must be in the depth texture before the compute shader reads it.
		glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
		glUseProgram(firstProgram);
		glBindTextureUnit(DEPTH_UNIT, depth);
		glBindSampler(DEPTH_UNIT, depthSampler);
		glBindImageTexture(0, pyramid, 0, false, 0, GL_WRITE_ONLY, GL_R32F);
		glUniform2i(glGetUniformLocation(firstProgram, "SourceSize"), width, height);
		glUniform2i(glGetUniformLocation(firstProgram, "TargetSize"), pyramidWidth, pyramidHeight);
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
			glUniform2i(glGetUniformLocation(downProgram, "SourceSize"), sw, sh);
			glUniform2i(glGetUniformLocation(downProgram, "TargetSize"), w, h);
			glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
		}
		glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);
		glBindImageTexture(0, 0, 0, false, 0, GL_READ_ONLY, GL_R32F);
		glBindImageTexture(1, 0, 0, false, 0, GL_READ_ONLY, GL_R32F);
	}

	/** Copies the results for reading a few frames later, unless every read-back buffer is still waiting. */
	private static void readBack(SectionRenderDispatcher.RenderSection[] sections, int count, int boxes, long bytes) {
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
		System.arraycopy(sections, 0, free.sections, 0, count);
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
	static long frame() {
		return frame;
	}

	private static void collect() {
		if (portable) {
			OcclusionPortable.Readback done;
			while ((done = OcclusionPortable.oldestDone()) != null) {
				try (GpuBufferSlice.MappedView view = OcclusionPortable.map(done)) {
					ByteBuffer answers = view.data();
					apply(done.frame, done.sections, done.count, done.owners, done.positions, done.boxCount, i -> OcclusionPortable.seen(answers, i));
				}
				OcclusionPortable.release(done);
			}
			return;
		}
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
			IntBuffer results = MemoryUtil.memAllocInt(oldest.count + oldest.boxCount);
			try {
				glGetNamedBufferSubData(oldest.buffer, 0, results);
				apply(oldest.frame, oldest.sections, oldest.count, oldest.owners, oldest.positions, oldest.boxCount, i -> results.get(i) != 0);
			} finally {
				MemoryUtil.memFree(results);
			}
			java.util.Arrays.fill(oldest.sections, 0, oldest.count, null);
			java.util.Arrays.fill(oldest.owners, 0, oldest.boxCount, null);
			oldest.boxCount = 0;
		}
	}

	/** Takes in the answers of one test: per section, then per entity or block entity box, whether it was seen. */
	private static void apply(long testFrame, SectionRenderDispatcher.RenderSection[] sections, int count, Object[] owners, double[] positions, int boxCount,
			java.util.function.IntPredicate seen) {
		int hidden = 0;
		for (int i = 0; i < count; i++) {
			boolean h = !seen.test(i);
			((OccludedSection) sections[i]).afterburner$setHiddenAt(h ? testFrame : -1);
			if (h) hidden++;
		}
		if (count > 0) hiddenShare = hiddenShare * 0.9F + 0.1F * hidden / count;
		if (measuring) {
			testedSections += count;
			hiddenSections += hidden;
		}
		for (int i = 0; i < boxCount; i++) {
			OccludedObject o = (OccludedObject) owners[i];
			if (!seen.test(count + i)) {
				o.afterburner$setHiddenAt(testFrame, positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
				if (measuring) hiddenObjects++;
			} else {
				o.afterburner$setHiddenAt(-1, 0, 0, 0);
			}
		}
		if (measuring) testedObjects += boxCount;
	}
}
