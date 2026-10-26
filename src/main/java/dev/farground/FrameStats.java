package dev.farground;

import java.util.Arrays;

/**
 * Watches frame times to catch stutters (single slow frames), which barely move the average FPS.
 * {@link #frame()} is called once per rendered frame; once a second the last frames are checked:
 * if the slowest 1% are far slower than a typical frame, that second counts as stuttery.
 */
public final class FrameStats {
	private static final int SIZE = 512;
	private static final long[] times = new long[SIZE];
	private static int count;
	private static long lastFrame;
	private static long lastCheck;
	private static volatile long lastStutterNanos;

	private FrameStats() {}

	/** Record one frame. Call from the render thread. */
	public static void frame() {
		long now = System.nanoTime();
		if (lastFrame != 0) {
			long dt = now - lastFrame;
			if (dt > 0 && dt < 2_000_000_000L) { // ignore pauses longer than 2 s (menus, loading)
				times[count % SIZE] = dt;
				count++;
			}
		}
		lastFrame = now;
		if (now - lastCheck >= 1_000_000_000L) {
			lastCheck = now;
			if (count >= 20 && isStutter(Arrays.copyOf(times, Math.min(count, SIZE)))) {
				lastStutterNanos = now;
			}
			count = 0;
		}
	}

	/**
	 * True if the slowest frames are a stutter: the 99th-percentile frame takes more than 2.5x a
	 * typical (median) frame and at least 20 ms longer. Public for testing.
	 */
	public static boolean isStutter(long[] frameNanos) {
		long[] s = frameNanos.clone();
		Arrays.sort(s);
		long median = s[s.length / 2];
		long p99 = s[Math.min(s.length - 1, (int) Math.ceil(s.length * 0.99) - 1)];
		return p99 > median * 2.5 && p99 - median > 20_000_000L;
	}

	/** Was there a stutter in the last couple of seconds? */
	public static boolean recentStutter() {
		long t = lastStutterNanos;
		return t != 0 && System.nanoTime() - t < 2_500_000_000L;
	}
}
