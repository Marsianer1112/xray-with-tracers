package dev.macrokit.xray;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Liest die Worldgen-Daten, die Minecraft selbst mitbringt (Biome, Placed/Configured Features),
 * baut daraus die Feature-Reihenfolge wie FeatureSorter nach und liefert die Erz-Features,
 * die der Xray braucht. Nichts davon ist fest einkodiert, es kommt alles aus den Spieldaten.
 */
final class VanillaData {
	static final int ORE_STEP = 6; // GenerationStep.Decoration.UNDERGROUND_ORES

	/** Liefert den Text einer Ressource wie "data/minecraft/worldgen/biome/plains.json" oder null. */
	interface Source {
		String read(String path);
	}

	private record Node(int idx, int step, String id) {
	}

	private final Source src;
	/** Biom-ID -> Features je Step. */
	final Map<String, List<List<String>>> biomes = new LinkedHashMap<>();
	/** Biom-ID -> alle Placed-Feature-IDs des Bioms (für den Biome-Filter). */
	final Map<String, Set<String>> biomeFeatureSets = new LinkedHashMap<>();
	/** Sortierte Feature-Liste je Step. */
	final List<List<String>> steps = new ArrayList<>();
	/** Erz-Features (Step 6), die eines der bekannten Erze setzen, in Reihenfolge. */
	final List<OreFeature> oreFeatures = new ArrayList<>();
	final List<String> warnings = new ArrayList<>();

	VanillaData(Source src) {
		this.src = src;
	}

	void load() {
		List<String> ids = new ArrayList<>();
		resolveTag("minecraft:is_overworld", ids, new HashSet<>());
		if (ids.isEmpty()) {
			throw new IllegalStateException("Biom-Tag minecraft:is_overworld nicht gefunden");
		}
		for (String id : ids) {
			JsonObject b = readJson("worldgen/biome", id);
			if (b == null || !b.has("features") || !b.get("features").isJsonArray()) {
				warnings.add("Biom ohne Daten: " + id);
				continue;
			}
			List<List<String>> perStep = new ArrayList<>();
			Set<String> all = new HashSet<>();
			for (JsonElement stepEl : b.getAsJsonArray("features")) {
				List<String> list = new ArrayList<>();
				if (stepEl.isJsonArray()) {
					for (JsonElement f : stepEl.getAsJsonArray()) {
						if (f.isJsonPrimitive()) {
							String fid = norm(f.getAsString());
							list.add(fid);
							all.add(fid);
						}
					}
				}
				perStep.add(list);
			}
			biomes.put(id, perStep);
			biomeFeatureSets.put(id, all);
		}
		if (biomes.isEmpty()) {
			throw new IllegalStateException("Keine Biom-Dateien gefunden (data/minecraft/worldgen/biome)");
		}
		sortFeatures();
		parseOreFeatures();
	}

	Set<String> featuresOfBiome(String biomeId) {
		return biomeFeatureSets.get(biomeId);
	}

	// ---------------------------------------------------------------- Reihenfolge (FeatureSorter)

	private void sortFeatures() {
		Map<String, Integer> featureToIndex = new LinkedHashMap<>();
		java.util.Comparator<Node> cmp = java.util.Comparator.comparingInt(Node::step).thenComparingInt(Node::idx);
		Map<Node, Set<Node>> graph = new TreeMap<>(cmp);
		int maxSteps = 0;

		for (List<List<String>> perStep : biomes.values()) {
			List<Node> previous = new ArrayList<>();
			maxSteps = Math.max(maxSteps, perStep.size());
			for (int step = 0; step < perStep.size(); step++) {
				for (String f : perStep.get(step)) {
					int idx = featureToIndex.computeIfAbsent(f, k -> featureToIndex.size());
					previous.add(new Node(idx, step, f));
				}
			}
			for (int i = 0; i < previous.size(); i++) {
				Set<Node> set = graph.computeIfAbsent(previous.get(i), k -> new TreeSet<>(cmp));
				if (i < previous.size() - 1) {
					set.add(previous.get(i + 1));
				}
			}
		}

		List<Node> sorted = new ArrayList<>();
		Set<Node> temp = new TreeSet<>(cmp);
		Set<Node> visited = new TreeSet<>(cmp);
		for (Node n : graph.keySet()) {
			if (!visited.contains(n) && dfs(graph, visited, temp, sorted::add, n)) {
				throw new IllegalStateException("Feature-Reihenfolge enthält einen Zyklus bei " + n.id());
			}
		}
		Collections.reverse(sorted);

		for (int s = 0; s < maxSteps; s++) {
			steps.add(new ArrayList<>());
		}
		for (Node n : sorted) {
			steps.get(n.step()).add(n.id());
		}
	}

