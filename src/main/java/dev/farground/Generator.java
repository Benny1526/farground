package dev.farground;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;

/**
 * Generates chunks around each player, nearest first, and hands them to Voxy.
 *
 * Speed: non-blocking requests, tile order, a short hold so neighbors reuse work, fast
 * lighting, and terrain-only generation for far-away rings.
 *
 * Staying out of your way: slows down while you move, backs off when your frame rate dips
 * or the server lags, waits for the chunks around you to load first, pauses on battery,
 * and pauses when Voxy's own queue is backed up.
 */
public final class Generator {
	private static final int SAVE_INTERVAL_TICKS = 20 * 60 * 5;
	private static final int SECOND = 20;
	private static final int SCAN_BUDGET = 2048;
	private static final int VOXY_QUEUE_LIMIT = 4096;

	/** Last stage that only builds the land shape: "terrain" in 26.3, "surface" in 26.2. */
	private static final ChunkStatus TERRAIN_ONLY = lookupStatus("terrain", "surface");

	private static ChunkStatus lookupStatus(String... names) {
		for (String n : names) {
			var s = BuiltInRegistries.CHUNK_STATUS.getOptional(Identifier.withDefaultNamespace(n));
			if (s.isPresent()) return s.get();
		}
		return ChunkStatus.FEATURES;
	}

	private final MinecraftServer server;
	private final FargroundConfig cfg;
	private final Map<ResourceKey<Level>, DimState> dims = new HashMap<>();
	private final Map<UUID, Spiral> spirals = new HashMap<>();
	private final Map<UUID, ResourceKey<Level>> spiralDims = new HashMap<>();
	private final ArrayDeque<AreaJob> areas = new ArrayDeque<>();
	private final Set<String> announced = new HashSet<>();
	private final int[] cell = new int[2];

	private boolean stopped;
	private int ticks;
	private int limit;          // from server tick time
	private int fpsLimit;       // from client frame rate
	private int inFlight;

	// Read from the client thread (status command, HUD).
	volatile boolean paused;
	volatile long generated;
	volatile long generatedFull;
	volatile long failed;
	volatile double lastMspt;
	volatile int lastLimit;
	volatile int lastInFlight;
	volatile String waitingReason = "starting";
	volatile long voxyWaitTicks;
	volatile long firstRequestNanos;
	volatile long lastDoneNanos;
	volatile boolean playersMoving;
	volatile boolean onBattery;
	volatile double progress;       // 0..1 of the area around the (first) player
	volatile double recentRate;     // chunks per second over the last few seconds
	volatile double averageRate;    // chunks per second over the last few minutes, for the time-left estimate
	volatile long remaining = -1;   // chunks still to generate around the (first) player, -1 = unknown
	private int rateSamples;
	volatile Tr.Msg areaStatus;     // non-null while an /farground area job runs
	volatile boolean afk;           // window unfocused, or not moving for afkSeconds
	volatile boolean memoryTight;   // Java memory above memoryPausePercent
	volatile int fullDetailNow;     // full-detail distance in use for the overworld (after "auto")
	volatile Tr.Msg tuneStatus;     // non-null while auto-tune runs
	volatile int radiusNow;         // radius in use (after "match my LOD mod")
	volatile boolean radiusIsAuto;  // radiusNow came from the LOD mod's render distance
	private volatile boolean settled; // everything in range got done, and little has come up since

	private static final class DimState {
		final DoneIndex done;
		final LongOpenHashSet pending = new LongOpenHashSet();
		final StaleChunks stale;
		DimState(Path file) {
			this.done = new DoneIndex(file);
			this.stale = new StaleChunks(file.resolveSibling(file.getFileName() + ".stale"));
		}
	}

	/** A one-off /farground area job: fill a square around a point at full detail. */
	private static final class AreaJob {
		final ResourceKey<Level> dim;
		final Spiral spiral;
		final int x, z, radius;
		/** This job's chunks that may still be in progress; pruned as they finish. */
		final LongOpenHashSet requested = new LongOpenHashSet();
		AreaJob(ResourceKey<Level> dim, int x, int z, int radius) {
			this.dim = dim;
			this.x = x; this.z = z; this.radius = radius;
			this.spiral = new Spiral(x, z, 0, radius);
		}
	}

	/**
	 * Dedicated server with Voxy Server: generate finished chunks and let Voxy Server turn them
	 * into LODs (it voxelizes every chunk that finishes loading). No direct Voxy calls here.
	 */
	private final boolean serverMode;

	/** Which LOD mods get the chunks. Voxy needs a client (on a server, Voxy Server does it). */
	private final boolean useVoxy, useDh;

	public Generator(MinecraftServer server, FargroundConfig cfg, boolean serverMode) {
		this.server = server;
		this.cfg = cfg;
		this.serverMode = serverMode;
		this.limit = cfg.maxConcurrent;
		this.fpsLimit = cfg.maxConcurrent;
		this.radiusNow = cfg.radiusChunks;
		this.useVoxy = !serverMode && Platform.isModLoaded("voxy");
		this.useDh = Platform.isModLoaded("distanthorizons");
		refreshRadius();
		checkC2me();
	}

	/**
	 * With C2ME, chunks are built on its thread pool, so the right number at once depends on its
	 * thread count. If that count changed since the auto-tune (or the tune ran before Farground
	 * recorded it), start from a scaled guess and tune again the next time you are away.
	 */
	private void checkC2me() {
		C2meBridge.Threads t = C2meBridge.threads();
		if (t == null) return;
		if (t.belowDefault()) {
			Farground.LOG.warn("C2ME is set to {} world-gen threads, below its default of {} for this PC. Farground can't generate"
					+ " faster than those threads allow; set globalExecutorParallelism = \"default\" in config/c2me.toml.", t.threads(), t.defaultThreads());
		}
		if (serverMode) return; // the auto-tune only runs in singleplayer
		int old = cfg.tunedForC2meThreads;
		if (!cfg.autoTuned || old == t.threads()) return;
		int guess = old > 0 ? (int) Math.round(cfg.maxConcurrent * (double) t.threads() / old)
				: Math.max(cfg.maxConcurrent, t.threads() * 2);
		guess = Math.clamp(guess, 8, 256);
		Farground.LOG.info("C2ME now has {} world-gen threads ({} when the speed was tuned): starting at {} at once"
				+ " instead of {}, and tuning again the next time you are away", t.threads(), old > 0 ? old : "unknown", guess, cfg.maxConcurrent);
		setMaxConcurrent(guess);
		cfg.autoTuned = false;
		cfg.save();
	}

