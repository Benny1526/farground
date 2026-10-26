package dev.farground;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.util.function.Function;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Client-only parts: frame-rate signals, the HUD, and the singleplayer /farground command. */
public final class FargroundClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
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

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("farground", "status"), new Hud());

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
				literal("farground")
						.executes(FargroundClient::status)
						.then(literal("status").executes(FargroundClient::status))
						.then(literal("pause").executes(c -> onServer(c, g -> { g.paused = true; return Tr.tr("farground.cmd.paused"); })))
						.then(literal("resume").executes(c -> onServer(c, g -> { g.paused = false; return Tr.tr("farground.cmd.resumed"); })))
						.then(literal("radius")
								.then(literal("auto").executes(c -> onServer(c, FargroundCommands::radiusAuto)))
								.then(argument("chunks", IntegerArgumentType.integer(8, 4096))
								.executes(c -> onServer(c, g -> FargroundCommands.radius(g, IntegerArgumentType.getInteger(c, "chunks"))))))
						.then(literal("fulldetail")
								.then(literal("auto").executes(c -> onServer(c, FargroundCommands::fullDetailAuto)))
								.then(argument("chunks", IntegerArgumentType.integer(0, 4096))
								.executes(c -> onServer(c, g -> FargroundCommands.fullDetail(g, IntegerArgumentType.getInteger(c, "chunks"))))))
						.then(literal("speed").then(argument("chunks", IntegerArgumentType.integer(1, 256))
								.executes(c -> onServer(c, g -> FargroundCommands.speed(g, IntegerArgumentType.getInteger(c, "chunks"))))))
						.then(literal("movingspeed").then(argument("chunks", IntegerArgumentType.integer(0, 256))
								.executes(c -> onServer(c, g -> FargroundCommands.movingSpeed(IntegerArgumentType.getInteger(c, "chunks"))))))
						.then(literal("autotune")
								.executes(c -> onServer(c, FargroundCommands::autotune))
								.then(literal("cancel").executes(c -> onServer(c, FargroundCommands::autotuneCancel))))
						.then(literal("prune")
								.executes(c -> onServer(c, g -> FargroundCommands.prune()))
								.then(literal("cancel").executes(c -> onServer(c, g -> FargroundCommands.pruneCancel()))))
						.then(literal("hud")
								.then(literal("off").executes(c -> hud(c, FargroundConfig.HudMode.OFF)))
								.then(literal("generating").executes(c -> hud(c, FargroundConfig.HudMode.WHILE_GENERATING)))
								.then(literal("always").executes(c -> hud(c, FargroundConfig.HudMode.ALWAYS))))
						.then(literal("area")
								.then(literal("cancel").executes(c -> onServer(c, FargroundCommands::cancelAreas)))
								.then(argument("x", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer())
										.then(argument("radius", IntegerArgumentType.integer(1, 2048)).executes(c -> {
											int x = IntegerArgumentType.getInteger(c, "x");
											int z = IntegerArgumentType.getInteger(c, "z");
											int r = IntegerArgumentType.getInteger(c, "radius");
											return onServer(c, g -> {
												ServerLevel level = currentLevel();
												return level == null ? Tr.tr("farground.no_world") : FargroundCommands.area(g, level, x, z, r);
											});
										})))))
						.then(literal("reset").executes(c -> onServer(c, g -> {
							ServerLevel level = currentLevel();
							if (level != null) g.reset(level);
							return Tr.tr("farground.cmd.reset");
						})))
		));
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
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voxy")) return -1;
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
		var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
		int best = -1;
		if (loader.isModLoaded("voxy")) {
			try {
				Class<?> c = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
				Object cfg = c.getField("CONFIG").get(null);
				float sections = ((Number) cfg.getClass().getField("sectionRenderDistance").get(cfg)).floatValue();
				if (sections > 0) best = Math.max(best, Math.round(sections * 32));
			} catch (Throwable ignored) {
			}
		}
		if (loader.isModLoaded("distanthorizons")) best = Math.max(best, DhBridge.renderDistanceChunks());
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

	private static int hud(CommandContext<FabricClientCommandSource> c, FargroundConfig.HudMode mode) {
		Farground.config().hud = mode;
		Farground.config().save();
		c.getSource().sendFeedback(Tr.msg("farground.cmd.hud", Tr.tr("farground.hud_mode." + mode.name().toLowerCase())));
		return 1;
	}

	/** Runs an action on the integrated server thread and reports its message in chat. */
	private static int onServer(CommandContext<FabricClientCommandSource> c, Function<Generator, Component> action) {
		var server = Minecraft.getInstance().getSingleplayerServer();
		Generator g = Farground.generator();
		if (server == null || g == null) {
			c.getSource().sendError(notRunningReason(server));
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

	private static int status(CommandContext<FabricClientCommandSource> c) {
		var mc = Minecraft.getInstance();
		var server = mc.getSingleplayerServer();
		Generator g = Farground.generator();
		if (server == null || g == null || mc.player == null) {
			c.getSource().sendError(notRunningReason(server));
			return 0;
		}
		ServerLevel level = server.getLevel(mc.player.level().dimension());
		for (Component line : FargroundCommands.status(g, level, mc.player.getUUID())) {
			c.getSource().sendFeedback(line);
		}
		return 1;
	}
}
