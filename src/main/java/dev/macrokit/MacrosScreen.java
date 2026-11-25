package dev.macrokit;

import dev.macrokit.model.Macro;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Macro-Liste: starten, Taste zuweisen, endlos wiederholen, löschen, Aufnahme starten/beenden. */
final class MacrosScreen extends Screen {
	private static final int ROWS = 6;

	private final Screen parent;
	private final int page;
	/** Name des Macros, bei dem "Löschen" einmal angeklickt wurde (zweiter Klick löscht). */
	private String pendingDelete;
	/** Macro, dem gerade eine Taste zugewiesen wird (null = keines). */
	private String capturing;
	private Hotkeys.Capture capture;
	/** Kurze Rückmeldung unter der Liste. */
	private String notice = "";
	private final Map<String, Button> keyButtons = new HashMap<>();

	MacrosScreen(Screen parent, int page) {
		super(Component.literal("MacroKit – Macros"));
		this.parent = parent;
		this.page = page;
	}

	@Override
	protected void init() {
		Minecraft mc = Minecraft.getInstance();
		int cx = this.width / 2;
		int left = cx - 150;
		this.keyButtons.clear();

		String recLabel = Recorder.isRecording() ? "Aufnahme beenden" : "Aufnahme starten";
		this.addRenderableWidget(Button.builder(Component.literal(recLabel), b -> {
			String error;
			if (Recorder.isRecording()) {
				error = Actions.stopRecording(mc);
			} else {
				error = Actions.startRecording("rec-" + LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss")));
			}
			if (error != null) {
				Chat.info(mc, error);
			}
			mc.gui.setScreen(null);
		}).bounds(left, 24, 148, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Macro stoppen"), b -> {
			String error = Actions.stopPlayback(mc);
			if (error != null) {
				Chat.info(mc, error);
			}
			mc.gui.setScreen(null);
		}).bounds(cx + 2, 24, 148, 20).build());

		List<String> names = MacroStore.names();
		int pages = Math.max(1, (names.size() + ROWS - 1) / ROWS);
		int current = Math.min(this.page, pages - 1);
		int y = 54;
		for (int i = current * ROWS; i < Math.min(names.size(), (current + 1) * ROWS); i++) {
			String name = names.get(i);
			Macro m = MacroStore.get(name);
			if (m == null) {
				continue;
			}
			String label = String.format(Locale.ROOT, "%s (%.1fs)", name, m.length / 20f);
			this.addRenderableWidget(Button.builder(Component.literal(label), b -> run(mc, name, 1))
				.bounds(left, y, 112, 20).build());

			Button keyButton = Button.builder(keyLabel(name), b -> toggleCapture(mc, name))
				.bounds(left + 114, y, 74, 20).build();
			this.keyButtons.put(name, keyButton);
			this.addRenderableWidget(keyButton);

			this.addRenderableWidget(Button.builder(Component.literal("Loop"), b -> run(mc, name, 0))
				.bounds(left + 190, y, 40, 20).build());
			boolean confirm = name.equals(this.pendingDelete);
			this.addRenderableWidget(Button.builder(Component.literal(confirm ? "Sicher?" : "Löschen"), b -> {
				if (name.equals(this.pendingDelete)) {
					MacroStore.delete(name);
					this.pendingDelete = null;
				} else {
					this.pendingDelete = name;
				}
				MacrosScreen next = new MacrosScreen(this.parent, current);
				next.pendingDelete = this.pendingDelete;
				mc.gui.setScreen(next);
			}).bounds(left + 232, y, 68, 20).build());
			y += 24;
		}

		int navY = 54 + ROWS * 24 + 4;
		Button prev = Button.builder(Component.literal("<"),
			b -> mc.gui.setScreen(new MacrosScreen(this.parent, current - 1))).bounds(left, navY, 30, 20).build();
		prev.active = current > 0;
		this.addRenderableWidget(prev);
		Button next = Button.builder(Component.literal(">"),
			b -> mc.gui.setScreen(new MacrosScreen(this.parent, current + 1))).bounds(left + 270, navY, 30, 20).build();
		next.active = current < pages - 1;
		this.addRenderableWidget(next);
		this.addRenderableWidget(Button.builder(Component.literal("Zurück"), b -> this.onClose())
			.bounds(cx - 50, navY, 100, 20).build());
	}

