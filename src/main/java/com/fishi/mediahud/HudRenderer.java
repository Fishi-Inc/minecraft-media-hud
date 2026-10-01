package com.fishi.mediahud;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
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
 * Alle Maße sind in Karten-Pixeln und werden mit der Größenstufe aus der Config skaliert
 * (zusätzlich zur GUI-Skalierung von Minecraft). Läuft ausschließlich auf dem Render-Thread.
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

	/** Nach so langer Pause wird die Karte ausgeblendet. */
	private static final long PAUSE_FADE_DELAY_MS = 30_000;
	private static final long FADE_DURATION_MS = 1_000;
	/** Darunter wird gar nicht mehr gezeichnet (Schrift mit fast 0 Alpha würde sonst deckend). */
	private static final float MIN_ALPHA = 0.05f;

	/** Abstand zwischen Ende und erneutem Anfang der Laufschrift. */
	private static final int MARQUEE_GAP = 30;
	/** Geschwindigkeit der Laufschrift in GUI-Pixeln pro Sekunde. */
	private static final int MARQUEE_SPEED = 30;

	private static final ResourceLocation COVER_TEXTURE = ResourceLocation.fromNamespaceAndPath("mediahud", "cover");
	/** Aktuell als Textur geladenes Cover. Verglichen wird das Objekt, nicht die Id (Ids beginnen nach einem Neustart des Skripts wieder bei 1). */
	private static MediaWatcher.Cover loadedCover = null;
	/** Cover, das nicht geladen werden konnte (wird nicht erneut versucht). */
	private static MediaWatcher.Cover failedCover = null;

	/** Titel, der zuletzt gespielt hat (bzw. neu erschienen ist), und wann. */
	private static String activeKey = null;
	private static long lastActiveMs = 0;

	private HudRenderer() {
	}

	static void render(GuiGraphics graphics, Minecraft minecraft, MediaWatcher.Track track) {
		Font font = minecraft.font;
		long now = System.currentTimeMillis();
		float alpha = fadeAlpha(track, now);
		if (alpha < MIN_ALPHA) {
			return;
		}

		float scale = MediaHudConfig.scale();
		int offsetX = MediaHudConfig.offsetX();
		int originX = MediaHudConfig.corner() == MediaHudConfig.Corner.TOP_RIGHT
			? graphics.guiWidth() - Math.round(WIDTH * scale) - offsetX
			: offsetX;
		int originY = MediaHudConfig.offsetY();

		// Layout in Karten-Pixeln (Ursprung oben links auf der Karte)
		int coverX = ACCENT_WIDTH + 4;
		int coverY = 6;
		int textX = coverX + COVER_SIZE + 6;
		int textRight = WIDTH - 6;
		int line1Y = 7;
		int line2Y = 19;
		int iconX = textRight - ICON_SIZE + 1;
		boolean hasDuration = track.durationMs() > 0;
		String time = hasDuration ? formatTime(track.positionAt(now)) + " / " + formatTime(track.durationMs()) : "";
		int titleRight = hasDuration ? textRight - font.width(time) - 6 : textRight;

		PoseStack pose = graphics.pose();
		pose.pushPose();
		try {
			pose.translate(originX, originY, 0);
			pose.scale(scale, scale, 1);

			graphics.fill(0, 0, WIDTH, HEIGHT, fade(COLOR_BACKGROUND, alpha));
			graphics.fill(0, 0, ACCENT_WIDTH, HEIGHT, fade(COLOR_ACCENT, alpha));

			if (prepareCover(minecraft.getTextureManager(), track.coverId())) {
				drawCover(graphics, coverX, coverY, alpha);
			} else {
				graphics.fill(coverX, coverY, coverX + COVER_SIZE, coverY + COVER_SIZE, fade(COLOR_COVER_PLACEHOLDER, alpha));
			}

			if (hasDuration) {
				graphics.drawString(font, time, textRight - font.width(time), line1Y, fade(COLOR_TIME, alpha));

				int barY = 33;
				int barWidth = textRight - textX;
				int filled = (int) (barWidth * track.positionAt(now) / track.durationMs());
				graphics.fill(textX, barY, textRight, barY + BAR_HEIGHT, fade(COLOR_BAR_BACKGROUND, alpha));
				graphics.fill(textX, barY, textX + Math.max(0, Math.min(filled, barWidth)), barY + BAR_HEIGHT, fade(COLOR_BAR, alpha));
			}

			if (track.playing()) {
				drawPlayIcon(graphics, iconX, line2Y, fade(COLOR_ICON, alpha));
			} else {
				drawPauseIcon(graphics, iconX, line2Y, fade(COLOR_ICON, alpha));
			}
		} finally {
			pose.popPose();
		}

		// Texte zuletzt, da die Laufschrift einen eigenen Ausschnitt (Scissor) braucht.
		drawLabel(graphics, font, track.title(), originX, originY, scale,
			textX, line1Y, titleRight - textX, fade(COLOR_TITLE, alpha), now);
		drawLabel(graphics, font, track.artist(), originX, originY, scale,
			textX, line2Y, iconX - 6 - textX, fade(COLOR_ARTIST, alpha), now);
	}

	/**
	 * 1 = voll sichtbar. Ist der Titel länger als {@link #PAUSE_FADE_DELAY_MS} pausiert,
	 * wird die Karte innerhalb von {@link #FADE_DURATION_MS} ausgeblendet.
	 */
	private static float fadeAlpha(MediaWatcher.Track track, long now) {
		String key = track.artist() + "\n" + track.title();
		if (track.playing() || !key.equals(activeKey)) {
			activeKey = key;
			lastActiveMs = now;
		}
		long fadingFor = now - lastActiveMs - PAUSE_FADE_DELAY_MS;
		if (fadingFor <= 0) {
			return 1f;
		}
		return Math.max(0f, 1f - fadingFor / (float) FADE_DURATION_MS);
	}

	/** Multipliziert den Alpha-Kanal einer ARGB-Farbe. */
	private static int fade(int argb, float alpha) {
		int a = Math.round(((argb >>> 24) & 0xFF) * alpha);
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	private static void drawCover(GuiGraphics graphics, int x, int y, float alpha) {
		RenderSystem.enableBlend();
		graphics.setColor(1f, 1f, 1f, alpha);
		try {
			graphics.blit(COVER_TEXTURE, x, y, COVER_SIZE, COVER_SIZE, 0f, 0f,
				COVER_TEXTURE_SIZE, COVER_TEXTURE_SIZE, COVER_TEXTURE_SIZE, COVER_TEXTURE_SIZE);
		} finally {
			graphics.setColor(1f, 1f, 1f, 1f);
			RenderSystem.disableBlend();
		}
	}

	/**
	 * Zeichnet einen Text an Karten-Position (x, y). Passt er nicht in {@code width},
	 * läuft er als Laufschrift durch einen Ausschnitt.
	 */
	private static void drawLabel(GuiGraphics graphics, Font font, String text, int originX, int originY, float scale,
			int x, int y, int width, int color, long now) {
		if (text.isEmpty() || width <= 0) {
			return;
		}
		int textWidth = font.width(text);
		if (textWidth <= width) {
			PoseStack pose = graphics.pose();
			pose.pushPose();
			try {
				pose.translate(originX, originY, 0);
				pose.scale(scale, scale, 1);
				graphics.drawString(font, text, x, y, color);
			} finally {
				pose.popPose();
			}
			return;
		}

		int cycle = textWidth + MARQUEE_GAP;
		int offset = (int) ((now * MARQUEE_SPEED / 1000) % cycle);
		// Der Ausschnitt wird in Bildschirm-Koordinaten angegeben (+1 für den Schatten),
		// daher hier selbst skalieren und erst danach die Skalierung für den Text setzen.
		int left = originX + (int) Math.floor(x * scale);
		int top = originY + (int) Math.floor(y * scale);
		int right = originX + (int) Math.ceil((x + width) * scale);
		int bottom = originY + (int) Math.ceil((y + font.lineHeight + 1) * scale);
		graphics.enableScissor(left, top, right, bottom);
		try {
			PoseStack pose = graphics.pose();
			pose.pushPose();
			try {
				pose.translate(originX, originY, 0);
				pose.scale(scale, scale, 1);
				graphics.drawString(font, text, x - offset, y, color);
				graphics.drawString(font, text, x - offset + cycle, y, color);
			} finally {
				pose.popPose();
			}
		} finally {
			graphics.disableScissor();
		}
	}

	/** Dreieck ▶, aus Rechtecken gezeichnet (keine Abhängigkeit von Schrift-Glyphen). */
	private static void drawPlayIcon(GuiGraphics graphics, int x, int y, int color) {
		for (int row = 0; row < ICON_SIZE; row++) {
			int width = Math.min(row, ICON_SIZE - 1 - row) + 1;
			graphics.fill(x, y + row, x + width, y + row + 1, color);
		}
	}

	/** Zwei Balken ❚❚. */
	private static void drawPauseIcon(GuiGraphics graphics, int x, int y, int color) {
		graphics.fill(x, y, x + 2, y + ICON_SIZE, color);
		graphics.fill(x + 4, y, x + 6, y + ICON_SIZE, color);
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
