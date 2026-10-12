package com.afterburner.labs.gui;

import com.afterburner.labs.shaderpack.PackOptions;
import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
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
public final class PacksScreen extends Screen {
	private static final Component TITLE = Component.translatable("afterburner.shaders.title");
	private static final int SMALL = 150;

	private final Screen lastScreen;
	private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private @Nullable PacksList list;

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

	public PacksScreen(Screen lastScreen, Options options) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		this.layout = new HeaderAndFooterLayout(this);
		this.layout.addTitleHeader(TITLE, this.font);
		this.list = this.layout.addToContents(new PacksList(this.minecraft, this.width, this.layout.getContentHeight(), this.layout.getHeaderHeight(), 25));
		this.addOptions();
		this.addFooter();
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
		if (this.list != null) this.list.updateSize(this.width, this.layout);
	}

	private void addOptions() {
		loadOptions();
		this.profileButton = null;
		PacksList list = this.list;
		list.add(Button.builder(packLabel(), b -> {
			this.dropdown = !this.dropdown;
			this.rebuild(true);
		}).tooltip(Tooltip.create(Component.translatable("afterburner.option.shaderpack.tooltip"))).width(310).build());
		if (this.dropdown) {
			this.addChoice("", CommonComponents.OPTION_OFF);
			for (String p : Shaderpacks.available()) this.addChoice(p, Component.literal(p));
		}
		if (this.pack.isEmpty()) return;

		PackOptions o = this.packOptions;
		if (o == null) {
			list.add(header(Component.translatable("afterburner.shaders.unreadable", String.valueOf(this.error)).withStyle(ChatFormatting.RED)));
			return;
		}
		String screen = this.screen();
		list.add(header((screen.isEmpty() ? Component.translatable("afterburner.shaders.settings") : this.screenName(screen))
				.withStyle(ChatFormatting.UNDERLINE, ChatFormatting.BOLD)));
		List<AbstractWidget> widgets = new ArrayList<>();
		for (String entry : entries(o, screen)) {
			if (entry.equals("*")) {
				for (String name : unplaced(o)) widgets.add(this.widget(o, name));
			} else {
				widgets.add(this.widget(o, entry));
			}
		}
		if (widgets.isEmpty()) list.add(header(Component.translatable("afterburner.shaders.none").withStyle(ChatFormatting.GRAY)));
		else {
			for (int i = 0; i < widgets.size(); i += 2) {
				if (i + 1 < widgets.size()) list.add(widgets.get(i), widgets.get(i + 1));
				else list.add(widgets.get(i));
			}
		}
	}

	/** One pack in the drop-down; the chosen one is ticked. */
	private void addChoice(String name, Component label) {
		boolean chosen = name.equals(Shaderpacks.selected());
		MutableComponent text = chosen ? Component.literal("✔ ").append(label).withStyle(ChatFormatting.GREEN) : label.copy();
		this.list.add(Button.builder(text, b -> {
			this.dropdown = false;
			if (!chosen) {
				this.save();
				Shaderpacks.select(name);
			}
			this.rebuild(false);
		}).width(310).build());
	}

	/** Reads the chosen pack's options, once per pack. */
	private void loadOptions() {
		String selected = Shaderpacks.selected();
		if (selected.equals(this.pack) && (selected.isEmpty() || this.packOptions != null || this.error != null)) return;
		this.pack = selected;
		this.packOptions = null;
		this.error = null;
		this.path.clear();
		this.dirty = false;
		this.values = new LinkedHashMap<>();
		if (selected.isEmpty()) return;
		this.values = PackOptions.readValues(Shaderpacks.optionsFile(selected));
		try {
			this.packOptions = Shaderpacks.options(selected);
		} catch (IOException | RuntimeException e) {
			this.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
		}
	}

	private String screen() {
		String last = this.path.peekLast();
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
			this.profileButton = b;
			return b;
		}
		if (entry.startsWith("[") && entry.endsWith("]") && entry.length() > 2) {
			String sub = entry.substring(1, entry.length() - 1);
			return Button.builder(this.screenName(sub).append("..."), b -> {
				this.path.addLast(sub);
				this.rebuild(false);
			}).width(SMALL).build();
		}
		PackOptions.Option option = o.options.get(entry);
		// Empty spaces, and options this pack doesn't have as the game sees it, keep the pack's layout.
		if (option == null) return spacer();
		if (o.sliders.contains(option.name) && !option.isBoolean() && option.values.size() > 2) return new OptionSlider(o, option);
		return new OptionButton(o, option);
	}

	private StringWidget spacer() {
		return new StringWidget(SMALL, 20, Component.empty(), this.font);
	}

	// ---- Names, from the pack's lang file ----

	private String lang(String key, String fallback) {
		PackOptions o = this.packOptions;
		String v = o == null ? null : o.lang.get(key);
		return v != null ? v : fallback;
	}

	private MutableComponent screenName(String screen) {
		return Component.literal(this.lang("screen." + screen, screen));
	}

	private Component label(PackOptions.Option option, String value) {
		MutableComponent text = Component.literal(this.lang("option." + option.name, option.name) + ": ");
		if (option.isBoolean()) {
			boolean on = value.equals("true");
			return text.append(Component.empty().append(on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF)
					.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
		}
		String shown = this.lang("value." + option.name + "." + value, null);
		if (shown == null) shown = this.lang("prefix." + option.name, "") + value + this.lang("suffix." + option.name, "");
		MutableComponent v = Component.literal(shown);
		return text.append(value.equals(option.defaultValue) ? v : v.withStyle(ChatFormatting.YELLOW));
	}

	private Tooltip tooltip(PackOptions.Option option) {
		MutableComponent text = Component.literal(this.lang("option." + option.name + ".comment", this.lang("option." + option.name, option.name)));
		String def = option.isBoolean() ? (option.defaultValue.equals("true") ? "ON" : "OFF")
				: this.lang("value." + option.name + "." + option.defaultValue, option.defaultValue);
		text.append(Component.literal("\n\n").append(Component.translatable("afterburner.shaders.default", def)).withStyle(ChatFormatting.GRAY));
		return Tooltip.create(text);
	}

	private Component profileLabel(PackOptions o) {
		String current = o.currentProfile(this.values);
		Component name = current == null ? Component.translatable("afterburner.shaders.custom")
				: Component.literal(this.lang("profile." + current, current));
		return Component.translatable("afterburner.shaders.profile", name);
	}

	private Component packLabel() {
		String p = Shaderpacks.selected();
		return Component.translatable("afterburner.option.shaderpack", p.isEmpty() ? CommonComponents.OPTION_OFF : Component.literal(p))
				.append(this.dropdown ? " ▲" : " ▼");
	}

	// ---- Changes ----

	private void changed() {
		this.dirty = true;
		if (this.applyButton != null) this.applyButton.active = true;
		if (this.profileButton != null && this.packOptions != null) this.profileButton.setMessage(this.profileLabel(this.packOptions));
	}

	/** Saves the pack's values and has it loaded again with them. */
	private void save() {
		if (!this.dirty || this.pack.isEmpty()) return;
		this.dirty = false;
		try {
			PackOptions.writeValues(Shaderpacks.optionsFile(this.pack), this.values);
		} catch (IOException e) {
			this.error = e.getMessage();
		}
		if (this.pack.equals(Shaderpacks.selected())) Shaderpacks.reload();
		if (this.applyButton != null) this.applyButton.active = false;
	}

	private void reset() {
		if (this.values.isEmpty()) return;
		this.values.clear();
		this.changed();
		this.rebuild(true);
	}

	/** Builds the page again, at the same place in the list or at its top. */
	private void rebuild(boolean keepScroll) {
		double scroll = this.list != null ? this.list.getScrollAmount() : 0;
		this.rebuildWidgets();
		if (keepScroll && this.list != null) this.list.setScrollAmount(scroll);
	}

	private void addFooter() {
		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(6));
		footer.addChild(Button.builder(Component.translatable("afterburner.shaders.folder"), b -> Util.getPlatform().openPath(Shaderpacks.folder())).width(76).build());
		Button resetButton = footer.addChild(Button.builder(Component.translatable("afterburner.shaders.reset"), b -> this.reset())
				.tooltip(Tooltip.create(Component.translatable("afterburner.shaders.reset.tooltip"))).width(76).build());
		resetButton.active = this.packOptions != null;
		this.applyButton = footer.addChild(Button.builder(Component.translatable("afterburner.shaders.apply"), b -> this.save()).width(76).build());
		this.applyButton.active = this.dirty;
		footer.addChild(Button.builder(this.path.isEmpty() ? CommonComponents.GUI_DONE : CommonComponents.GUI_BACK, b -> this.onClose()).width(76).build());
	}

	/** Esc or Done: back a screen in the pack's settings, or away from the page (saving). */
	@Override
	public void onClose() {
		if (!this.path.isEmpty()) {
			this.path.removeLast();
			this.rebuild(false);
			return;
		}
		this.save();
		this.minecraft.setScreen(this.lastScreen);
	}

	// ---- The list and its rows ----

	private Row header(Component text) {
		return new Row(new StringWidget(310, 20, text, this.font));
	}

	/**
	 * The page's list: rows of widgets, one across it or two side by side, laid out as the newer list does (1.21.1's own
	 * options list has no headers to put them in).
	 */
	private final class PacksList extends ContainerObjectSelectionList<Row> {
		PacksList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
			super(minecraft, width, height, y, itemHeight);
			this.centerListVertically = false;
		}

		@Override
		public int getRowWidth() {
			return 310;
		}

		void add(AbstractWidget... widgets) {
			this.addEntry(new Row(widgets));
		}

		void add(Row row) {
			this.addEntry(row);
		}
	}

	/** A row: one widget across the page, or two side by side. */
	private final class Row extends ContainerObjectSelectionList.Entry<Row> {
		private final List<AbstractWidget> children;

		Row(AbstractWidget... widgets) {
			this.children = List.of(widgets);
		}

		@Override
		public void render(GuiGraphics guiGraphics, int index, int y, int x, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTick) {
			int at = PacksScreen.this.width / 2 - 155;
			for (AbstractWidget widget : this.children) {
				widget.setPosition(at, y);
				widget.render(guiGraphics, mouseX, mouseY, partialTick);
				at += 160;
			}
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return this.children;
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return this.children;
		}
	}

	// ---- Widgets ----

	/** Goes through an option's values: left click (or Enter) forward, right click (or Shift+Enter) back. */
	private final class OptionButton extends Button {
		private final PackOptions o;
		private final PackOptions.Option option;

		OptionButton(PackOptions o, PackOptions.Option option) {
			super(0, 0, SMALL, 20, Component.empty(), b -> {
			}, DEFAULT_NARRATION);
			this.o = o;
			this.option = option;
			this.setMessage(PacksScreen.this.label(option, o.value(option, PacksScreen.this.values)));
			this.setTooltip(PacksScreen.this.tooltip(option));
		}

		@Override
		protected boolean isValidClickButton(int button) {
			return button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT;
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (this.active && this.visible && this.isValidClickButton(button) && this.clicked(mouseX, mouseY)) {
				this.playDownSound(Minecraft.getInstance().getSoundManager());
				this.step(button == InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1);
				return true;
			}
			return false;
		}

		@Override
		public void onPress() {
			this.step(Screen.hasShiftDown() ? -1 : 1);
		}

		private void step(int by) {
			List<String> all = this.option.values;
			int at = all.indexOf(this.o.value(this.option, PacksScreen.this.values));
			String next = all.get(Math.floorMod(at + by, all.size()));
			PackOptions.set(this.option, next, PacksScreen.this.values);
			this.setMessage(PacksScreen.this.label(this.option, next));
			PacksScreen.this.changed();
		}
	}

	/** An option's values along a slider. */
	private final class OptionSlider extends AbstractSliderButton {
		private final PackOptions.Option option;

		OptionSlider(PackOptions o, PackOptions.Option option) {
			super(0, 0, SMALL, 20, Component.empty(), (double) option.values.indexOf(o.value(option, PacksScreen.this.values)) / (option.values.size() - 1));
			this.option = option;
			this.updateMessage();
			this.setTooltip(PacksScreen.this.tooltip(option));
		}

		private String chosen() {
			return this.option.values.get((int) Math.round(this.value * (this.option.values.size() - 1)));
		}

		@Override
		protected void updateMessage() {
			this.setMessage(PacksScreen.this.label(this.option, this.chosen()));
		}

		@Override
		protected void applyValue() {
			String v = this.chosen();
			if (v.equals(PacksScreen.this.values.getOrDefault(this.option.name, this.option.defaultValue))) return;
			PackOptions.set(this.option, v, PacksScreen.this.values);
			PacksScreen.this.changed();
		}
	}

	/** The pack's profiles in turn (a profile sets many options at once). */
	private final class ProfileButton extends Button {
		private final PackOptions o;

		ProfileButton(PackOptions o) {
			super(0, 0, SMALL, 20, Component.empty(), b -> {
			}, DEFAULT_NARRATION);
			this.o = o;
			this.setMessage(PacksScreen.this.profileLabel(o));
			this.setTooltip(Tooltip.create(Component.translatable("afterburner.shaders.profile.tooltip")));
		}

		@Override
		protected boolean isValidClickButton(int button) {
			return button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT;
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (this.active && this.visible && this.isValidClickButton(button) && this.clicked(mouseX, mouseY)) {
				this.playDownSound(Minecraft.getInstance().getSoundManager());
				this.step(button == InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1);
				return true;
			}
			return false;
		}

		@Override
		public void onPress() {
			this.step(Screen.hasShiftDown() ? -1 : 1);
		}

		private void step(int by) {
			List<String> names = new ArrayList<>(this.o.profiles.keySet());
			String current = this.o.currentProfile(PacksScreen.this.values);
			int at = current == null ? (by > 0 ? -1 : 0) : names.indexOf(current);
			this.o.applyProfile(names.get(Math.floorMod(at + by, names.size())), PacksScreen.this.values);
			PacksScreen.this.changed();
			PacksScreen.this.rebuild(true);
		}
	}
}
