package com.afterburner.bench.client;

import com.afterburner.Afterburner;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.time.Duration;

/**
 * With {@code -Dafterburner.jfr=FILE}, records a Java Flight Recorder profile of just the part of a benchmark that
 * measures, so loading doesn't drown it out.
 */
public final class BenchProfile {
	private static final @Nullable String FILE = System.getProperty("afterburner.jfr");
	private static @Nullable Recording recording;

	private BenchProfile() {
	}

	public static void start() {
		if (FILE == null) return;
		try {
			Recording r = new Recording(Configuration.getConfiguration("profile"));
			// The same rate for both, so time in native code (OpenGL, the driver) and in Java compare directly.
			r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
			r.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(10));
			r.start();
			recording = r;
		} catch (Exception e) {
			Afterburner.LOGGER.warn("Couldn't start the benchmark profile", e);
		}
	}

	public static void stop() {
		Recording r = recording;
		if (r == null) return;
		recording = null;
		try {
			r.stop();
			r.dump(Path.of(FILE));
		} catch (Exception e) {
			Afterburner.LOGGER.warn("Couldn't save the benchmark profile", e);
		} finally {
			r.close();
		}
	}
}
