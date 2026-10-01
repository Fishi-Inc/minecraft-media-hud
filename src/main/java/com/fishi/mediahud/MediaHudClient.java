package com.fishi.mediahud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = "mediahud", dist = Dist.CLIENT)
public final class MediaHudClient {
	private static final int X = 4;
	private static final int Y = 4;
	private static final int COLOR = 0xFFFFFFFF;
	/** Maximale Breite der Anzeige in GUI-Pixeln (entspricht etwa 50 Zeichen). */
	private static final int MAX_WIDTH = 250;
	/** Abstand zwischen Ende und erneutem Anfang der Laufschrift. */
	private static final int MARQUEE_GAP = 30;
	/** Geschwindigkeit der Laufschrift in GUI-Pixeln pro Sekunde. */
	private static final int MARQUEE_SPEED = 30;

	public MediaHudClient(IEventBus modEventBus) {
		modEventBus.addListener(MediaHudClient::onClientSetup);
		NeoForge.EVENT_BUS.addListener(MediaHudClient::onRenderGui);
		// Reine Java-Absicherung: beim Beenden der JVM den Hintergrundprozess stoppen.
		Runtime.getRuntime().addShutdownHook(new Thread(MediaWatcher::stop, "MediaHUD-Shutdown"));
	}

	private static void onClientSetup(FMLClientSetupEvent event) {
		MediaWatcher.start();
	}

	private static void onRenderGui(RenderGuiEvent.Post event) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.options == null || minecraft.font == null) {
			return;
		}
		if (minecraft.options.hideGui) {
			return;
		}
		String text = MediaWatcher.getCurrentText();
		if (text == null) {
			return;
		}
		drawText(event.getGuiGraphics(), minecraft.font, text);
	}

	/** Passt der Text in die Box, wird er normal gezeichnet, sonst als Laufschrift. */
	private static void drawText(GuiGraphics graphics, Font font, String text) {
		int boxWidth = Math.min(MAX_WIDTH, graphics.guiWidth() - 2 * X);
		if (boxWidth <= 0) {
			return;
		}
		int textWidth = font.width(text);
		if (textWidth <= boxWidth) {
			graphics.drawString(font, text, X, Y, COLOR);
			return;
		}

		int cycle = textWidth + MARQUEE_GAP;
		int offset = (int) ((System.currentTimeMillis() * MARQUEE_SPEED / 1000) % cycle);
		// Alles außerhalb der Box wird abgeschnitten (+1 für den Schatten).
		graphics.enableScissor(X, Y, X + boxWidth, Y + font.lineHeight + 1);
		try {
			graphics.drawString(font, text, X - offset, Y, COLOR);
			graphics.drawString(font, text, X - offset + cycle, Y, COLOR);
		} finally {
			graphics.disableScissor();
		}
	}
}
