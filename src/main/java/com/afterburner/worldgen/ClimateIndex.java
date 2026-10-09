package com.afterburner.worldgen;

import com.afterburner.Afterburner;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.biome.Climate;

/**
 * Vanilla's biome search tree (Climate.RTree) laid out in flat arrays: the same nodes in the same order, searched the
 * same way, so it finds the same biome, ties and all. Vanilla walks objects (each node an array of Parameter records)
 * through a distance callback; this reads longs from one array, and stops adding up a distance once it can't win.
 * <p>
 * The search starts from the biome this thread found last, kept in the tree's own lastResult like vanilla does, since
 * that decides which of two equally close biomes wins.
 */
public final class ClimateIndex {
	public static final boolean CHECK = Boolean.getBoolean("afterburner.checkClimate");
	private static final AtomicLong CHECKED = new AtomicLong(), DIFFERENT = new AtomicLong();
	private static final int DIMS = 7;

	/** Per node: min and max for each of the 7 parameters. */
	private final long[] bounds;
	/** Per node: where its children start and end in {@link #kids}, or -1 and the leaf number for a leaf. */
	private final int[] first, end;
	private final int[] kids;
	/** Per leaf: its node, its value and vanilla's leaf (for lastResult). */
	private final int[] leafNode;
	private final Object[] values, leaves;
	private final ThreadLocal<Object> lastResult;

	private ClimateIndex(long[] bounds, int[] first, int[] end, int[] kids, int[] leafNode, Object[] values, Object[] leaves, ThreadLocal<Object> lastResult) {
		this.bounds = bounds;
		this.first = first;
		this.end = end;
		this.kids = kids;
		this.leafNode = leafNode;
		this.values = values;
		this.leaves = leaves;
		this.lastResult = lastResult;
	}

