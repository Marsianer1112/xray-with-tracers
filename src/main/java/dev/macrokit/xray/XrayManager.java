package dev.macrokit.xray;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.macrokit.Chat;
import dev.macrokit.MacroKitClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verwaltet den Xray: berechnet die Erzpositionen chunkweise (aus dem Seed oder durch Absuchen
 * der geladenen Chunks), hält sie im Cache und liefert der Anzeige die sichtbaren Markierungen.
 */
public final class XrayManager {
	/** Ein Erzblock. {@code air} = vom Spiel nur platziert, wenn keine Luft daneben liegt (nur Seed-Modus). */
	public record Hit(int x, int y, int z, OreType type, boolean air) {
	}

	private static final Hit[] EMPTY = new Hit[0];
	private static final OreType[] TYPES = OreType.values();
	/** Rechenzeit pro Tick für neue Chunks bzw. die Prüfung (Nanosekunden). */
	private static final long TICK_BUDGET_NS = 6_000_000L;
	private static final long VERIFY_BUDGET_NS = 15_000_000L;
	private static final int[] DX = {1, -1, 0, 0, 0, 0};
	private static final int[] DY = {0, 0, 1, -1, 0, 0};
	private static final int[] DZ = {0, 0, 0, 0, 1, -1};
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static XraySettings settings = new XraySettings();
	private static VanillaData data;
	private static String dataError;
	private static boolean dataTried;

	private static final Map<Long, List<Hit>> CACHE = new HashMap<>();
	/** Seed-Modus: wie viele der 8 Nachbar-Chunks beim Berechnen eines Chunks geladen waren (für die Neuberechnung). */
	private static final Map<Long, Integer> NEIGHBORS = new HashMap<>();
	/** Höhe der Geländeoberfläche je Chunk (256 Werte, Integer.MIN_VALUE = noch nicht berechnet). */
	private static final Map<Long, int[]> SURFACE = new HashMap<>();
	private static final Pattern ID_AT_END = Pattern.compile("([a-z0-9_.-]+:[a-z0-9_./-]+)\\]?\\s*$");
	private static final OreSim SIM = new OreSim();
	private static final List<String> REPORT = new ArrayList<>();
	private static volatile Hit[] visible = EMPTY;
	/** Ticks seit der letzten Neuberechnung der sichtbaren Liste. */
	private static int sinceRebuild;
	private static int neighborCheck;
	/** Zahl aller berechneten Erzpositionen beim letzten Rebuild (bestimmt, wie oft neu berechnet wird). */
	private static int lastTotal;

	private XrayManager() {
	}

