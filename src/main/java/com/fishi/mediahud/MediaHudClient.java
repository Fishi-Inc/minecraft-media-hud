package com.fishi.mediahud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

public final class MediaHudClient implements ClientModInitializer {
	private static final int X = 4;
	private static final int Y = 4;
	private static final int COLOR = 0xFFFFFFFF;

	@Override
	public void onInitializeClient() {
		HudRenderCallback.EVENT.register(MediaHudClient::render);
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> MediaWatcher.start());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MediaWatcher.stop());
	}

	private static void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.options == null || client.textRenderer == null) {
			return;
		}
		if (client.options.hudHidden) {
			return;
		}
		String text = MediaWatcher.getCurrentText();
		if (text == null) {
			return;
		}
		context.drawTextWithShadow(client.textRenderer, text, X, Y, COLOR);
	}
}
