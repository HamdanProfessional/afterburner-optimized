package com.afterburner.labs.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A pack's block.properties: which blocks get which ID in {@code mc_Entity.x}. Each line is {@code block.<id>=<block> <block> ...}
 * where a block is {@code [namespace:]name[:property=value[,value...]]...}; names without a namespace are minecraft's.
 * Old names that no longer exist (red_flower, leaves2) just match nothing.
 */
public final class BlockIdRules {
	/** One block of a line: its ID, and the property values a state needs (any of each property's values) to match. */
	public record Rule(int id, String namespace, String path, Map<String, Set<String>> properties) {}

	/** Highest ID a vertex can hold (see the shader pack terrain format). */
	public static final int MAX_ID = 65534;

	public final List<Rule> rules;
	public final List<String> warnings;

	private BlockIdRules(List<Rule> rules, List<String> warnings) {
		this.rules = rules;
		this.warnings = warnings;
	}

	public static final BlockIdRules NONE = new BlockIdRules(List.of(), List.of());

	public static BlockIdRules parse(PackProperties properties) {
		List<Rule> rules = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		for (PackProperties.Entry e : properties.withPrefix("block.")) {
			int id;
			try {
				id = Integer.parseInt(e.key().substring("block.".length()).strip());
			} catch (NumberFormatException ex) {
				warnings.add("block.properties: " + e.key() + " isn't a number");
				continue;
			}
			if (id < 0 || id > MAX_ID) {
				warnings.add("block.properties: " + e.key() + " is past " + MAX_ID);
				continue;
			}
			for (String block : e.value().split("\\s+")) {
				if (block.isEmpty()) continue;
				Rule rule = rule(id, block);
				if (rule != null) rules.add(rule);
			}
		}
		return new BlockIdRules(List.copyOf(rules), List.copyOf(warnings));
	}

	private static Rule rule(int id, String block) {
		String[] parts = block.split(":");
		int at = 0;
		String namespace = "minecraft";
		if (parts.length >= 2 && !parts[1].contains("=")) namespace = parts[at++];
		String path = parts[at++];
		if (path.isEmpty() || path.contains("=")) return null;
		Map<String, Set<String>> properties = new LinkedHashMap<>();
		for (; at < parts.length; at++) {
			int eq = parts[at].indexOf('=');
			if (eq <= 0) continue;
			Set<String> values = properties.computeIfAbsent(parts[at].substring(0, eq), k -> new LinkedHashSet<>());
			for (String v : parts[at].substring(eq + 1).split(",")) if (!v.isEmpty()) values.add(v);
		}
		return new Rule(id, namespace, path, properties);
	}
}
