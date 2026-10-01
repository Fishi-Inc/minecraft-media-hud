package com.fishi.mediahud;

import net.minecraft.client.Minecraft;
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
		event.getGuiGraphics().drawString(minecraft.font, text, X, Y, COLOR);
	}
}