	// ---------------------------------------------------------------- Einstellungen

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("macrokit-xray.json");
	}

	public static void load() {
		Path f = file();
		if (Files.isRegularFile(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				XraySettings s = GSON.fromJson(r, XraySettings.class);
				if (s != null) {
					settings = s;
				}
			} catch (Exception e) {
				MacroKitClient.LOGGER.warn("Konnte {} nicht lesen", f, e);
			}
		}
		if (settings.shifts == null) {
			settings.shifts = new HashMap<>();
		}
		if (settings.ores == null) {
			settings.ores = new HashMap<>();
		}
		settings.radiusChunks = Math.max(1, Math.min(XraySettings.MAX_RADIUS, settings.radiusChunks));
		settings.maxMarkers = Math.max(100, Math.min(10000, settings.maxMarkers));
	}

	public static void save() {
		try {
			Files.createDirectories(file().getParent());
			try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
				GSON.toJson(settings, w);
			}
		} catch (IOException e) {
			MacroKitClient.LOGGER.error("Konnte Xray-Einstellungen nicht speichern", e);
		}
	}

	public static XraySettings settings() {
		return settings;
	}

	/** Wirft alle berechneten Chunks weg (nach Änderung von Seed, Reichweite, Modus, Kalibrierung). */
	public static void invalidate() {
		CACHE.clear();
		NEIGHBORS.clear();
		SURFACE.clear();
		visible = EMPTY;
		sinceRebuild = 0;
		lastTotal = 0;
	}

	public static void setEnabled(Minecraft mc, boolean on) {
		settings.enabled = on;
		save();
		invalidate();
		Chat.info(mc, "Xray " + (on ? "AN" : "AUS") + (on ? " (" + modeName() + ", " + settings.radiusChunks + " Chunks)" : ""));
	}

	public static void toggle(Minecraft mc) {
		setEnabled(mc, !settings.enabled);
	}

	public static String modeName() {
		return settings.scanMode ? "Scan geladener Chunks" : "Seed";
	}

	public static Hit[] visible() {
		return visible;
	}

	public static List<String> report() {
		return REPORT;
	}

	public static String status() {
		if (!settings.enabled) {
			return "Xray ist aus.";
		}
		if (dataError != null && !settings.scanMode) {
			return "Fehler: " + dataError;
		}
		return "Xray an – " + modeName() + ", " + visible.length + " Markierungen";
	}

	// ---------------------------------------------------------------- Spieldaten

	private static boolean ensureData() {
		if (data != null) {
			return true;
		}
		if (dataTried) {
			return false;
		}
		dataTried = true;
		try {
			VanillaData d = new VanillaData(path -> {
				try (InputStream in = Minecraft.class.getClassLoader().getResourceAsStream(path)) {
					return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
				} catch (IOException e) {
					return null;
				}
			});
			d.load();
			data = d;
			MacroKitClient.LOGGER.info("Worldgen-Daten gelesen: {} Biome, {} Erz-Features, {} Hinweise",
				d.biomes.size(), d.oreFeatures.size(), d.warnings.size());
			for (String w : d.warnings) {
				MacroKitClient.LOGGER.warn("Xray: {}", w);
			}
			return true;
		} catch (RuntimeException e) {
			dataError = e.getMessage() == null ? e.toString() : e.getMessage();
			MacroKitClient.LOGGER.error("Xray: Worldgen-Daten konnten nicht gelesen werden", e);
			return false;
		}
	}

	// ---------------------------------------------------------------- Blocktabellen

	/** Wird erst beim ersten Gebrauch geladen, wenn die Blöcke sicher registriert sind. */
	private static final class Tables {
		/** Wirtsgestein -> Variante (OreType.STONE oder OreType.DEEPSLATE). */
		static final IdentityHashMap<Block, Integer> HOST = new IdentityHashMap<>();
		/** Erzblock -> (Erz-Nummer << 2) | Variante. */
		static final IdentityHashMap<Block, Integer> ORE = new IdentityHashMap<>();
		/** Natürliche Geländeblöcke: Daraus ergibt sich die Höhe der Oberfläche (Bäume, Laub und Wasser zählen nicht). */
		static final IdentityHashMap<Block, Boolean> GROUND = new IdentityHashMap<>();

		static {
			HOST.put(Blocks.STONE, OreType.STONE);
			HOST.put(Blocks.GRANITE, OreType.STONE);
			HOST.put(Blocks.DIORITE, OreType.STONE);
			HOST.put(Blocks.ANDESITE, OreType.STONE);
			HOST.put(Blocks.DEEPSLATE, OreType.DEEPSLATE);
			HOST.put(Blocks.TUFF, OreType.DEEPSLATE);
			ore(OreType.COAL, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
			ore(OreType.IRON, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
			ore(OreType.COPPER, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
			ore(OreType.GOLD, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE);
			ore(OreType.REDSTONE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
			ore(OreType.LAPIS, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
			ore(OreType.DIAMOND, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
			ore(OreType.EMERALD, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
			for (Block b : HOST.keySet()) {
				GROUND.put(b, Boolean.TRUE);
			}
			for (Block b : ORE.keySet()) {
				GROUND.put(b, Boolean.TRUE);
			}
			for (Block b : new Block[] {Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.PODZOL, Blocks.MYCELIUM,
				Blocks.MUD, Blocks.CLAY, Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.SANDSTONE, Blocks.RED_SANDSTONE,
				Blocks.TERRACOTTA, Blocks.RED_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA,
				Blocks.WHITE_TERRACOTTA, Blocks.BROWN_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.SNOW_BLOCK,
				Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.CALCITE, Blocks.BEDROCK}) {
				GROUND.put(b, Boolean.TRUE);
			}
		}

		private static void ore(OreType t, Block stone, Block deepslate) {
			ORE.put(stone, (t.ordinal() << 2) | OreType.STONE);
			ORE.put(deepslate, (t.ordinal() << 2) | OreType.DEEPSLATE);
		}
	}

	/** Welche Variante (OreType.STONE / DEEPSLATE) wäre das Erz an dieser Stelle? 0 = Block passt nicht. */
	private static int variantAt(Block b, OreType t) {
		Integer host = Tables.HOST.get(b);
		if (host != null) {
			return host;
		}
		Integer ore = Tables.ORE.get(b);
		return ore != null && (ore >> 2) == t.ordinal() ? (ore & 3) : 0;
	}

	private static boolean isOreOf(Block b, OreType t) {
		Integer ore = Tables.ORE.get(b);
		return ore != null && (ore >> 2) == t.ordinal();
	}

	/** Grenzt der Block an Luft? Nachbarn in nicht geladenen Chunks zählen nicht. */
	private static boolean exposedToAir(ClientLevel lvl, BlockPos.MutableBlockPos mp, int x, int y, int z) {
		for (int i = 0; i < 6; i++) {
			int nx = x + DX[i];
			int nz = z + DZ[i];
			if (((nx >> 4) != (x >> 4) || (nz >> 4) != (z >> 4)) && !lvl.hasChunk(nx >> 4, nz >> 4)) {
				continue;
			}
			if (lvl.getBlockState(mp.set(nx, y + DY[i], nz)).isAir()) {
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------- Tick

	public static void tick(Minecraft mc) {
		stepVerify(mc);
		ClientLevel lvl = mc.level;
		LocalPlayer p = mc.player;
		if (!settings.enabled || lvl == null || p == null || lvl.dimension() != Level.OVERWORLD) {
			if (visible.length > 0) {
				visible = EMPTY;
			}
			return;
		}
		if (!settings.scanMode && !ensureData()) {
			settings.enabled = false;
			save();
			Chat.info(mc, "Xray (Seed) nicht möglich: " + dataError
				+ ". Der Scan-Modus geht trotzdem: /macro xray mode scan");
			return;
		}

		int pcx = p.getBlockX() >> 4;
		int pcz = p.getBlockZ() >> 4;
		int r = settings.radiusChunks;

		CACHE.keySet().removeIf(k -> Math.abs((int) (k >> 32) - pcx) > r + 1 || Math.abs((int) (long) k - pcz) > r + 1);
		NEIGHBORS.keySet().removeIf(k -> Math.abs((int) (k >> 32) - pcx) > r + 1 || Math.abs((int) (long) k - pcz) > r + 1);
		SURFACE.keySet().removeIf(k -> Math.abs((int) (k >> 32) - pcx) > r + 2 || Math.abs((int) (long) k - pcz) > r + 2);
		// Seed-Modus: Hat ein Chunk inzwischen mehr geladene Nachbarn, wird er neu berechnet (die Adern reichen über den Rand)
		if (!settings.scanMode && ++neighborCheck >= 20) {
			neighborCheck = 0;
			CACHE.keySet().removeIf(k -> {
				Integer old = NEIGHBORS.get(k);
				return old != null && old < 8 && loadedNeighbors(lvl, (int) (k >> 32), (int) (long) k) > old;
			});
		}

		boolean dirty = false;
		long deadline = System.nanoTime() + TICK_BUDGET_NS;
		outer:
		for (int d = 0; d <= r; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dz = -d; dz <= d; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != d) {
						continue;
					}
					int cx = pcx + dx;
					int cz = pcz + dz;
					long key = key(cx, cz);
					if (CACHE.containsKey(key) || !lvl.hasChunk(cx, cz)) {
						continue;
					}
					if (!settings.scanMode) {
						NEIGHBORS.put(key, loadedNeighbors(lvl, cx, cz));
					}
					CACHE.put(key, settings.scanMode ? scanChunk(lvl, cx, cz) : seedChunk(lvl, cx, cz));
					dirty = true;
					if (System.nanoTime() >= deadline) {
						break outer;
					}
				}
			}
		}

		// Bei großer Reichweite gibt es sehr viele Erzpositionen: dann seltener neu berechnen, sonst ruckelt das Spiel.
		// Bis ca. 60 000 Positionen (etwa 8 Chunks) wie bisher: sofort bei neuen Chunks, sonst jede Sekunde.
		sinceRebuild++;
		int gap = Math.min(60, lastTotal / 60_000);
		if ((dirty && sinceRebuild > gap) || sinceRebuild >= Math.max(20, gap * 2)) {
			sinceRebuild = 0;
			rebuild(lvl, p);
		}
	}

	private static long key(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	private static long pack(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
	}

	// ---------------------------------------------------------------- Berechnung je Chunk

	private static OreSim.BiomeCheck biomeCheck(ClientLevel lvl) {
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		return (x, y, z, featureId) -> {
			Set<String> features = featuresAt(lvl, mp.set(x, y, z));
			return features != null && features.contains(featureId);
		};
	}

	private static Set<String> featuresAt(ClientLevel lvl, BlockPos pos) {
		Holder<Biome> biome = lvl.getBiome(pos);
		String id = biome.unwrapKey().map(k -> biomeId(k.toString())).orElse(null);
		return id == null ? null : data.featuresOfBiome(id);
	}

	/** "ResourceKey[minecraft:worldgen/biome / minecraft:plains]" -> "minecraft:plains" (auch bei anderem Textformat). */
	static String biomeId(String text) {
		Matcher m = ID_AT_END.matcher(text);
		return m.find() ? m.group(1) : null;
	}

	/** Blick der Simulation in die echte Welt: Gestein, Luft und Höhe der Oberfläche (nur in geladenen Chunks). */
	private static final class WorldTerrain implements OreSim.Terrain {
		private final ClientLevel lvl;
		private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		private int lastCx = Integer.MIN_VALUE;
		private int lastCz = Integer.MIN_VALUE;
		private boolean lastLoaded;

		WorldTerrain(ClientLevel lvl) {
			this.lvl = lvl;
		}

		private boolean loaded(int x, int z) {
			int cx = x >> 4;
			int cz = z >> 4;
			if (cx != lastCx || cz != lastCz) {
				lastCx = cx;
				lastCz = cz;
				lastLoaded = lvl.hasChunk(cx, cz);
			}
			return lastLoaded;
		}

		@Override
		public boolean target(int x, int y, int z) {
			if (!loaded(x, z)) {
				return true;
			}
			Block b = lvl.getBlockState(mp.set(x, y, z)).getBlock();
			return Tables.HOST.containsKey(b) || Tables.ORE.containsKey(b);
		}

		@Override
		public boolean air(int x, int y, int z) {
			return loaded(x, z) && lvl.getBlockState(mp.set(x, y, z)).isAir();
		}

		@Override
		public int surface(int x, int z) {
			if (!loaded(x, z)) {
				return Integer.MAX_VALUE;
			}
			int[] column = SURFACE.computeIfAbsent(key(x >> 4, z >> 4), k -> {
				int[] a = new int[256];
				Arrays.fill(a, Integer.MIN_VALUE);
				return a;
			});
			int i = ((z & 15) << 4) | (x & 15);
			int h = column[i];
			if (h == Integer.MIN_VALUE) {
				h = OreSim.MIN_Y;
				for (int y = OreSim.MIN_Y + OreSim.HEIGHT - 1; y >= OreSim.MIN_Y; y--) {
					if (Tables.GROUND.containsKey(lvl.getBlockState(mp.set(x, y, z)).getBlock())) {
						h = y + 1;
						break;
					}
				}
				column[i] = h;
			}
			return h;
		}
	}

	private static int loadedNeighbors(ClientLevel lvl, int cx, int cz) {
		int n = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if ((dx != 0 || dz != 0) && lvl.hasChunk(cx + dx, cz + dz)) {
					n++;
				}
			}
		}
		return n;
	}

	private static List<Hit> seedChunk(ClientLevel lvl, int cx, int cz) {
		List<OreSim.Hit> raw = SIM.simulateChunk(data.oreFeatures,
			f -> settings.shifts.getOrDefault(f.id, 0), cx, cz, settings.seed, biomeCheck(lvl), new WorldTerrain(lvl));
		// Überschneiden sich zwei Adern, gewinnt die zuerst generierte
		Map<Long, Hit> unique = new java.util.LinkedHashMap<>();
		for (OreSim.Hit h : raw) {
			unique.putIfAbsent(pack(h.x(), h.y(), h.z()), new Hit(h.x(), h.y(), h.z(), h.type(), h.airCheck()));
		}
		return new ArrayList<>(unique.values());
	}

	private static List<Hit> scanChunk(ClientLevel lvl, int cx, int cz) {
		List<Hit> out = new ArrayList<>();
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		for (int y = OreSim.MIN_Y; y < OreSim.MIN_Y + OreSim.HEIGHT; y++) {
			for (int x = 0; x < 16; x++) {
				for (int z = 0; z < 16; z++) {
					Integer ore = Tables.ORE.get(lvl.getBlockState(mp.set((cx << 4) + x, y, (cz << 4) + z)).getBlock());
					if (ore != null) {
						out.add(new Hit(mp.getX(), mp.getY(), mp.getZ(), TYPES[ore >> 2], false));
					}
				}
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- sichtbare Liste

	private static void rebuild(ClientLevel lvl, LocalPlayer p) {
		int[] modes = new int[TYPES.length];
		boolean any = false;
		int total = 0;
		for (List<Hit> chunk : CACHE.values()) {
			total += chunk.size();
		}
		lastTotal = total;
		for (OreType t : TYPES) {
			modes[t.ordinal()] = settings.mode(t);
			any |= modes[t.ordinal()] != 0;
		}
		if (!any) {
			visible = EMPTY;
			return;
		}
		boolean scan = settings.scanMode;
		List<Hit> list = new ArrayList<>();
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos np = new BlockPos.MutableBlockPos();
		for (List<Hit> chunk : CACHE.values()) {
			for (Hit h : chunk) {
				int m = modes[h.type().ordinal()];
				if (m == 0) {
					continue;
				}
				int y = h.y();
				// Deepslate gibt es nur bis Y=7, Stein erst ab Y=0
				if (!(((m & OreType.DEEPSLATE) != 0 && y < 8) || ((m & OreType.STONE) != 0 && y >= 0))) {
					continue;
				}
				Block b = lvl.getBlockState(mp.set(h.x(), y, h.z())).getBlock();
				int v = variantAt(b, h.type());
				if (v == 0 || (m & v) == 0) {
					continue; // Luft, Wasser, Lava, schon abgebaut oder anderer Block
				}
				boolean host = Tables.HOST.containsKey(b);
				if (host && (scan || (h.air() && exposedToAir(lvl, np, h.x(), y, h.z())))) {
					continue; // Scan: nur echte Erze. Seed: Block wäre an Luft vom Spiel verworfen worden
				}
				list.add(h);
			}
		}
		double px = p.getX();
		double py = p.getY();
		double pz = p.getZ();
		int n = list.size();
		long[] keys = new long[n];
		for (int i = 0; i < n; i++) {
			float d2 = (float) dist2(list.get(i), px, py, pz);
			keys[i] = ((long) Float.floatToIntBits(d2) << 32) | i;
		}
		Arrays.sort(keys);
		int k = Math.min(n, settings.maxMarkers);
		Hit[] out = new Hit[k];
		for (int i = 0; i < k; i++) {
			out[i] = list.get((int) (keys[i] & 0xFFFFFFFFL));
		}
		visible = out;
	}

	static double dist2(Hit h, double px, double py, double pz) {
		double dx = h.x() + 0.5 - px;
		double dy = h.y() + 0.5 - py;
		double dz = h.z() + 0.5 - pz;
		return dx * dx + dy * dy + dz * dz;
	}

	// ---------------------------------------------------------------- Diagnose

	/**
	 * Schreibt einen Bericht für die Fehlersuche nach config/macrokit-xray-debug.txt (Daten, Biom, Erz-Reihenfolge und
	 * ein Vergleich der berechneten Positionen mit den echten Blöcken im Chunk des Spielers) und eine Kurzfassung in den Chat.
	 */
	public static void debug(Minecraft mc) {
		ClientLevel lvl = mc.level;
		LocalPlayer p = mc.player;
		if (lvl == null || p == null) {
			Chat.info(mc, "Du musst in einer Welt sein.");
			return;
		}
		List<String> out = new ArrayList<>();
		List<String> brief = new ArrayList<>();
		try {
			out.add("MacroKit Xray - Diagnose");
			out.add("Seed: " + settings.seed + ", Modus: " + modeName() + ", Reichweite: " + settings.radiusChunks
				+ " Chunks, Kalibrierung: " + settings.shifts);
			out.add("Dimension: " + lvl.dimension());
			if (!ensureData()) {
				out.add("Worldgen-Daten: FEHLER: " + dataError);
				brief.add("Worldgen-Daten fehlen: " + dataError);
			} else {
				out.add("Worldgen-Daten: " + data.biomes.size() + " Biome, " + data.steps.size() + " Steps, "
					+ data.oreFeatures.size() + " Erz-Features, " + data.warnings.size() + " Hinweise");
				for (String w : data.warnings) {
					out.add("  Hinweis: " + w);
				}
				for (int i = 0; i < data.steps.size(); i++) {
					out.add("Step " + i + ": " + data.steps.get(i).size() + " Features");
				}
				List<String> ores = data.steps.get(VanillaData.ORE_STEP);
				out.add("Step " + VanillaData.ORE_STEP + " (Erze) in der Reihenfolge des Spiels:");
				for (int i = 0; i < ores.size(); i++) {
					out.add("  " + i + " " + ores.get(i));
				}
				out.add("Erz-Features, die der Xray nutzt:");
				for (OreFeature f : data.oreFeatures) {
					out.add(String.format(Locale.ROOT, "  %2d %-34s %-9s Groesse %d, Verwerfen %.1f, %d Schritte, Verschiebung %d",
						f.index, f.id, f.type, f.size, f.discard, f.mods.size(), settings.shifts.getOrDefault(f.id, 0)));
				}

				int px = p.getBlockX();
				int pz = p.getBlockZ();
				int py = (int) Math.floor(p.getY());
				int cx = px >> 4;
				int cz = pz >> 4;
				BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
				Holder<Biome> biome = lvl.getBiome(mp.set(px, py, pz));
				String raw = biome.unwrapKey().map(Object::toString).orElse("(kein Schluessel)");
				String id = biomeId(raw);
				Set<String> feats = id == null ? null : data.featuresOfBiome(id);
				out.add("Position: " + px + " " + py + " " + pz + " (Chunk " + cx + " " + cz + ")");
				out.add("Biom: roh=\"" + raw + "\" erkannt=" + id + ", Features im Biom: "
					+ (feats == null ? "KEINE DATEN (Biom nicht in den Spieldaten gefunden)" : String.valueOf(feats.size())));
				brief.add("Biom: " + id + (feats == null ? " (KEINE DATEN)" : ""));

				out.add("Berechnung im Chunk " + cx + " " + cz + " (Vergleich mit den echten Blöcken):");
				OreSim sim = new OreSim();
				WorldTerrain terrain = new WorldTerrain(lvl);
				OreSim.BiomeCheck bc = biomeCheck(lvl);
				int[] typeTotal = new int[TYPES.length];
				int[] typeOre = new int[TYPES.length];
				for (OreFeature f : data.oreFeatures) {
					int shift = settings.shifts.getOrDefault(f.id, 0);
					List<OreSim.Hit> hits = sim.simulateChunk(List.of(f), ft -> shift, cx, cz, settings.seed, bc, terrain);
					int ore = 0;
					int host = 0;
					int other = 0;
					StringBuilder sample = new StringBuilder();
					for (OreSim.Hit h : hits) {
						if (!lvl.hasChunk(h.x() >> 4, h.z() >> 4)) {
							continue;
						}
						Block b = lvl.getBlockState(mp.set(h.x(), h.y(), h.z())).getBlock();
						if (isOreOf(b, f.type)) {
							ore++;
						} else if (Tables.HOST.containsKey(b)) {
							host++;
						} else {
							other++;
						}
						if (sample.length() < 200 && sample.length() / 60 < 3) {
							sample.append(" [").append(h.x()).append(' ').append(h.y()).append(' ').append(h.z())
								.append(": ").append(b).append(']');
						}
					}
					typeTotal[f.type.ordinal()] += ore + host + other;
					typeOre[f.type.ordinal()] += ore;
					out.add("  " + f.id.replace("minecraft:", "") + ": " + hits.size() + " berechnet, echtes Erz " + ore
						+ ", Gestein " + host + ", anderer Block " + other + sample);
				}
				for (OreType t : TYPES) {
					if (typeTotal[t.ordinal()] > 0) {
						brief.add(t.label + ": " + typeTotal[t.ordinal()] + " berechnet, " + typeOre[t.ordinal()] + " davon echtes Erz");
					}
				}
			}
			Path f = FabricLoader.getInstance().getConfigDir().resolve("macrokit-xray-debug.txt");
			Files.write(f, out, StandardCharsets.UTF_8);
			for (String line : brief) {
				Chat.info(mc, line);
			}
			Chat.info(mc, "Diagnose gespeichert: config/macrokit-xray-debug.txt");
		} catch (IOException | RuntimeException e) {
			MacroKitClient.LOGGER.error("Xray-Diagnose fehlgeschlagen", e);
			Chat.info(mc, "Diagnose fehlgeschlagen: " + e);
		}
	}

	// ---------------------------------------------------------------- Prüfen und kalibrieren

	/** Laufende Prüfung. Sie wird über mehrere Ticks verteilt, damit das Spiel nicht einfriert. */
	private static final class VerifyJob {
		final ClientLevel lvl;
		final List<int[]> chunks;
		final List<OreFeature> features;
		final OreSim sim = new OreSim();
		final OreSim.BiomeCheck biomes;
		final OreSim.Terrain terrain;
		final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		final BlockPos.MutableBlockPos np = new BlockPos.MutableBlockPos();
		final int[] total = new int[13];
		final double[] rate = new double[13];
		final int[] typeGood = new int[TYPES.length];
		final int[] typeUsable = new int[TYPES.length];
		final int[] typeNoData = new int[TYPES.length];
		int feature;
		int shift = -6;
		int good;
		int usable;

		VerifyJob(ClientLevel lvl, List<int[]> chunks, List<OreFeature> features) {
			this.lvl = lvl;
			this.chunks = chunks;
			this.features = features;
			this.biomes = biomeCheck(lvl);
			this.terrain = new WorldTerrain(lvl);
		}
	}

	private static VerifyJob job;

	private static void say(Minecraft mc, String line) {
		REPORT.add(line);
		Chat.info(mc, line);
	}

	/** Nur in den Bericht und ins Log, nicht in den Chat. */
	private static void note(String line) {
		REPORT.add(line);
		MacroKitClient.LOGGER.info("Xray-Prüfung: {}", line);
	}

	/**
	 * Vergleicht die berechneten Erzpositionen mit den echten Blöcken in den geladenen Chunks.
	 * Findet pro Feature die Index-Verschiebung mit der besten Übereinstimmung und speichert sie.
	 * Funktioniert nur, wenn der Server keine Anti-Xray-Verschleierung benutzt.
	 */
	public static void verify(Minecraft mc) {
		if (job != null) {
			say(mc, "Die Prüfung läuft schon.");
			return;
		}
		REPORT.clear();
		ClientLevel lvl = mc.level;
		LocalPlayer p = mc.player;
		if (lvl == null || p == null) {
			say(mc, "Du musst in einer Welt sein.");
			return;
		}
		if (lvl.dimension() != Level.OVERWORLD) {
			say(mc, "Prüfen geht nur in der Oberwelt.");
			return;
		}
		if (!ensureData()) {
			say(mc, "Worldgen-Daten fehlen: " + dataError);
			return;
		}
		int pcx = p.getBlockX() >> 4;
		int pcz = p.getBlockZ() >> 4;
		List<int[]> chunks = new ArrayList<>();
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				if (lvl.hasChunk(pcx + dx, pcz + dz)) {
					chunks.add(new int[] {pcx + dx, pcz + dz});
				}
			}
		}
		if (chunks.size() < 9) {
			say(mc, "Zu wenige Chunks geladen (" + chunks.size() + "). Warte kurz und versuche es nochmal.");
			return;
		}
		say(mc, "Prüfe " + chunks.size() + " Chunks mit Seed " + settings.seed
			+ " (" + data.oreFeatures.size() + " Features, das dauert einen Moment) …");
		job = new VerifyJob(lvl, chunks, data.oreFeatures);
	}

	public static boolean verifying() {
		return job != null;
	}

	private static void stepVerify(Minecraft mc) {
		VerifyJob j = job;
		if (j == null) {
			return;
		}
		if (mc.level != j.lvl || mc.player == null) {
			job = null;
			say(mc, "Prüfung abgebrochen, weil du die Welt gewechselt hast.");
			return;
		}
		long deadline = System.nanoTime() + VERIFY_BUDGET_NS;
		while (job != null && System.nanoTime() < deadline) {
			stepOne(mc, j);
		}
	}

	/** Rechnet ein Feature mit einer Verschiebung durch; nach der letzten Verschiebung wird ausgewertet. */
	private static void stepOne(Minecraft mc, VerifyJob j) {
		OreFeature f = j.features.get(j.feature);
		final int shift = j.shift;
		int hits = 0;
		int t = 0;
		for (int[] c : j.chunks) {
			for (OreSim.Hit h : j.sim.simulateChunk(List.of(f), ft -> shift, c[0], c[1], settings.seed, j.biomes, j.terrain)) {
				if (!j.lvl.hasChunk(h.x() >> 4, h.z() >> 4)) {
					continue;
				}
				boolean ore = isOreOf(j.lvl.getBlockState(j.mp.set(h.x(), h.y(), h.z())).getBlock(), f.type);
				if (!ore && h.airCheck() && exposedToAir(j.lvl, j.np, h.x(), h.y(), h.z())) {
					continue; // das Spiel hätte diesen Block an der Luft verworfen
				}
				t++;
				if (ore) {
					hits++;
				}
			}
		}
		j.total[shift + 6] = t;
		j.rate[shift + 6] = t == 0 ? 0 : hits / (double) t;
		if (++j.shift <= 6) {
			return;
		}

		// alle Verschiebungen dieses Features sind durch: auswerten
		int best = 0;
		for (int i = 1; i < 13; i++) {
			if (j.rate[i] > j.rate[best]) {
				best = i;
			}
		}
		double second = 0;
		for (int i = 0; i < 13; i++) {
			if (i != best) {
				second = Math.max(second, j.rate[i]);
			}
		}
		String name = f.id.replace("minecraft:", "");
		int ti = f.type.ordinal();
		if (j.total[best] < 25) {
			j.typeNoData[ti]++;
			note(name + ": zu wenig Daten (" + j.total[best] + " Blöcke in geladenen Chunks)");
		} else {
			j.usable++;
			j.typeUsable[ti]++;
			if (j.rate[best] >= 0.15 && j.rate[best] >= 3 * Math.max(second, 0.01)) {
				int sh = best - 6;
				if (sh == 0) {
					settings.shifts.remove(f.id);
				} else {
					settings.shifts.put(f.id, sh);
				}
				j.good++;
				j.typeGood[ti]++;
				note(String.format(Locale.ROOT, "%s: passt (Verschiebung %+d, %.0f%% von %d Blöcken)",
					name, sh, j.rate[best] * 100, j.total[best]));
			} else {
				note(String.format(Locale.ROOT, "%s: keine Übereinstimmung (bester Wert %.0f%% von %d)",
					name, j.rate[best] * 100, j.total[best]));
			}
		}

		j.shift = -6;
		if (++j.feature < j.features.size()) {
			return;
		}
		finishVerify(mc, j);
	}

	private static void finishVerify(Minecraft mc, VerifyJob j) {
		job = null;
		for (OreType t : TYPES) {
			int i = t.ordinal();
			int all = j.typeUsable[i] + j.typeNoData[i];
			if (all == 0) {
				continue;
			}
			String text = j.typeUsable[i] == 0
				? "zu wenig Daten in den geladenen Chunks"
				: j.typeGood[i] + " von " + j.typeUsable[i] + " Features passen";
			say(mc, t.label + ": " + text);
		}
		if (j.usable == 0) {
			say(mc, "Nichts zum Vergleichen gefunden. Geh in eine andere Gegend und versuche es nochmal.");
		} else if (j.good == 0) {
			say(mc, "Kein Erz stimmt überein: falscher Seed, Anti-Xray auf dem Server oder andere Weltgenerierung. "
				+ "Nutze dann /macro xray mode scan.");
		} else {
			say(mc, "Kalibrierung gespeichert (" + j.good + " von " + j.usable + " Features passen).");
		}
		save();
		invalidate();
	}
}
