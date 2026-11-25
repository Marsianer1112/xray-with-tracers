package dev.macrokit.xray;

import java.util.HashMap;
import java.util.Map;

/** Einstellungen des Xray, gespeichert in config/macrokit-xray.json. */
public final class XraySettings {
	/** Größte einstellbare Zahl von Tracern. */
	public static final int MAX_TRACERS = 5;
	/** Größte einstellbare Reichweite in Chunks (32 = größte Sichtweite im Spiel). */
	public static final int MAX_RADIUS = 32;

	public boolean enabled = false;
	/** Weltseed (Standard: der Seed deiner Welt). */
	public long seed = 6608149111735331168L;
	/** Reichweite in Chunks um den Spieler (Quadrat, 6 = 13 x 13 Chunks). */
	public int radiusChunks = 6;
	/**
	 * Je Erz, was angezeigt wird (Name des Erzes -> 0 aus, 1 Deepslate, 2 Stein, 3 beides).
	 * Fehlt ein Erz in der Liste, gilt der Standard: Kohle, Redstone, Diamant, Smaragd als Deepslate, der Rest aus.
	 */
	public Map<String, Integer> ores = new HashMap<>();
	/** false = aus dem Seed berechnen, true = geladene Chunks nach den Erzen absuchen. */
	public boolean scanMode = false;
	/** Wie viele Linien (Tracer) von der Bildschirmmitte zu den nächsten Erzen gezogen werden (0 = aus). */
	public int tracerCount = 3;
	/** Höchstzahl gleichzeitig gezeichneter Markierungen (die nächsten zuerst). */
	public int maxMarkers = 1500;
	/** Kalibrierung: Verschiebung des Feature-Index je Feature (nur nach /macro xray verify gesetzt). */
	public Map<String, Integer> shifts = new HashMap<>();

	private static int defaultMode(OreType t) {
		return switch (t) {
			case COAL, REDSTONE, DIAMOND, EMERALD -> OreType.DEEPSLATE;
			default -> 0;
		};
	}

	/** Anzeigemodus eines Erzes: 0 aus, 1 Deepslate, 2 Stein, 3 beides. */
	public int mode(OreType t) {
		Integer m = ores == null ? null : ores.get(t.name());
		return m == null ? defaultMode(t) : (m & 3);
	}

	public void setMode(OreType t, int mode) {
		if (ores == null) {
			ores = new HashMap<>();
		}
		ores.put(t.name(), mode & 3);
	}

	/** Schaltet ein Erz weiter: aus, Deepslate, Stein, beides, aus … */
	public int cycle(OreType t) {
		int next = (mode(t) + 1) & 3;
		setMode(t, next);
		return next;
	}
}
