package com.afterburner.bench;

import com.afterburner.Afterburner;
import com.sun.management.HotSpotDiagnosticMXBean;
import net.fabricmc.loader.api.FabricLoader;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What the game's memory is made of, for benchmarks: a histogram of the live objects (taken after a full GC, so garbage
 * doesn't count) and, if the JVM runs with {@code -XX:NativeMemoryTracking=summary}, the memory outside the Java heap.
 */
public final class MemoryReport {
	private MemoryReport() {
	}

	/**
	 * Writes the histogram (and native memory summary) to {@code afterburner/benchmarks/<name>-heap.txt} and returns a line
	 * like "Memory: 512 MB live after full GC, heap 2048 MB committed".
	 */
	public static String write(String name) {
		String histogram = command("gcClassHistogram");
		// For tools/heapdump.py: -Dafterburner.heapDump=<file.hprof> also saves every live object.
		String dump = System.getProperty("afterburner.heapDump");
		if (dump != null) {
			try {
				Files.deleteIfExists(Path.of(dump));
				ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpHeap(dump, true);
			} catch (IOException e) {
				Afterburner.LOGGER.warn("Couldn't save the heap dump to {}", dump, e);
			}
		}
		String nativeMemory = command("vmNativeMemory", "summary");
		MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		String line = "Memory: " + mb(heap.getUsed()) + " MB live after full GC, heap " + mb(heap.getCommitted()) + " MB committed"
				+ nativeTotal(nativeMemory);
		Path file = FabricLoader.getInstance().getGameDir().resolve("afterburner").resolve("benchmarks").resolve(name + "-heap.txt");
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, line + "\n\n" + nativeMemory + "\n\n" + histogram);
		} catch (IOException e) {
			Afterburner.LOGGER.warn("Couldn't save the memory report to {}", file, e);
		}
		return line;
	}

	private static String command(String operation, String... arguments) {
		try {
			MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			Object result = server.invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"), operation,
					new Object[]{arguments}, new String[]{String[].class.getName()});
			return String.valueOf(result);
		} catch (Exception e) {
			return operation + " failed: " + e;
		}
	}

	/** ", native 812 MB committed" from the summary's total line, or nothing if native memory isn't tracked. */
	private static String nativeTotal(String summary) {
		for (String l : summary.split("\n")) {
			int at = l.indexOf("Total: reserved=");
			int committed = l.indexOf("committed=");
			if (at >= 0 && committed > at) {
				String kb = l.substring(committed + "committed=".length()).replaceAll("[^0-9].*", "");
				if (!kb.isEmpty()) return ", whole process (tracked) " + mb(Long.parseLong(kb) * 1024) + " MB";
			}
		}
		return "";
	}

	private static long mb(long bytes) {
		return Math.round(bytes / 1048576.0);
	}
}
