package dev.farground.fabric;

import dev.farground.FargroundClient;
import dev.farground.Hud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

/** Fabric client entry point: the HUD line and the singleplayer /farground command. */
public final class FargroundFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		FargroundClient.init();
		Hud hud = new Hud();
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("farground", "status"), hud::extractRenderState);
		var replies = new FargroundClient.Replies<FabricClientCommandSource>(
				FabricClientCommandSource::sendFeedback, FabricClientCommandSource::sendError);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) ->
				dispatcher.register(FargroundClient.commandTree(replies)));
	}
}
