package dev.farground;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

import static dev.farground.Tr.n;
import static dev.farground.Tr.n1;
import static dev.farground.Tr.tr;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** The actual work behind /farground, shared by the client (singleplayer) and server commands. */
public final class FargroundCommands {
	private FargroundCommands() {}

	private static Component indent(Component c) {
		return Component.literal("  ").append(c);
	}

	/** Status lines for the given dimension. Call on the server thread. */
	public static List<Component> status(Generator g, ServerLevel level, java.util.UUID player) {
		FargroundConfig cfg = Farground.config();
		List<Component> out = new ArrayList<>();
		out.add(Component.literal("[Farground] ").append(Tr.reason(g.waitingReason)));
		if (g.areaStatus != null) out.add(indent(g.areaStatus.component()));
		out.add(indent(tr("farground.status.progress", n(g.progress * 100), g.radiusNow,
				g.radiusIsAuto ? tr("farground.status.radius_auto", g.lodName()) : "",
				player == null ? 0 : g.currentRadius(player), level == null ? 0 : g.doneCount(level))));
		out.add(indent(tr("farground.status.session", g.generated, g.generatedFull, g.failed, n(g.recentRate))));
		out.add(indent(tr("farground.status.limits", g.lastInFlight, g.lastLimit, cfg.maxConcurrent, cfg.activeConcurrent, n1(g.lastMspt))));
		Object auto = cfg.fullDetailRadius >= 0 ? ""
				: net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voxy") ? tr("farground.status.auto_voxy")
				: tr("farground.status.auto");
		out.add(indent(tr("farground.status.full_detail", level == null ? g.fullDetailNow : g.fullDetailFor(level), auto,
				g.stutterBackoffs > 0 ? tr("farground.status.stutters", g.stutterBackoffs) : "")));
		if (g.afk && cfg.afkTurbo) out.add(indent(tr("farground.status.turbo")));
		if (g.memoryTight) out.add(indent(tr("farground.status.memory", cfg.memoryPausePercent)));
		if (g.tuneStatus != null) out.add(indent(g.tuneStatus.component()));
		if (g.onBattery) out.add(indent(tr("farground.status.battery")));
		if (Farground.pruneRequested) out.add(indent(tr("farground.status.prune_scheduled")));
		if (Farground.lastPrune != null) out.add(indent(Farground.lastPrune.component()));
		return out;
	}

	public static Component autotune(Generator g) {
		g.requestAutotune();
		return tr("farground.cmd.autotune");
	}

	public static Component autotuneCancel(Generator g) {
		g.cancelAutotune();
		return tr("farground.cmd.autotune_cancel");
	}

	public static Component prune() {
		Farground.pruneRequested = true;
		return tr("farground.cmd.prune");
	}

	public static Component pruneCancel() {
		boolean was = Farground.pruneRequested;
		Farground.pruneRequested = false;
		return tr(was ? "farground.cmd.prune_cancel" : "farground.cmd.prune_none");
	}

	public static Component radius(Generator g, int r) {
		Farground.config().radiusChunks = r;
		Farground.config().autoRadius = false;
		Farground.config().save();
		g.restartSpirals();
		return tr("farground.cmd.radius", r);
	}

	public static Component radiusAuto(Generator g) {
		Farground.config().autoRadius = true;
		Farground.config().save();
		g.refreshRadiusNow();
		return g.radiusIsAuto ? tr("farground.cmd.radius_auto", g.radiusNow) : tr("farground.cmd.radius_auto_unknown", g.radiusNow);
	}

	public static Component fullDetail(Generator g, int r) {
		Farground.config().fullDetailRadius = r;
		Farground.config().manualFullDetail = r;
		Farground.config().save();
		g.restartSpirals();
		return tr("farground.cmd.fulldetail", r);
	}

	public static Component fullDetailAuto(Generator g) {
		Farground.config().fullDetailRadius = -1;
		Farground.config().save();
		g.restartSpirals();
		return tr("farground.cmd.fulldetail_auto");
	}

	public static Component speed(Generator g, int n) {
		g.setMaxConcurrent(n);
		Farground.config().save();
		return tr("farground.cmd.speed", n);
	}

	public static Component movingSpeed(int n) {
		Farground.config().activeConcurrent = n;
		Farground.config().save();
		return n == 0 ? tr("farground.cmd.movingspeed_pause") : tr("farground.cmd.movingspeed", n);
	}

	public static Component area(Generator g, ServerLevel level, int blockX, int blockZ, int radius) {
		g.addArea(level, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16), radius);
		return tr("farground.cmd.area", radius, blockX, blockZ);
	}

	public static Component cancelAreas(Generator g) {
		int n = g.cancelAreas();
		return n == 0 ? tr("farground.cmd.area_none") : tr("farground.cmd.area_cancel", n);
	}

	// ---- Dedicated-server command tree (operators only) ----

	public static LiteralArgumentBuilder<CommandSourceStack> serverTree() {
		return literal("farground")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(FargroundCommands::serverStatus)
				.then(literal("status").executes(FargroundCommands::serverStatus))
				.then(literal("pause").executes(c -> run(c, g -> { g.paused = true; return tr("farground.cmd.paused"); })))
				.then(literal("resume").executes(c -> run(c, g -> { g.paused = false; return tr("farground.cmd.resumed"); })))
				.then(literal("radius").then(argument("chunks", IntegerArgumentType.integer(8, 4096))
						.executes(c -> run(c, g -> radius(g, IntegerArgumentType.getInteger(c, "chunks"))))))
				.then(literal("speed").then(argument("chunks", IntegerArgumentType.integer(1, 256))
						.executes(c -> run(c, g -> speed(g, IntegerArgumentType.getInteger(c, "chunks"))))))
				.then(literal("prune")
						.executes(c -> run(c, g -> prune()))
						.then(literal("cancel").executes(c -> run(c, g -> pruneCancel()))))
				.then(literal("area")
						.then(literal("cancel").executes(c -> run(c, FargroundCommands::cancelAreas)))
						.then(argument("x", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer())
								.then(argument("radius", IntegerArgumentType.integer(1, 2048)).executes(c -> run(c, g -> area(g,
										c.getSource().getLevel(),
										IntegerArgumentType.getInteger(c, "x"),
										IntegerArgumentType.getInteger(c, "z"),
										IntegerArgumentType.getInteger(c, "radius"))))))));
	}

	private interface Action { Component apply(Generator g); }

	private static int run(CommandContext<CommandSourceStack> c, Action a) {
		Generator g = Farground.generator();
		if (g == null) {
			c.getSource().sendFailure(tr("farground.not_running"));
			return 0;
		}
		Component msg = a.apply(g);
		c.getSource().sendSuccess(() -> Component.literal("[Farground] ").append(msg), true);
		return 1;
	}

	private static int serverStatus(CommandContext<CommandSourceStack> c) {
		Generator g = Farground.generator();
		if (g == null) {
			c.getSource().sendFailure(tr("farground.not_running"));
			return 0;
		}
		var src = c.getSource();
		java.util.UUID id = src.isPlayer() ? src.getPlayer().getUUID() : null;
		for (Component line : status(g, src.getLevel(), id)) src.sendSuccess(() -> line, false);
		return 1;
	}
}
