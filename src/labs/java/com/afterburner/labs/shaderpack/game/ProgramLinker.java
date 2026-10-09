package com.afterburner.labs.shaderpack.game;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Links the shader pack's programs on a thread of its own, so the game doesn't stop while the driver works.
 * <p>
 * Each of the pack's pipelines is a big program, and the driver links it all at once: on Intel's, 50 to 150 ms each, and
 * a pack has a hundred or more (one per kind of thing drawn, per pass). Linked on the render thread, as the game links its
 * own, that froze the game for seconds after a pack loaded and for a moment each time something new came on screen. Here a
 * second OpenGL context, sharing the game's programs, links them on its own thread; the render thread only picks up the
 * finished program (see the program link mixin), and draws what it's for once it's there.
 * <p>
 * Linked programs are also kept on disk, as the driver gives them (glGetProgramBinary), under the exact sources, the GPU and
 * the driver: the same pack and options then load at once, the next time and after the game restarts. A driver update just
 * makes them miss.
 * <p>
 * If the second context can't be made, programs are linked on the render thread as before.
 */
public final class ProgramLinker {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	/** Our pipelines' names (see {@link PackPipelines}): only those are linked here. */
	private static final String OURS = "afterburner:shaderpack/";
	private static final Path CACHE = FabricLoader.getInstance().getGameDir().resolve("afterburner").resolve("shader-cache");
	/** The cache is cut back to this when bigger, the files used longest ago first. */
	private static final long CACHE_BYTES = 512L << 20;
	/** The game's hidden utility windows (hidden, utility, not focusable); OpenGL is added. */
	private static final long WINDOW_FLAGS = 2147614728L;
	private static final int GL_TRUE = 1;

	private record Job(String name, ShaderType[] types, String[] sources, CompletableFuture<Integer> result) {}

	private static final Map<String, CompletableFuture<Integer>> LINKS = new ConcurrentHashMap<>();
	private static final Set<String> DISCARDED = ConcurrentHashMap.newKeySet();
	private static final BlockingQueue<Runnable> QUEUE = new LinkedBlockingQueue<>();
	private static volatile boolean running;
	/** The ID of the hidden window the linker's context draws to (never shown): the game ignores its events. */
	private static volatile int linkerWindow;
	/** The linker thread's. */
	private static @Nullable Cache cache;
	private static boolean tried;

	private ProgramLinker() {
	}

