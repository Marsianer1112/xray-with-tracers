package dev.macrokit.xray;

import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Rechnet Minecrafts Erzgenerierung für einen Chunk aus dem Weltseed nach (wie OreSim/Seed-Xray).
 *
 * <p>Vereinfachungen: Es wird angenommen, dass überall passendes Gestein liegt (Luft und Höhlen sind dem Seed nicht anzusehen).
 * Höhlen und frühere Erze, die in der echten Welt Adern verändern, sind dem Seed allein nicht anzusehen.
 * Dadurch gibt es vereinzelt falsche Treffer, vor allem in der Nähe von Höhlen.
 */
final class OreSim {
	static final int MIN_Y = -64;
	static final int HEIGHT = 384;

	/** Prüft, ob das Biom an einer Position das Placed Feature mit dieser ID enthält. */
	interface BiomeCheck {
		boolean has(int x, int y, int z, String placedFeatureId);
	}

	/**
	 * Blick in die echte Welt (nur geladene Chunks). Damit rechnet die Simulation wie das Spiel: Dort verbraucht eine
	 * Ader nur an Stellen Zufallszahlen, an denen wirklich Gestein liegt, und Adern, die ganz über dem Gelände liegen,
	 * werden gar nicht gesetzt. Ohne diese Angaben verschiebt sich der Zufall nach der ersten Ader eines Chunks.
	 */
	interface Terrain {
		/** Liegt hier Gestein oder Erz, das ein Erz-Feature setzen darf? Unbekannt (Chunk nicht geladen) = true. */
		boolean target(int x, int y, int z);

		/** Ist hier Luft? Unbekannt (Chunk nicht geladen) = false. */
		boolean air(int x, int y, int z);

		/** Höhe der Oberfläche wie bei OCEAN_FLOOR_WG (oberster fester Block + 1), unbekannt = Integer.MAX_VALUE. */
		int surface(int x, int z);
	}

	/** Unterhalb dieser Höhe gibt es kein Gelände mehr: dort wird die Höhenprüfung gar nicht erst gemacht. */
	private static final int SURFACE_CHECK_MIN_Y = 16;

	/**
	 * Ein berechneter Erzblock. {@code airCheck} = das Spiel verwirft diesen Block, wenn er an Luft grenzt
	 * (Eigenschaft discard_chance_on_air_exposure); dann darf in der echten Welt keine Luft daneben liegen.
	 */
	record Hit(int x, int y, int z, OreType type, boolean airCheck) {
	}

	private final Xoro rnd = new Xoro(0);
	private OreFeature f;
	private BiomeCheck biomes;
	private List<Hit> out;
	private Terrain terrain;
	/** Stellen, an denen in diesem Chunk schon ein Erz gesetzt wurde (dort liegt dann kein Gestein mehr). */
	private final Set<Long> placedKeys = new HashSet<>();

	/**
	 * Simuliert alle übergebenen Features für einen Chunk.
	 *
	 * @param shift Verschiebung des Feature-Index je Feature (Kalibrierung, normalerweise 0)
	 */
	List<Hit> simulateChunk(List<OreFeature> features, ToIntFunction<OreFeature> shift,
							int chunkX, int chunkZ, long worldSeed, BiomeCheck biomeCheck) {
		return simulateChunk(features, shift, chunkX, chunkZ, worldSeed, biomeCheck, null);
	}

	/** Wie oben, aber mit Blick in die echte Welt ({@code terrain} darf null sein: dann wie ohne). */
	List<Hit> simulateChunk(List<OreFeature> features, ToIntFunction<OreFeature> shift,
							int chunkX, int chunkZ, long worldSeed, BiomeCheck biomeCheck, Terrain terrain) {
		this.biomes = biomeCheck;
		this.terrain = terrain;
		this.placedKeys.clear();
		this.out = new ArrayList<>();
		long decoration = rnd.setDecorationSeed(worldSeed, chunkX << 4, chunkZ << 4);
		for (OreFeature feature : features) {
			this.f = feature;
			rnd.setFeatureSeed(decoration, feature.index + shift.applyAsInt(feature), feature.step);
			run(0, chunkX << 4, MIN_Y, chunkZ << 4);
		}
		return out;
	}

