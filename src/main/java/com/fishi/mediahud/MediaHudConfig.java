package com.fishi.mediahud;

import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Client settings (config/mediahud-client.toml, in game via Mods -> Media HUD -> Config). */
public final class MediaHudConfig {
	public enum Corner {
		/** Top left, unless Xaero's Minimap is installed (its default position is top left). */
		AUTO,
		TOP_LEFT,
		TOP_RIGHT
	}

	public static final ModConfigSpec SPEC;
	public static final ModConfigSpec.EnumValue<Corner> CORNER;
	public static final ModConfigSpec.IntValue SIZE;
	public static final ModConfigSpec.IntValue PAUSE_TIMEOUT;
	public static final ModConfigSpec.IntValue OFFSET_X;
	public static final ModConfigSpec.IntValue OFFSET_Y;
	public static final ModConfigSpec.ConfigValue<List<? extends String>> SOURCES;

	static {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
		CORNER = builder
			.comment("Screen corner of the display. AUTO: top right if Xaero's Minimap is installed, otherwise top left")
			.defineEnum("corner", Corner.AUTO);
		SIZE = builder
			.comment("Size of the display, in addition to the GUI scale: 1 = small, 2 = medium, 3 = large")
			.defineInRange("size", 2, 1, 3);
		PAUSE_TIMEOUT = builder
			.comment("Seconds after which the display is hidden while playback is paused (0 = never, max. 300)")
			.defineInRange("pauseTimeout", 30, 0, 300);
		OFFSET_X = builder
			.comment("Horizontal distance to the screen edge (GUI pixels)")
			.defineInRange("offsetX", 4, 0, 2000);
		OFFSET_Y = builder
			.comment("Vertical distance to the screen edge (GUI pixels), e.g. increase it to move below a minimap")
			.defineInRange("offsetY", 4, 0, 2000);
		SOURCES = builder
			.comment("Whitelisted media sources (Windows app ids, e.g. \"Spotify.exe\"). Only these are shown. Empty = all sources")
			.defineListAllowEmpty("sources", List.of(), () -> "", value -> value instanceof String);
		SPEC = builder.build();
	}

	private MediaHudConfig() {
	}

	/** Result of the mod detection; does not change at runtime, so it is determined only once. */
	private static Corner autoCorner = null;

	/** Corner to actually use (never {@link Corner#AUTO}). */
	static Corner corner() {
		Corner corner;
		try {
			corner = CORNER.get();
		} catch (RuntimeException e) {
			// Config not loaded (yet): use the default.
			corner = Corner.AUTO;
		}
		return corner == Corner.AUTO ? autoCorner() : corner;
	}

	/**
	 * Xaero's Minimap sits top left by default, so we move to the right.
	 * JourneyMap sits top right by default, so we stay on the left. If both are installed,
	 * no corner is free; then we stay on the left (adjust the vertical offset in the config).
	 * If a minimap was moved, the corner can be set manually.
	 */
	private static Corner autoCorner() {
		if (autoCorner == null) {
			boolean xaero = false;
			boolean journeyMap = false;
			try {
				ModList mods = ModList.get();
				xaero = mods.isLoaded("xaerominimap");
				journeyMap = mods.isLoaded("journeymap");
			} catch (RuntimeException e) {
				// Detection not possible: default (left).
			}
			autoCorner = xaero && !journeyMap ? Corner.TOP_RIGHT : Corner.TOP_LEFT;
		}
		return autoCorner;
	}

	/** Scale factor of the card for each size step. */
	static float scale() {
		int size;
		try {
			size = SIZE.get();
		} catch (RuntimeException e) {
			size = 2;
		}
		return switch (size) {
			case 1 -> 0.5f;
			case 3 -> 1f;
			default -> 2f / 3f;
		};
	}

	static int pauseTimeoutSeconds() {
		try {
			return PAUSE_TIMEOUT.get();
		} catch (RuntimeException e) {
			return 30;
		}
	}

	static int offsetX() {
		try {
			return OFFSET_X.get();
		} catch (RuntimeException e) {
			return 4;
		}
	}

	static int offsetY() {
		try {
			return OFFSET_Y.get();
		} catch (RuntimeException e) {
			return 4;
		}
	}

	/**
	 * Whitelisted source ids, cleaned up (no empty entries, no tabs or line breaks, no duplicates).
	 * Empty = all sources. Never throws.
	 */
	static List<String> sources() {
		List<String> result = new ArrayList<>();
		try {
			for (Object value : SOURCES.get()) {
				if (!(value instanceof String s)) {
					continue;
				}
				String id = s.replaceAll("[\\t\\r\\n]", " ").trim();
				if (!id.isEmpty() && !containsIgnoreCase(result, id)) {
					result.add(id);
				}
			}
		} catch (RuntimeException e) {
			// Config not loaded (yet): no whitelist.
			return List.of();
		}
		return List.copyOf(result);
	}

	static boolean isWhitelisted(String id) {
		return containsIgnoreCase(sources(), id);
	}

	/** Adds the source to the whitelist or removes it again, and saves the config. */
	static void toggleSource(String id) {
		List<String> list = new ArrayList<>(sources());
		if (containsIgnoreCase(list, id)) {
			list.removeIf(entry -> entry.equalsIgnoreCase(id));
		} else {
			list.add(id);
		}
		try {
			SOURCES.set(List.copyOf(list));
			SOURCES.save();
		} catch (RuntimeException e) {
			// Config not loaded: nothing changes, the game keeps running.
		}
	}

	/** Same comparison as in the script (PowerShell -contains ignores case). */
	private static boolean containsIgnoreCase(List<String> list, String id) {
		for (String entry : list) {
			if (entry.toLowerCase(Locale.ROOT).equals(id.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}
}
