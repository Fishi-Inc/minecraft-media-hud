package com.fishi.mediahud;

import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-Einstellungen (config/mediahud-client.toml, im Spiel über Mods -> Media HUD -> Konfiguration). */
public final class MediaHudConfig {
	public enum Corner {
		/** Oben links, außer Xaero's Minimap ist installiert (deren Standardplatz ist oben links). */
		AUTO,
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
			.comment("Bildschirmecke der Anzeige. AUTO: oben rechts, wenn Xaero's Minimap installiert ist, sonst oben links")
			.defineEnum("corner", Corner.AUTO);
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

	/** Ergebnis der Mod-Erkennung; ändert sich zur Laufzeit nicht, daher nur einmal ermittelt. */
	private static Corner autoCorner = null;

	/** Tatsächlich zu nutzende Ecke (nie {@link Corner#AUTO}). */
	static Corner corner() {
		Corner corner;
		try {
			corner = CORNER.get();
		} catch (RuntimeException e) {
			// Config (noch) nicht geladen: Standardwert nutzen.
			corner = Corner.AUTO;
		}
		return corner == Corner.AUTO ? autoCorner() : corner;
	}

	/**
	 * Xaero's Minimap sitzt standardmäßig oben links, also weichen wir nach rechts aus.
	 * JourneyMap sitzt standardmäßig oben rechts, dort bleiben wir links. Sind beide installiert,
	 * ist keine Ecke frei; dann bleibt es bei links (Abstand vertikal in der Config anpassen).
	 * Wurde eine Minimap verschoben, lässt sich die Ecke manuell einstellen.
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
				// Erkennung nicht möglich: Standard (links).
			}
			autoCorner = xaero && !journeyMap ? Corner.TOP_RIGHT : Corner.TOP_LEFT;
		}
		return autoCorner;
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
