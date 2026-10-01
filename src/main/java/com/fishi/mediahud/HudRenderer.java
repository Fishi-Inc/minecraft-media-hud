package com.fishi.mediahud;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * Zeichnet die Medien-Karte:
 * <pre>
 * ┃ [Cover]  Titel              1:06 / 4:23
 * ┃ [Cover]  Künstler                     ▶
 * ┃ [Cover]  ━━━━━━━━━━──────────────────────
 * </pre>
 * Läuft ausschließlich auf dem Render-Thread.
 */
final class HudRenderer {
	private static final Logger LOGGER = LoggerFactory.getLogger("mediahud");

	private static final int WIDTH = 200;
	private static final int HEIGHT = 44;
	private static final int ACCENT_WIDTH = 2;
	private static final int COVER_SIZE = 32;
	/** Das Skript liefert Cover immer als 128x128-PNG (scharf bis GUI-Skalierung 4). */
	private static final int COVER_TEXTURE_SIZE = 128;
	private static final int ICON_SIZE = 7;
	private static final int BAR_HEIGHT = 2;

	private static final int COLOR_BACKGROUND = 0xCC151515;
	private static final int COLOR_ACCENT = 0xFF1DB954;
	private static final int COLOR_COVER_PLACEHOLDER = 0xFF333333;
	private static final int COLOR_TITLE = 0xFFFFFFFF;
	private static final int COLOR_ARTIST = 0xFFAAAAAA;
	private static final int COLOR_TIME = 0xFFDDDDDD;
	private static final int COLOR_ICON = 0xFFFFFFFF;
	private static final int COLOR_BAR_BACKGROUND = 0xFF404040;
	private static final int COLOR_BAR = 0xFFFFFFFF;

	/** Abstand zwischen Ende und erneutem Anfang der Laufschrift. */
	private static final int MARQUEE_GAP = 30;
	/** Geschwindigkeit der Laufschrift in GUI-Pixeln pro Sekunde. */
	private static final int MARQUEE_SPEED = 30;

	private static final ResourceLocation COVER_TEXTURE = ResourceLocation.fromNamespaceAndPath("mediahud", "cover");
	/** Aktuell als Textur geladenes Cover. Verglichen wird das Objekt, nicht die Id (Ids beginnen nach einem Neustart des Skripts wieder bei 1). */
	private static MediaWatcher.Cover loadedCover = null;
	/** Cover, das nicht geladen werden konnte (wird nicht erneut versucht). */
	private static MediaWatcher.Cover failedCover = null;

	private HudRenderer() {
	}

	static void render(GuiGraphics graphics, Minecraft minecraft, MediaWatcher.Track track) {
		Font font = minecraft.font;
		long now = System.currentTimeMillis();

		int offsetX = MediaHudConfig.offsetX();
		int x = MediaHudConfig.corner() == MediaHudConfig.Corner.TOP_RIGHT
			? graphics.guiWidth() - WIDTH - offsetX
			: offsetX;
		int y = MediaHudConfig.offsetY();

		graphics.fill(x, y, x + WIDTH, y + HEIGHT, COLOR_BACKGROUND);
		graphics.fill(x, y, x + ACCENT_WIDTH, y + HEIGHT, COLOR_ACCENT);

		// Cover
		int coverX = x + ACCENT_WIDTH + 4;
		int coverY = y + 6;
		if (prepareCover(minecraft.getTextureManager(), track.coverId())) {
			graphics.blit(COVER_TEXTURE, coverX, coverY, COVER_SIZE, COVER_SIZE, 0f, 0f,
				COVER_TEXTURE_SIZE, COVER_TEXTURE_SIZE, COVER_TEXTURE_SIZE, COVER_TEXTURE_SIZE);
		} else {
			graphics.fill(coverX, coverY, coverX + COVER_SIZE, coverY + COVER_SIZE, COLOR_COVER_PLACEHOLDER);
		}

		int textX = coverX + COVER_SIZE + 6;
		int textRight = x + WIDTH - 6;
		int line1Y = y + 7;
		int line2Y = y + 19;
		boolean hasDuration = track.durationMs() > 0;

		// Zeile 1: Titel und Zeit
		int titleRight = textRight;
		if (hasDuration) {
			String time = formatTime(track.positionAt(now)) + " / " + formatTime(track.durationMs());
			int timeX = textRight - font.width(time);
			graphics.drawString(font, time, timeX, line1Y, COLOR_TIME);
			titleRight = timeX - 6;
		}
		drawScrollingText(graphics, font, track.title(), textX, line1Y, titleRight - textX, COLOR_TITLE, now);

		// Zeile 2: Künstler und Play/Pause-Symbol
		int iconX = textRight - ICON_SIZE + 1;
		drawScrollingText(graphics, font, track.artist(), textX, line2Y, iconX - 6 - textX, COLOR_ARTIST, now);
		if (track.playing()) {
			drawPlayIcon(graphics, iconX, line2Y);
		} else {
			drawPauseIcon(graphics, iconX, line2Y);
		}

		// Fortschrittsbalken
		if (hasDuration) {
			int barY = y + 33;
			int barWidth = textRight - textX;
			int filled = (int) (barWidth * track.positionAt(now) / track.durationMs());
			graphics.fill(textX, barY, textRight, barY + BAR_HEIGHT, COLOR_BAR_BACKGROUND);
			graphics.fill(textX, barY, textX + Math.max(0, Math.min(filled, barWidth)), barY + BAR_HEIGHT, COLOR_BAR);
		}
	}