	/** Null if vanilla's tree isn't laid out as expected; the caller then uses vanilla's search. */
	@SuppressWarnings("unchecked")
	public static ClimateIndex of(Climate.ParameterList<?> list) {
		try {
			Object tree = field(Climate.ParameterList.class, "index").get(list);
			Class<?> treeClass = tree.getClass();
			Object root = field(treeClass, "root").get(tree);
			ThreadLocal<Object> lastResult = (ThreadLocal<Object>) field(treeClass, "lastResult").get(tree);
			Builder b = new Builder();
			b.add(root);
			int n = b.nodes.size();
			long[] bounds = new long[n * DIMS * 2];
			int[] first = new int[n], end = new int[n];
			List<Integer> kids = new ArrayList<>();
			List<Integer> leafNode = new ArrayList<>();
			List<Object> values = new ArrayList<>(), leaves = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				Object node = b.nodes.get(i);
				Climate.Parameter[] space = (Climate.Parameter[]) b.space.get(node.getClass()).get(node);
				if (space.length != DIMS) return null;
				for (int d = 0; d < DIMS; d++) {
					bounds[(i * DIMS + d) * 2] = space[d].min();
					bounds[(i * DIMS + d) * 2 + 1] = space[d].max();
				}
				Object[] children = b.children(node);
				if (children == null) {
					first[i] = -1;
					end[i] = leaves.size();
					((ClimateLeaf) node).afterburner$setIndex(leaves.size());
					leafNode.add(i);
					leaves.add(node);
					values.add(b.value(node));
				} else {
					first[i] = kids.size();
					for (Object child : children) kids.add(b.number.get(child));
					end[i] = kids.size();
				}
			}
			return new ClimateIndex(bounds, first, end, kids.stream().mapToInt(Integer::intValue).toArray(),
					leafNode.stream().mapToInt(Integer::intValue).toArray(), values.toArray(), leaves.toArray(), lastResult);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Afterburner.LOGGER.warn("Faster biome search is off: the game's biome tree isn't as expected ({})", e.toString());
			return null;
		}
	}

	public Object search(Climate.TargetPoint point) {
		long[] target = {point.temperature(), point.humidity(), point.continentalness(), point.erosion(), point.depth(), point.weirdness(), 0L};
		Object last = lastResult.get();
		int start = last == null ? -1 : ((ClimateLeaf) last).afterburner$index();
		long startDistance = start < 0 ? Long.MAX_VALUE : distance(leafNode[start], target, Long.MAX_VALUE);
		int[] found = {start};
		if (first[0] < 0) found[0] = end[0];
		else search(0, target, found, startDistance);
		lastResult.set(leaves[found[0]]);
		return values[found[0]];
	}

	/** Same as SubTree.search: the closest leaf under node goes in found[0] if closer than minDistance; returns its distance. */
	private long search(int node, long[] target, int[] found, long minDistance) {
		for (int k = first[node], stop = end[node]; k < stop; k++) {
			int child = kids[k];
			long childDistance = distance(child, target, minDistance);
			if (minDistance > childDistance) {
				long leafDistance;
				if (first[child] < 0) {
					found[0] = end[child];
					leafDistance = childDistance;
				} else {
					leafDistance = search(child, target, found, minDistance);
				}
				if (minDistance > leafDistance) minDistance = leafDistance;
			}
		}
		return minDistance;
	}

	/** Vanilla's distance, or any number at least stop once it's clear it reaches stop (it can't win then). */
	private long distance(int node, long[] target, long stop) {
		long distance = 0L;
		int at = node * DIMS * 2;
		for (int d = 0; d < DIMS; d++, at += 2) {
			long t = target[d];
			long above = t - bounds[at + 1];
			long below = bounds[at] - t;
			long off = above > 0L ? above : Math.max(below, 0L);
			distance += off * off;
			if (distance >= stop) return distance;
		}
		return distance;
	}

	/** The leaf this thread's searches start from (for the check). */
	public Object last() {
		return lastResult.get();
	}

	public void restore(Object leaf) {
		lastResult.set(leaf);
	}

	public static void compare(Object ours, Object vanilla) {
		CHECKED.incrementAndGet();
		if (ours != vanilla && DIFFERENT.incrementAndGet() <= 5) Afterburner.LOGGER.warn("Biome search check: got {}, vanilla {}", ours, vanilla);
	}

	public static String summary() {
		return "biome search check: " + String.format("%,d", CHECKED.get()) + " compared, " + DIFFERENT.get() + " different";
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException ignored) {
			}
		}
		throw new NoSuchFieldException(owner.getName() + "." + name);
	}

	/** Numbers the nodes depth first and reads their fields. */
	private static final class Builder {
		final List<Object> nodes = new ArrayList<>();
		final java.util.IdentityHashMap<Object, Integer> number = new java.util.IdentityHashMap<>();
		final ClassValue<Field> space = fieldValue("parameterSpace");
		final ClassValue<Field> children = fieldValue("children");
		final ClassValue<Field> value = fieldValue("value");

		void add(Object node) throws ReflectiveOperationException {
			number.put(node, nodes.size());
			nodes.add(node);
			Object[] kids = children(node);
			if (kids != null) for (Object kid : kids) add(kid);
		}

		Object[] children(Object node) throws ReflectiveOperationException {
			if (node instanceof ClimateLeaf) return null;
			Field f = children.get(node.getClass());
			if (f == null) throw new NoSuchFieldException(node.getClass().getName() + ".children");
			return (Object[]) f.get(node);
		}

		Object value(Object node) throws ReflectiveOperationException {
			Field f = value.get(node.getClass());
			if (f == null) throw new NoSuchFieldException(node.getClass().getName() + ".value");
			return f.get(node);
		}

		private static ClassValue<Field> fieldValue(String name) {
			return new ClassValue<>() {
				@Override
				protected Field computeValue(Class<?> type) {
					try {
						return field(type, name);
					} catch (NoSuchFieldException e) {
						return null;
					}
				}
			};
		}
	}
}