	/** Wendet die Platzierungs-Schritte der Reihe nach an, verschachtelt wie die Streams im Spiel. */
	private void run(int i, int x, int y, int z) {
		if (i == f.mods.size()) {
			if (f.scattered) {
				placeScattered(x, y, z);
			} else {
				placeVein(x, y, z);
			}
			return;
		}
		switch (f.mods.get(i)) {
			case OreFeature.Count c -> {
				int n = c.n().sample(rnd);
				for (int k = 0; k < n; k++) {
					run(i + 1, x, y, z);
				}
			}
			case OreFeature.Rarity r -> {
				if (rnd.nextFloat() < 1.0F / (float) r.chance()) {
					run(i + 1, x, y, z);
				}
			}
			case OreFeature.InSquare s -> {
				int nx = rnd.nextInt(16) + x;
				int nz = rnd.nextInt(16) + z;
				run(i + 1, nx, y, nz);
			}
			case OreFeature.Height h -> run(i + 1, x, h.sample(rnd, MIN_Y, HEIGHT), z);
			case OreFeature.BiomeFilter b -> {
				if (biomes.has(x, y, z, f.id)) {
					run(i + 1, x, y, z);
				}
			}
		}
	}

	/**
	 * Wie das Spiel (shouldSkipAirCheck): Bei 0 wird nie, bei 1 immer geprüft, dazwischen entscheidet ein
	 * Zufall pro Block (der hier verbraucht wird). Liefert true, wenn der Block nicht an Luft grenzen darf.
	 */
	private boolean airCheckNeeded() {
		if (f.discard <= 0f) {
			return false;
		}
		if (f.discard >= 1f) {
			return true;
		}
		return rnd.nextFloat() < f.discard;
	}

	// ---------------------------------------------------------------- normale Ader (OreFeature)

	private void placeVein(int ox, int oy, int oz) {
		float size = (float) f.size;
		float angle = rnd.nextFloat() * (float) Math.PI;
		float g = size / 8.0F;
		int i = Mth.ceil((size / 16.0F * 2.0F + 1.0F) / 2.0F);
		double d = (double) ox + Math.sin((double) angle) * (double) g;
		double e = (double) ox - Math.sin((double) angle) * (double) g;
		double h = (double) oz + Math.cos((double) angle) * (double) g;
		double j = (double) oz - Math.cos((double) angle) * (double) g;
		double l = (double) (oy + rnd.nextInt(3) - 2);
		double m = (double) (oy + rnd.nextInt(3) - 2);
		int n = ox - Mth.ceil(g) - i;
		int o = oy - 2 - i;
		int p = oz - Mth.ceil(g) - i;
		int q = 2 * (Mth.ceil(g) + i);
		int r = 2 * (2 + i);
		if (terrain != null && o > SURFACE_CHECK_MIN_Y && !reachesGround(o, n, p, q)) {
			return; // Das Spiel setzt eine Ader nicht, die in allen Spalten über dem Gelände liegt (und rechnet nichts durch)
		}
		fillVein(d, e, h, j, l, m, n, o, p, q, r);
	}

