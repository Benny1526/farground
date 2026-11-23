package dev.farground;

/**
 * Reads C2ME's world-gen thread count. With C2ME installed, every chunk Farground asks for is
 * built on C2ME's thread pool, so its size sets how fast Farground can go: more chunks at once
 * than the pool can work on just wait in line.
 *
 * Read by reflection so Farground needs no build dependency on C2ME and keeps working if a C2ME
 * update moves these fields (it then just behaves as if C2ME were not installed).
 */
final class C2meBridge {
	private C2meBridge() {}

	private static final String ENTRY = "com.ishland.c2me.base.ModuleEntryPoint";

	/** threads: what C2ME runs with; defaultThreads: what C2ME would pick for this PC. */
	record Threads(int threads, int defaultThreads) {
		/** True when someone set fewer threads than C2ME's own default. */
		boolean belowDefault() {
			return threads < defaultThreads;
		}
	}

	private static volatile boolean looked;
	private static volatile Threads cached;

	/** C2ME's thread counts, or null when C2ME isn't installed (or can't be read). Fixed for the game session. */
	static Threads threads() {
		if (looked) return cached;
		Threads t = null;
		if (Platform.isModLoaded("c2me")) {
			try {
				Class<?> c = Class.forName(ENTRY, true, C2meBridge.class.getClassLoader());
				long configured = c.getField("globalExecutorParallelism").getLong(null);
				int dflt = c.getField("defaultParallelism").getInt(null);
				if (configured > 0 && dflt > 0) t = new Threads((int) Math.min(configured, 4096), dflt);
			} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
				Farground.LOG.info("C2ME is installed but its thread count could not be read ({}); ignoring it", e.toString());
			}
		}
		cached = t;
		looked = true;
		return t;
	}

	/**
	 * Speeds for the auto-tune: multiples of C2ME's thread count. Fewer than one chunk per thread
	 * leaves threads idle; past about ten per thread, chunks only queue up. Without C2ME, null
	 * (the generator uses its fixed list).
	 */
	static int[] tuneLevels() {
		Threads t = threads();
		if (t == null) return null;
		return levelsFor(t.threads());
	}

	/** Public for testing: distinct multiples of the thread count, at least 8 and at most 256. */
	static int[] levelsFor(int threads) {
		int[] mult = {1, 2, 3, 4, 6, 8, 10};
		return java.util.stream.IntStream.of(mult)
				.map(m -> Math.clamp((long) m * threads, 8, 256))
				.distinct().sorted().toArray();
	}
}
