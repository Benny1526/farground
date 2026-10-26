package dev.farground;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.Requirement;
import me.shedaniel.clothconfig2.gui.entries.BooleanListEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The settings screen shown from Mod Menu (built with Cloth Config).
 *
 * Every entry has a hover tooltip: what it does, its default, then a colour-coded performance
 * impact (Low / Medium / High) that follows the value currently picked. All text comes from
 * the language files (farground.config.*).
 */
final class ConfigScreen {
	private ConfigScreen() {}

	private enum Impact {
		LOW("low", ChatFormatting.GREEN),
		MEDIUM("medium", ChatFormatting.YELLOW),
		HIGH("high", ChatFormatting.RED);

		final String id;
		final ChatFormatting color;

		Impact(String id, ChatFormatting color) {
			this.id = id;
			this.color = color;
		}
	}

	private static final String P = "farground.config.";

	private static Component label(String id) {
		return Tr.tr(P + id);
	}

	private static Component note(String key) {
		return Tr.tr(key).withStyle(ChatFormatting.GRAY);
	}

	/**
	 * Tooltip: the setting's description (one line per "\n"), its default if given, a blank
	 * line, then "Performance impact: High" in colour and a short reason.
	 */
	private static Optional<Component[]> tip(String id, Object def, Impact impact, String whyKey, Object... args) {
		List<Component> out = new ArrayList<>();
		for (String line : Tr.str(P + id + ".tooltip", args).split("\n")) out.add(Component.literal(line));
		if (def != null) out.add(Component.literal(Tr.str(P + "default", def)));
		out.add(Component.empty());
		out.add(Component.literal(Tr.str(P + "impact", "")).withStyle(ChatFormatting.GRAY)
				.append(Tr.tr(P + "impact." + impact.id).withStyle(impact.color, ChatFormatting.BOLD)));
		if (whyKey != null) out.add(note(P + whyKey));
		return Optional.of(out.toArray(Component[]::new));
	}

	private static Impact byValue(int v, int lowUpTo, int mediumUpTo) {
		return v <= lowUpTo ? Impact.LOW : v <= mediumUpTo ? Impact.MEDIUM : Impact.HIGH;
	}

	private static Component num(int v) {
		return Component.literal(String.valueOf(v));
	}

	private static Component unit(String id, Object... args) {
		return Tr.tr(P + "unit." + id, args);
	}

	private static String duration(int s) {
		if (s < 60) return Tr.str(P + "unit.seconds", s);
		if (s % 60 == 0) return Tr.str(P + "unit.minutes", s / 60);
		return Tr.str(P + "unit.minutes_seconds", s / 60, s % 60);
	}

