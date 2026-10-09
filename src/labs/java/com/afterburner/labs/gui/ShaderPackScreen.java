package com.afterburner.labs.gui;

import com.afterburner.labs.shaderpack.PackOptions;
import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shader pack page: a drop-down of the packs in the shaderpacks folder, the upscaling mode, and the chosen pack's own
 * settings, laid out as its shaders.properties lays them out (screens, sub-screens, sliders, profiles) and named by its lang
 * file, as OptiFine shows them. Left click goes to an option's next value, right click to the one before. Changes are saved
 * to shaderpacks/&lt;pack&gt;.txt and the pack loaded again on Apply, or when the page closes.
 */
public final class ShaderPackScreen extends OptionsSubScreen {
	private static final Component TITLE = Component.translatable("afterburner.shaders.title");
	private static final int SMALL = 150;

	/** The pack whose settings are shown ("" for none), its options (null if none, or they couldn't be read), and why not. */
	private String pack = "";
	private @Nullable PackOptions packOptions;
	private @Nullable String error;
	/** The player's values for the pack, only those not at their defaults. */
	private Map<String, String> values = new LinkedHashMap<>();
	private boolean dirty;
	private boolean dropdown;
	/** The pack's settings screens opened, the one shown last; empty: its main screen. */
	private final Deque<String> path = new ArrayDeque<>();
	private @Nullable Button profileButton;
	private @Nullable Button applyButton;

	public ShaderPackScreen(Screen lastScreen, Options options) {
		super(lastScreen, options, TITLE);
	}

	@Override
	protected void addOptions() {
		loadOptions();
		profileButton = null;
		list.addBig(Button.builder(packLabel(), b -> {
			dropdown = !dropdown;
			rebuild(true);
		}).tooltip(Tooltip.create(Component.translatable("afterburner.option.shaderpack.tooltip"))).width(310).build());
		if (dropdown) {
			addChoice("", CommonComponents.OPTION_OFF);
			for (String p : Shaderpacks.available()) addChoice(p, Component.literal(p));
		}
		if (pack.isEmpty()) return;

		PackOptions o = packOptions;
		if (o == null) {
			list.addHeader(Component.translatable("afterburner.shaders.unreadable", String.valueOf(error)).withStyle(ChatFormatting.RED));
			return;
		}
		String screen = screen();
		list.addHeader((screen.isEmpty() ? Component.translatable("afterburner.shaders.settings") : screenName(screen))
				.withStyle(ChatFormatting.UNDERLINE, ChatFormatting.BOLD));
		List<AbstractWidget> widgets = new ArrayList<>();
		for (String entry : entries(o, screen)) {
			if (entry.equals("*")) {
				for (String name : unplaced(o)) widgets.add(widget(o, name));
			} else {
				widgets.add(widget(o, entry));
			}
		}
		if (widgets.isEmpty()) list.addHeader(Component.translatable("afterburner.shaders.none").withStyle(ChatFormatting.GRAY));
		else list.addSmall(widgets);
	}

	/** One pack in the drop-down; the chosen one is ticked. */
	private void addChoice(String name, Component label) {
		boolean chosen = name.equals(Shaderpacks.selected());
		MutableComponent text = chosen ? Component.literal("✔ ").append(label).withStyle(ChatFormatting.GREEN) : label.copy();
		list.addBig(Button.builder(text, b -> {
			dropdown = false;
			if (!chosen) {
				save();
				Shaderpacks.select(name);
			}
			rebuild(false);
		}).width(310).build());
	}

	/** Reads the chosen pack's options, once per pack. */
	private void loadOptions() {
		String selected = Shaderpacks.selected();
		if (selected.equals(pack) && (selected.isEmpty() || packOptions != null || error != null)) return;
		pack = selected;
		packOptions = null;
		error = null;
		path.clear();
		dirty = false;
		values = new LinkedHashMap<>();
		if (selected.isEmpty()) return;
		values = PackOptions.readValues(Shaderpacks.optionsFile(selected));
		try {
			packOptions = Shaderpacks.options(selected);
		} catch (IOException | RuntimeException e) {
			error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
		}
	}

	private String screen() {
		String last = path.peekLast();
		return last == null ? "" : last;
	}

	/** A screen's entries: as the pack lists them, or every option on the main screen of a pack that has none. */
	private static List<String> entries(PackOptions o, String screen) {
		List<String> listed = o.screens.get(screen);
		if (listed != null) return listed;
		return screen.isEmpty() ? List.copyOf(o.options.keySet()) : List.of();
	}

