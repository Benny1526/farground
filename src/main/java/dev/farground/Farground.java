package dev.farground;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared core (client and dedicated server, Fabric and NeoForge). Each loader's entry point
 * calls {@link #init()} once and forwards the server events to the methods below.
 *
 * Works with Voxy, Distant Horizons, or both.
 * Singleplayer / LAN host: generates chunks and hands them straight to the LOD mod.
 * Dedicated server: needs Distant Horizons, or Voxy with Voxy Server. Farground generates
 * chunks around players and the LOD mod turns them into LODs and sends them to everyone.
 */
public final class Farground {
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

	private Farground() {}

	/** Loads the settings. Call after {@link Platform#set}. */
	public static void init() {
		config = FargroundConfig.load();
	}

	public static void serverStarted(MinecraftServer server) {
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
	}

	public static void serverStopping(MinecraftServer server) {
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
	}

	public static void serverStopped(MinecraftServer server) {
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
	}

	public static void serverTick(MinecraftServer server) {
		Generator g = generator;
		if (g != null) g.tick();
	}

	/**
	 * On a dedicated server, /farground is a normal server command for operators. In
	 * singleplayer the client registers its own version instead, so only call this for
	 * dedicated servers.
	 */
	public static void registerServerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(FargroundCommands.serverTree());
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
