package com.fishi.mediahud;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-Einstellungen (config/mediahud-client.toml, im Spiel über Mods -> Media HUD -> Konfiguration). */
public final class MediaHudConfig {
	public enum Corner {
		TOP_LEFT,
		TOP_RIGHT
	}

	public static final ModConfigSpec SPEC;
	public static final ModConfigSpec.EnumValue<Corner> CORNER;
	public static final ModConfigSpec.IntValue OFFSET_X;
	public static final ModConfigSpec.IntValue OFFSET_Y;

	static {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
		CORNER = builder
			.comment("Bildschirmecke der Anzeige")
			.defineEnum("corner", Corner.TOP_LEFT);
		OFFSET_X = builder
			.comment("Horizontaler Abstand zum Bildschirmrand (GUI-Pixel)")
			.defineInRange("offsetX", 4, 0, 2000);
		OFFSET_Y = builder
			.comment("Vertikaler Abstand zum Bildschirmrand (GUI-Pixel), z. B. erhöhen, um unter eine Minimap zu rutschen")
			.defineInRange("offsetY", 4, 0, 2000);
		SPEC = builder.build();
	}

	private MediaHudConfig() {
	}

	/** Liest einen Wert; falls die Config (noch) nicht geladen ist, wird der Standardwert genutzt. */
	static Corner corner() {
		try {
			return CORNER.get();
		} catch (RuntimeException e) {
			return Corner.TOP_LEFT;
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
}
