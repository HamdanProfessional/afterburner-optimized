package com.afterburner.labs.shaderpack;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * Read-only access to the "shaders" folder of a shader pack, from a zip or a folder. Paths are absolute inside that folder
 * ("/lib/config.glsl"). No Minecraft classes, so tools can use it outside the game.
 */
public final class PackFiles implements Closeable {
	private final String name;
	private final @Nullable ZipFile zip;
	/** Path of the "shaders/" folder inside the zip, ending in '/'. */
	private final String zipRoot;
	private final @Nullable Path dir;
	/** Rewrites text files as they're read (the player's choice of the pack's options), or null. */
	private @Nullable Rewriter rewriter;

	/** Changes a text file's contents as it's read. */
	public interface Rewriter {
		String rewrite(String path, String text);
	}

	private PackFiles(String name, @Nullable ZipFile zip, String zipRoot, @Nullable Path dir) {
		this.name = name;
		this.zip = zip;
		this.zipRoot = zipRoot;
		this.dir = dir;
	}

	/** Opens a pack zip, a pack folder (holding "shaders/"), or a "shaders" folder itself. */
	public static PackFiles open(Path pack) throws IOException {
		String name = pack.getFileName().toString();
		if (Files.isDirectory(pack)) {
			Path shaders = pack.resolve("shaders");
			if (Files.isDirectory(shaders)) return new PackFiles(name, null, "", shaders);
			if (name.equals("shaders")) return new PackFiles(pack.getParent().getFileName().toString(), null, "", pack);
			throw new IOException("No shaders folder in " + pack);
		}
		ZipFile zip = new ZipFile(pack.toFile(), StandardCharsets.UTF_8);
		String root = null;
		for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
			String entry = e.nextElement().getName();
			String candidate;
			if (entry.startsWith("shaders/")) {
				candidate = "shaders/";
			} else {
				int at = entry.indexOf("/shaders/");
				if (at < 0) continue;
				candidate = entry.substring(0, at + "/shaders/".length());
			}
			if (root == null || candidate.length() < root.length()) root = candidate;
		}
		if (root == null) {
			zip.close();
			throw new IOException("No shaders folder in " + pack);
		}
		return new PackFiles(name, zip, root, null);
	}

	/** The pack's file name. */
	public String name() {
		return this.name;
	}

	/** From now on, text files are read through {@code rewriter} (null: as they are). */
	public void rewriteWith(@Nullable Rewriter rewriter) {
		this.rewriter = rewriter;
	}

	/** The text of a file (rewritten, see {@link #rewriteWith}), or null if there is none. */
	public @Nullable String read(String path) throws IOException {
		String text = this.readRaw(path);
		Rewriter r = this.rewriter;
		return text == null || r == null ? text : r.rewrite(normalize(path), text);
	}

	/** The text of a file as it is in the pack, or null if there is none. */
	public @Nullable String readRaw(String path) throws IOException {
		String normal = normalize(path);
		if (normal == null) return null;
		if (this.zip != null) {
			ZipEntry entry = this.zip.getEntry(this.zipRoot + normal.substring(1));
			if (entry == null || entry.isDirectory()) return null;
			try (InputStream in = this.zip.getInputStream(entry)) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
		Path file = this.dir.resolve(normal.substring(1));
		return Files.isRegularFile(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : null;
	}

	/** Every file's path ("/lib/config.glsl"), sorted. */
	public List<String> list() throws IOException {
		List<String> out = new ArrayList<>();
		if (this.zip != null) {
			for (Enumeration<? extends ZipEntry> e = this.zip.entries(); e.hasMoreElements(); ) {
				ZipEntry entry = e.nextElement();
				if (!entry.isDirectory() && entry.getName().startsWith(this.zipRoot)) out.add("/" + entry.getName().substring(this.zipRoot.length()));
			}
		} else {
			try (Stream<Path> files = Files.walk(this.dir)) {
				files.filter(Files::isRegularFile).forEach(f -> out.add("/" + this.dir.relativize(f).toString().replace('\\', '/')));
			}
		}
		out.sort(null);
		return out;
	}

	/** The bytes of a file (textures), or null if there is none. */
	public byte @Nullable [] readBytes(String path) throws IOException {
		String normal = normalize(path);
		if (normal == null) return null;
		if (this.zip != null) {
			ZipEntry entry = this.zip.getEntry(this.zipRoot + normal.substring(1));
			if (entry == null || entry.isDirectory()) return null;
			try (InputStream in = this.zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
		Path file = this.dir.resolve(normal.substring(1));
		return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
	}

	public boolean exists(String path) {
		String normal = normalize(path);
		if (normal == null) return false;
		if (this.zip != null) {
			ZipEntry entry = this.zip.getEntry(this.zipRoot + normal.substring(1));
			return entry != null && !entry.isDirectory();
		}
		return Files.isRegularFile(this.dir.resolve(normal.substring(1)));
	}

	/**
	 * Makes a path absolute and clean: "/a/./b/../c" is "/a/c". A relative path is taken from {@code from}'s folder. Null if
	 * it leaves the shaders folder.
	 */
	public static @Nullable String resolve(String from, String path) {
		if (path.startsWith("/")) return normalize(path);
		int slash = from.lastIndexOf('/');
		return normalize(from.substring(0, slash + 1) + path);
	}

	static @Nullable String normalize(String path) {
		Deque<String> parts = new ArrayDeque<>();
		for (String part : path.replace('\\', '/').split("/")) {
			if (part.isEmpty() || part.equals(".")) continue;
			if (part.equals("..")) {
				if (parts.isEmpty()) return null;
				parts.removeLast();
			} else {
				parts.addLast(part);
			}
		}
		return "/" + String.join("/", parts);
	}

	@Override
	public void close() throws IOException {
		if (this.zip != null) this.zip.close();
	}
}
