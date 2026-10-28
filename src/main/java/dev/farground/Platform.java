package dev.farground;

import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * The few things the shared code needs from the mod loader (Fabric or NeoForge). Each loader's
 * entry point fills these in before anything else runs.
 */
public final class Platform {
	private static volatile Predicate<String> modLoaded = id -> false;
	private static volatile Path configDir = Path.of("config");
	private static volatile String loaderName = "unknown";

	private Platform() {}

	public static void set(String loader, Predicate<String> isModLoaded, Path config) {
		loaderName = loader;
		modLoaded = isModLoaded;
		configDir = config;
	}

	/** Whether a mod with this id is installed. */
	public static boolean isModLoaded(String id) {
		return modLoaded.test(id);
	}

	/** The game's config folder. */
	public static Path configDir() {
		return configDir;
	}

	/** "Fabric" or "NeoForge". */
	public static String loader() {
		return loaderName;
	}
}
