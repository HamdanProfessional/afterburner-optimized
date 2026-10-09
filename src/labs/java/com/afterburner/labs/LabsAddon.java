package com.afterburner.labs;

import com.afterburner.client.AfterburnerAddon;
import com.afterburner.client.gui.SettingsScreen;
import com.afterburner.labs.gui.PacksScreen;
import com.afterburner.labs.lod.Lod;
import com.afterburner.labs.lod.LodSettings;
import com.afterburner.labs.shaderpack.game.Shaderpacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** The shader pack loader, upscaling and far terrain, as Afterburner sees them, and their rows on its settings page. */
public final class LabsAddon implements AfterburnerAddon {
	@Override
	public boolean shaderPackActive() {
		return LabsFeatures.SHADERS && Shaderpacks.active();
	}

	@Override
	public int farTerrainChunks() {
		return Lod.available() ? LodSettings.distance() : 0;
	}

	@Override
	public void addSettings(SettingsScreen.Rows rows) {
		if (LabsFeatures.SHADERS) addShaderSettings(rows);
		if (LabsFeatures.FAR_TERRAIN) {
			rows.header("afterburner.group.farTerrain");
			rows.add(farTerrainSlider());
			rows.add(Button.builder(unseenLandLabel(), b -> {
				LodSettings.setUnseenLand(!LodSettings.unseenLand());
				b.setMessage(unseenLandLabel());
			}).tooltip(Tooltip.create(Component.translatable("afterburner.option.unseenLand.tooltip"))).width(310).build());
		}
	}

	private static void addShaderSettings(SettingsScreen.Rows rows) {
		rows.header("afterburner.group.upscale");
		rows.add(Button.builder(upscaleLabel(), b -> {
			Shaderpacks.Upscale[] modes = Shaderpacks.Upscale.values();
			Shaderpacks.setUpscale(modes[(Shaderpacks.upscale().ordinal() + 1) % modes.length]);
			b.setMessage(upscaleLabel());
		}).tooltip(Tooltip.create(Component.translatable("afterburner.option.upscale.tooltip"))).width(310).build());
		rows.header("afterburner.group.shaders");
		rows.add(Button.builder(packsLabel(), b -> Minecraft.getInstance().gui.setScreen(new PacksScreen(rows.screen(), Minecraft.getInstance().options)))
				.tooltip(Tooltip.create(Component.translatable("afterburner.option.shaderpacks.tooltip"))).width(310).build());
		rows.add(Button.builder(shadowUpdatesLabel(), b -> {
			Shaderpacks.ShadowUpdates[] modes = Shaderpacks.ShadowUpdates.values();
			Shaderpacks.setShadowUpdates(modes[(Shaderpacks.shadowUpdates().ordinal() + 1) % modes.length]);
			b.setMessage(shadowUpdatesLabel());
		}).tooltip(Tooltip.create(Component.translatable("afterburner.option.shadowUpdates.tooltip"))).width(310).build());
	}

	/** The chosen pack; opens the shader pack page. */
	private static Component packsLabel() {
		String pack = Shaderpacks.selected();
		return Component.translatable("afterburner.option.shaderpacks", pack.isEmpty() ? CommonComponents.OPTION_OFF : Component.literal(pack))
				.append("...");
	}

	/** Off at the far left, then {@link LodSettings#MIN} to {@link LodSettings#MAX} chunks; it takes effect when let go. */
	private static OptionInstance<Integer> farTerrainSlider() {
		int steps = LodSettings.MAX / LodSettings.STEP, first = LodSettings.MIN / LodSettings.STEP - 1;
		return new OptionInstance<>("afterburner.option.farTerrainDistance",
				OptionInstance.cachedConstantTooltip(Component.translatable("afterburner.option.farTerrainDistance.tooltip")),
				(caption, step) -> Options.genericValueLabel(caption, step <= first ? CommonComponents.OPTION_OFF
						: Component.translatable("afterburner.farTerrain.chunks", step * LodSettings.STEP)),
				new OptionInstance.IntRange(first, steps, false), Math.max(first, LodSettings.distance() / LodSettings.STEP),
				step -> LodSettings.set(step <= first ? 0 : step * LodSettings.STEP));
	}

	private static Component unseenLandLabel() {
		return Component.translatable("afterburner.option.unseenLand", LodSettings.unseenLand() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
	}

	private static Component shadowUpdatesLabel() {
		return Component.translatable("afterburner.option.shadowUpdates", Component.translatable("afterburner.shadowUpdates." + Shaderpacks.shadowUpdates().id));
	}

	/** The upscaling mode, and how much of the screen's width the world is drawn at. */
	private static Component upscaleLabel() {
		Shaderpacks.Upscale mode = Shaderpacks.upscale();
		MutableComponent name = Component.translatable("afterburner.upscale." + mode.id);
		if (mode != Shaderpacks.Upscale.OFF) name.append(Component.literal(", " + Math.round(mode.scale * 100) + "%"));
		return Component.translatable("afterburner.option.upscale", name);
	}
}
