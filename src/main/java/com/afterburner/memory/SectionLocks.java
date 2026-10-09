package com.afterburner.memory;

import net.minecraft.ReportedException;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * The "is another thread using this chunk section?" check of {@link PalettedContainer}, kept in one field of the
 * container instead of vanilla's four objects (a detector, a semaphore, a lock and their two inner parts, about
 * 128 bytes for every block and biome container of every loaded chunk section).
 * <p>
 * It works like vanilla's: a thread takes the container, uses it and gives it back. If a second thread tries to take it
 * meanwhile, that thread waits; when the first gives it back, it builds the same "Accessing PalettedContainer from
 * multiple threads" crash report with both threads' stack traces, and both threads throw it.
 */
public final class SectionLocks {
	/** The field the mixin adds: null when free, else the {@link Thread} using the container, or a {@link Clash}. */
	private static final VarHandle USER;

	static {
		try {
			USER = MethodHandles.privateLookupIn(PalettedContainer.class, MethodHandles.lookup())
					.findVarHandle(PalettedContainer.class, "afterburner$user", Object.class);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Afterburner's chunk section lock field is missing", e);
		}
	}

	private SectionLocks() {
	}

	private static final class Clash {
		final Thread waiting;
		volatile ReportedException failure;

		Clash(Thread waiting) {
			this.waiting = waiting;
		}
	}

	public static void acquire(PalettedContainer<?> container) {
		Thread me = Thread.currentThread();
		if (!USER.compareAndSet(container, null, me)) acquireSlow(container, me);
	}

	public static void release(PalettedContainer<?> container) {
		Object user = USER.getVolatile(container);
		if (!(user instanceof Thread) || !USER.compareAndSet(container, user, null)) releaseSlow(container);
	}

	private static void acquireSlow(PalettedContainer<?> container, Thread me) {
		while (true) {
			Object user = USER.getVolatile(container);
			if (user == null) {
				if (USER.compareAndSet(container, null, me)) return;
				continue;
			}
			Clash clash;
			if (user instanceof Clash c) {
				clash = c;
			} else {
				clash = new Clash(me);
				if (!USER.compareAndSet(container, user, clash)) continue;
			}
			// Like vanilla: wait for the thread using it to give it back, then fail with the report that thread made.
			while (clash.failure == null) LockSupport.parkNanos(100_000);
			throw clash.failure;
		}
	}

	private static void releaseSlow(PalettedContainer<?> container) {
		while (true) {
			Object user = USER.getVolatile(container);
			if (user instanceof Clash clash) {
				ReportedException failure = ThreadingDetector.makeThreadingException("PalettedContainer", clash.waiting);
				clash.failure = failure;
				USER.setVolatile(container, null);
				throw failure;
			}
			if (USER.compareAndSet(container, user, null)) return;
		}
	}
}
