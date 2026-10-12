package com.afterburner.bench.client;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Times every frame, from the start of one pass through the game loop to the start of the next, so a frame's time
 * includes everything: game logic, rendering, and waiting for VSync or the FPS limit. Frames with a menu or the chat
 * open aren't counted, so the benchmark only measures actual gameplay.
 */
public final class FrameRecorder {
	private static Run run;
	private static long lastFrame;
	private static boolean lastInGame;

	private FrameRecorder() {
	}

	/** Called at the start of every frame. */
	public static void onFrame(Minecraft mc) {
		long now = System.nanoTime();
		boolean inGame = mc.level != null && mc.gui.screen() == null;
		Run r = run;
		if (r != null) {
			if (mc.level == null) {
				run = null;
				r.onDone.accept(null);
			} else if (inGame && lastInGame && lastFrame != 0) {
				r.add(mc, now - lastFrame);
			} else if (!inGame && lastInGame) {
				r.menuOpened++;
			}
		}
		lastFrame = now;
		lastInGame = inGame;
	}

	public static boolean running() {
		return run != null;
	}

	/** Records {@code seconds} of gameplay frames, then calls {@code onDone} (with null if the player left the world). */
	public static void start(int seconds, Consumer<Run> onDone) {
		run = new Run(seconds * 1_000_000_000L, onDone);
	}

	public static final class Run {
		private final LongArrayList frames = new LongArrayList();
		private final long length;
		private final Consumer<Run> onDone;
		private long recorded;
		private int shownSecond = -1;
		private int menuOpened;

		private Run(long length, Consumer<Run> onDone) {
			this.length = length;
			this.onDone = onDone;
		}

		private void add(Minecraft mc, long took) {
			frames.add(took);
			recorded += took;
			int second = (int) (recorded / 1_000_000_000L);
			if (second != shownSecond && mc.player != null) {
				shownSecond = second;
				mc.player.sendOverlayMessage(Component.literal("Benchmarking: " + second + " / " + length / 1_000_000_000L + " s"));
			}
			if (recorded >= length) {
				run = null;
				onDone.accept(this);
			}
		}

		public long[] frames() {
			return frames.toLongArray();
		}

		/** How many times a menu or the chat was opened during the run (those frames weren't counted). */
		public int menuOpened() {
			return menuOpened;
		}
	}
}