	/** True if at least one LOD mod is installed to receive what Farground generates. */
	public static boolean anyLodModInstalled(boolean serverMode) {
		return Platform.isModLoaded("distanthorizons")
				|| (serverMode ? Platform.isModLoaded("voxyserver") : Platform.isModLoaded("voxy"));
	}

	/** "Voxy", "Distant Horizons" or "Voxy and Distant Horizons", for messages. */
	public String lodName() {
		if (useVoxy && useDh) return "Voxy and Distant Horizons";
		if (useDh) return "Distant Horizons";
		return serverMode ? "Voxy Server" : "Voxy";
	}

	/** Does an installed LOD mod keep LODs for this dimension? */
	private boolean lodEnabledFor(ServerLevel level) {
		if (serverMode && !useDh) return true; // Voxy Server handles every dimension
		return (useVoxy && VoxyBridge.enabledFor(level)) || (useDh && DhBridge.enabledFor(level));
	}

	/** Chunks per second since the first request, for /farground status. */
	public double rate() {
		long start = firstRequestNanos, end = lastDoneNanos;
		if (start == 0 || end <= start) return 0;
		return generated / ((end - start) / 1e9);
	}

	private DimState dim(ServerLevel level) {
		return dims.computeIfAbsent(level.dimension(), key -> {
			String name = key.identifier().toString().replace(':', '_').replace('/', '_');
			Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("farground").resolve(name + ".bin");
			return new DimState(file);
		});
	}

	// =====================================================================================
	// Tick
	// =====================================================================================

	private long generatedAtLastSample;

	public void tick() {
		if (stopped) return;
		ticks++;
		if (ticks % SAVE_INTERVAL_TICKS == 0) saveAll();
		if (ticks % SECOND == 0) {
			adjustForServerLag();
			adjustForFrameRate();
			onBattery = cfg.pauseOnBattery && Battery.onBattery();
			checkMemory();
			refreshRadius();
			refreshFullDetail();
			stepAutotune();
			if (useDh) DhBridge.pauseOwnGenerator(cfg.enabled && cfg.replaceDhGenerator);
		}
		if (ticks % (60 * SECOND) == 0) forgetOfflinePlayers();
		if (ticks % (5 * SECOND) == 0) {
			recentRate = (generated - generatedAtLastSample) / 5.0;
			generatedAtLastSample = generated;
			updateAverageRate();
			updateProgress();
		}
		trackMovement();
		lastInFlight = inFlight;
		lastLimit = cap();
		releaseHeld(false);
		clearStaleChunks();
		fill();
	}