	/** On the render thread, with the game's context current: makes the second context and starts its thread, once. */
	public static void start() {
		if (tried) return;
		tried = true;
		if (Boolean.getBoolean("afterburner.renderThreadLink")) {
			LOGGER.info("[Afterburner] Shader programs are linked on the render thread (-Dafterburner.renderThreadLink)");
			return;
		}
		long window = SDLVideo.SDL_GL_GetCurrentWindow();
		long context = SDLVideo.SDL_GL_GetCurrentContext();
		if (window == 0L || context == 0L) return;
		int major = GL11C.glGetInteger(GL30C.GL_MAJOR_VERSION);
		int minor = GL11C.glGetInteger(GL30C.GL_MINOR_VERSION);
		long hidden = 0L;
		long shared = 0L;
		try {
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, major);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, minor);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 1);
			hidden = SDLVideo.SDL_CreateWindow("Afterburner shader linker", 16, 16, SDLVideo.SDL_WINDOW_OPENGL | WINDOW_FLAGS);
			if (hidden != 0L) linkerWindow = SDLVideo.SDL_GetWindowID(hidden);
			if (hidden != 0L) shared = SDLVideo.SDL_GL_CreateContext(hidden);
		} finally {
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 0);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 3);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 3);
			// Making a context makes it current: the game's back, on the window it had.
			SDLVideo.SDL_GL_MakeCurrent(window, context);
		}
		if (shared == 0L) {
			if (hidden != 0L) SDLVideo.SDL_DestroyWindow(hidden);
			LOGGER.warn("[Afterburner] Couldn't make a second OpenGL context; shader programs are linked on the render thread");
			return;
		}
		long linkWindow = hidden;
		long linkContext = shared;
		CompletableFuture<Boolean> started = new CompletableFuture<>();
		Thread thread = new Thread(() -> run(linkWindow, linkContext, started), "Afterburner shader linker");
		thread.setDaemon(true);
		thread.start();
		running = started.join();
		if (running) LOGGER.info("[Afterburner] Shader programs are linked off the render thread (OpenGL {}.{}), kept in {}", major, minor, CACHE);
	}

	private static void run(long window, long context, CompletableFuture<Boolean> started) {
		try {
			if (!SDLVideo.SDL_GL_MakeCurrent(window, context)) throw new IllegalStateException("couldn't make the context current");
			GLCapabilities caps = GL.createCapabilities();
			cache = Cache.open(caps);
		} catch (RuntimeException | LinkageError e) {
			LOGGER.warn("[Afterburner] The shader linker didn't start; programs are linked on the render thread", e);
			started.complete(false);
			return;
		}
		started.complete(true);
		while (true) {
			Runnable job;
			try {
				job = QUEUE.take();
			} catch (InterruptedException e) {
				return;
			}
			try {
				job.run();
			} catch (RuntimeException e) {
				LOGGER.warn("[Afterburner] The shader linker failed on a job", e);
			}
		}
	}

	/** On the linker's thread. */
	private static void run(Job job) {
		int program = 0;
		try {
			if (!DISCARDED.remove(job.name)) program = link(job, cache);
		} finally {
			job.result.complete(program);
		}
	}

	/**
	 * From the game's pipeline compile, on a background thread, once the shaders are turned into the GLSL the driver gets: a
	 * pipeline of ours has it linked by the linker's thread.
	 */
	public static void submit(BackendRenderPipeline.CreateInfo info, @Nullable Map<BackendRenderPipeline.CreateInfo.Shader, String> decompiled) {
		if (!running || decompiled == null || !info.name().startsWith(OURS)) return;
		if (DISCARDED.remove(info.name())) return;
		List<BackendRenderPipeline.CreateInfo.Shader> list = info.shaders();
		ShaderType[] types = new ShaderType[list.size()];
		String[] sources = new String[list.size()];
		for (int i = 0; i < list.size(); i++) {
			BackendRenderPipeline.CreateInfo.Shader shader = list.get(i);
			String source = decompiled.get(shader);
			if (source == null) return;
			types[i] = shader.module().type();
			// What the game's compile does to the source too (see the source mixin).
			sources[i] = ExternalBindings.source(source);
		}
		CompletableFuture<Integer> result = new CompletableFuture<>();
		LINKS.put(info.name(), result);
		Job job = new Job(info.name(), types, sources, result);
		QUEUE.add(() -> run(job));
	}

	/** Whether an SDL window ID is the linker's hidden window's. */
	public static boolean isLinkerWindow(int windowId) {
		return windowId != 0 && windowId == linkerWindow;
	}

	/** Whether the pipeline named so can be finished without waiting: its program is linked, or it isn't linked here. */
	public static boolean ready(String name) {
		CompletableFuture<Integer> link = LINKS.get(name);
		return link == null || link.isDone();
	}

	/**
	 * On the render thread, as the game would link the pipeline's program: the program linked here (waited for if needed),
	 * 0 if it didn't compile (logged), or null if it isn't linked here.
	 */
	public static @Nullable Integer take(String name) {
		CompletableFuture<Integer> link = LINKS.remove(name);
		return link == null ? null : link.join();
	}

	/** A pipeline of ours that won't be finished (its pack closed first): its program, now or once linked, is deleted. */
	public static void discard(String name) {
		CompletableFuture<Integer> link = LINKS.remove(name);
		if (link == null) {
			// Not submitted yet (still being turned into GLSL), or never will be.
			if (running) DISCARDED.add(name);
			return;
		}
		link.thenAccept(program -> {
			if (program > 0) QUEUE.add(() -> GL20C.glDeleteProgram(program));
		});
	}

	private static int link(Job job, Cache cache) {
		String key = cache.key(job.types, job.sources);
		int program = GL20C.glCreateProgram();
		if (program <= 0) return 0;
		if (cache.load(program, key)) return program;

		int[] shaders = new int[job.sources.length];
		boolean ok = true;
		for (int i = 0; i < shaders.length && ok; i++) {
			int shader = GL20C.glCreateShader(job.types[i] == ShaderType.VERTEX ? GL20C.GL_VERTEX_SHADER : GL20C.GL_FRAGMENT_SHADER);
			shaders[i] = shader;
			GL20C.glShaderSource(shader, job.sources[i]);
			GL20C.glCompileShader(shader);
			if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
				// As the game words it, so the pack's "see the log above" points here.
				LOGGER.error("Couldn't compile {} shader for pipeline ({}): {}", job.types[i].getName(), job.name,
					GL20C.glGetShaderInfoLog(shader, 32768).strip());
				ok = false;
			} else {
				GL20C.glAttachShader(program, shader);
			}
		}
		if (ok) {
			if (cache.on()) GL41C.glProgramParameteri(program, GL41C.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);
			GL20C.glLinkProgram(program);
			String log = GL20C.glGetProgramInfoLog(program, 32768);
			if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0 || log.contains("Failed for unknown reason")) {
				LOGGER.error("Couldn't link the program for pipeline ({}): {}", job.name, log.strip());
				ok = false;
			}
		}
		for (int shader : shaders) {
			if (shader == 0) continue;
			if (ok) GL20C.glDetachShader(program, shader);
			GL20C.glDeleteShader(shader);
		}
		if (!ok) {
			GL20C.glDeleteProgram(program);
			GL11C.glFinish();
			return 0;
		}
		cache.save(program, key);
		// Done before the render thread uses it from the game's context.
		GL11C.glFinish();
		return program;
	}

	/** Linked programs on disk: one file per program, named by a hash of the sources, the GPU and the driver. */
	private static final class Cache {
		private final boolean on;
		private final String driver;

		private Cache(boolean on, String driver) {
			this.on = on;
			this.driver = driver;
		}

		static Cache open(GLCapabilities caps) {
			String driver = GL11C.glGetString(GL11C.GL_VENDOR) + "\n" + GL11C.glGetString(GL11C.GL_RENDERER) + "\n" + GL11C.glGetString(GL11C.GL_VERSION);
			boolean on = (caps.OpenGL41 || caps.GL_ARB_get_program_binary) && GL11C.glGetInteger(GL41C.GL_NUM_PROGRAM_BINARY_FORMATS) > 0;
			if (on) {
				try {
					Files.createDirectories(CACHE);
					trim();
				} catch (IOException e) {
					LOGGER.warn("[Afterburner] Can't keep linked shader programs in {}: {}", CACHE, e.toString());
					on = false;
				}
			}
			return new Cache(on, driver);
		}

		boolean on() {
			return this.on;
		}

		String key(ShaderType[] types, String[] sources) {
			MessageDigest digest;
			try {
				digest = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
			digest.update(this.driver.getBytes(StandardCharsets.UTF_8));
			for (int i = 0; i < sources.length; i++) {
				digest.update((byte) 0);
				digest.update(types[i].getName().getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
				digest.update(sources[i].getBytes(StandardCharsets.UTF_8));
			}
			return HexFormat.of().formatHex(digest.digest(), 0, 20);
		}

		/** Whether the program was loaded from the cache (linked as it was saved). */
		boolean load(int program, String key) {
			if (!this.on) return false;
			Path file = CACHE.resolve(key + ".bin");
			byte[] bytes;
			try {
				if (!Files.isRegularFile(file)) return false;
				bytes = Files.readAllBytes(file);
			} catch (IOException e) {
				return false;
			}
			if (bytes.length <= 4) return false;
			ByteBuffer data = MemoryUtil.memAlloc(bytes.length - 4);
			try {
				data.put(bytes, 4, bytes.length - 4).flip();
				int format = ByteBuffer.wrap(bytes, 0, 4).getInt();
				GL41C.glProgramBinary(program, format, data);
			} finally {
				MemoryUtil.memFree(data);
			}
			if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
				// Saved by another driver build: linked from the sources instead, and saved again.
				GL11C.glGetError();
				return false;
			}
			try {
				Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
			} catch (IOException ignored) {
			}
			GL11C.glFinish();
			return true;
		}

		void save(int program, String key) {
			if (!this.on) return;
			int length = GL20C.glGetProgrami(program, GL41C.GL_PROGRAM_BINARY_LENGTH);
			if (length <= 0) return;
			ByteBuffer data = MemoryUtil.memAlloc(length);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer written = stack.mallocInt(1);
				IntBuffer format = stack.mallocInt(1);
				GL41C.glGetProgramBinary(program, written, format, data);
				int size = written.get(0);
				if (size <= 0) return;
				byte[] bytes = new byte[4 + size];
				ByteBuffer.wrap(bytes, 0, 4).putInt(format.get(0));
				data.get(0, bytes, 4, size);
				Path file = CACHE.resolve(key + ".bin");
				Path temp = CACHE.resolve(key + ".tmp");
				Files.write(temp, bytes);
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				LOGGER.debug("[Afterburner] Couldn't save a linked shader program", e);
			} finally {
				MemoryUtil.memFree(data);
			}
		}

		/** Cuts the cache back to {@link #CACHE_BYTES}, dropping the files used longest ago. */
		private static void trim() throws IOException {
			List<Path> files = new ArrayList<>();
			try (Stream<Path> list = Files.list(CACHE)) {
				list.filter(p -> p.getFileName().toString().endsWith(".bin")).forEach(files::add);
			}
			long total = 0;
			Map<Path, Long> sizes = new HashMap<>();
			Map<Path, Long> times = new HashMap<>();
			for (Path p : files) {
				long size = Files.size(p);
				sizes.put(p, size);
				times.put(p, Files.getLastModifiedTime(p).toMillis());
				total += size;
			}
			if (total <= CACHE_BYTES) return;
			files.sort(Comparator.comparingLong(times::get));
			for (Path p : files) {
				if (total <= CACHE_BYTES * 3 / 4) break;
				Files.deleteIfExists(p);
				total -= sizes.get(p);
			}
		}
	}
}
