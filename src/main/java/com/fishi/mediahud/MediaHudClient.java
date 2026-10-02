package com.fishi.mediahud;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
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
		container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
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
		// F1 (HUD aus) oder F3 (Debug-Anzeige liegt ebenfalls oben links)
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
			// Ein Fehler in der Anzeige darf nie das Spiel abstürzen lassen.
			if (!loggedRenderFailure) {
				LOGGER.warn("Media HUD: Anzeige fehlgeschlagen.", e);
				loggedRenderFailure = true;
			}
		}
	}
}
