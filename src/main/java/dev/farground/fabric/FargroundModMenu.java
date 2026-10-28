package dev.farground.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.farground.ConfigScreen;
import net.fabricmc.loader.api.FabricLoader;

/** Mod Menu entry. The settings screen needs Cloth Config; without it, there is just no button. */
public final class FargroundModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		if (!FabricLoader.getInstance().isModLoaded("cloth-config")) return parent -> null;
		return ConfigScreen::create;
	}
}
