package dev.farground.neoforge;

import dev.farground.ConfigScreen;
import dev.farground.FargroundClient;
import dev.farground.Hud;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge client entry point: the HUD line, the singleplayer /farground command, and the settings screen. */
@Mod(value = "farground", dist = Dist.CLIENT)
public final class FargroundNeoForgeClient {
	public FargroundNeoForgeClient(IEventBus modBus, ModContainer container) {
		FargroundClient.init();

		Hud hud = new Hud();
		modBus.addListener((RegisterGuiLayersEvent e) ->
				e.registerAboveAll(Identifier.fromNamespaceAndPath("farground", "status"), hud::extractRenderState));

		var replies = new FargroundClient.Replies<CommandSourceStack>(
				(source, msg) -> source.sendSuccess(() -> msg, false), CommandSourceStack::sendFailure);
		NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) ->
				e.getDispatcher().register(FargroundClient.commandTree(replies)));

		// The settings screen needs Cloth Config; without it, there is just no Config button.
		if (ModList.get().isLoaded("cloth_config")) {
			container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> ConfigScreen.create(parent));
		}
	}
}
