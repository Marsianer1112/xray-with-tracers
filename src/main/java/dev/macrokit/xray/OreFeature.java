package dev.macrokit.xray;

import java.util.List;
import java.util.Set;

/** Ein Erz-Feature aus den Worldgen-Daten (z. B. "minecraft:ore_diamond") samt Platzierungsregeln. */
final class OreFeature {

	/** Ganzzahl-Provider: konstant (kein Zufall) oder gleichverteilt (verbraucht eine Zufallszahl). */
	record IntProv(boolean constant, int min, int max) {
		int sample(Xoro r) {
			return constant ? min : r.between(min, max);
		}
	}

	/** Höhenangabe: 'a' = absolut, 'b' = above_bottom, 't' = below_top. */
	record Anchor(char kind, int value) {
		int resolve(int minY, int height) {
			return switch (kind) {
				case 'b' -> minY + value;
				case 't' -> height - 1 + minY - value;
				default -> value;
			};
		}
	}

	sealed interface Mod permits Count, Rarity, InSquare, Height, BiomeFilter {
	}

	record Count(IntProv n) implements Mod {
	}

	record Rarity(int chance) implements Mod {
	}

	record InSquare() implements Mod {
	}

	record BiomeFilter() implements Mod {
	}

	record Height(boolean trapezoid, Anchor min, Anchor max, int plateau) implements Mod {
		int sample(Xoro r, int minY, int height) {
			int i = min.resolve(minY, height);
			int j = max.resolve(minY, height);
			if (i > j) {
				return i;
			}
			if (!trapezoid) {
				return r.between(i, j);
			}
			int k = j - i;
			if (plateau >= k) {
				return r.between(i, j);
			}
			int l = (k - plateau) / 2;
			int m = k - l;
			return i + r.between(0, m) + r.between(0, l);
		}
	}

	final String id;
	final OreType type;
	final boolean scattered;
	final int size;
	final float discard;
	final List<Mod> mods;
	/** Alle Blöcke, die dieses Feature setzt (normale und Deepslate-Variante). */
	final Set<String> blocks;
	/** Decoration-Step (6 = UNDERGROUND_ORES). */
	int step;
	/** Position in der sortierten Feature-Liste dieses Steps. */
	int index;

	OreFeature(String id, OreType type, boolean scattered, int size, float discard, List<Mod> mods, Set<String> blocks) {
		this.id = id;
		this.type = type;
		this.scattered = scattered;
		this.size = size;
		this.discard = discard;
		this.mods = mods;
		this.blocks = blocks;
	}
}
