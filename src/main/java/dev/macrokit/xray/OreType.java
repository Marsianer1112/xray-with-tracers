package dev.macrokit.xray;

import java.util.Locale;

/** Die Erze der Oberwelt, die der Xray anzeigen kann (jeweils Stein- und Deepslate-Variante). */
public enum OreType {
	COAL("Kohle", 0xFFC8C8C8),
	IRON("Eisen", 0xFFE8B896),
	COPPER("Kupfer", 0xFFFF8C28),
	GOLD("Gold", 0xFFFFE030),
	REDSTONE("Redstone", 0xFFFF3030),
	LAPIS("Lapis", 0xFF4060FF),
	DIAMOND("Diamant", 0xFF30E8FF),
	EMERALD("Smaragd", 0xFF30FF70);

	/** Modus-Bit: Deepslate-Variante anzeigen. */
	public static final int DEEPSLATE = 1;
	/** Modus-Bit: Stein-Variante anzeigen. */
	public static final int STONE = 2;

	public final String label;
	public final int color;

	OreType(String label, int color) {
		this.label = label;
		this.color = color;
	}

	/** Name des Deepslate-Blocks, z. B. "minecraft:deepslate_diamond_ore". */
	String deepslateBlock() {
		return "minecraft:deepslate_" + name().toLowerCase(Locale.ROOT) + "_ore";
	}

	/** Name des normalen Blocks, z. B. "minecraft:diamond_ore". */
	String stoneBlock() {
		return "minecraft:" + name().toLowerCase(Locale.ROOT) + "_ore";
	}

	/** Anzeigename eines Modus (0 = aus, 1 = Deepslate, 2 = Stein, 3 = beides). */
	public static String modeName(int mode) {
		return switch (mode) {
			case DEEPSLATE -> "Deepslate";
			case STONE -> "Stein";
			case DEEPSLATE | STONE -> "Beides";
			default -> "Aus";
		};
	}
}