	static Screen create(Screen parent) {
		FargroundConfig c = Farground.config();
		FargroundConfig d = new FargroundConfig(); // defaults
		ConfigBuilder b = ConfigBuilder.create().setParentScreen(parent).setTitle(label("title"));
		ConfigEntryBuilder e = b.entryBuilder();

		// ---------------------------------------------------------------- Distance
		ConfigCategory dist = b.getOrCreateCategory(label("category.distance"));
		dist.addEntry(e.startTextDescription(note(P + "distance.intro")).build());

		dist.addEntry(e.startBooleanToggle(label("enabled"), c.enabled).setDefaultValue(d.enabled)
				.setTooltipSupplier(v -> tip("enabled", null, v ? Impact.MEDIUM : Impact.LOW, v ? "enabled.why.on" : "enabled.why.off"))
				.setSaveConsumer(v -> c.enabled = v).build());

		int lodDistance = FargroundClient.lodRenderDistance();
		BooleanListEntry autoRadius = e.startBooleanToggle(label("auto_radius"), c.autoRadius).setDefaultValue(d.autoRadius)
				.setTooltipSupplier(v -> {
					var t = tip("auto_radius", null, Impact.LOW, "auto_radius.why");
					if (lodDistance > 0) {
						Component[] lines = t.get();
						List<Component> withNow = new ArrayList<>(List.of(lines));
						withNow.add(3, Component.literal(Tr.str(P + "auto_radius.now", lodDistance)));
						return Optional.of(withNow.toArray(Component[]::new));
					}
					return t;
				})
				.setSaveConsumer(v -> c.autoRadius = v).build();
		dist.addEntry(autoRadius);

		dist.addEntry(e.startIntSlider(label("radius"), c.radiusChunks, 32, 1024).setDefaultValue(d.radiusChunks)
				.setTextGetter(ConfigScreen::num)
				.setTooltipSupplier(v -> tip("radius", d.radiusChunks, byValue(v, 192, 384), "radius.why"))
				.setRequirement(Requirement.isFalse(autoRadius))
				.setSaveConsumer(v -> c.radiusChunks = v).build());

		// The toggle and the slider both decide fullDetailRadius; it is set once both have saved.
		boolean[] autoChosen = {c.fullDetailRadius < 0};
		BooleanListEntry auto = e.startBooleanToggle(label("auto_full_detail"), c.fullDetailRadius < 0)
				.setDefaultValue(d.fullDetailRadius < 0)
				.setTooltipSupplier(v -> tip("auto_full_detail", null, Impact.LOW, "auto_full_detail.why", d.manualFullDetail))
				.setSaveConsumer(v -> autoChosen[0] = v).build();
		dist.addEntry(auto);

		dist.addEntry(e.startIntSlider(label("full_detail"), c.manualFullDetail, 0, 512).setDefaultValue(d.manualFullDetail)
				.setTextGetter(ConfigScreen::num)
				.setTooltipSupplier(v -> tip("full_detail", d.manualFullDetail, byValue(v, 64, 192), "full_detail.why"))
				.setRequirement(Requirement.isFalse(auto))
				.setSaveConsumer(v -> c.manualFullDetail = v).build());

		dist.addEntry(e.startIntSlider(label("auto_voxel"), c.autoDetailVoxelSize, 1, 16)
				.setDefaultValue(d.autoDetailVoxelSize)
				.setTextGetter(v -> unit(v == 1 ? "block" : "blocks", v))
				.setTooltipSupplier(v -> tip("auto_voxel", Tr.str(P + "unit.blocks", d.autoDetailVoxelSize),
						v >= 4 ? Impact.LOW : v >= 2 ? Impact.MEDIUM : Impact.HIGH, "auto_voxel.why"))
				.setRequirement(Requirement.isTrue(auto))
				.setSaveConsumer(v -> c.autoDetailVoxelSize = v).build());

		dist.addEntry(e.startIntSlider(label("other_dim"), c.otherDimensionFullDetail, 0, 256)
				.setDefaultValue(d.otherDimensionFullDetail).setTextGetter(ConfigScreen::num)
				.setTooltipSupplier(v -> tip("other_dim", d.otherDimensionFullDetail, byValue(v, 32, 96), null))
				.setSaveConsumer(v -> c.otherDimensionFullDetail = v).build());

		dist.addEntry(e.startBooleanToggle(label("gen_overworld"), c.generateOverworld).setDefaultValue(d.generateOverworld)
				.setTooltipSupplier(v -> tip("gen_overworld", null, v ? Impact.MEDIUM : Impact.LOW, v ? "gen.why" : null))
				.setSaveConsumer(v -> c.generateOverworld = v).build());
		dist.addEntry(e.startBooleanToggle(label("gen_nether"), c.generateNether).setDefaultValue(d.generateNether)
				.setTooltipSupplier(v -> tip("gen_nether", null, v ? Impact.MEDIUM : Impact.LOW, v ? "gen.why" : null))
				.setSaveConsumer(v -> c.generateNether = v).build());
		dist.addEntry(e.startBooleanToggle(label("gen_end"), c.generateEnd).setDefaultValue(d.generateEnd)
				.setTooltipSupplier(v -> tip("gen_end", null, v ? Impact.MEDIUM : Impact.LOW, v ? "gen.why" : null))
				.setSaveConsumer(v -> c.generateEnd = v).build());

		dist.addEntry(e.startBooleanToggle(label("pause_dh"), c.replaceDhGenerator).setDefaultValue(d.replaceDhGenerator)
				.setTooltipSupplier(v -> tip("pause_dh", null, v ? Impact.LOW : Impact.HIGH, v ? "pause_dh.why.on" : "pause_dh.why.off"))
				.setSaveConsumer(v -> c.replaceDhGenerator = v).build());

		dist.addEntry(e.startBooleanToggle(label("fast_lighting"), c.fastLighting).setDefaultValue(d.fastLighting)
				.setTooltipSupplier(v -> tip("fast_lighting", null, v ? Impact.LOW : Impact.MEDIUM,
						v ? "fast_lighting.why.on" : "fast_lighting.why.off"))
				.setSaveConsumer(v -> c.fastLighting = v).build());

		// ---------------------------------------------------------------- Speed
		ConfigCategory speed = b.getOrCreateCategory(label("category.speed"));
		speed.addEntry(e.startTextDescription(note(P + "speed.intro")).build());

		speed.addEntry(e.startIntSlider(label("max_concurrent"), c.maxConcurrent, 1, 256).setDefaultValue(d.maxConcurrent)
				.setTextGetter(ConfigScreen::num)
				.setTooltipSupplier(v -> tip("max_concurrent", d.maxConcurrent, byValue(v, 32, 96), "max_concurrent.why"))
				.setSaveConsumer(v -> c.maxConcurrent = v).build());

		speed.addEntry(e.startIntSlider(label("moving"), c.activeConcurrent, 0, 64).setDefaultValue(d.activeConcurrent)
				.setTextGetter(v -> v == 0 ? unit("pause_moving") : num(v))
				.setTooltipSupplier(v -> tip("moving", d.activeConcurrent, byValue(v, 8, 24), "moving.why"))
				.setSaveConsumer(v -> c.activeConcurrent = v).build());

		speed.addEntry(e.startIntSlider(label("idle"), c.idleSeconds, 1, 30).setDefaultValue(d.idleSeconds)
				.setTextGetter(v -> unit("seconds", v))
				.setTooltipSupplier(v -> tip("idle", duration(d.idleSeconds), v >= 3 ? Impact.LOW : Impact.MEDIUM, "idle.why"))
				.setSaveConsumer(v -> c.idleSeconds = v).build());

		speed.addEntry(e.startIntSlider(label("ramp"), c.rampPerSecond, 1, 64).setDefaultValue(d.rampPerSecond)
				.setTextGetter(v -> Component.literal("+" + v))
				.setTooltipSupplier(v -> tip("ramp", "+" + d.rampPerSecond, byValue(v, 4, 16), "ramp.why"))
				.setSaveConsumer(v -> c.rampPerSecond = v).build());

		speed.addEntry(e.startBooleanToggle(label("frame_aware"), c.frameRateAware).setDefaultValue(d.frameRateAware)
				.setTooltipSupplier(v -> tip("frame_aware", null, v ? Impact.LOW : Impact.HIGH,
						v ? "frame_aware.why.on" : "frame_aware.why.off"))
				.setSaveConsumer(v -> c.frameRateAware = v).build());

		speed.addEntry(e.startIntSlider(label("tick_budget"), (int) Math.round(c.tickBudgetMs), 20, 100)
				.setDefaultValue((int) Math.round(d.tickBudgetMs)).setTextGetter(v -> unit("ms", v))
				.setTooltipSupplier(v -> tip("tick_budget", Tr.str(P + "unit.ms", Math.round(d.tickBudgetMs)),
						byValue(v, 40, 60), v > 50 ? "tick_budget.why" : null))
				.setSaveConsumer(v -> c.tickBudgetMs = v).build());

		speed.addEntry(e.startBooleanToggle(label("turbo"), c.afkTurbo).setDefaultValue(d.afkTurbo)
				.setTooltipSupplier(v -> tip("turbo", null, v ? Impact.MEDIUM : Impact.LOW, v ? "turbo.why" : null))
				.setSaveConsumer(v -> c.afkTurbo = v).build());

		speed.addEntry(e.startIntSlider(label("away_after"), c.afkSeconds, 10, 600).setDefaultValue(d.afkSeconds)
				.setTextGetter(v -> Component.literal(duration(v)))
				.setTooltipSupplier(v -> tip("away_after", duration(d.afkSeconds), v >= 30 ? Impact.LOW : Impact.MEDIUM,
						v < 30 ? "away_after.why" : null))
				.setSaveConsumer(v -> c.afkSeconds = v).build());

		speed.addEntry(e.startBooleanToggle(label("tune_first"), c.autoTuneOnFirstAfk).setDefaultValue(d.autoTuneOnFirstAfk)
				.setTooltipSupplier(v -> tip("tune_first", null, v ? Impact.MEDIUM : Impact.LOW, v ? "tune.why" : null))
				.setSaveConsumer(v -> c.autoTuneOnFirstAfk = v).build());

		speed.addEntry(e.startBooleanToggle(label("tune_again"), false).setDefaultValue(false)
				.setTooltipSupplier(v -> tip("tune_again", null, v ? Impact.MEDIUM : Impact.LOW, v ? "tune.why" : null))
				.setSaveConsumer(v -> { if (v) c.autoTuned = false; }).build());

		speed.addEntry(e.startIntSlider(label("memory"), c.memoryPausePercent, 0, 99).setDefaultValue(d.memoryPausePercent)
				.setTextGetter(v -> v == 0 ? unit("never") : unit("percent", v))
				.setTooltipSupplier(v -> tip("memory", Tr.str(P + "unit.percent", d.memoryPausePercent),
						v == 0 || v > 95 ? Impact.MEDIUM : Impact.LOW, v == 0 ? "memory.why.off" : "memory.why.on"))
				.setSaveConsumer(v -> c.memoryPausePercent = v).build());

		speed.addEntry(e.startBooleanToggle(label("battery"), c.pauseOnBattery).setDefaultValue(d.pauseOnBattery)
				.setTooltipSupplier(v -> tip("battery", null, v ? Impact.LOW : Impact.MEDIUM, v ? null : "battery.why.off"))
				.setSaveConsumer(v -> c.pauseOnBattery = v).build());

		speed.addEntry(e.startBooleanToggle(label("fly_ahead"), c.flyAhead).setDefaultValue(d.flyAhead)
				.setTooltipSupplier(v -> tip("fly_ahead", null, Impact.LOW, null))
				.setSaveConsumer(v -> c.flyAhead = v).build());

		// ---------------------------------------------------------------- Disk
		ConfigCategory disk = b.getOrCreateCategory(label("category.disk"));
		disk.addEntry(e.startBooleanToggle(label("save_unvisited"), c.saveUnvisitedChunks).setDefaultValue(d.saveUnvisitedChunks)
				.setTooltipSupplier(v -> tip("save_unvisited", null, Impact.LOW, v ? "save_unvisited.why.on" : "save_unvisited.why.off"))
				.setSaveConsumer(v -> c.saveUnvisitedChunks = v).build());
		disk.addEntry(e.startBooleanToggle(label("prune"), Farground.pruneRequested).setDefaultValue(false)
				.setTooltipSupplier(v -> tip("prune", null, Impact.LOW, v ? "prune.why" : null))
				.setSaveConsumer(v -> Farground.pruneRequested = v).build());

		// ---------------------------------------------------------------- Display
		ConfigCategory display = b.getOrCreateCategory(label("category.display"));
		display.addEntry(e.startEnumSelector(label("hud"), FargroundConfig.HudMode.class, c.hud).setDefaultValue(d.hud)
				.setEnumNameProvider(m -> Tr.tr("farground.hud_mode." + m.name().toLowerCase()))
				.setTooltipSupplier(v -> tip("hud", null, Impact.LOW, null))
				.setSaveConsumer(v -> c.hud = v).build());
		display.addEntry(e.startEnumSelector(label("corner"), FargroundConfig.HudCorner.class, c.hudCorner).setDefaultValue(d.hudCorner)
				.setEnumNameProvider(m -> Tr.tr("farground.corner." + m.name().toLowerCase()))
				.setTooltipSupplier(v -> tip("corner", null, Impact.LOW, null))
				.setSaveConsumer(v -> c.hudCorner = v).build());
		display.addEntry(e.startBooleanToggle(label("announce"), c.announceDone).setDefaultValue(d.announceDone)
				.setTooltipSupplier(v -> tip("announce", null, Impact.LOW, null))
				.setSaveConsumer(v -> c.announceDone = v).build());

		b.setSavingRunnable(() -> {
			c.fullDetailRadius = autoChosen[0] ? -1 : c.manualFullDetail;
			c.clamp();
			c.save();
			// Apply live in a running world.
			Generator g = Farground.generator();
			var server = Minecraft.getInstance().getSingleplayerServer();
			if (g != null && server != null) {
				server.execute(() -> {
					g.setMaxConcurrent(c.maxConcurrent);
					g.refreshRadiusNow();
					g.restartSpirals();
				});
			}
		});
		return b.build();
	}
}
