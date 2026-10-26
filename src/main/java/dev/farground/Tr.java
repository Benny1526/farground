package dev.farground;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Translations. All text lives in assets/farground/lang/<language>.json; en_us.json is the
 * source. Messages carry the English text as a fallback, so players without Farground (for
 * example on a dedicated server) still read English instead of raw keys.
 */
public final class Tr {
	private Tr() {}

	private static final Map<String, String> EN = loadEnglish();

	private static Map<String, String> loadEnglish() {
		try (InputStream in = Tr.class.getResourceAsStream("/assets/farground/lang/en_us.json")) {
			if (in == null) return Map.of();
			Map<String, String> m = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					new TypeToken<Map<String, String>>() {}.getType());
			return m == null ? Map.of() : m;
		} catch (Exception e) {
			return Map.of();
		}
	}

	/** A message in the player's language, with English as the fallback. */
	public static MutableComponent tr(String key, Object... args) {
		return Component.translatableWithFallback(key, EN.getOrDefault(key, key), args);
	}

	/** "[Farground] " + message. */
	public static MutableComponent msg(String key, Object... args) {
		return Component.literal("[Farground] ").append(tr(key, args));
	}

	/** English text, for the log. */
	public static String en(String key, Object... args) {
		return format(EN.getOrDefault(key, key), args);
	}

	/** Text in the game's current language as a plain string (client side: HUD, tooltips). */
	public static String str(String key, Object... args) {
		String pattern;
		try {
			pattern = Language.getInstance().getOrDefault(key, EN.getOrDefault(key, key));
		} catch (Throwable t) {
			pattern = EN.getOrDefault(key, key);
		}
		return format(pattern, args);
	}

	/** Minecraft-style "%s" / "%1$s" / "%%" formatting. Numbers should be passed already formatted. */
	private static String format(String pattern, Object... args) {
		if (args.length == 0) return pattern.replace("%%", "%");
		Object[] plain = new Object[args.length];
		for (int i = 0; i < args.length; i++) {
			plain[i] = args[i] instanceof Component c ? c.getString() : String.valueOf(args[i]);
		}
		try {
			return String.format(Locale.ROOT, pattern, plain);
		} catch (Exception e) {
			return pattern;
		}
	}

	/** One decimal-free number, like "95". */
	public static String n(double v) {
		return String.format(Locale.ROOT, "%.0f", v);
	}

	/** One decimal, like "12.5". */
	public static String n1(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}

	// ---- Status reasons ----
	// The generator keeps its status as a fixed English phrase (used in its logic and the log);
	// it is translated only when shown.

	private static final Map<String, String> REASONS = new HashMap<>();

	static {
		reason("starting", "starting");
		reason("disabled in config", "disabled");
		reason("paused", "paused");
		reason("paused while on battery power", "battery");
		reason("waiting for free memory", "memory");
		reason("waiting for Voxy to start", "voxy_start");
		reason("letting Voxy catch up", "voxy_busy");
		reason("waiting for Distant Horizons to start", "dh_start");
		reason("letting Distant Horizons catch up", "dh_busy");
		reason("paused while you move", "paused_moving");
		reason("generating", "generating");
		reason("generating (tuning speed)", "generating_tuning");
		reason("generating (slowed while you move)", "generating_moving");
		reason("generating (turbo while you are away)", "generating_turbo");
		reason("generating area", "generating_area");
		reason("no players", "no_players");
		reason("waiting for the chunks around you to load", "waiting_nearby");
		reason("done - turned off in this dimension (settings)", "done_dimension_off");
		reason("done - everything in range is generated", "done");
	}

	private static void reason(String english, String id) {
		REASONS.put(english, "farground.reason." + id);
	}

	/** The generator's status, translated. */
	public static MutableComponent reason(String english) {
		String key = REASONS.get(english);
		return key == null ? Component.literal(english) : tr(key);
	}

	/** A message stored as key + arguments, so it can be shown in each player's language. */
	public record Msg(String key, Object... args) {
		public MutableComponent component() {
			return tr(key, args);
		}

		public String english() {
			return en(key, args);
		}
	}
}