	/** Drops per-player state for players who left, so a server with many visitors doesn't keep growing. */
	private void forgetOfflinePlayers() {
		Set<UUID> online = new HashSet<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) online.add(p.getUUID());
		lastPose.keySet().retainAll(online);
		velocity.keySet().retainAll(online);
		spirals.keySet().retainAll(online);
		spiralDims.keySet().retainAll(online);
		nearbyCache.keySet().retainAll(online);
		generatedAtSpiralStart.keySet().retainAll(online);
	}

	/** Cut by a quarter when the server falls behind; grow by a quarter with plenty of headroom. */
	private void adjustForServerLag() {
		double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
		lastMspt = mspt;
		if (mspt > cfg.tickBudgetMs) {
			limit = Math.max(1, limit * 3 / 4);
		} else if (mspt < cfg.tickBudgetMs * 0.75) {
			limit = Math.min(turboMax(), limit + Math.max(1, limit / 4));
		}
	}

	/** Upper limit while you are away: half again the normal maximum. */
	private int turboMax() {
		return cfg.afkTurbo && !serverMode ? Math.min(256, cfg.maxConcurrent * 3 / 2) : cfg.maxConcurrent;
	}

	/**
	 * Pause new work when Java memory is really nearly full; resume once it drops back 10 points.
	 *
	 * "Really" matters: collectors like ZGC and G1 let memory fill up before cleaning it, so the
	 * current usage is often 90%+ on a perfectly healthy game. What counts is how much is still
	 * in use right after the last cleanup, so that is what this measures.
	 */
	private void checkMemory() {
		if (cfg.memoryPausePercent <= 0) { memoryTight = false; return; }
		double pct = liveHeapPercent();
		if (pct >= cfg.memoryPausePercent) memoryTight = true;
		else if (pct < cfg.memoryPausePercent - 10) memoryTight = false;
	}

	/** Percent of the maximum heap still in use after the most recent garbage collection. */
	static double liveHeapPercent() {
		long max = Runtime.getRuntime().maxMemory();
		long afterGc = 0;
		boolean any = false;
		for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getType() != java.lang.management.MemoryType.HEAP) continue;
			var u = pool.getCollectionUsage();
			if (u == null) continue;
			afterGc += u.getUsed();
			any = true;
		}
		if (!any || afterGc <= 0) return 0; // no collection yet: nothing to worry about
		return 100.0 * afterGc / max;
	}

	/** Re-reads the LOD mod's render distance right away (after a command). */
	public void refreshRadiusNow() {
		refreshRadius();
	}

	/** Picks the radius: the LOD mod's render distance when "match" is on, else the setting. */
	private void refreshRadius() {
		int r = cfg.radiusChunks;
		boolean auto = false;
		ClientSignals s = Farground.signals;
		if (cfg.autoRadius && !serverMode && s != null) {
			int lod = s.lodRenderDistanceChunks();
			if (lod > 0) {
				r = Math.clamp(lod, 16, 4096);
				auto = true;
			}
		}
		radiusIsAuto = auto;
		if (r != radiusNow) {
			Farground.LOG.info("Generating out to {} chunks{}", r, auto ? " (" + lodName() + " render distance)" : "");
			radiusNow = r;
			restartSpirals(); // spirals know their outer edge
		}
	}

	/** Works out the full-detail distance ("auto" = from Voxy's detail setting). */
	private void refreshFullDetail() {
		int fd = cfg.fullDetailRadius;
		if (fd < 0) {
			ClientSignals s = Farground.signals;
			int auto = s == null ? -1 : s.autoFullDetailChunks(cfg.autoDetailVoxelSize);
			fd = auto > 0 ? auto : 128;
		}
		fullDetailNow = Math.min(fd, radiusNow);
	}

	/** Whether the settings allow generating in this dimension. Other modded dimensions count as Overworld-like. */
	private boolean dimensionEnabled(ServerLevel level) {
		if (level.dimension() == Level.NETHER) return cfg.generateNether;
		if (level.dimension() == Level.END) return cfg.generateEnd;
		return cfg.generateOverworld;
	}

	/** Full-detail distance for a level: capped in the Nether and the End. */
	int fullDetailFor(ServerLevel level) {
		int fd = fullDetailNow;
		if (level.dimensionType().hasCeiling() || level.dimension() == Level.END) {
			fd = Math.min(fd, cfg.otherDimensionFullDetail);
		}
		return fd;
	}

	private double fpsAverage;

	/**
	 * Backs off when the frame rate dips well below its recent average, and recovers when it
	 * holds. Comparing against the average (not a fixed target) means a game that simply runs
	 * at 50 FPS with shaders is not throttled forever; only sudden drops are.
	 */
	private void adjustForFrameRate() {
		ClientSignals s = Farground.signals;
		if (!cfg.frameRateAware || s == null || !s.active()) {
			fpsLimit = cfg.maxConcurrent;
			return;
		}
		int fps = s.fps();
		if (fps <= 0) return;
		if (fpsAverage == 0) fpsAverage = fps;
		int floor = Math.max(1, cfg.activeConcurrent);
		if (s.stutter()) {
			// A few very slow frames: the hitches you feel. Back off hard and hold for a while.
			fpsLimit = Math.max(floor, fpsLimit * 2 / 3);
			lastStutterTick = ticks;
			stutterBackoffs++;
		} else if (FrameStats.isDip(fps, fpsAverage)) {
			fpsLimit = Math.max(floor, fpsLimit * 3 / 4);
		} else if (ticks - lastStutterTick > 3 * SECOND) {
			fpsLimit = Math.min(cfg.maxConcurrent, fpsLimit + Math.max(1, fpsLimit / 8));
		}
		fpsAverage = fpsAverage * 0.95 + fps * 0.05;
	}

	private int lastStutterTick = Integer.MIN_VALUE / 2;
	volatile int stutterBackoffs;

	// ---- Slow down while a player is moving or looking around ----

	private final Map<UUID, double[]> lastPose = new HashMap<>();
	private final Map<UUID, double[]> velocity = new HashMap<>();
	// Counts as "just moved" at world join, so you are never treated as away right after loading in.
	private int lastMoveTick = 0;
	private double stillCap = 0;

	private void trackMovement() {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			double[] now = {p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()};
			double[] before = lastPose.put(p.getUUID(), now);
			if (before == null) continue;
			double dx = now[0] - before[0], dz = now[2] - before[2];
			double[] v = velocity.computeIfAbsent(p.getUUID(), k -> new double[2]);
			v[0] = v[0] * 0.8 + dx * 0.2;   // smoothed blocks per tick
			v[1] = v[1] * 0.8 + dz * 0.2;
			double moved = Math.abs(dx) + Math.abs(now[1] - before[1]) + Math.abs(dz);
			double turned = Math.abs(now[3] - before[3]) + Math.abs(now[4] - before[4]);
			if (moved > 0.02 || turned > 0.5) lastMoveTick = ticks;
		}
		playersMoving = ticks - lastMoveTick < cfg.idleSeconds * SECOND;
		ClientSignals s = Farground.signals;
		afk = !serverMode && s != null
				&& (!s.focused() || ticks - lastMoveTick > cfg.afkSeconds * SECOND);
		if (playersMoving) {
			stillCap = cfg.activeConcurrent;
		} else {
			stillCap = Math.min(maxNow(), stillCap + cfg.rampPerSecond / (double) SECOND);
		}
	}

	private int maxNow() {
		return Math.min(Math.min(limit, fpsLimit), cfg.maxConcurrent);
	}

	/** How many chunks may be in flight right now. */
	private int cap() {
		if (tuneLevel > 0) return tuneLevel;
		if (serverMode) return Math.min(limit, cfg.maxConcurrent); // only server lag counts
		if (afk && cfg.afkTurbo && !playersMoving) {
			ClientSignals s = Farground.signals;
			// Window in the background: frame rate doesn't matter. Still on screen: keep the
			// stutter protection, just with 50% more room.
			return s != null && s.focused() ? Math.min(limit, Math.max(1, fpsLimit * 3 / 2)) : limit;
		}
		int moving = Math.min(maxNow(), cfg.activeConcurrent);
		return playersMoving ? moving : Math.max(moving, (int) stillCap);
	}

	// =====================================================================================
	// Auto-tune: try a few speeds for 10 s each while you are away, keep the best one
	// =====================================================================================

	private static final int[] DEFAULT_TUNE_LEVELS = {16, 24, 32, 48, 64, 96, 128};
	private static final int TUNE_WARMUP = 4, TUNE_MEASURE = 10; // seconds
	private boolean tuneRequested;
	private int tuneIndex = -1, tuneSeconds, tuneLevel;
	private long tuneStartCount;
	/** Speeds to try: multiples of C2ME's thread count when it is installed, else a fixed list. */
	private int[] tuneLevels = DEFAULT_TUNE_LEVELS;
	private double[] tuneRate = new double[tuneLevels.length];
	private double[] tuneMspt = new double[tuneLevels.length];

	private boolean tuneManual, tuneAnnounced;

	/** /farground autotune: runs as soon as you stand still (not only when away). */
	public void requestAutotune() {
		tuneRequested = true;
		tuneManual = true;
		tuneAnnounced = false;
	}

	/** Stops a tune in progress; it will not start again by itself. */
	public void cancelAutotune() {
		tuneRequested = false;
		tuneManual = false;
		tuneIndex = -1;
		tuneLevel = 0;
		tuneStatus = null;
		cfg.autoTuned = true;
		// Cancelled on purpose: don't start it again just because the biome speed-up is in use.
		if (FastBiomes.used) cfg.tunedWithFastBiomes = true;
		cfg.save();
	}

	/**
	 * The faster modded-biome climate makes chunks much cheaper (about 3x with Lithosphere), so a
	 * speed tuned without it is too low. The first time it is used in a tuned world, tune again
	 * the next time you are away. Checked every tick because it only shows once chunks generate.
	 */
	private void checkFastBiomes() {
		if (!FastBiomes.used || !cfg.autoTuned || cfg.tunedWithFastBiomes) return;
		Farground.LOG.info("Chunks build faster now (biomes are worked out in one batch for this world's modded biome source):"
				+ " tuning the speed again the next time you are away");
		cfg.autoTuned = false;
		cfg.save();
	}

	private void stepAutotune() {
		if (serverMode) return;
		checkFastBiomes();
		if (!tuneRequested && cfg.autoTuneOnFirstAfk && !cfg.autoTuned && afk) tuneRequested = true;
		boolean working = waitingReason.startsWith("generating") || tuneIndex >= 0;
		if (tuneIndex < 0) {
			// The automatic tune only runs while you are away; the command version runs once you stand still.
			boolean ready = tuneManual ? !playersMoving : afk && !playersMoving;
			if (tuneRequested && ready && working && !paused && !onBattery) {
				tuneIndex = 0;
				tuneSeconds = 0;
				int[] c2me = C2meBridge.tuneLevels();
				tuneLevels = c2me != null ? c2me : DEFAULT_TUNE_LEVELS;
				tuneRate = new double[tuneLevels.length];
				tuneMspt = new double[tuneLevels.length];
				// Only the command version says so in chat; the automatic one shows "tuning" in the corner.
				if (tuneManual && !tuneAnnounced) {
					tuneAnnounced = true;
					broadcast("farground.tune.start");
				}
			} else {
				tuneLevel = 0;
				tuneStatus = !tuneRequested ? null
						: tuneManual ? new Tr.Msg("farground.tune.waiting_manual")
						: new Tr.Msg("farground.tune.waiting_away");
				return;
			}
		}
		if (playersMoving || paused || onBattery) {
			tuneIndex = -1;
			tuneLevel = 0;
			tuneStatus = new Tr.Msg("farground.tune.paused");
			return;
		}
		tuneLevel = tuneLevels[tuneIndex];
		tuneStatus = new Tr.Msg("farground.tune.trying", tuneLevel, tuneIndex + 1, tuneLevels.length);
		tuneSeconds++;
		if (tuneSeconds == TUNE_WARMUP) tuneStartCount = generated;
		if (tuneSeconds >= TUNE_WARMUP + TUNE_MEASURE) {
			tuneRate[tuneIndex] = (generated - tuneStartCount) / (double) TUNE_MEASURE;
			tuneMspt[tuneIndex] = lastMspt;
			tuneIndex++;
			tuneSeconds = 0;
			if (tuneIndex >= tuneLevels.length) finishAutotune();
		}
	}

	private void finishAutotune() {
		double best = 0;
		for (int i = 0; i < tuneLevels.length; i++) {
			if (tuneMspt[i] <= cfg.tickBudgetMs) best = Math.max(best, tuneRate[i]);
		}
		int pick = cfg.maxConcurrent;
		for (int i = 0; i < tuneLevels.length; i++) {
			// Smallest setting within 8% of the best: same speed, less load on your PC.
			if (tuneMspt[i] <= cfg.tickBudgetMs && tuneRate[i] >= best * 0.92) { pick = tuneLevels[i]; break; }
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < tuneLevels.length; i++) sb.append(tuneLevels[i]).append('=').append(Math.round(tuneRate[i])).append("/s ");
		Farground.LOG.info("Auto-tune results: {}-> picked {}", sb, pick);
		setMaxConcurrent(pick);
		cfg.autoTuned = true;
		C2meBridge.Threads c2me = C2meBridge.threads();
		cfg.tunedForC2meThreads = c2me == null ? 0 : c2me.threads();
		cfg.tunedWithFastBiomes = FastBiomes.used;
		cfg.save();
		tuneRequested = false;
		tuneManual = false;
		tuneIndex = -1;
		tuneLevel = 0;
		tuneStatus = null;
		if (tuneAnnounced) broadcast("farground.tune.done", pick, Math.round(best));
		tuneAnnounced = false;
	}

	// =====================================================================================
	// Choosing chunks
	// =====================================================================================

	private boolean filling;

	/** Tops up chunks in flight. Runs every tick and the moment a chunk finishes. */
	private void fill() {
		if (stopped || filling) return;
		filling = true;
		try {
			fill0();
		} finally {
			filling = false;
		}
	}

	private void fill0() {
		if (!cfg.enabled) { waitingReason = "disabled in config"; return; }
		if (paused) { waitingReason = "paused"; return; }
		if (onBattery) { waitingReason = "paused while on battery power"; return; }
		if (memoryTight) { waitingReason = "waiting for free memory"; return; }

		if (useVoxy) {
			String wait = VoxyBridge.notReady();
			if (wait != null) { waitingReason = wait; voxyWaitTicks++; return; }
		}
		if (useDh) {
			String wait = DhBridge.notReady(ticks);
			if (wait != null) { waitingReason = wait; return; }
		}
		int cap = cap();
		String busy = tuneLevel > 0 ? "generating (tuning speed)"
				: playersMoving ? "generating (slowed while you move)"
				: afk && cfg.afkTurbo ? "generating (turbo while you are away)"
				: "generating";
		if (cap == 0) { waitingReason = "paused while you move"; return; }
		if (inFlight >= cap) { waitingReason = busy; return; }

		// 1) Area jobs come first.
		while (!areas.isEmpty() && inFlight < cap) {
			AreaJob job = areas.peekFirst();
			ServerLevel level = server.getLevel(job.dim);
			if (level == null) { areas.pollFirst(); continue; }
			if (useDh && DhBridge.busy(level)) { waitingReason = "letting Distant Horizons catch up"; return; }
			DimState state = dim(level);
			// Drop finished chunks now and then, so a huge area doesn't keep every key it ever asked for.
			if (job.requested.size() > 4096) job.requested.removeIf((LongPredicate) k -> !state.pending.contains(k));
			pump(level, state, job.spiral, cap, true, job.x, job.z, job.requested);
			if (job.spiral.finished()) {
				// Wait for this job's own chunks only. Waiting for the whole dimension to go idle
				// never ended: generation around the player starts meanwhile and keeps chunks in
				// progress, so the job was never reported done and blocked any job queued after it.
				job.requested.removeIf((LongPredicate) k -> !state.pending.contains(k));
				if (job.requested.isEmpty()) {
					areas.pollFirst();
					broadcast("farground.area.done", job.x * 16, job.z * 16, job.radius);
				} else {
					break; // wait for its last chunks
				}
			} else {
				areaStatus = new Tr.Msg("farground.area.status", job.radius, job.x * 16, job.z * 16, job.spiral.radius(), job.radius);
				waitingReason = "generating area";
				return;
			}
		}
		areaStatus = null;

		// 2) Then the area around each player.
		List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
		if (players.isEmpty()) { waitingReason = "no players"; return; }

		int viewDistance = server.getPlayerList().getViewDistance();
		int innerRadius = viewDistance + 1;
		boolean anyWork = false, anyWaitingNearby = false, anyDimensionOff = false, lodBusy = false;

		int offset = ticks % players.size();
		for (int p = 0; p < players.size() && inFlight < cap; p++) {
			ServerPlayer player = players.get((p + offset) % players.size());
			if (!(player.level() instanceof ServerLevel level)) continue;
			if (!dimensionEnabled(level)) { anyDimensionOff = true; continue; }

			if (!lodEnabledFor(level)) continue;
			if (useDh && DhBridge.busy(level)) { lodBusy = true; continue; }

			if (!nearbyLoaded(player, level, viewDistance)) {
				anyWaitingNearby = true;
				continue;
			}

			Spiral spiral = spiralFor(player, level, innerRadius);
			DimState state = dim(level);
			if (spiral.finished()) {
				if (state.pending.isEmpty()) announceDone(player, level, spiral);
				continue;
			}
			anyWork = true;
			pump(level, state, spiral, cap, false, spiral.centerX, spiral.centerZ, null);
		}
		if (anyWork || inFlight > 0) waitingReason = busy;
		else if (lodBusy) waitingReason = "letting Distant Horizons catch up";
		else if (anyWaitingNearby) waitingReason = "waiting for the chunks around you to load";
		else if (anyDimensionOff) waitingReason = "done - turned off in this dimension (settings)";
		else {
			if (!waitingReason.startsWith("done")) progress = 1.0; // don't wait for the next 5 s refresh
			waitingReason = "done - everything in range is generated";
			settled = true;
		}
	}

	/**
	 * Requests chunks from one spiral until the cap or scan budget is reached. track, if not null,
	 * collects the chunks this spiral is waiting on (its own requests, and any already in progress).
	 */
	private void pump(ServerLevel level, DimState state, Spiral spiral, int cap, boolean forceFull, int centerX, int centerZ,
	                  LongOpenHashSet track) {
		var border = level.getWorldBorder();
		int fullDetail = fullDetailFor(level);
		int budget = SCAN_BUDGET;
		while (inFlight < cap && budget-- > 0 && spiral.next(cell)) {
			int cx = cell[0], cz = cell[1];
			int d = Math.max(Math.abs(cx - centerX), Math.abs(cz - centerZ));
			boolean full = serverMode || forceFull || d <= fullDetail;
			if (state.done.isDone(cx, cz, full)) continue;
			long key = ChunkPos.pack(cx, cz);
			if (state.pending.contains(key)) {
				if (track != null) track.add(key);
				continue;
			}
			if (!border.isWithinBounds(new ChunkPos(cx, cz))) continue;
			// Terrain-only chunks well outside the full-detail ring may be left unsaved (see finish).
			request(level, state, cx, cz, key, full, !full && d > fullDetail + 1);
			if (track != null && state.pending.contains(key)) track.add(key);
		}
	}

	private void announceDone(ServerPlayer player, ServerLevel level, Spiral spiral) {
		if (!cfg.announceDone) return;
		// Once per player and dimension each time you open the world. Travelling on and filling
		// in new land later stays quiet (the corner display shows that).
		String key = player.getUUID() + "|" + level.dimension().identifier();
		if (!announced.add(key)) return;
		player.sendSystemMessage(Tr.msg("farground.done", radiusNow));
	}

	private final Map<UUID, Long> generatedAtSpiralStart = new HashMap<>();

	private long generatedSinceSpiral(ServerPlayer player) {
		return generated - generatedAtSpiralStart.getOrDefault(player.getUUID(), 0L);
	}

	private void broadcast(String key, Object... args) {
		Farground.LOG.info(Tr.en(key, args)); // also for server consoles, where nobody may be online
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			p.sendSystemMessage(Tr.msg(key, args));
		}
	}

	private final Map<UUID, Boolean> nearbyCache = new HashMap<>();

	/** True once the chunks around the player are loaded (8 sample points, checked once a second). */
	private boolean nearbyLoaded(ServerPlayer player, ServerLevel level, int viewDistance) {
		UUID uuid = player.getUUID();
		Boolean cached = nearbyCache.get(uuid);
		if (cached != null && ticks % SECOND != 0) return cached;
		if (player.tickCount < 100) {
			nearbyCache.put(uuid, false);
			return false;
		}
		ChunkPos c = player.chunkPosition();
		int r = Math.max(2, viewDistance - 2);
		int d = (int) (r * 0.7);
		int[][] points = {{r, 0}, {-r, 0}, {0, r}, {0, -r}, {d, d}, {d, -d}, {-d, d}, {-d, -d}};
		boolean ok = true;
		for (int[] p : points) {
			if (!level.hasChunk(c.x() + p[0], c.z() + p[1])) {
				ok = false;
				break;
			}
		}
		nearbyCache.put(uuid, ok);
		return ok;
	}

	private Spiral spiralFor(ServerPlayer player, ServerLevel level, int innerRadius) {
		UUID uuid = player.getUUID();
		ChunkPos pos = player.chunkPosition();
		int cx = pos.x(), cz = pos.z();
		// Flying fast: centre the search on where you will be in a few seconds.
		double[] v = velocity.get(uuid);
		if (cfg.flyAhead && v != null) {
			double speed = Math.hypot(v[0], v[1]);
			if (speed > 0.6) { // ~12 blocks/s: elytra, creative flight, fast boats
				cx = (int) Math.floor((player.getX() + v[0] * 100) / 16.0);
				cz = (int) Math.floor((player.getZ() + v[1] * 100) / 16.0);
			}
		}
		Spiral s = spirals.get(uuid);
		boolean moved = s == null
				|| spiralDims.get(uuid) != level.dimension()
				|| Math.max(Math.abs(cx - s.centerX), Math.abs(cz - s.centerZ)) >= cfg.recenterChunks;
		if (moved) {
			// Each new ring starts with the part you are looking at.
			s = new Spiral(cx, cz, innerRadius, radiusNow, () -> {
				ServerPlayer p = server.getPlayerList().getPlayer(uuid);
				return p == null ? Double.NaN : p.getYRot();
			});
			spirals.put(uuid, s);
			spiralDims.put(uuid, level.dimension());
			generatedAtSpiralStart.put(uuid, generated);
		}
		return s;
	}

	// =====================================================================================
	// Requesting and finishing chunks
	// =====================================================================================

	/** Loading-only ticket (no ticking). Times out on its own as a safety net. */
	private static final TicketType TICKET = new TicketType(20 * 60, TicketType.FLAG_LOADING);
	private static final int FULL_LEVEL = ChunkLevel.byStatus(FullChunkStatus.FULL);

	private record Held(int releaseTick, ServerChunkCache chunks, ChunkPos pos, int radius) {}
	private final ArrayDeque<Held> held = new ArrayDeque<>();

	private void releaseHeld(boolean all) {
		while (!held.isEmpty() && (all || held.peekFirst().releaseTick() <= ticks)) {
			Held h = held.pollFirst();
			h.chunks().removeTicketWithRadius(TICKET, h.pos(), h.radius());
		}
	}

	/**
	 * ServerChunkCache.getChunkFutureMainThread: the non-blocking request. The public
	 * getChunkFuture() blocks the server thread until the chunk is done when called from it;
	 * this is the private method it wraps, which only schedules the work.
	 *
	 * Called through a method handle instead of an access widener. Widening it meant Loom had to
	 * rewrite the Minecraft jar on every fresh build, which on-access antivirus scanners (seen
	 * with Bitdefender) block by holding the new jar open. A handle costs about the same as a
	 * direct call. Minecraft 26.x runs with its real names on both loaders, so the name holds.
	 */
	private static final MethodHandle CHUNK_FUTURE;
	static {
		try {
			Method m = ServerChunkCache.class.getDeclaredMethod("getChunkFutureMainThread",
					int.class, int.class, ChunkStatus.class, boolean.class);
			m.setAccessible(true);
			CHUNK_FUTURE = MethodHandles.lookup().unreflect(m);
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new IllegalStateException("Farground needs ServerChunkCache.getChunkFutureMainThread(int, int, ChunkStatus, boolean),"
					+ " which this Minecraft version doesn't have. Farground needs an update for it.", e);
		}
	}

	@SuppressWarnings("unchecked")
	private static CompletableFuture<ChunkResult<ChunkAccess>> chunkFuture(ServerChunkCache chunks, int cx, int cz, ChunkStatus target) {
		try {
			return (CompletableFuture<ChunkResult<ChunkAccess>>) CHUNK_FUTURE.invokeExact(chunks, cx, cz, target, true);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable t) {
			throw new RuntimeException(t); // it declares no checked exceptions
		}
	}

	private void request(ServerLevel level, DimState state, int cx, int cz, long key, boolean full, boolean farTerrain) {
		state.pending.add(key);
		inFlight++;
		if (firstRequestNanos == 0) firstRequestNanos = System.nanoTime();
		// Full detail: up to FEATURES (last block-placing step), or LIGHT for real lighting.
		// Far away: only the land shape.
		ChunkStatus target = serverMode ? ChunkStatus.FULL
				: !full ? TERRAIN_ONLY
				: cfg.fastLighting ? ChunkStatus.FEATURES : ChunkStatus.LIGHT;
		int ticketLevel = ChunkLevel.byStatus(target);
		ChunkPos pos = new ChunkPos(cx, cz);
		var chunks = level.getChunkSource();
		// Our own ticket keeps the chunk loading until we are done with it.
		chunks.addTicket(new Ticket(TICKET, ticketLevel), pos);
		// Schedules the work and returns without blocking the server (see CHUNK_FUTURE).
		CompletableFuture<ChunkResult<ChunkAccess>> future;
		try {
			future = chunkFuture(chunks, cx, cz, target);
		} catch (RuntimeException e) {
			// Couldn't even ask (another mod's generator threw right away): undo, so this
			// request doesn't count as "in progress" forever and slowly block all generation.
			state.pending.remove(key);
			inFlight--;
			failed++;
			chunks.removeTicketWithRadius(TICKET, pos, FULL_LEVEL - ticketLevel);
			Farground.LOG.warn("Could not request chunk {} {}", cx, cz, e);
			return;
		}
		future.whenComplete((result, error) -> {
			Runnable done = () -> {
				try {
					finish(level, state, cx, cz, key, full, farTerrain, result, error);
				} finally {
					if (cfg.holdTicks > 0) {
						held.add(new Held(ticks + cfg.holdTicks, chunks, pos, FULL_LEVEL - ticketLevel));
					} else {
						chunks.removeTicketWithRadius(TICKET, pos, FULL_LEVEL - ticketLevel);
					}
				}
			};
			if (server.isSameThread()) done.run();
			else server.execute(done);
		});
	}

	private void finish(ServerLevel level, DimState state, int cx, int cz, long key, boolean full, boolean farTerrain,
	                    ChunkResult<ChunkAccess> result, Throwable error) {
		state.pending.remove(key);
		inFlight--;
		if (stopped) return;
		if (error != null || result == null || !result.isSuccess()) {
			failed++;
			return;
		}
		ChunkAccess chunk = result.orElse(null);
		boolean realLight = full && !cfg.fastLighting;
		boolean sent = false;
		if (chunk != null) {
			// Dedicated server + Voxy Server: it voxelizes every chunk that finishes loading.
			if (serverMode && !useDh) sent = chunk instanceof LevelChunk;
			if (useVoxy) sent |= VoxyBridge.send(level, chunk, cx, cz, realLight);
			if (useDh) sent |= DhBridge.send(level, chunk);
		}
		if (sent) {
			state.done.markDone(cx, cz, full);
			generated++;
			if (full) generatedFull++;
			lastDoneNanos = System.nanoTime();
			// Don't write far terrain-only chunks to disk (see skipSaving).
			if (farTerrain && !cfg.saveFarTerrain && skipSaving(level, state, chunk, cx, cz)) {
				chunk.tryMarkSaved();
				state.stale.add(cx, cz); // an early copy of it may already be on disk
			}
			if (generated % 2000 == 0) {
				Farground.LOG.info("Sent {} chunks to " + lodName() + " this session ({} full detail, {} generating, limit {}, tick {} ms)",
						generated, generatedFull, inFlight, cap(), String.format("%.1f", lastMspt));
			}
		} else {
			failed++;
		}
		fill();
	}

	/**
	 * Whether a finished far chunk can be left unsaved. Safe only for chunks that have just the
	 * land shape: Minecraft's terrain step writes into its own chunk only, so a skipped chunk is
	 * re-created exactly the same if anyone goes there. Trees and other decorations spill into
	 * the neighbouring chunks, so a chunk is always saved when it, or any chunk next to it, has
	 * been decorated (otherwise the parts of a neighbour's trees inside it would be lost). If a
	 * neighbour gets decorated later, its trees mark this chunk as changed again, and Minecraft
	 * saves it after all.
	 */
	private static boolean skipSaving(ServerLevel level, DimState state, ChunkAccess chunk, int cx, int cz) {
		if (chunk instanceof LevelChunk || chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) return false;
		var chunkMap = level.getChunkSource().chunkMap;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				int nx = cx + dx, nz = cz + dz;
				if (state.done.isDone(nx, nz, true)) return false;
				var holder = chunkMap.getUpdatingChunkIfPresent(ChunkPos.pack(nx, nz));
				if (holder == null) continue;
				ChunkStatus s = holder.getLatestStatus();
				if (s != null && s.isOrAfter(ChunkStatus.FEATURES)) return false;
			}
		}
		return true;
	}

	// ---- Removing out-of-date early copies of far chunks from disk (see StaleChunks) ----

	private static final int STALE_CHECKS_PER_TICK = 128, STALE_MAX_READING = 512;
	/** A structure can reach 8 chunks, and its decoration 1 more. */
	private static final int STRUCTURE_REACH = 9;
	/** How far the spiral must be past a chunk: its 8-chunk tile, the one before, and the 8-chunk reach. */
	private static final int SPIRAL_PAST = 24;

	private enum StaleAction { WAIT, KEEP, CHECK }

	private void clearStaleChunks() {
		if (cfg.saveFarTerrain) return;
		for (var e : dims.entrySet()) {
			StaleChunks q = e.getValue().stale;
			if (q.isEmpty()) continue;
			ServerLevel level = server.getLevel(e.getKey());
			if (level == null) continue;
			for (int n = 0; n < STALE_CHECKS_PER_TICK && !q.isEmpty() && q.reading < STALE_MAX_READING; n++) {
				long key = q.peek();
				int cx = StaleChunks.x(key), cz = StaleChunks.z(key);
				StaleAction action = staleAction(level, cx, cz);
				if (action == StaleAction.WAIT) break;
				q.pop();
				if (action == StaleAction.CHECK) removeIfEarlyCopy(level, e.getValue(), cx, cz);
			}
		}
	}

	/** Waits until every spiral here is well past the chunk; keeps chunks close to a player. */
	private StaleAction staleAction(ServerLevel level, int cx, int cz) {
		int keepNear = server.getPlayerList().getViewDistance() + STRUCTURE_REACH + 1;
		int fullDetail = fullDetailFor(level);
		boolean anySpiral = false;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.level() != level) continue;
			ChunkPos at = p.chunkPosition();
			if (Math.max(Math.abs(cx - at.x()), Math.abs(cz - at.z())) <= keepNear) return StaleAction.KEEP;
			Spiral s = spirals.get(p.getUUID());
			if (s == null || spiralDims.get(p.getUUID()) != level.dimension()) continue;
			anySpiral = true;
			int d = Math.max(Math.abs(cx - s.centerX), Math.abs(cz - s.centerZ));
			if (d <= fullDetail + STRUCTURE_REACH) return StaleAction.KEEP;
			if (!s.finished() && d <= radiusNow + 8 && s.radius() < d + SPIRAL_PAST) return StaleAction.WAIT;
		}
		return anySpiral ? StaleAction.CHECK : StaleAction.WAIT;
	}

	/**
	 * Deletes a chunk's saved copy only if the copy itself is from before any blocks existed. Such
	 * a copy can't hold anything from a neighbour, so Minecraft re-creates it exactly.
	 */
	private void removeIfEarlyCopy(ServerLevel level, DimState state, int cx, int cz) {
		ChunkPos pos = new ChunkPos(cx, cz);
		var chunkMap = level.getChunkSource().chunkMap;
		state.stale.reading++;
		chunkMap.read(pos).whenComplete((tag, error) -> server.execute(() -> {
			state.stale.reading--;
			if (stopped || error != null || tag.isEmpty()) return;
			if (!StaleChunks.earlyStage(tag.get().getStringOr("Status", "minecraft:full"))) return;
			if (!safeToRemove(level, state, cx, cz)) return;
			chunkMap.write(pos, (net.minecraft.nbt.CompoundTag) null);
			state.stale.removed++;
		}));
	}

	private static boolean safeToRemove(ServerLevel level, DimState state, int cx, int cz) {
		int r = STRUCTURE_REACH;
		if (state.done.anyFullInSquare(cx - r, cz - r, cx + r, cz + r)) return false;
		var chunkMap = level.getChunkSource().chunkMap;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				var holder = chunkMap.getUpdatingChunkIfPresent(ChunkPos.pack(cx + dx, cz + dz));
				if (holder == null) continue;
				ChunkStatus s = holder.getLatestStatus();
				if (s != null && s.isOrAfter(ChunkStatus.FEATURES)) return false;
				if (dx == 0 && dz == 0) {
					ChunkAccess c = holder.getLatestChunk();
					if (c != null && c.isUnsaved()) return false; // about to be saved anyway
				}
			}
		}
		return true;
	}

	/** Dimensions where saved chunks were removed this session, so their files can be shrunk. */
	public List<ResourceKey<Level>> dimensionsWithRemovedChunks() {
		List<ResourceKey<Level>> out = new ArrayList<>();
		for (var e : dims.entrySet()) if (e.getValue().stale.removed > 0) out.add(e.getKey());
		return out;
	}

	// =====================================================================================
	// Progress, commands, lifecycle
	// =====================================================================================

	/**
	 * Averages the speed over the last few minutes (a plain average for the first 3 minutes, then a
	 * moving one), counting time spent slowed while you move but not time spent paused. The
	 * time-left estimate uses this, so it doesn't jump with every short slowdown or turbo burst.
	 */
	private void updateAverageRate() {
		boolean counting = !paused && !onBattery && !memoryTight && areaStatus == null && !waitingReason.startsWith("done");
		if (!counting) return;
		double weight = Math.max(1.0 / (rateSamples + 1), 5.0 / 180.0);
		averageRate = rateSamples == 0 ? recentRate : averageRate + (recentRate - averageRate) * weight;
		rateSamples++;
	}

	/** Seconds until the area around the first player is done at the recent average speed, or -1 if unknown. */
	public long secondsLeft() {
		long left = remaining;
		double rate = averageRate;
		if (left <= 0 || rateSamples < 6 || rate < 0.5) return -1; // needs 30 s of samples first
		return (long) Math.ceil(left / rate);
	}

	/** Share of the ring around the first player that is done (both qualities count). */
	private void updateProgress() {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty() || !(players.get(0).level() instanceof ServerLevel level)) return;
		Spiral s = spirals.get(players.get(0).getUUID());
		if (s == null) return;
		int r = radiusNow;
		int inner = server.getPlayerList().getViewDistance() + 1;
		DoneIndex done = dim(level).done;
		long outer = done.countInSquare(s.centerX - r, s.centerZ - r, s.centerX + r, s.centerZ + r);
		long innerDone = done.countInSquare(s.centerX - inner + 1, s.centerZ - inner + 1, s.centerX + inner - 1, s.centerZ + inner - 1);
		long total = (long) (2 * r + 1) * (2 * r + 1) - (long) (2 * inner - 1) * (2 * inner - 1);
		progress = total <= 0 ? 1 : Math.min(1.0, (outer - innerDone) / (double) total);
		remaining = total <= 0 ? 0 : Math.max(0, total - (outer - innerDone));
		if (progress < SETTLED_PROGRESS && remaining > SETTLED_REMAINING && !waitingReason.startsWith("done")) settled = false;
	}

	/** With less than this share done and this many chunks left, new land is a real job again. */
	private static final double SETTLED_PROGRESS = 0.75;
	private static final long SETTLED_REMAINING = 20_000;

	/**
	 * True when the first fill is done and Farground is just adding the strips of land that come
	 * into range as you move. Not worth a line on screen. Travelling far enough to leave a lot
	 * to do, starting an area job, pausing or tuning the speed still shows it.
	 */
	boolean onlyToppingUp() {
		var tune = tuneStatus;
		return settled && !paused && areaStatus == null
				&& (tune == null || !tune.key().equals("farground.tune.trying"));
	}

	/** Queues an area job in the given dimension. */
	public void addArea(ServerLevel level, int chunkX, int chunkZ, int radius) {
		areas.addLast(new AreaJob(level.dimension(), chunkX, chunkZ, radius));
	}

	public int cancelAreas() {
		int n = areas.size();
		areas.clear();
		areaStatus = null;
		return n;
	}

	/** Clears progress for one dimension so it gets generated again. */
	public void reset(ServerLevel level) {
		dim(level).done.clear();
		settled = false;
		restartSpirals();
	}

	/** Apply a new maximum right away instead of waiting for the lag check. */
	public void setMaxConcurrent(int max) {
		cfg.maxConcurrent = max;
		limit = max;
		fpsLimit = max;
	}

	/** Forget spirals so new settings (like radius) take effect. */
	public void restartSpirals() {
		spirals.clear();
		spiralDims.clear();
		announced.clear();
	}

	public int currentRadius(UUID player) {
		Spiral s = spirals.get(player);
		return s == null ? 0 : Math.min(s.radius(), radiusNow);
	}

	public long doneCount(ServerLevel level) {
		return dim(level).done.count();
	}

	public void saveAll() {
		for (DimState s : dims.values()) {
			s.done.saveIfDirty();
			s.stale.saveIfDirty();
		}
	}

	/**
	 * Called as the world closes. Lets chunks we already asked for finish first: Minecraft won't
	 * save and close until its chunk system is idle, and half-done requests left behind could
	 * keep it waiting forever on the "Saving world" screen. Gives up after 15 seconds.
	 */
	public void stop() {
		stopped = true;
		if (inFlight > 0 && server.isSameThread()) {
			int before = inFlight;
			long t0 = System.nanoTime();
			long deadline = t0 + 15_000_000_000L;
			server.managedBlock(() -> inFlight <= 0 || System.nanoTime() > deadline);
			Farground.LOG.info("Waited {} ms for {} chunks in progress to finish before closing ({} left)",
					(System.nanoTime() - t0) / 1_000_000, before, inFlight);
		}
		releaseHeld(true);
		saveAll();
		for (var e : dims.entrySet()) {
			StaleChunks q = e.getValue().stale;
			if (q.removed > 0 || !q.isEmpty()) {
				Farground.LOG.info("{}: removed {} out-of-date far chunks from disk, {} still waiting",
						e.getKey().identifier(), q.removed, q.size());
			}
		}
		if (useDh) DhBridge.reset();
	}
}
