package dev.farground;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A small "Farground 42% · 95/s" line in a corner of the screen. */
public final class Hud implements HudElement {
	private static final int MARGIN = 4;
	private static final int PAD = 3;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		FrameStats.frame(); // called every frame: feeds the stutter detector
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return; // (HUD elements are skipped entirely while F1 hides the GUI)
		FargroundConfig cfg = Farground.config();
		Generator gen = Farground.generator();
		if (cfg == null || gen == null || cfg.hud == FargroundConfig.HudMode.OFF) return;

		String reason = gen.waitingReason;
		boolean done = reason.startsWith("done");
		if (cfg.hud == FargroundConfig.HudMode.WHILE_GENERATING && done) return;

		String text;
		if (done) text = Tr.str("farground.hud.done");
		else if (gen.paused) text = Tr.str("farground.hud.paused");
		else if (gen.onBattery) text = Tr.str("farground.hud.battery");
		else if (gen.memoryTight) text = Tr.str("farground.hud.memory");
		else if (gen.areaStatus != null) text = Tr.str("farground.hud.area", Tr.n(gen.recentRate));
		else {
			var tune = gen.tuneStatus;
			String extra = tune != null && tune.key().equals("farground.tune.trying") ? Tr.str("farground.hud.tuning")
					: gen.playersMoving ? Tr.str("farground.hud.slowed")
					: gen.afk && cfg.afkTurbo ? Tr.str("farground.hud.turbo") : "";
			text = Tr.str("farground.hud.progress", (int) Math.floor(gen.progress * 100), Tr.n(gen.recentRate), extra);
		}

		var font = mc.font;
		int w = font.width(text) + PAD * 2;
		int h = font.lineHeight + PAD * 2 - 1;
		int sw = g.guiWidth(), sh = g.guiHeight();
		int x = switch (cfg.hudCorner) {
			case TOP_LEFT, BOTTOM_LEFT -> MARGIN;
			case TOP_RIGHT, BOTTOM_RIGHT -> sw - w - MARGIN;
		};
		int y = switch (cfg.hudCorner) {
			case TOP_LEFT, TOP_RIGHT -> MARGIN;
			case BOTTOM_LEFT, BOTTOM_RIGHT -> sh - h - MARGIN;
		};
		g.fill(x, y, x + w, y + h, 0x80000000);
		g.text(font, text, x + PAD, y + PAD, 0xFFFFFFFF, false);
	}
}
