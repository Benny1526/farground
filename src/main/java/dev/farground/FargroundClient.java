package dev.farground;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Client-only parts shared by Fabric and NeoForge: frame-rate signals, the singleplayer
 * /farground command, and reading the LOD mod's settings. Each loader's client entry point
 * calls {@link #init()} and registers {@link #commandTree} and the {@link Hud}.
 */
public final class FargroundClient {
	private FargroundClient() {}

	public static void init() {
		Farground.signals = new ClientSignals() {
			@Override
			public boolean active() {
				Minecraft mc = Minecraft.getInstance();
				return mc.isWindowActive() && !mc.isPaused() && mc.player != null;
			}

			@Override
			public boolean focused() {
				return Minecraft.getInstance().isWindowActive();
			}

			@Override
			public int fps() {
				return Minecraft.getInstance().getFps();
			}

			@Override
			public boolean stutter() {
				return FrameStats.recentStutter();
			}

			@Override
			public int autoFullDetailChunks(int voxelSize) {
				return autoDetail(voxelSize);
			}

			@Override
			public int lodRenderDistanceChunks() {
				return lodRenderDistance();
			}
		};
	}

	/** How each loader's client command source shows a message: normal feedback or an error. */
	public record Replies<S>(BiConsumer<S, Component> feedback, BiConsumer<S, Component> error) {}

	private static <S> LiteralArgumentBuilder<S> literal(String name) {
		return LiteralArgumentBuilder.literal(name);
	}

	private static <S> RequiredArgumentBuilder<S, Integer> argument(String name, IntegerArgumentType type) {
		return RequiredArgumentBuilder.argument(name, type);
	}

	/** The singleplayer /farground command, for any loader's client command source type. */
	public static <S> LiteralArgumentBuilder<S> commandTree(Replies<S> r) {
		return FargroundClient.<S>literal("farground")
				.executes(c -> status(c, r))
				.then(FargroundClient.<S>literal("status").executes(c -> status(c, r)))
				.then(FargroundClient.<S>literal("pause").executes(c -> onServer(c, r, g -> { g.paused = true; return Tr.tr("farground.cmd.paused"); })))
				.then(FargroundClient.<S>literal("resume").executes(c -> onServer(c, r, g -> { g.paused = false; return Tr.tr("farground.cmd.resumed"); })))
				.then(FargroundClient.<S>literal("radius")
						.then(FargroundClient.<S>literal("auto").executes(c -> onServer(c, r, FargroundCommands::radiusAuto)))
						.then(FargroundClient.<S>argument("chunks", IntegerArgumentType.integer(8, 4096))
								.executes(c -> onServer(c, r, g -> FargroundCommands.radius(g, IntegerArgumentType.getInteger(c, "chunks"))))))
				.then(FargroundClient.<S>literal("fulldetail")
						.then(FargroundClient.<S>literal("auto").executes(c -> onServer(c, r, FargroundCommands::fullDetailAuto)))
						.then(FargroundClient.<S>argument("chunks", IntegerArgumentType.integer(0, 4096))
								.executes(c -> onServer(c, r, g -> FargroundCommands.fullDetail(g, IntegerArgumentType.getInteger(c, "chunks"))))))
				.then(FargroundClient.<S>literal("speed").then(FargroundClient.<S>argument("chunks", IntegerArgumentType.integer(1, 256))
						.executes(c -> onServer(c, r, g -> FargroundCommands.speed(g, IntegerArgumentType.getInteger(c, "chunks"))))))
				.then(FargroundClient.<S>literal("movingspeed").then(FargroundClient.<S>argument("chunks", IntegerArgumentType.integer(0, 256))
						.executes(c -> onServer(c, r, g -> FargroundCommands.movingSpeed(IntegerArgumentType.getInteger(c, "chunks"))))))
				.then(FargroundClient.<S>literal("autotune")
						.executes(c -> onServer(c, r, FargroundCommands::autotune))
						.then(FargroundClient.<S>literal("cancel").executes(c -> onServer(c, r, FargroundCommands::autotuneCancel))))
				.then(FargroundClient.<S>literal("prune")
						.executes(c -> onServer(c, r, g -> FargroundCommands.prune()))
						.then(FargroundClient.<S>literal("cancel").executes(c -> onServer(c, r, g -> FargroundCommands.pruneCancel()))))
				.then(FargroundClient.<S>literal("hud")
						.then(FargroundClient.<S>literal("off").executes(c -> hud(c, r, FargroundConfig.HudMode.OFF)))
						.then(FargroundClient.<S>literal("generating").executes(c -> hud(c, r, FargroundConfig.HudMode.WHILE_GENERATING)))
						.then(FargroundClient.<S>literal("always").executes(c -> hud(c, r, FargroundConfig.HudMode.ALWAYS))))
				.then(FargroundClient.<S>literal("area")
						.then(FargroundClient.<S>literal("cancel").executes(c -> onServer(c, r, FargroundCommands::cancelAreas)))
						.then(FargroundClient.<S>argument("x", IntegerArgumentType.integer()).then(FargroundClient.<S>argument("z", IntegerArgumentType.integer())
								.then(FargroundClient.<S>argument("radius", IntegerArgumentType.integer(1, 2048)).executes(c -> {
									int x = IntegerArgumentType.getInteger(c, "x");
									int z = IntegerArgumentType.getInteger(c, "z");
									int rad = IntegerArgumentType.getInteger(c, "radius");
									return onServer(c, r, g -> {
										ServerLevel level = currentLevel();
										return level == null ? Tr.tr("farground.no_world") : FargroundCommands.area(g, level, x, z, rad);
									});
								})))))
				.then(FargroundClient.<S>literal("reset").executes(c -> onServer(c, r, g -> {
					ServerLevel level = currentLevel();
					if (level != null) g.reset(level);
					return Tr.tr("farground.cmd.reset");
				})));
	}

	/**
	 * Voxy keeps splitting a region into finer detail until it covers about subDivisionSize
	 * pixels on screen (its screen-space test). A region at detail level L is 32 * 2^L blocks
	 * wide and its blocks are 2^L blocks big, so blocks reach `voxelSize` at a distance of
	 * about voxelSize * 32 * f * sqrt(k) / subDivisionSize, where f is the focal length in
	 * pixels and k (~1.3) converts a cube's face to its projected area.
	 */
	static int autoDetail(int voxelSize) {
		// Only Voxy's detail rule is known; with Distant Horizons alone the fixed default is used.
		if (!Platform.isModLoaded("voxy")) return -1;
		try {
			Minecraft mc = Minecraft.getInstance();
			float subDiv = voxySubDivisionSize();
			int height = Math.max(1, mc.getWindow().getHeight());
			double fov = Math.toRadians(mc.options.fov().get());
			double focal = height / (2 * Math.tan(fov / 2));
			double blocks = voxelSize * 32 * focal * Math.sqrt(1.3) / subDiv;
			return (int) Math.round(blocks / 16);
		} catch (Throwable t) {
			return -1;
		}
	}

	/**
	 * The LOD mod's render distance in chunks: Voxy stores it as "sections" of 32 chunks
	 * (sectionRenderDistance), Distant Horizons in chunks. The larger one if both are installed.
	 */
	static int lodRenderDistance() {
		int best = -1;
		if (Platform.isModLoaded("voxy")) {
			try {
				Class<?> c = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
				Object cfg = c.getField("CONFIG").get(null);
				float sections = ((Number) cfg.getClass().getField("sectionRenderDistance").get(cfg)).floatValue();
				if (sections > 0) best = Math.max(best, Math.round(sections * 32));
			} catch (Throwable ignored) {
			}
		}
		if (Platform.isModLoaded("distanthorizons")) best = Math.max(best, DhBridge.renderDistanceChunks());
		return best;
	}

	/** Voxy's subDivisionSize setting (64 by default), read without depending on its classes. */
	private static float voxySubDivisionSize() {
		try {
			Class<?> c = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
			Object cfg = c.getField("CONFIG").get(null);
			return ((Number) cfg.getClass().getField("subDivisionSize").get(cfg)).floatValue();
		} catch (Throwable t) {
			return 64f;
		}
	}

	/** Why /farground can't act: no LOD mod installed, or not the host of this world. */
	private static Component notRunningReason(Object server) {
		if (server != null && !Generator.anyLodModInstalled(false)) return Tr.tr("farground.idle_no_lod");
		return Tr.tr("farground.singleplayer_only");
	}

	private static ServerLevel currentLevel() {
		var mc = Minecraft.getInstance();
		var server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) return null;
		return server.getLevel(mc.player.level().dimension());
	}

	private static <S> int hud(CommandContext<S> c, Replies<S> r, FargroundConfig.HudMode mode) {
		Farground.config().hud = mode;
		Farground.config().save();
		r.feedback().accept(c.getSource(), Tr.msg("farground.cmd.hud", Tr.tr("farground.hud_mode." + mode.name().toLowerCase())));
		return 1;
	}

	/** Runs an action on the integrated server thread and reports its message in chat. */
	private static <S> int onServer(CommandContext<S> c, Replies<S> r, Function<Generator, Component> action) {
		var server = Minecraft.getInstance().getSingleplayerServer();
		Generator g = Farground.generator();
		if (server == null || g == null) {
			r.error().accept(c.getSource(), notRunningReason(server));
			return 0;
		}
		server.execute(() -> {
			Component msg = action.apply(g);
			Minecraft.getInstance().execute(() -> {
				var p = Minecraft.getInstance().player;
				if (p != null) p.sendSystemMessage(Component.literal("[Farground] ").append(msg));
			});
		});
		return 1;
	}

	private static <S> int status(CommandContext<S> c, Replies<S> r) {
		var mc = Minecraft.getInstance();
		var server = mc.getSingleplayerServer();
		Generator g = Farground.generator();
		if (server == null || g == null || mc.player == null) {
			r.error().accept(c.getSource(), notRunningReason(server));
			return 0;
		}
		ServerLevel level = server.getLevel(mc.player.level().dimension());
		for (Component line : FargroundCommands.status(g, level, mc.player.getUUID())) {
			r.feedback().accept(c.getSource(), line);
		}
		return 1;
	}
}
