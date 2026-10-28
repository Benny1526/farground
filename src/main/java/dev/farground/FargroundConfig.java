package dev.farground;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings stored in config/farground.json. Every field can also be changed in the Mod Menu screen. */
public final class FargroundConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path file() {
		return Platform.configDir().resolve("farground.json");
	}

	public enum HudMode { OFF, WHILE_GENERATING, ALWAYS }
	public enum HudCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

	// ---- What to generate ----
	/** Turn generation on or off. */
	public boolean enabled = true;
	/** How far out to generate, in chunks (square around each player). */
	public int radiusChunks = 256;
	/**
	 * Generate exactly as far as the LOD mod draws: Voxy's or Distant Horizons' render distance
	 * (the larger one if both are installed). radiusChunks is used when this is off, on a
	 * dedicated server, or when the LOD mod's distance can't be read.
	 */
	public boolean autoRadius = true;
	/**
	 * Out to this distance, chunks get full detail (trees, ores, structures). Beyond it, only the
	 * land shape is generated, which is several times faster and looks the same from far away.
	 * -1 = automatic: worked out from Voxy's detail setting, your screen size and FOV, i.e. the
	 * distance where Voxy's blocks become too coarse to show trees anyway.
	 */
	public int fullDetailRadius = -1;
	/** The full-detail distance to use when automatic is off (remembered while auto is on). */
	public int manualFullDetail = 128;
	/** Which dimensions to generate in. Modded dimensions follow the Overworld switch. */
	public boolean generateOverworld = true;
	public boolean generateNether = true;
	public boolean generateEnd = true;
	/**
	 * With Distant Horizons: pause DH's own distant generator while Farground runs, so the land
	 * isn't generated twice. Not saved to DH's settings; DH gets its own setting back when
	 * Farground stops or this is turned off.
	 */
	public boolean replaceDhGenerator = true;
	/** For "automatic": the Voxy block size (in blocks) beyond which trees stop being worth it. */
	public int autoDetailVoxelSize = 4;
	/** Full detail cap in the Nether and the End (ceiling and void hide most distant detail). */
	public int otherDimensionFullDetail = 32;
	/**
	 * Skip Minecraft's lighting pass and work out sky light from the terrain height instead.
	 * Much less work per chunk; distant LODs look the same since caves are never visible from afar.
	 */
	public boolean fastLighting = true;

	// ---- Speed ----
	/** Upper limit on chunks generating at once. The real limit adapts to lag. */
	public int maxConcurrent = Math.clamp(Runtime.getRuntime().availableProcessors() * 4L, 16, 128);
	/** Most chunks at once while you move or look around (0 = pause while moving). */
	public int activeConcurrent = 6;
	/** How long (seconds) you must stand still before Farground starts speeding back up. */
	public int idleSeconds = 3;
	/** After that, how many more chunks at once it allows each second, up to maxConcurrent. */
	public int rampPerSecond = 4;
	/** Back off when your frame rate dips below its recent average. */
	public boolean frameRateAware = true;
	/** Back off when the average server tick takes longer than this. 50 ms = 20 TPS. */
	public double tickBudgetMs = 40.0;
	/** Pause while a laptop is running on battery. */
	public boolean pauseOnBattery = true;
	/** When flying fast, generate ahead of where you are heading. */
	public boolean flyAhead = true;
	/** Run faster than normal while you are away (window not focused, or not moving for a while). */
	public boolean afkTurbo = true;
	/** Seconds without moving before you count as away. */
	public int afkSeconds = 60;
	/** Pause new work while Java memory use is above this percent (0 = never). */
	public int memoryPausePercent = 90;
	/** Run the speed auto-tune once, the first time you are away. */
	public boolean autoTuneOnFirstAfk = true;
	/** Set after an auto-tune has picked maxConcurrent for this PC. */
	public boolean autoTuned = false;
	/** Start a new spiral once the player moves this many chunks from the last center. */
	public int recenterChunks = 8;
	/** Keep finished chunks loaded this many ticks so their neighbors can reuse them. */
	public int holdTicks = 200;

	// ---- Disk ----
	/**
	 * Save generated chunks you have never visited. Turning this off saves a lot of disk space,
	 * but those chunks are re-created if you go there, and trees or structures right on the edge
	 * of where you have explored can end up cut off. Experimental.
	 */
	public boolean saveUnvisitedChunks = true;

	// ---- Display ----
	public HudMode hud = HudMode.WHILE_GENERATING;
	public HudCorner hudCorner = HudCorner.TOP_LEFT;
	/** Post a chat message when everything in range has been generated. */
	public boolean announceDone = true;

	/** Bumped when defaults change in a way old config files should pick up. */
	private static final int CURRENT_VERSION = 3;
	public int configVersion = 0;

	public static FargroundConfig load() {
		FargroundConfig cfg = null;
		if (Files.exists(file())) {
			try (Reader r = Files.newBufferedReader(file())) {
				cfg = GSON.fromJson(r, FargroundConfig.class);
			} catch (Exception e) {
				Farground.LOG.warn("Could not read {}, using defaults", file(), e);
			}
		}
		if (cfg == null) cfg = new FargroundConfig();
		if (cfg.configVersion < 2) {
			// Version 1 saved a low concurrency limit from before requests were non-blocking.
			cfg.maxConcurrent = new FargroundConfig().maxConcurrent;
		}
		if (cfg.configVersion < 3 && cfg.fullDetailRadius == 128) {
			// 128 was the old fixed default; switch to automatic.
			cfg.fullDetailRadius = -1;
		}
		cfg.configVersion = CURRENT_VERSION;
		cfg.clamp();
		cfg.save();
		return cfg;
	}

	public void clamp() {
		radiusChunks = Math.clamp(radiusChunks, 8, 4096);
		fullDetailRadius = Math.clamp(fullDetailRadius, -1, 4096);
		if (fullDetailRadius >= 0) manualFullDetail = fullDetailRadius;
		manualFullDetail = Math.clamp(manualFullDetail, 0, 4096);
		autoDetailVoxelSize = Math.clamp(autoDetailVoxelSize, 1, 64);
		otherDimensionFullDetail = Math.clamp(otherDimensionFullDetail, 0, 4096);
		afkSeconds = Math.clamp(afkSeconds, 5, 3600);
		memoryPausePercent = Math.clamp(memoryPausePercent, 0, 99);
		maxConcurrent = Math.clamp(maxConcurrent, 1, 256);
		activeConcurrent = Math.clamp(activeConcurrent, 0, 256);
		idleSeconds = Math.clamp(idleSeconds, 1, 120);
		rampPerSecond = Math.clamp(rampPerSecond, 1, 256);
		tickBudgetMs = Math.clamp(tickBudgetMs, 10.0, 200.0);
		recenterChunks = Math.clamp(recenterChunks, 1, 128);
		holdTicks = Math.clamp(holdTicks, 0, 1200);
		if (hud == null) hud = HudMode.WHILE_GENERATING;
		if (hudCorner == null) hudCorner = HudCorner.TOP_LEFT;
	}

	public void save() {
		try {
			Files.createDirectories(file().getParent());
			try (Writer w = Files.newBufferedWriter(file())) {
				GSON.toJson(this, w);
			}
		} catch (IOException e) {
			Farground.LOG.warn("Could not save {}", file(), e);
		}
	}
}
