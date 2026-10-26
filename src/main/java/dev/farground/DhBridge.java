package dev.farground;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateQueueManager;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.WorldChunkUpdateManager;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.HashMap;
import java.util.Map;

/**
 * Everything that talks to Distant Horizons. Only loaded when DH is installed.
 *
 * Finished chunks go to DH through its public API (terrainRepo.overwriteChunkDataAsync),
 * the same path DH uses for chunks Minecraft loads normally. DH builds the LODs (and lights
 * them) on its own threads.
 *
 * DH's update queue drops the furthest chunks when it is full, and Farground mostly sends far
 * chunks, so Farground waits while the queue is more than half full. Reading the queue uses DH
 * internals; if a future DH version changes them, it falls back to a fixed gentle pace.
 */
final class DhBridge {
	private DhBridge() {}

	private static final Map<ServerLevel, IDhApiLevelWrapper> wrappers = new HashMap<>();
	private static boolean internalsWork = true;
	private static boolean ownGeneratorPaused;
	private static int sentThisTick;
	private static int tickStamp;

	/** null when DH is ready to take chunks; otherwise what we are waiting for. */
	static String notReady(int tick) {
		if (tick != tickStamp) {
			tickStamp = tick;
			sentThisTick = 0;
		}
		try {
			var proxy = DhApi.Delayed.worldProxy;
			if (proxy == null || !proxy.worldLoaded() || DhApi.Delayed.terrainRepo == null) return "waiting for Distant Horizons to start";
		} catch (Throwable t) {
			return "waiting for Distant Horizons to start";
		}
		return null;
	}

	/** DH's chunk update queue for this level is more than half full (or the fallback pace is used up). */
	static boolean busy(ServerLevel level) {
		IDhApiLevelWrapper w = wrapperFor(level);
		if (w == null) return true;
		if (internalsWork) {
			try {
				ChunkUpdateQueueManager q = WorldChunkUpdateManager.INSTANCE.getByLevelWrapper((ILevelWrapper) w);
				if (q == null) return false;
				return q.getQueuedCount() > Math.max(64, q.maxSize / 2);
			} catch (Throwable t) {
				internalsWork = false;
				Farground.LOG.warn("Could not read Distant Horizons' queue ({}); using a fixed pace instead", t.toString());
			}
		}
		return sentThisTick >= 8; // about 160 chunks per second
	}

	/** Is DH keeping LODs for this dimension? */
	static boolean enabledFor(ServerLevel level) {
		return wrapperFor(level) != null;
	}

	/** Hands a generated chunk to DH. */
	static boolean send(ServerLevel level, ChunkAccess chunk) {
		IDhApiLevelWrapper w = wrapperFor(level);
		if (w == null) return false;
		try {
			var result = DhApi.Delayed.terrainRepo.overwriteChunkDataAsync(w, new Object[]{chunk, level});
			if (!result.success) {
				Farground.LOG.debug("Distant Horizons refused a chunk: {}", result.message);
				return false;
			}
			sentThisTick++;
			return true;
		} catch (Throwable t) {
			Farground.LOG.warn("Distant Horizons refused a chunk", t);
			return false;
		}
	}

	/**
	 * Pause DH's own distant generator while Farground does the work, so the two don't
	 * generate the same land twice. Uses DH's API override, which is never saved to DH's
	 * config file; clearing it gives DH back its own setting.
	 */
	static void pauseOwnGenerator(boolean pause) {
		if (pause == ownGeneratorPaused) return;
		try {
			var value = DhApi.Delayed.configs.worldGenerator().GeneratorPlan();
			if (pause) value.setValue(EDhApiGeneratorPlan.DISABLED, "Farground");
			else value.clearValue();
			ownGeneratorPaused = pause;
			Farground.LOG.info(pause ? "Paused Distant Horizons' own generator while Farground runs"
					: "Gave Distant Horizons its own generator setting back");
		} catch (Throwable t) {
			Farground.LOG.warn("Could not change Distant Horizons' generator setting: {}", t.toString());
		}
	}

	/** DH's LOD render distance in chunks, or -1. */
	static int renderDistanceChunks() {
		try {
			Integer v = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
			return v == null ? -1 : v;
		} catch (Throwable t) {
			return -1;
		}
	}

	/** Forget cached level handles (the world closed). */
	static void reset() {
		wrappers.clear();
		pauseOwnGenerator(false);
	}

	/** DH's server-side handle for this level, or null if DH hasn't loaded it. */
	private static IDhApiLevelWrapper wrapperFor(ServerLevel level) {
		IDhApiLevelWrapper cached = wrappers.get(level);
		if (cached != null) return cached;
		try {
			String name = level.dimension().identifier().toString();
			for (IDhApiLevelWrapper w : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
				if (w.getLevelType() == EDhApiLevelType.SERVER_LEVEL && name.equals(w.getDimensionName())) {
					wrappers.put(level, w);
					return w;
				}
			}
			// Fall back to matching the Minecraft level object itself.
			for (IDhApiLevelWrapper w : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
				if (w.getWrappedMcObject() == level) {
					wrappers.put(level, w);
					return w;
				}
			}
		} catch (Throwable ignored) {
			// DH not ready yet
		}
		return null;
	}
}
