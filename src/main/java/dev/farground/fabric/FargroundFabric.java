package dev.farground.fabric;

import dev.farground.Farground;
import dev.farground.Platform;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;

/** Fabric entry point (client and dedicated server): hands Fabric's events to the shared core. */
public final class FargroundFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		FabricLoader loader = FabricLoader.getInstance();
		Platform.set("Fabric", loader::isModLoaded, loader.getConfigDir());
		Farground.init();

		ServerLifecycleEvents.SERVER_STARTED.register(Farground::serverStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(Farground::serverStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(Farground::serverStopped);
		ServerTickEvents.END_SERVER_TICK.register(Farground::serverTick);
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
			if (environment == Commands.CommandSelection.DEDICATED) Farground.registerServerCommands(dispatcher);
		});
	}
}