	/** Wie im Spiel: Die Ader wird gesetzt, sobald in einer Spalte der Box das Gelände mindestens bis zu ihrer Unterkante reicht. */
	private boolean reachesGround(int minY, int minX, int minZ, int size) {
		for (int x = minX; x <= minX + size; x++) {
			for (int z = minZ; z <= minZ + size; z++) {
				if (minY <= terrain.surface(x, z)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean adjacentToAir(int x, int y, int z) {
		return terrain.air(x + 1, y, z) || terrain.air(x - 1, y, z) || terrain.air(x, y + 1, z)
			|| terrain.air(x, y - 1, z) || terrain.air(x, y, z + 1) || terrain.air(x, y, z - 1);
	}

	private static long key(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
	}

	private void fillVein(double startX, double endX, double startZ, double endZ, double startY, double endY,
						  int minX, int minY, int minZ, int sizeX, int sizeY) {
		BitSet placed = new BitSet(sizeX * sizeY * sizeX);
		int n = f.size;
		double[] ds = new double[n * 4];
		for (int k = 0; k < n; k++) {
			float fr = (float) k / (float) n;
			double d = Mth.lerp((double) fr, startX, endX);
			double e = Mth.lerp((double) fr, startY, endY);
			double g = Mth.lerp((double) fr, startZ, endZ);
			double h = rnd.nextDouble() * (double) n / 16.0;
			double l = ((double) (Mth.sin((float) Math.PI * fr) + 1.0F) * h + 1.0) / 2.0;
			ds[k * 4] = d;
			ds[k * 4 + 1] = e;
			ds[k * 4 + 2] = g;
			ds[k * 4 + 3] = l;
		}
		for (int k = 0; k < n - 1; k++) {
			if (ds[k * 4 + 3] <= 0.0) {
				continue;
			}
			for (int m = k + 1; m < n; m++) {
				if (ds[m * 4 + 3] <= 0.0) {
					continue;
				}
				double d = ds[k * 4] - ds[m * 4];
				double e = ds[k * 4 + 1] - ds[m * 4 + 1];
				double g = ds[k * 4 + 2] - ds[m * 4 + 2];
				double h = ds[k * 4 + 3] - ds[m * 4 + 3];
				if (h * h > d * d + e * e + g * g) {
					if (h > 0.0) {
						ds[m * 4 + 3] = -1.0;
					} else {
						ds[k * 4 + 3] = -1.0;
					}
				}
			}
		}
		for (int k = 0; k < n; k++) {
			double rad = ds[k * 4 + 3];
			if (rad < 0.0) {
				continue;
			}
			double cx = ds[k * 4];
			double cy = ds[k * 4 + 1];
			double cz = ds[k * 4 + 2];
			int x0 = Math.max(Mth.floor(cx - rad), minX);
			int y0 = Math.max(Mth.floor(cy - rad), minY);
			int z0 = Math.max(Mth.floor(cz - rad), minZ);
			int x1 = Math.max(Mth.floor(cx + rad), x0);
			int y1 = Math.max(Mth.floor(cy + rad), y0);
			int z1 = Math.max(Mth.floor(cz + rad), z0);
			for (int x = x0; x <= x1; x++) {
				double u = ((double) x + 0.5 - cx) / rad;
				if (u * u >= 1.0) {
					continue;
				}
				for (int y = y0; y <= y1; y++) {
					double w = ((double) y + 0.5 - cy) / rad;
					if (u * u + w * w >= 1.0) {
						continue;
					}
					for (int z = z0; z <= z1; z++) {
						double v = ((double) z + 0.5 - cz) / rad;
						if (u * u + w * w + v * v >= 1.0 || y < MIN_Y || y >= MIN_Y + HEIGHT) {
							continue;
						}
						int idx = x - minX + (y - minY) * sizeX + (z - minZ) * sizeX * sizeY;
						if (placed.get(idx)) {
							continue;
						}
						placed.set(idx);
						if (terrain == null) {
							out.add(new Hit(x, y, z, f.type, airCheckNeeded()));
							continue;
						}
						// Wie das Spiel: nur auf Gestein wird gesetzt, nur dort wird eine Zufallszahl verbraucht
						long pk = key(x, y, z);
						if (placedKeys.contains(pk) || !terrain.target(x, y, z)) {
							continue;
						}
						boolean check = airCheckNeeded();
						if (check && adjacentToAir(x, y, z)) {
							continue; // an Luft verworfen, der Block bleibt Gestein
						}
						placedKeys.add(pk);
						out.add(new Hit(x, y, z, f.type, check));
					}
				}
			}
		}
	}

	// ---------------------------------------------------------------- verstreute Erze (ScatteredOreFeature)

	private void placeScattered(int ox, int oy, int oz) {
		int count = rnd.nextInt(f.size + 1);
		for (int j = 0; j < count; j++) {
			int spread = Math.min(j, 7);
			int dx = offset(spread);
			int dy = offset(spread);
			int dz = offset(spread);
			out.add(new Hit(ox + dx, oy + dy, oz + dz, f.type, airCheckNeeded()));
		}
	}

	private int offset(int spread) {
		return Math.round((rnd.nextFloat() - rnd.nextFloat()) * (float) spread);
	}
}