	private Component keyLabel(String name) {
		if (name.equals(this.capturing)) {
			return Component.literal("Drücke Taste …");
		}
		int key = MacroStore.hotkey(name);
		return Component.literal(key < 0 ? "Taste setzen" : "[" + Hotkeys.keyName(key) + "]");
	}

	private void refreshKeyButtons() {
		for (Map.Entry<String, Button> e : this.keyButtons.entrySet()) {
			e.getValue().setMessage(keyLabel(e.getKey()));
		}
	}

	/** Klick auf "Taste setzen": ab jetzt wartet das Menü auf die nächste Taste. Noch ein Klick bricht ab. */
	private void toggleCapture(Minecraft mc, String name) {
		if (name.equals(this.capturing)) {
			this.capturing = null;
			this.capture = null;
			this.notice = "Abgebrochen.";
		} else {
			this.capturing = name;
			this.capture = new Hotkeys.Capture(mc);
			this.notice = "Drücke jetzt die Taste für " + name + " (Entf = Taste entfernen)";
		}
		refreshKeyButtons();
	}

	private void onKeyCaptured(int key) {
		String name = this.capturing;
		if (key == 256) { // Esc
			this.capturing = null;
			this.capture = null;
			this.notice = "Abgebrochen.";
		} else if (key == 259 || key == 261) { // Rück / Entf
			MacroStore.setHotkey(name, -1);
			this.capturing = null;
			this.capture = null;
			this.notice = "Taste von " + name + " entfernt.";
		} else if (Hotkeys.isReserved(key)) {
			this.notice = Hotkeys.keyName(key) + " geht nicht, bitte eine andere Taste.";
			return;
		} else {
			String other = MacroStore.macroForKey(key);
			MacroStore.setHotkey(name, key);
			this.capturing = null;
			this.capture = null;
			this.notice = Hotkeys.keyName(key) + " startet jetzt " + name
				+ (other != null && !other.equalsIgnoreCase(name) ? " (vorher: " + other + ")" : "");
		}
		refreshKeyButtons();
	}

	private static void run(Minecraft mc, String name, int times) {
		String error = Actions.run(mc, name, times);
		if (error != null) {
			Chat.info(mc, error);
		}
		mc.gui.setScreen(null);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		if (this.capturing != null && this.capture != null) {
			int key = this.capture.poll(this.minecraft);
			if (key == -2) {
				this.capturing = null;
				this.capture = null;
				this.notice = "Tastenabfrage geht auf dieser Version nicht. Nutze /macro bind <1-9> <name>.";
				refreshKeyButtons();
			} else if (key >= 0) {
				onKeyCaptured(key);
			}
		}
		super.extractRenderState(g, mouseX, mouseY, delta);
		int cx = this.width / 2;
		g.text(this.font, "Macros", cx - this.font.width("Macros") / 2, 10, 0xFFFFFFFF, true);
		if (MacroStore.names().isEmpty()) {
			String hint = "Noch keine Macros. Anlegen: /macro add <name> <text>";
			g.text(this.font, hint, cx - this.font.width(hint) / 2, 62, 0xFFAAAAAA, true);
		}
		int y = 54 + ROWS * 24 + 4 + 26;
		String hint1 = "Taste setzen: Knopf klicken, dann die Taste drücken.";
		String hint2 = "Im Spiel startet sie das Macro, nochmal drücken stoppt es.";
		g.text(this.font, hint1, cx - this.font.width(hint1) / 2, y, 0xFF888888, true);
		g.text(this.font, hint2, cx - this.font.width(hint2) / 2, y + 11, 0xFF888888, true);
		if (!this.notice.isEmpty()) {
			g.text(this.font, this.notice, cx - this.font.width(this.notice) / 2, y + 24, 0xFFFFE070, true);
		}
	}
}
