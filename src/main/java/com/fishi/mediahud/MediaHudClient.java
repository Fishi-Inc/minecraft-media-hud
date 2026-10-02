package com.fishi.mediahud;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = "mediahud", dist = Dist.CLIENT)
public final class MediaHudClient {
	private static final Logger LOGGER = LoggerFactory.getLogger("mediahud");
	private static boolean loggedRenderFailure = false;

	public MediaHudClient(IEventBus modEventBus, ModContainer container) {
		container.registerConfig(ModConfig.Type.CLIENT, MediaHudConfig.SPEC);
		container.registerExtensionPoint(IConfigScreenFactory.class, MediaHudScreen::new);
		modEventBus.addListener(MediaHudClient::onClientSetup);
		NeoForge.EVENT_BUS.addListener(MediaHudClient::onRenderGui);
		// Plain Java safeguard: stop the background process when the JVM exits.
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
		// F1 (HUD hidden) or F3 (the debug screen is also at the top left)
		if (minecraft.options.hideGui || minecraft.getDebugOverlay().showDebugScreen()) {
			return;
		}
		MediaWatcher.Track track = MediaWatcher.getTrack();
		if (track == null) {
			return;
		}
		try {
			HudRenderer.render(event.getGuiGraphics(), minecraft, track);
		} catch (RuntimeException e) {
			// An error in the display must never crash the game.
			if (!loggedRenderFailure) {
				LOGGER.warn("Media HUD: rendering failed.", e);
				loggedRenderFailure = true;
			}
		}
	}
}
