package dev.farground;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared entry point (client and dedicated server).
 *
 * Works with Voxy, Distant Horizons, or both.
 * Singleplayer / LAN host: generates chunks and hands them straight to the LOD mod.
 * Dedicated server: needs Distant Horizons, or Voxy with Voxy Server. Farground generates
 * chunks around players and the LOD mod turns them into LODs and sends them to everyone.
 */
public final class Farground implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("Farground");

	private static FargroundConfig config;
	private static volatile Generator generator;
	/** Set by the client entry point; stays null on a dedicated server. */
	public static volatile ClientSignals signals;

	/** Set by /farground prune; the prune runs when the world closes. */
	public static volatile boolean pruneRequested;
	/** Summary of the last prune in this game session, shown in /farground status. */
	public static volatile Tr.Msg lastPrune;
	private static Path pruneRoot;
	private static List<Path> pruneDirs;

	@Override
	public void onInitialize() {
		config = FargroundConfig.load();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			boolean serverMode = server.isDedicatedServer();
			if (!Generator.anyLodModInstalled(serverMode)) {
				LOG.info(serverMode
						? "Farground is idle on this dedicated server: install Distant Horizons, or Voxy with Voxy Server, so the LODs reach players."
						: "Farground is idle: install Voxy or Distant Horizons to see the distant terrain it generates.");
				return;
			}
			generator = new Generator(server, config, serverMode);
			LOG.info("Farground started ({} mode, for {}, radius {} chunks, full detail {} chunks, up to {} at once)",
					serverMode ? "server" : "singleplayer", generator.lodName(), config.radiusChunks, config.fullDetailRadius, config.maxConcurrent);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			Generator g = generator;
			generator = null;
			if (g != null) g.stop();
			if (pruneRequested) {
				// Remember where the chunk files are; the prune itself runs once everything is saved.
				pruneRoot = server.getWorldPath(LevelResource.ROOT);
				pruneDirs = new ArrayList<>();
				for (ServerLevel level : server.getAllLevels()) {
					pruneDirs.add(DimensionType.getStorageFolder(level.dimension(), pruneRoot).resolve("region"));
				}
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			if (!pruneRequested || pruneRoot == null) return;
			pruneRequested = false;
			try {
				long t = System.nanoTime();
				Pruner.Result r = Pruner.prune(pruneRoot, pruneDirs);
				lastPrune = new Tr.Msg("farground.prune.result", r.chunksRemoved(), r.filesChanged(),
						Tr.n(r.bytesBefore() / 1e6), Tr.n(r.bytesAfter() / 1e6), pruneRoot.relativize(r.backup()).toString());
				LOG.info("{} in {} ms", lastPrune.english(), (System.nanoTime() - t) / 1_000_000);
			} catch (Exception e) {
				lastPrune = new Tr.Msg("farground.prune.failed", String.valueOf(e.getMessage()));
				LOG.error("Prune failed", e);
			} finally {
				pruneRoot = null;
				pruneDirs = null;
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Generator g = generator;
			if (g != null) g.tick();
		});

		// On a dedicated server, /farground is a normal server command for operators.
		// In singleplayer the client registers its own version instead.
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
			if (environment != Commands.CommandSelection.DEDICATED) return;
			dispatcher.register(FargroundCommands.serverTree());
		});
	}

	/** The loaded settings. */
	public static FargroundConfig config() {
		return config;
	}

	/** The running generator, or null when no world is open. */
	public static Generator generator() {
		return generator;
	}
}
