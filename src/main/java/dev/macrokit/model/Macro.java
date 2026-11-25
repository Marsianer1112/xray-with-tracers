package dev.macrokit.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ein Macro ist entweder ein Text-Macro (Zeilen + Wartezeiten, aus {@link #text} erzeugt)
 * oder eine Aufnahme (Eingaben pro Tick in {@link #frames}).
 */
public final class Macro {
	public String name;
	/** Nur bei Text-Macros: Originaltext, z. B. "/sethome a ;; @wait 2s ;; Hallo". */
	public String text;
	/** Wartezeit in Ticks zwischen zwei Zeilen eines Text-Macros. */
	public int delay = 10;
	/** Aufnahmen: Blickrichtung relativ zur aktuellen Blickrichtung abspielen. */
	public boolean relativeLook = true;
	/** Blickrichtung (Yaw) beim Start der Aufnahme. */
	public float startYaw;
	/** Gesamtdauer in Ticks. */
	public int length;
	public List<Frame> frames = new ArrayList<>();

	public boolean isText() {
		return text != null;
	}

	/**
	 * Baut {@link #frames} aus {@link #text}. Zeilen werden mit ";;" oder Zeilenumbruch getrennt,
	 * "@wait 40" / "@wait 2s" / "@wait 500ms" wartet. Alles andere wird gesendet
	 * (mit "/" am Anfang als Befehl).
	 *
	 * @throws IllegalArgumentException bei einer ungültigen Wartezeit
	 */
	public void compile() {
		frames = new ArrayList<>();
		int tick = 0;
		for (String raw : text.split(";;|\\R")) {
			String part = raw.trim();
			if (part.isEmpty()) {
				continue;
			}
			if (part.toLowerCase(Locale.ROOT).startsWith("@wait")) {
				tick += parseWait(part.substring(5).trim());
				continue;
			}
			Frame f = new Frame(tick);
			f.say = part;
			frames.add(f);
			tick += Math.max(delay, 0);
		}
		length = Math.max(tick, 1);
	}

	private static int parseWait(String s) {
		try {
			String t = s.toLowerCase(Locale.ROOT);
			float ticks;
			if (t.endsWith("ms")) {
				ticks = Float.parseFloat(t.substring(0, t.length() - 2).trim()) / 50f;
			} else if (t.endsWith("s")) {
				ticks = Float.parseFloat(t.substring(0, t.length() - 1).trim()) * 20f;
			} else {
				ticks = Float.parseFloat(t);
			}
			return Math.max(0, Math.round(ticks));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Ungültige Wartezeit: \"" + s + "\" (erlaubt: 40, 2s, 500ms)");
		}
	}
}
