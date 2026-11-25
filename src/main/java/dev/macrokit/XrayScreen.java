package dev.macrokit;

import dev.macrokit.xray.OreType;
import dev.macrokit.xray.XrayManager;
import dev.macrokit.xray.XraySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Xray-Menü: an/aus, Modus, Erze (je Aus/Deepslate/Stein/Beides), Reichweite, Prüfen/Kalibrieren. */
final class XrayScreen extends Screen {
	/** Sprungwerte für die Reichweite (Klick auf die Anzeige). */
	private static final int[] RANGE_PRESETS = {4, 8, 12, 16, 24, XraySettings.MAX_RADIUS};

	private final Screen parent;
	/** Höhe, ab der die Infozeilen unter den Knöpfen stehen. */
	private int infoY;

	XrayScreen(Screen parent) {
		super(Component.literal("MacroKit – Xray"));
		this.parent = parent;
	}

	private static Component onOff(String label, boolean value) {
		return Component.literal(label + ": " + (value ? "AN" : "AUS"));
	}

	private static Component modeLabel(XraySettings s) {
		return Component.literal("Modus: " + (s.scanMode ? "Scan geladener Chunks" : "Seed (berechnet)"));
	}

	private static Component oreLabel(OreType t, XraySettings s) {
		return Component.literal(t.label + ": " + OreType.modeName(s.mode(t)));
	}

	private static Component tracerLabel(XraySettings s) {
		return Component.literal(s.tracerCount == 0 ? "Tracer: Aus" : "Tracer: " + s.tracerCount + " nächste");
	}

	private static Component rangeLabel(XraySettings s) {
		return Component.literal("Reichweite: " + s.radiusChunks + " Chunks");
	}

	@Override
	protected void init() {
		XraySettings s = XrayManager.settings();
		Minecraft mc = Minecraft.getInstance();
		int cx = this.width / 2;
		int left = cx - 152;
		int right = cx + 2;
		int bw = 150;
		int y = 20;

		this.addRenderableWidget(Button.builder(onOff("Xray", s.enabled), b -> {
			s.enabled = !s.enabled;
			XrayManager.save();
			XrayManager.invalidate();
			b.setMessage(onOff("Xray", s.enabled));
		}).bounds(left, y, bw, 20).build());
		this.addRenderableWidget(Button.builder(modeLabel(s), b -> {
			s.scanMode = !s.scanMode;
			XrayManager.save();
			XrayManager.invalidate();
			b.setMessage(modeLabel(s));
		}).bounds(right, y, bw, 20).build());
		y += 24;

		// Erze: ein Klick schaltet Aus -> Deepslate -> Stein -> Beides
		OreType[] types = OreType.values();
		for (int i = 0; i < types.length; i++) {
			OreType t = types[i];
			int col = i % 2;
			int row = i / 2;
			this.addRenderableWidget(Button.builder(oreLabel(t, s), b -> {
				s.cycle(t);
				XrayManager.save();
				b.setMessage(oreLabel(t, s));
			}).bounds(col == 0 ? left : right, y + row * 22, bw, 20).build());
		}
		y += 22 * ((types.length + 1) / 2) + 2;

		// Reichweite links (- Anzeige +), Tracer rechts
		// Mitte: ein Klick springt zur nächsten Voreinstellung (4, 8, 12, 16, 24, 32), - und + gehen in Einerschritten
		Button label = Button.builder(rangeLabel(s), b -> {
			int next = RANGE_PRESETS[0];
			for (int preset : RANGE_PRESETS) {
				if (preset > s.radiusChunks) {
					next = preset;
					break;
				}
			}
			s.radiusChunks = next;
			XrayManager.save();
			XrayManager.invalidate();
			b.setMessage(rangeLabel(s));
		}).bounds(left + 22, y, bw - 44, 20).build();
		this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
			s.radiusChunks = Math.max(1, s.radiusChunks - 1);
			XrayManager.save();
			XrayManager.invalidate();
			label.setMessage(rangeLabel(s));
		}).bounds(left, y, 20, 20).build());
		this.addRenderableWidget(label);
		this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
			s.radiusChunks = Math.min(XraySettings.MAX_RADIUS, s.radiusChunks + 1);
			XrayManager.save();
			XrayManager.invalidate();
			label.setMessage(rangeLabel(s));
		}).bounds(left + bw - 20, y, 20, 20).build());
		this.addRenderableWidget(Button.builder(tracerLabel(s), b -> {
			s.tracerCount = s.tracerCount >= XraySettings.MAX_TRACERS ? 0 : s.tracerCount + 1;
			XrayManager.save();
			b.setMessage(tracerLabel(s));
		}).bounds(right, y, bw, 20).build());
		y += 24;

		this.addRenderableWidget(Button.builder(Component.literal("Prüfen und kalibrieren"), b -> {
			XrayManager.verify(mc);
		}).bounds(left, y, bw, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Zurück"), b -> this.onClose())
			.bounds(right, y, bw, 20).build());
		y += 28;
		this.infoY = y;
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		XraySettings s = XrayManager.settings();
		int cx = this.width / 2;
		String title = "Xray";
		g.text(this.font, title, cx - this.font.width(title) / 2, 6, 0xFFFFFFFF, true);

		int y = this.infoY;
		String hint = "Klick auf ein Erz: Aus, Deepslate, Stein, Beides";
		g.text(this.font, hint, cx - this.font.width(hint) / 2, y, 0xFFAAAAAA, true);
		y += 11;
		String rangeHint = "Reichweite: Klick auf die Zahl springt weiter (bis 32). Es zählen nur geladene Chunks.";
		g.text(this.font, rangeHint, cx - this.font.width(rangeHint) / 2, y, 0xFFAAAAAA, true);
		y += 11;
		String seed = "Seed: " + s.seed + "  (ändern: /macro xray seed <zahl>)";
		g.text(this.font, seed, cx - this.font.width(seed) / 2, y, 0xFFAAAAAA, true);
		y += 11;
		String status = XrayManager.verifying() ? "Prüfung läuft … (Ergebnis im Chat)" : XrayManager.status();
		g.text(this.font, status, cx - this.font.width(status) / 2, y, 0xFFAAAAAA, true);

		List<String> report = XrayManager.report();
		int from = Math.max(0, report.size() - 3);
		for (int i = from; i < report.size(); i++) {
			y += 11;
			String line = report.get(i);
			g.text(this.font, line, cx - this.font.width(line) / 2, y, 0xFFCCCC88, true);
		}
	}
}
