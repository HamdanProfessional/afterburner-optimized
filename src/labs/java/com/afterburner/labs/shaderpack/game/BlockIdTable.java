package com.afterburner.labs.shaderpack.game;

import com.afterburner.client.render.TerrainExtras;
import com.afterburner.labs.shaderpack.BlockIdRules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;
import java.util.Set;

/** A pack's block IDs (block.properties) over all block states, for {@link TerrainExtras#use}. */
final class BlockIdTable {
	private BlockIdTable() {
	}

	/** The pack's ID + 1 of every block state, by its id in Block.BLOCK_STATE_REGISTRY (0 for none). */
	static int[] of(BlockIdRules rules) {
		int[] table = new int[Block.BLOCK_STATE_REGISTRY.size()];
		for (BlockIdRules.Rule rule : rules.rules) {
			Identifier id = Identifier.tryBuild(rule.namespace(), rule.path());
			if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) continue;
			Block block = BuiltInRegistries.BLOCK.getValue(id);
			if (block == null) continue;
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				if (!matches(block, state, rule.properties())) continue;
				int at = Block.BLOCK_STATE_REGISTRY.getId(state);
				if (at >= 0 && at < table.length) table[at] = rule.id() + 1;
			}
		}
		return table;
	}

	private static boolean matches(Block block, BlockState state, Map<String, Set<String>> properties) {
		for (Map.Entry<String, Set<String>> p : properties.entrySet()) {
			Property<?> property = block.getStateDefinition().getProperty(p.getKey());
			if (property == null || !p.getValue().contains(valueName(state, property))) return false;
		}
		return true;
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}
}
