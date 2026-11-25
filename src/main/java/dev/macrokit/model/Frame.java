package dev.macrokit.model;

/**
 * Ein Zeitpunkt in einem Macro. Nur Felder, die sich ändern, sind gesetzt
 * (null = unverändert), dadurch bleiben Aufnahmen klein.
 */
public final class Frame {
	/** Tick seit Macro-Start (20 Ticks = 1 Sekunde). */
	public int tick;
	/** Bitmaske der gedrückten Tasten (siehe InputBits). */
	public Integer keys;
	/** Blickrichtung horizontal, relativ zur Startrichtung der Aufnahme. */
	public Float yaw;
	/** Blickrichtung vertikal (absolut). */
	public Float pitch;
	/** Gewählter Hotbar-Slot (0-8). */
	public Integer slot;
	/** Chat-Nachricht oder Befehl (Befehle beginnen mit "/"). */
	public String say;

	public Frame() {
	}

	public Frame(int tick) {
		this.tick = tick;
	}
}
