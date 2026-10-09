package com.afterburner.client.gui;

import com.afterburner.Features;
import com.afterburner.Settings;
import com.afterburner.client.Addons;
import com.afterburner.client.perf.FpsTarget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Afterburner's page in Video Settings: one on/off button per optimization ({@link Features}), grouped as in the
 * settings file ({@link Settings}), which is written when the page closes. Occlusion and entity culling switch right
 * away; the rest change the game's code at startup, so the page says when a restart is needed.
 */
public final class SettingsScreen extends OptionsSubScreen {
	private static final Component TITLE = Component.translatable("afterburner.settings.title");
	/** Vanilla's color for its own "restart required" line in Video Settings. */
	private static final int RESTART_COLOR = -2142128;
	private final Map<Features, OptionInstance<Boolean>> buttons = new EnumMap<>(Features.class);
	private final LinearLayout header = LinearLayout.vertical().spacing(2);
	private @Nullable StringWidget restartWarning;

	public SettingsScreen(Screen lastScreen, Options options) {
		super(lastScreen, options, TITLE);
	}

	@Override
	protected void addTitle() {
		header.defaultCellSetting().alignHorizontallyCenter().alignVerticallyMiddle();
		header.addChild(new StringWidget(title, font));
		restartWarning = header.addChild(new StringWidget(Component.translatable("afterburner.settings.restart").withColor(RESTART_COLOR), font));
		layout.addToHeader(header);
	}

	@Override
	protected void addOptions() {
		buttons.clear();
		list.addHeader(Component.translatable("afterburner.group.fpsTarget").withStyle(ChatFormatting.UNDERLINE, ChatFormatting.BOLD));
		list.addBig(fpsTargetSlider());
		Addons.addSettings(new Rows() {
			@Override
			public Screen screen() {
				return SettingsScreen.this;
			}

			@Override
			public void header(String key) {
				list.addHeader(Component.translatable(key).withStyle(ChatFormatting.UNDERLINE, ChatFormatting.BOLD));
			}

			@Override
			public void add(OptionInstance<?> option) {
				list.addBig(option);
			}

			@Override
			public void add(AbstractWidget widget) {
				list.addBig(widget);
			}
		});
		Features.Group group = null;
		List<OptionInstance<?>> row = new ArrayList<>();
		for (Features f : Features.values()) {
			if (f.group() != group) {
				addRow(row);
				group = f.group();
				list.addHeader(Component.translatable("afterburner.group." + group.name().toLowerCase(Locale.ROOT))
						.withStyle(ChatFormatting.UNDERLINE, ChatFormatting.BOLD));
			}
			Tooltip tooltip = tooltip(f);
			OptionInstance<Boolean> button = OptionInstance.createBoolean("afterburner.option." + f.key(), value -> tooltip,
					locked(f) ? f.enabled() : Settings.get(f.key()), value -> changed(f, value));
			buttons.put(f, button);
			row.add(button);
		}
		addRow(row);
		refresh();
	}

	/** Off at the far left, then {@link FpsTarget#CHOICES}. */
	private static OptionInstance<Integer> fpsTargetSlider() {
		int[] choices = FpsTarget.CHOICES;
		int initial = 0;
		for (int i = 0; i < choices.length; i++) if (choices[i] == FpsTarget.target()) initial = i;
		return new OptionInstance<>("afterburner.option.fpsTarget",
				OptionInstance.cachedConstantTooltip(Component.translatable("afterburner.option.fpsTarget.tooltip")),
				(caption, i) -> Options.genericValueLabel(caption, choices[i] == 0 ? CommonComponents.OPTION_OFF
						: Component.translatable("afterburner.fpsTarget.fps", choices[i])),
				new OptionInstance.IntRange(0, choices.length - 1, false), initial, i -> FpsTarget.set(choices[i]));
	}

	/** Where an add-on puts its rows ({@link com.afterburner.client.AfterburnerAddon#addSettings}), under the FPS target. */
	public interface Rows {
		/** This page, to come back to from a page the add-on opens. */
		Screen screen();

		/** A heading, by its translation key. */
		void header(String key);

		void add(OptionInstance<?> option);

		void add(AbstractWidget widget);
	}

	private void addRow(List<OptionInstance<?>> row) {
		if (!row.isEmpty()) list.addSmall(row.toArray(OptionInstance[]::new));
		row.clear();
	}

	@Override
	protected void addFooter() {
		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.translatable("afterburner.settings.defaults"), b -> allOn()).build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).build());
	}

	private void changed(Features f, boolean on) {
		Settings.set(f.key(), on);
		f.setActive(on);
		refresh();
	}

	private void allOn() {
		for (Features f : Features.values()) {
			if (!locked(f)) changed(f, true);
		}
		layout.removeChildren();
		header.removeChildren();
		rebuildWidgets();
	}

	/** Greys out the buttons that can't do anything now, and shows the restart line when it's needed. */
	private void refresh() {
		if (list == null) return;
		for (Map.Entry<Features, OptionInstance<Boolean>> e : buttons.entrySet()) {
			AbstractWidget widget = list.findOption(e.getValue());
			if (widget != null) widget.active = !locked(e.getKey()) && (e.getKey().needs() == null || atStart(e.getKey().needs()));
		}
		if (restartWarning != null) restartWarning.visible = restartNeeded();
	}

	@Override
	public void removed() {
		Settings.save();
		super.removed();
	}

	/** Replaced by another mod, or set by a JVM flag: the settings file doesn't decide it. */
	private static boolean locked(Features f) {
		return f.replacedBy() != null || f.forced();
	}

	/** Whether it will be on after the next restart, as the settings stand. */
	private static boolean atStart(Features f) {
		if (locked(f)) return f.enabled();
		return Settings.get(f.key()) && (f.needs() == null || atStart(f.needs()));
	}

	private static boolean restartNeeded() {
		for (Features f : Features.values()) {
			if (locked(f)) continue;
			// A live one switched off doesn't need a restart; one off since startup does, to come back on.
			boolean differs = f.live() ? atStart(f) && !f.enabled() : atStart(f) != f.enabled();
			if (differs) return true;
		}
		return false;
	}

	private static Tooltip tooltip(Features f) {
		MutableComponent text = Component.translatable("afterburner.option." + f.key() + ".tooltip");
		if (f.replacedBy() != null) {
			text.append("\n\n").append(Component.translatable("afterburner.settings.replaced", f.replacedBy()).withStyle(ChatFormatting.YELLOW));
		} else if (f.forced()) {
			text.append("\n\n").append(Component.translatable("afterburner.settings.forced",
					Boolean.getBoolean("afterburner.disable") ? "-Dafterburner.disable=true" : "-Dafterburner." + f.key()).withStyle(ChatFormatting.YELLOW));
		} else {
			if (f.needs() != null) {
				text.append("\n\n").append(Component.translatable("afterburner.settings.needs",
						Component.translatable("afterburner.option." + f.needs().key())).withStyle(ChatFormatting.GRAY));
			}
			if (f.group() == Features.Group.WORLD) {
				text.append("\n\n").append(Component.translatable("afterburner.settings.server").withStyle(ChatFormatting.GRAY));
			}
			text.append("\n\n").append(Component.translatable(f.live() ? "afterburner.settings.live" : "afterburner.settings.restartNote")
					.withStyle(ChatFormatting.GRAY));
		}
		return Tooltip.create(text);
	}
}
