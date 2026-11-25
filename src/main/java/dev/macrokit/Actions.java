package dev.macrokit;

import dev.macrokit.model.Macro;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Gemeinsame Aktionen für Tasten und Befehle. Rückgabe: null = erfolgreich,
 * sonst eine Fehlermeldung für den Spieler.
 */
final class Actions {
	private static final String INVALID_NAME = "Ungültiger Name. Erlaubt: Buchstaben, Ziffern, _ und - (max. 32 Zeichen).";

	/** Zuletzt gestartetes oder aufgenommenes Macro (für die Taste "Letztes Macro wiederholen"). */
	static String lastName;

	private Actions() {
	}

	static String addText(String name, String text) {
		Minecraft mc = Minecraft.getInstance();
		if (!MacroStore.isValidName(name)) {
			return INVALID_NAME;
		}
		Macro m = new Macro();
		m.name = name;
		m.text = text;
		m.delay = MacroStore.defaultDelay();
		try {
			m.compile();
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		if (m.frames.isEmpty()) {
			return "Das Macro enthält keine Zeilen.";
		}
		if (!MacroStore.put(m)) {
			return "Konnte das Macro nicht speichern (siehe Log).";
		}
		Chat.info(mc, String.format(Locale.ROOT, "Text-Macro gespeichert: %s (%d Zeilen, %.1f s)",
			m.name, m.frames.size(), m.length / 20f));
		return null;
	}

	static String startRecording(String name) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return "Du musst in einer Welt oder auf einem Server sein.";
		}
		if (!MacroStore.isValidName(name)) {
			return INVALID_NAME;
		}
		if (Recorder.isRecording()) {
			return "Es läuft schon eine Aufnahme (" + Recorder.currentName() + ").";
		}
		if (MacroPlayer.isActive()) {
			return "Erst das laufende Macro stoppen (/macro stop).";
		}
		Recorder.start(mc, name);
		String overwrite = MacroStore.get(name) != null ? " (überschreibt das bestehende Macro)" : "";
		Chat.info(mc, "Aufnahme gestartet: " + name + overwrite
			+ ". Beenden mit /macro record stop oder der Aufnahme-Taste.");
		return null;
	}

	static String stopRecording(Minecraft mc) {
		if (!Recorder.isRecording()) {
			return "Es läuft keine Aufnahme.";
		}
		Macro m = Recorder.stop();
		if (m == null || !MacroStore.put(m)) {
			return "Konnte die Aufnahme nicht speichern (siehe Log).";
		}
		lastName = m.name;
		Chat.info(mc, String.format(Locale.ROOT, "Aufnahme gespeichert: %s (%.1f s, %d Einträge). Abspielen: /macro run %s",
			m.name, m.length / 20f, m.frames.size(), m.name));
		return null;
	}

	static String cancelRecording(Minecraft mc) {
		if (!Recorder.isRecording()) {
			return "Es läuft keine Aufnahme.";
		}
		Recorder.cancel();
		Chat.info(mc, "Aufnahme verworfen.");
		return null;
	}

	/** @param times 0 = endlos wiederholen */
	static String run(Minecraft mc, String name, int times) {
		if (mc.player == null) {
			return "Du musst in einer Welt oder auf einem Server sein.";
		}
		Macro m = MacroStore.get(name);
		if (m == null) {
			return "Unbekanntes Macro: " + name;
		}
		if (Recorder.isRecording()) {
			return "Während einer Aufnahme kann kein Macro laufen.";
		}
		MacroPlayer.start(mc, m, times);
		lastName = m.name;
		String how = times == 0 ? "endlos (stoppen mit /macro stop)" : times == 1 ? "" : times + "x";
		Chat.info(mc, "Starte " + m.name + (how.isEmpty() ? "" : " – " + how));
		return null;
	}

	static String stopPlayback(Minecraft mc) {
		if (!MacroPlayer.isActive()) {
			return "Es läuft kein Macro.";
		}
		MacroPlayer.stop(mc, true);
		return null;
	}
}