	/** The options on no screen ("*" shows them). */
	private static List<String> unplaced(PackOptions o) {
		Set<String> placed = new HashSet<>();
		for (List<String> entries : o.screens.values()) placed.addAll(entries);
		List<String> out = new ArrayList<>();
		for (String name : o.options.keySet()) if (!placed.contains(name)) out.add(name);
		return out;
	}

	private AbstractWidget widget(PackOptions o, String entry) {
		if (entry.equals("<profile>")) {
			if (o.profiles.isEmpty()) return spacer();
			ProfileButton b = new ProfileButton(o);
			profileButton = b;
			return b;
		}
		if (entry.startsWith("[") && entry.endsWith("]") && entry.length() > 2) {
			String sub = entry.substring(1, entry.length() - 1);
			return Button.builder(screenName(sub).append("..."), b -> {
				path.addLast(sub);
				rebuild(false);
			}).width(SMALL).build();
		}
		PackOptions.Option option = o.options.get(entry);
		// Empty spaces, and options this pack doesn't have as the game sees it, keep the pack's layout.
		if (option == null) return spacer();
		if (o.sliders.contains(option.name) && !option.isBoolean() && option.values.size() > 2) return new OptionSlider(o, option);
		return new OptionButton(o, option);
	}

	private StringWidget spacer() {
		return new StringWidget(SMALL, 20, Component.empty(), font);
	}

	// ---- Names, from the pack's lang file ----

	private String lang(String key, String fallback) {
		PackOptions o = packOptions;
		String v = o == null ? null : o.lang.get(key);
		return v != null ? v : fallback;
	}

	private MutableComponent screenName(String screen) {
		return Component.literal(lang("screen." + screen, screen));
	}

	private Component label(PackOptions.Option option, String value) {
		MutableComponent text = Component.literal(lang("option." + option.name, option.name) + ": ");
		if (option.isBoolean()) {
			boolean on = value.equals("true");
			return text.append(Component.empty().append(on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF)
					.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
		}
		String shown = lang("value." + option.name + "." + value, null);
		if (shown == null) shown = lang("prefix." + option.name, "") + value + lang("suffix." + option.name, "");
		MutableComponent v = Component.literal(shown);
		return text.append(value.equals(option.defaultValue) ? v : v.withStyle(ChatFormatting.YELLOW));
	}

	private Tooltip tooltip(PackOptions.Option option) {
		MutableComponent text = Component.literal(lang("option." + option.name + ".comment", lang("option." + option.name, option.name)));
		String def = option.isBoolean() ? (option.defaultValue.equals("true") ? "ON" : "OFF")
				: lang("value." + option.name + "." + option.defaultValue, option.defaultValue);
		text.append(Component.literal("\n\n").append(Component.translatable("afterburner.shaders.default", def)).withStyle(ChatFormatting.GRAY));
		return Tooltip.create(text);
	}

	private Component profileLabel(PackOptions o) {
		String current = o.currentProfile(values);
		Component name = current == null ? Component.translatable("afterburner.shaders.custom")
				: Component.literal(lang("profile." + current, current));
		return Component.translatable("afterburner.shaders.profile", name);
	}

	private Component packLabel() {
		String p = Shaderpacks.selected();
		return Component.translatable("afterburner.option.shaderpack", p.isEmpty() ? CommonComponents.OPTION_OFF : Component.literal(p))
				.append(dropdown ? " ▲" : " ▼");
	}

	// ---- Changes ----

	private void changed() {
		dirty = true;
		if (applyButton != null) applyButton.active = true;
		if (profileButton != null && packOptions != null) profileButton.setMessage(profileLabel(packOptions));
	}

	/** Saves the pack's values and has it loaded again with them. */
	private void save() {
		if (!dirty || pack.isEmpty()) return;
		dirty = false;
		try {
			PackOptions.writeValues(Shaderpacks.optionsFile(pack), values);
		} catch (IOException e) {
			error = e.getMessage();
		}
		if (pack.equals(Shaderpacks.selected())) Shaderpacks.reload();
		if (applyButton != null) applyButton.active = false;
	}

	private void reset() {
		if (values.isEmpty()) return;
		values.clear();
		changed();
		rebuild(true);
	}

	/** Builds the page again, at the same place in the list or at its top. */
	private void rebuild(boolean keepScroll) {
		double scroll = list != null ? list.scrollAmount() : 0;
		layout.removeChildren();
		rebuildWidgets();
		if (keepScroll && list != null) list.setScrollAmount(scroll);
	}

	@Override
	protected void addFooter() {
		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(6));
		footer.addChild(Button.builder(Component.translatable("afterburner.shaders.folder"), b -> Blaze3D.openPath(Shaderpacks.folder())).width(76).build());
		Button resetButton = footer.addChild(Button.builder(Component.translatable("afterburner.shaders.reset"), b -> reset())
				.tooltip(Tooltip.create(Component.translatable("afterburner.shaders.reset.tooltip"))).width(76).build());
		resetButton.active = packOptions != null;
		applyButton = footer.addChild(Button.builder(Component.translatable("afterburner.shaders.apply"), b -> save()).width(76).build());
		applyButton.active = dirty;
		footer.addChild(Button.builder(path.isEmpty() ? CommonComponents.GUI_DONE : CommonComponents.GUI_BACK, b -> onClose()).width(76).build());
	}

