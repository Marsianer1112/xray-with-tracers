package dev.macrokit;

import dev.macrokit.xray.XrayManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Hauptmenü der Mod (Standardtaste: rechte Umschalttaste). */
final class MenuScreen extends Screen {
	private final Screen parent;

	MenuScreen(Screen parent) {
		super(Component.literal("MacroKit"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int y = this.height / 2 - 40;
		this.addRenderableWidget(Button.builder(Component.literal("Macros"),
			b -> Minecraft.getInstance().gui.setScreen(new MacrosScreen(this, 0)))
			.bounds(cx - 100, y, 200, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Xray (Erzfinder)"),
			b -> Minecraft.getInstance().gui.setScreen(new XrayScreen(this)))
			.bounds(cx - 100, y + 26, 200, 20).build());
		// Fast Right Click: Schalter und Zahl der Klicks pro Klick (1, 2, 4, 8, 16, 32, 64)
		this.addRenderableWidget(Button.builder(fastLabel(), b -> {
			FastClick.toggle(Minecraft.getInstance());
			b.setMessage(fastLabel());
		}).bounds(cx - 100, y + 52, 130, 20).build());
		this.addRenderableWidget(Button.builder(countLabel(), b -> {
			int c = MacroStore.fastClickCount();
			MacroStore.setFastClickCount(c >= MacroStore.FAST_CLICK_MAX ? 1 : c * 2);
			b.setMessage(countLabel());
		}).bounds(cx + 34, y + 52, 66, 20).build());
		// Halten: so viele Klicks pro Tick, solange die Rechtsklick-Taste (oder die Dauerklick-Taste) gehalten wird
		this.addRenderableWidget(Button.builder(holdLabel(), b -> {
			MacroStore.setFastClickHold(nextHold(MacroStore.fastClickHold()));
			b.setMessage(holdLabel());
		}).bounds(cx - 100, y + 74, 200, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Schließen"), b -> this.onClose())
			.bounds(cx - 100, y + 104, 200, 20).build());
	}

	private static Component fastLabel() {
		return Component.literal("Fast Right Click: " + (FastClick.enabled() ? "AN" : "AUS"));
	}

	/** Durchlauf der Halten-Werte: aus, 10, 50, 100, 200, wieder aus. */
	private static int nextHold(int current) {
		if (current <= 0) {
			return 10;
		}
		if (current < 50) {
			return 50;
		}
		if (current < 100) {
			return 100;
		}
		if (current < MacroStore.FAST_HOLD_MAX) {
			return MacroStore.FAST_HOLD_MAX;
		}
		return 0;
	}

	private static Component holdLabel() {
		int h = MacroStore.fastClickHold();
		return Component.literal("Halten: " + (h == 0 ? "AUS" : h + " Klicks pro Tick"));
	}

	private static Component countLabel() {
		return Component.literal("Klicks: " + MacroStore.fastClickCount());
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		int cx = this.width / 2;
		int top = this.height / 2 - 40;
		g.text(this.font, "MacroKit", cx - this.font.width("MacroKit") / 2, top - 30, 0xFFFFFFFF, true);

		String state;
		if (Recorder.isRecording()) {
			state = "Aufnahme läuft: " + Recorder.currentName();
		} else if (MacroPlayer.isActive()) {
			state = "Macro läuft: " + MacroPlayer.currentName();
		} else {
			state = "Macros: bereit";
		}
		g.text(this.font, state, cx - this.font.width(state) / 2, top - 16, 0xFFAAAAAA, true);

		String xray = XrayManager.status();
		g.text(this.font, xray, cx - this.font.width(xray) / 2, top + 132, 0xFFAAAAAA, true);
		String tip = "Fast Right Click: Tasten unter Optionen > Steuerung > MacroKit";
		g.text(this.font, tip, cx - this.font.width(tip) / 2, top + 144, 0xFF888888, true);
	}
}
