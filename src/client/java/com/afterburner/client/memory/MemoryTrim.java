package com.afterburner.client.memory;

import com.afterburner.Afterburner;
import com.afterburner.client.render.Probe;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import net.minecraft.client.Minecraft;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

/**
 * Gives memory the game isn't using back to Windows while nobody is playing.
 * <p>
 * Java grabs more memory whenever the game is busy (loading chunks makes lots of short-lived objects) but hardly ever
 * returns it: only after a full clean-up, and only if more than 70% of what it holds is empty. So the game keeps showing
 * 1-1.5 GB of Java memory in Task Manager when it really needs a few hundred MB.
 * <p>
 * When the game has been paused (singleplayer) or in the background for 10 seconds, this runs one full clean-up and,
 * just for that clean-up, lets Java keep only 30% spare, so it returns the rest. That stops the game for about half a
 * second, which nobody sees while it's paused or behind another window. While playing, Java sizes its memory exactly as
 * in vanilla (keeping it small all the time makes it clean up more often, which made the slowest frames slower). It
 * happens at most once per pause, only with the G1 collector the launcher uses, and not if the player set these Java
 * options themselves.
 */
public final class MemoryTrim {
	private static final long IDLE_NANOS = 10_000_000_000L;
	private static final long MIN_HELD = 512L << 20;

	private static HotSpotDiagnosticMXBean vm;
	private static long idleSince = -1;
	private static boolean trimmed;

	private MemoryTrim() {
	}

	public static void init() {
		boolean g1 = false;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) g1 |= gc.getName().startsWith("G1");
		if (!g1) {
			Afterburner.LOGGER.info("Memory trim off: the game doesn't use the G1 garbage collector");
			return;
		}
		HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
		if (bean.getVMOption("MinHeapFreeRatio").getOrigin() != VMOption.Origin.DEFAULT
				|| bean.getVMOption("MaxHeapFreeRatio").getOrigin() != VMOption.Origin.DEFAULT) {
			Afterburner.LOGGER.info("Memory trim off: MinHeapFreeRatio/MaxHeapFreeRatio were set by hand");
			return;
		}
		vm = bean;
	}

	/** Every frame, from the game loop. */
	public static void onFrame(Minecraft mc) {
		if (vm == null || Probe.get().busy()) return;
		boolean idle = mc.isPaused() || !mc.isWindowActive();
		if (!idle) {
			idleSince = -1;
			trimmed = false;
			return;
		}
		long now = System.nanoTime();
		if (idleSince < 0) idleSince = now;
		if (trimmed || now - idleSince < IDLE_NANOS) return;
		trimmed = true;
		MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		if (heap.getCommitted() < MIN_HELD) return;
		trim(heap);
	}

	private static void trim(MemoryUsage before) {
		String min = vm.getVMOption("MinHeapFreeRatio").getValue(), max = vm.getVMOption("MaxHeapFreeRatio").getValue();
		long t0 = System.nanoTime();
		try {
			// Each is checked against the other when set, so lower Min first and raise Max first when putting them back.
			vm.setVMOption("MinHeapFreeRatio", "10");
			vm.setVMOption("MaxHeapFreeRatio", "30");
			System.gc();
		} catch (RuntimeException e) {
			Afterburner.LOGGER.warn("Memory trim failed", e);
		} finally {
			vm.setVMOption("MaxHeapFreeRatio", max);
			vm.setVMOption("MinHeapFreeRatio", min);
		}
		MemoryUsage after = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		Afterburner.LOGGER.info("Memory trim: game idle, Java memory {} -> {} MB ({} MB in use), took {} ms", before.getCommitted() >> 20,
				after.getCommitted() >> 20, after.getUsed() >> 20, (System.nanoTime() - t0) / 1_000_000);
	}
}