	private static boolean dfs(Map<Node, Set<Node>> graph, Set<Node> visited, Set<Node> temp,
							   Consumer<Node> out, Node node) {
		if (visited.contains(node)) {
			return false;
		}
		if (temp.contains(node)) {
			return true;
		}
		temp.add(node);
		for (Node next : graph.getOrDefault(node, Set.of())) {
			if (dfs(graph, visited, temp, out, next)) {
				return true;
			}
		}
		temp.remove(node);
		visited.add(node);
		out.accept(node);
		return false;
	}

	// ---------------------------------------------------------------- Erz-Features

	private void parseOreFeatures() {
		if (steps.size() <= ORE_STEP) {
			throw new IllegalStateException("Weniger als " + (ORE_STEP + 1) + " Decoration-Steps gefunden");
		}
		List<String> list = steps.get(ORE_STEP);
		for (int i = 0; i < list.size(); i++) {
			String id = list.get(i);
			OreFeature f;
			try {
				f = parseOre(id);
			} catch (RuntimeException e) {
				warnings.add("Feature " + id + " nicht lesbar: " + e.getMessage());
				continue;
			}
			if (f != null) {
				f.step = ORE_STEP;
				f.index = i;
				oreFeatures.add(f);
			}
		}
		if (oreFeatures.isEmpty()) {
			throw new IllegalStateException("Keine Erz-Features für die Oberwelt-Erze gefunden");
		}
	}

	/** Liefert das Feature, wenn es eines der bekannten Erze setzt und sich vollständig lesen lässt. */
	private OreFeature parseOre(String placedId) {
		JsonObject placed = readJson("worldgen/placed_feature", placedId);
		if (placed == null || !placed.has("feature")) {
			return null;
		}
		JsonElement fe = placed.get("feature");
		JsonObject configured = fe.isJsonObject() ? fe.getAsJsonObject()
			: readJson("worldgen/configured_feature", norm(fe.getAsString()));
		if (configured == null || !configured.has("type")) {
			return null;
		}
		String type = strip(configured.get("type").getAsString());
		if (!type.equals("ore") && !type.equals("scattered_ore")) {
			return null;
		}
		JsonObject cfg = configured.getAsJsonObject("config");
		Set<String> blocks = new LinkedHashSet<>();
		for (JsonElement t : cfg.getAsJsonArray("targets")) {
			JsonObject state = t.getAsJsonObject().getAsJsonObject("state");
			blocks.add(norm(state.get("Name").getAsString()));
		}
		OreType ore = null;
		for (OreType o : OreType.values()) {
			if (blocks.contains(o.deepslateBlock()) || blocks.contains(o.stoneBlock())) {
				ore = o;
			}
		}
		if (ore == null) {
			return null; // setzt keines der Erze, die der Xray kennt
		}

		List<OreFeature.Mod> mods = new ArrayList<>();
		for (JsonElement me : placed.getAsJsonArray("placement")) {
			OreFeature.Mod m = parseMod(me.getAsJsonObject());
			if (m == null) {
				warnings.add("Feature " + placedId + ": unbekannter Platzierungs-Schritt "
					+ me.getAsJsonObject().get("type"));
				return null;
			}
			mods.add(m);
		}
		return new OreFeature(placedId, ore, type.equals("scattered_ore"),
			cfg.get("size").getAsInt(),
			cfg.has("discard_chance_on_air_exposure") ? cfg.get("discard_chance_on_air_exposure").getAsFloat() : 0f,
			mods, blocks);
	}