	/** Esc or Done: back a screen in the pack's settings, or away from the page (saving). */
	@Override
	public void onClose() {
		if (!path.isEmpty()) {
			path.removeLast();
			rebuild(false);
			return;
		}
		save();
		super.onClose();
	}

	// ---- Widgets ----

	/** Goes through an option's values: left click (or Enter) forward, right click (or Shift+Enter) back. */
	private final class OptionButton extends Button.Plain {
		private final PackOptions o;
		private final PackOptions.Option option;

		OptionButton(PackOptions o, PackOptions.Option option) {
			super(0, 0, SMALL, 20, Component.empty(), b -> {
			}, DEFAULT_NARRATION);
			this.o = o;
			this.option = option;
			setMessage(label(option, o.value(option, values)));
			setTooltip(tooltip(option));
		}

		@Override
		protected boolean isValidClickButton(MouseButtonInfo info) {
			return info.button() == InputConstants.MOUSE_BUTTON_LEFT || info.button() == InputConstants.MOUSE_BUTTON_RIGHT;
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			step(event.buttonInfo().button() == InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1);
		}

		@Override
		public void onPress(InputWithModifiers input) {
			step(input.hasShiftDown() ? -1 : 1);
		}

		private void step(int by) {
			List<String> all = option.values;
			int at = all.indexOf(o.value(option, values));
			String next = all.get(Math.floorMod(at + by, all.size()));
			PackOptions.set(option, next, values);
			setMessage(label(option, next));
			changed();
		}
	}

	/** An option's values along a slider. */
	private final class OptionSlider extends AbstractSliderButton {
		private final PackOptions.Option option;

		OptionSlider(PackOptions o, PackOptions.Option option) {
			super(0, 0, SMALL, 20, Component.empty(), (double) option.values.indexOf(o.value(option, values)) / (option.values.size() - 1));
			this.option = option;
			updateMessage();
			setTooltip(tooltip(option));
		}

		private String chosen() {
			return option.values.get((int) Math.round(value * (option.values.size() - 1)));
		}

		@Override
		protected void updateMessage() {
			setMessage(label(option, chosen()));
		}

		@Override
		protected void applyValue() {
			String v = chosen();
			if (v.equals(values.getOrDefault(option.name, option.defaultValue))) return;
			PackOptions.set(option, v, values);
			changed();
		}
	}

	/** The pack's profiles in turn (a profile sets many options at once). */
	private final class ProfileButton extends Button.Plain {
		private final PackOptions o;

		ProfileButton(PackOptions o) {
			super(0, 0, SMALL, 20, Component.empty(), b -> {
			}, DEFAULT_NARRATION);
			this.o = o;
			setMessage(profileLabel(o));
			setTooltip(Tooltip.create(Component.translatable("afterburner.shaders.profile.tooltip")));
		}

		@Override
		protected boolean isValidClickButton(MouseButtonInfo info) {
			return info.button() == InputConstants.MOUSE_BUTTON_LEFT || info.button() == InputConstants.MOUSE_BUTTON_RIGHT;
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			step(event.buttonInfo().button() == InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1);
		}

		@Override
		public void onPress(InputWithModifiers input) {
			step(input.hasShiftDown() ? -1 : 1);
		}

		private void step(int by) {
			List<String> names = new ArrayList<>(o.profiles.keySet());
			String current = o.currentProfile(values);
			int at = current == null ? (by > 0 ? -1 : 0) : names.indexOf(current);
			o.applyProfile(names.get(Math.floorMod(at + by, names.size())), values);
			changed();
			rebuild(true);
		}
	}
}