	/** Passt der Text in die Breite, wird er normal gezeichnet, sonst als Laufschrift. */
	private static void drawScrollingText(GuiGraphics graphics, Font font, String text, int x, int y, int width, int color, long now) {
		if (text.isEmpty() || width <= 0) {
			return;
		}
		int textWidth = font.width(text);
		if (textWidth <= width) {
			graphics.drawString(font, text, x, y, color);
			return;
		}
		int cycle = textWidth + MARQUEE_GAP;
		int offset = (int) ((now * MARQUEE_SPEED / 1000) % cycle);
		// Alles außerhalb der Breite wird abgeschnitten (+1 für den Schatten).
		graphics.enableScissor(x, y, x + width, y + font.lineHeight + 1);
		try {
			graphics.drawString(font, text, x - offset, y, color);
			graphics.drawString(font, text, x - offset + cycle, y, color);
		} finally {
			graphics.disableScissor();
		}
	}

	/** Dreieck ▶, aus Rechtecken gezeichnet (keine Abhängigkeit von Schrift-Glyphen). */
	private static void drawPlayIcon(GuiGraphics graphics, int x, int y) {
		for (int row = 0; row < ICON_SIZE; row++) {
			int width = Math.min(row, ICON_SIZE - 1 - row) + 1;
			graphics.fill(x, y + row, x + width, y + row + 1, COLOR_ICON);
		}
	}

	/** Zwei Balken ❚❚. */
	private static void drawPauseIcon(GuiGraphics graphics, int x, int y) {
		graphics.fill(x, y, x + 2, y + ICON_SIZE, COLOR_ICON);
		graphics.fill(x + 4, y, x + 6, y + ICON_SIZE, COLOR_ICON);
	}

	/** Lädt bei Bedarf das Cover als Textur. Gibt {@code true} zurück, wenn es gezeichnet werden kann. */
	private static boolean prepareCover(TextureManager textureManager, int wantedId) {
		if (wantedId <= 0) {
			return false;
		}
		MediaWatcher.Cover cover = MediaWatcher.getCover();
		if (cover == null || cover.id() != wantedId || cover == failedCover) {
			return false;
		}
		if (cover == loadedCover) {
			return true;
		}

		NativeImage image = null;
		try {
			image = NativeImage.read(new ByteArrayInputStream(cover.png()));
			if (image.getWidth() != COVER_TEXTURE_SIZE || image.getHeight() != COVER_TEXTURE_SIZE) {
				throw new IllegalStateException("Unerwartete Cover-Größe " + image.getWidth() + "x" + image.getHeight());
			}
			DynamicTexture texture = new DynamicTexture(image);
			image = null; // gehört jetzt der Textur
			loadedCover = null;
			textureManager.release(COVER_TEXTURE);
			textureManager.register(COVER_TEXTURE, texture);
			loadedCover = cover;
			return true;
		} catch (Exception e) {
			LOGGER.warn("Media HUD: Cover konnte nicht geladen werden.", e);
			failedCover = cover;
			return false;
		} finally {
			if (image != null) {
				image.close();
			}
		}
	}

	static String formatTime(long ms) {
		long totalSeconds = Math.max(0, ms) / 1000;
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;
		if (hours > 0) {
			return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
		}
		return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
	}
}