	private OreFeature.Mod parseMod(JsonObject o) {
		String type = strip(o.get("type").getAsString());
		switch (type) {
			case "count": {
				OreFeature.IntProv p = intProv(o.get("count"));
				return p == null ? null : new OreFeature.Count(p);
			}
			case "rarity_filter":
				return new OreFeature.Rarity(o.get("chance").getAsInt());
			case "in_square":
				return new OreFeature.InSquare();
			case "biome":
				return new OreFeature.BiomeFilter();
			case "height_range": {
				JsonElement h = o.get("height");
				if (h == null || !h.isJsonObject() || !h.getAsJsonObject().has("type")) {
					return null;
				}
				JsonObject ho = h.getAsJsonObject();
				String ht = strip(ho.get("type").getAsString());
				boolean trapezoid;
				if (ht.equals("uniform")) {
					trapezoid = false;
				} else if (ht.equals("trapezoid")) {
					trapezoid = true;
				} else {
					return null;
				}
				OreFeature.Anchor min = anchor(ho.get("min_inclusive"));
				OreFeature.Anchor max = anchor(ho.get("max_inclusive"));
				if (min == null || max == null) {
					return null;
				}
				int plateau = ho.has("plateau") ? ho.get("plateau").getAsInt() : 0;
				return new OreFeature.Height(trapezoid, min, max, plateau);
			}
			default:
				return null;
		}
	}

	private static OreFeature.IntProv intProv(JsonElement e) {
		if (e == null) {
			return null;
		}
		if (e.isJsonPrimitive()) {
			int v = e.getAsInt();
			return new OreFeature.IntProv(true, v, v);
		}
		JsonObject o = e.getAsJsonObject();
		String type = o.has("type") ? strip(o.get("type").getAsString()) : "uniform";
		if (type.equals("constant")) {
			int v = o.get("value").getAsInt();
			return new OreFeature.IntProv(true, v, v);
		}
		if (type.equals("uniform")) {
			JsonObject u = o.has("value") && o.get("value").isJsonObject() ? o.getAsJsonObject("value") : o;
			return new OreFeature.IntProv(false, u.get("min_inclusive").getAsInt(), u.get("max_inclusive").getAsInt());
		}
		return null;
	}

	private static OreFeature.Anchor anchor(JsonElement e) {
		if (e == null || !e.isJsonObject()) {
			return null;
		}
		JsonObject o = e.getAsJsonObject();
		if (o.has("absolute")) {
			return new OreFeature.Anchor('a', o.get("absolute").getAsInt());
		}
		if (o.has("above_bottom")) {
			return new OreFeature.Anchor('b', o.get("above_bottom").getAsInt());
		}
		if (o.has("below_top")) {
			return new OreFeature.Anchor('t', o.get("below_top").getAsInt());
		}
		return null;
	}

	// ---------------------------------------------------------------- Hilfen

	private void resolveTag(String tag, List<String> out, Set<String> seen) {
		if (!seen.add(tag)) {
			return;
		}
		JsonObject o = readJson("tags/worldgen/biome", tag);
		if (o == null || !o.has("values")) {
			return;
		}
		JsonArray values = o.getAsJsonArray("values");
		for (JsonElement e : values) {
			String v = e.isJsonObject() ? e.getAsJsonObject().get("id").getAsString() : e.getAsString();
			if (v.startsWith("#")) {
				resolveTag(norm(v.substring(1)), out, seen);
			} else {
				v = norm(v);
				if (!out.contains(v)) {
					out.add(v);
				}
			}
		}
	}

	private JsonObject readJson(String kind, String id) {
		id = norm(id);
		int colon = id.indexOf(':');
		String path = "data/" + id.substring(0, colon) + "/" + kind + "/" + id.substring(colon + 1) + ".json";
		String text = src.read(path);
		if (text == null) {
			return null;
		}
		try {
			JsonElement el = JsonParser.parseString(text);
			return el.isJsonObject() ? el.getAsJsonObject() : null;
		} catch (RuntimeException e) {
			warnings.add("Ungültiges JSON: " + path);
			return null;
		}
	}

	private static String norm(String id) {
		return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
	}

	private static String strip(String type) {
		return type.startsWith("minecraft:") ? type.substring(10) : type;
	}
}
