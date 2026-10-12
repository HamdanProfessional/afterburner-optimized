package com.afterburner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Loads every class an Afterburner mixin changes, with the mixins applied, without starting the game (fabric-loader-junit
 * runs the mod loader as a client). A mixin whose method, injection point or shadowed field isn't in 1.21.1 fails here
 * instead of when the game starts. Classes are loaded without running their static setup, so nothing of the game runs.
 */
class MixinsApplyTest {
	private static final String[] CONFIGS = {"afterburner.mixins.json", "afterburner.client.mixins.json", "afterburner_labs.mixins.json", "afterburner_bench.mixins.json"};

	@Test
	void everyMixinApplies() throws IOException {
		ClassLoader loader = getClass().getClassLoader();
		// Target class -> the mixins on it.
		Map<String, List<String>> targets = new TreeMap<>();
		List<String> found = new ArrayList<>();
		int mixins = 0;
		for (String name : CONFIGS) {
			JsonObject config;
			try (InputStream in = loader.getResourceAsStream(name)) {
				if (in == null) continue;
				config = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			}
			found.add(name);
			String pkg = config.get("package").getAsString();
			for (String side : new String[] {"mixins", "client", "server"}) {
				if (side.equals("server") || !config.has(side)) continue;
				for (JsonElement e : config.getAsJsonArray(side)) {
					String mixin = pkg + "." + e.getAsString();
					mixins++;
					for (String target : targetsOf(loader, mixin)) targets.computeIfAbsent(target, k -> new ArrayList<>()).add(mixin);
				}
			}
		}
		assertTrue(!found.isEmpty(), "no Afterburner mixin configs on the classpath");
		List<String> failures = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : targets.entrySet()) {
			try {
				Class.forName(entry.getKey(), false, loader);
			} catch (Throwable t) {
				StringBuilder why = new StringBuilder(entry.getKey()).append(" (").append(String.join(", ", entry.getValue())).append(")");
				for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
					why.append("\n    ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
				}
				failures.add(why.toString());
			}
		}
		System.out.println("Mixin check: " + found + ", " + mixins + " mixins on " + targets.size() + " classes, " + failures.size() + " failed");
		if (!failures.isEmpty()) fail(failures.size() + " of " + targets.size() + " classes failed:\n" + String.join("\n", failures));
	}

	/** The classes a mixin names in its {@code @Mixin} (by class or by name). */
	private static List<String> targetsOf(ClassLoader loader, String mixin) throws IOException {
		ClassNode node = new ClassNode();
		try (InputStream in = loader.getResourceAsStream(mixin.replace('.', '/') + ".class")) {
			if (in == null) fail("mixin class missing: " + mixin);
			new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
		}
		List<String> out = new ArrayList<>();
		if (node.invisibleAnnotations == null) return out;
		for (AnnotationNode a : node.invisibleAnnotations) {
			if (!a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || a.values == null) continue;
			for (int i = 0; i < a.values.size(); i += 2) {
				Object value = a.values.get(i + 1);
				if (!(value instanceof List<?> list)) continue;
				for (Object v : list) {
					if (v instanceof Type type) out.add(type.getClassName());
					else if (v instanceof String s) out.add(s.replace('/', '.'));
				}
			}
		}
		return out;
	}

}
