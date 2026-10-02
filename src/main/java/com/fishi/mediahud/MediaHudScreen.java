package com.fishi.mediahud;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings page of the mod (Mods -> Media HUD -> Config): a button to the general settings,
 * a switch for the whitelist and a list of media sources that can be whitelisted.
 * The list contains all programs currently reporting media plus all whitelisted ones.
 */
final class MediaHudScreen extends Screen {
	private static final int ROW_HEIGHT = 24;
	private static final int LIST_TOP = 116;
	private static final int BUTTON_WIDTH = 100;
	private static final int ROW_WIDTH = 310;

	private final ModContainer container;
	private final Screen parent;
	/** Sources shown in the list; rebuilt when they change. */
	private List<String> shown = List.of();
	private int hiddenCount = 0;

	MediaHudScreen(ModContainer container, Screen parent) {
		super(Component.translatable("mediahud.screen.title"));
		this.container = container;
		this.parent = parent;
	}

	@Override
	protected void init() {
		shown = currentSources();
		int center = width / 2;

		addRenderableWidget(Button.builder(Component.translatable("mediahud.screen.general"),
				button -> minecraft.setScreen(new ConfigurationScreen(container, this)))
			.bounds(center - 100, 32, 200, 20)
			.build());

		addRenderableWidget(Button.builder(useWhitelistLabel(), button -> {
				MediaHudConfig.setUseWhitelist(!MediaHudConfig.useWhitelist());
				// The whitelist buttons are only usable while the whitelist is on.
				rebuildWidgets();
			})
			.bounds(center - 100, 56, 200, 20)
			.build());

		int maxRows = Math.max(0, (height - 48 - LIST_TOP) / ROW_HEIGHT);
		int rows = Math.min(shown.size(), maxRows);
		hiddenCount = shown.size() - rows;
		for (int i = 0; i < rows; i++) {
			String id = shown.get(i);
			Button toggle = addRenderableWidget(Button.builder(toggleLabel(id), button -> {
					MediaHudConfig.toggleSource(id);
					button.setMessage(toggleLabel(id));
				})
				.bounds(center + ROW_WIDTH / 2 - BUTTON_WIDTH, LIST_TOP + i * ROW_HEIGHT, BUTTON_WIDTH, 20)
				.build());
			toggle.active = MediaHudConfig.useWhitelist();
		}

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
			.bounds(center - 100, height - 28, 200, 20)
			.build());
	}

	@Override
	public void tick() {
		super.tick();
		// Programs start and stop while the page is open.
		if (!currentSources().equals(shown)) {
			rebuildWidgets();
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		int center = width / 2;
		int left = center - ROW_WIDTH / 2;
		graphics.drawCenteredString(font, title, center, 15, 0xFFFFFFFF);
		graphics.drawCenteredString(font, Component.translatable("mediahud.screen.sources"), center, 86, 0xFFFFFFFF);
		String hintKey;
		if (!MediaHudConfig.useWhitelist()) {
			hintKey = "mediahud.screen.hint.off";
		} else if (MediaHudConfig.sources().isEmpty()) {
			hintKey = "mediahud.screen.hint.all";
		} else {
			hintKey = "mediahud.screen.hint.whitelist";
		}
		graphics.drawCenteredString(font, Component.translatable(hintKey), center, 98, 0xFFAAAAAA);

		if (shown.isEmpty()) {
			graphics.drawCenteredString(font, Component.translatable("mediahud.screen.empty"), center, LIST_TOP + 6, 0xFFAAAAAA);
			return;
		}
		int labelWidth = ROW_WIDTH - BUTTON_WIDTH - 8;
		int rows = shown.size() - hiddenCount;
		for (int i = 0; i < rows; i++) {
			String id = shown.get(i);
			String label = font.width(id) > labelWidth ? font.plainSubstrByWidth(id, labelWidth - font.width("...")) + "..." : id;
			graphics.drawString(font, label, left, LIST_TOP + i * ROW_HEIGHT + 6, 0xFFFFFFFF);
		}
		if (hiddenCount > 0) {
			graphics.drawString(font, Component.translatable("mediahud.screen.more", hiddenCount),
				left, LIST_TOP + rows * ROW_HEIGHT + 2, 0xFFAAAAAA);
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	private static Component useWhitelistLabel() {
		return Component.translatable("mediahud.screen.useWhitelist",
			MediaHudConfig.useWhitelist() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
	}

	private static Component toggleLabel(String id) {
		return Component.translatable(MediaHudConfig.isWhitelisted(id)
			? "mediahud.screen.whitelisted"
			: "mediahud.screen.whitelist");
	}

	/** Running sources first, then whitelisted ones that are not running right now. */
	private static List<String> currentSources() {
		List<String> result = new ArrayList<>(MediaWatcher.getSources());
		for (String id : MediaHudConfig.sources()) {
			if (result.stream().noneMatch(entry -> entry.equalsIgnoreCase(id))) {
				result.add(id);
			}
		}
		return result;
	}
}
