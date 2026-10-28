package dev.farground.neoforge;

import dev.farground.Farground;
import dev.farground.Platform;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** NeoForge entry point (client and dedicated server): hands NeoForge's events to the shared core. */
@Mod("farground")
public final class FargroundNeoForge {
	public FargroundNeoForge(IEventBus modBus) {
		Platform.set("NeoForge", id -> ModList.get().isLoaded(id), FMLPaths.CONFIGDIR.get());
		Farground.init();

		IEventBus bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> Farground.serverStarted(e.getServer()));
		bus.addListener((ServerStoppingEvent e) -> Farground.serverStopping(e.getServer()));
		bus.addListener((ServerStoppedEvent e) -> Farground.serverStopped(e.getServer()));
		bus.addListener((ServerTickEvent.Post e) -> Farground.serverTick(e.getServer()));
		bus.addListener((RegisterCommandsEvent e) -> {
			if (e.getCommandSelection() == Commands.CommandSelection.DEDICATED) Farground.registerServerCommands(e.getDispatcher());
		});
	}
}
