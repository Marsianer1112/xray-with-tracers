package dev.macrokit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.macrokit.model.Macro;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Speichert Macros als einzelne JSON-Dateien in config/macrokit/ und die Einstellungen
 * (Slot-Belegung, Standard-Wartezeit) in config/macrokit.json.
 */
public final class MacroStore {
	public static final int SLOT_COUNT = 9;

	private static final Gson GSON = new GsonBuilder().create();
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
	private static final Map<String, Macro> MACROS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

	public static final class Settings {
		/** Wartezeit in Ticks zwischen zwei Zeilen neuer Text-Macros. */
		public int defaultDelayTicks = 10;
		/** Welches Macro auf welchem der 9 Tasten-Slots liegt (null = leer). */
		public String[] slots = new String[SLOT_COUNT];
		/** Welche Taste (GLFW-Tastencode) welches Macro direkt startet: Name des Macros -> Tastencode. */
		public Map<String, Integer> hotkeys = new HashMap<>();
		/** Fast Right Click: Zahl der Rechtsklicks, die ein einzelner Klick auslöst (1 bis 64). */
		public int fastClickCount = 32;
		/** Fast Right Click: Klicks pro Tick, solange die Taste gehalten wird (0 = aus, höchstens 200). */
		public int fastClickHold = 200;
	}

	private static Settings settings = new Settings();

	private MacroStore() {
	}

	private static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("macrokit");
	}

	private static Path settingsFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("macrokit.json");
	}

	private static Path fileFor(String name) {
		// name ist durch isValidName() auf [A-Za-z0-9_-] beschränkt, kein Pfad-Trick möglich
		return dir().resolve(name + ".json");
	}

	public static boolean isValidName(String name) {
		return name != null && NAME.matcher(name).matches();
	}

	public static synchronized void load() {
		MACROS.clear();
		settings = new Settings();

		Path sf = settingsFile();
		if (Files.isRegularFile(sf)) {
			try (Reader reader = Files.newBufferedReader(sf, StandardCharsets.UTF_8)) {
				Settings loaded = GSON.fromJson(reader, Settings.class);
				if (loaded != null) {
					settings = loaded;
				}
			} catch (Exception e) {
				MacroKitClient.LOGGER.warn("Konnte {} nicht lesen", sf, e);
			}
		}
		if (settings.slots == null || settings.slots.length != SLOT_COUNT) {
			String[] fixed = new String[SLOT_COUNT];
			if (settings.slots != null) {
				System.arraycopy(settings.slots, 0, fixed, 0, Math.min(settings.slots.length, SLOT_COUNT));
			}
			settings.slots = fixed;
		}
		if (settings.hotkeys == null) {
			settings.hotkeys = new HashMap<>();
		}

		Path d = dir();
		if (!Files.isDirectory(d)) {
			return;
		}
		List<Path> files;
		try (Stream<Path> stream = Files.list(d)) {
			files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
		} catch (IOException e) {
			MacroKitClient.LOGGER.warn("Konnte {} nicht lesen", d, e);
			return;
		}
		for (Path p : files) {
			try (Reader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				Macro m = GSON.fromJson(reader, Macro.class);
				if (m == null || !isValidName(m.name)) {
					MacroKitClient.LOGGER.warn("Überspringe {} (ungültiger Name)", p);
					continue;
				}
				if (m.frames == null) {
					m.frames = new ArrayList<>();
				}
				if (m.isText()) {
					m.compile(); // Text immer neu übersetzen, damit Handbearbeitung in der JSON wirkt
				}
				MACROS.put(m.name, m);
			} catch (Exception e) {
				MacroKitClient.LOGGER.warn("Konnte Macro {} nicht laden", p, e);
			}
		}
		MacroKitClient.LOGGER.info("{} Macros geladen", MACROS.size());
	}

	public static synchronized Macro get(String name) {
		return name == null ? null : MACROS.get(name);
	}

	public static synchronized List<String> names() {
		return new ArrayList<>(MACROS.keySet());
	}

	/** Speichert (oder überschreibt) ein Macro. */
	public static synchronized boolean put(Macro m) {
		if (m == null || !isValidName(m.name)) {
			return false;
		}
		try {
			Files.createDirectories(dir());
			try (Writer writer = Files.newBufferedWriter(fileFor(m.name), StandardCharsets.UTF_8)) {
				GSON.toJson(m, writer);
			}
			MACROS.put(m.name, m);
			return true;
		} catch (IOException e) {
			MacroKitClient.LOGGER.error("Konnte Macro {} nicht speichern", m.name, e);
			return false;
		}
	}

	public static synchronized boolean delete(String name) {
		Macro m = get(name);
		if (m == null) {
			return false;
		}
		MACROS.remove(m.name);
		try {
			Files.deleteIfExists(fileFor(m.name));
		} catch (IOException e) {
			MacroKitClient.LOGGER.warn("Konnte Datei von {} nicht löschen", m.name, e);
		}
		boolean changed = false;
		for (int i = 0; i < SLOT_COUNT; i++) {
			if (settings.slots[i] != null && settings.slots[i].equalsIgnoreCase(m.name)) {
				settings.slots[i] = null;
				changed = true;
			}
		}
		if (settings.hotkeys.remove(m.name) != null) {
			changed = true;
		}
		if (changed) {
			saveSettings();
		}
		return true;
	}

	public static synchronized boolean rename(String oldName, String newName) {
		Macro m = get(oldName);
		if (m == null || !isValidName(newName) || get(newName) != null) {
			return false;
		}
		String old = m.name;
		MACROS.remove(old);
		try {
			Files.deleteIfExists(fileFor(old));
		} catch (IOException e) {
			MacroKitClient.LOGGER.warn("Konnte alte Datei von {} nicht löschen", old, e);
		}
		m.name = newName;
		if (!put(m)) {
			return false;
		}
		boolean changed = false;
		for (int i = 0; i < SLOT_COUNT; i++) {
			if (settings.slots[i] != null && settings.slots[i].equalsIgnoreCase(old)) {
				settings.slots[i] = newName;
				changed = true;
			}
		}
		Integer key = settings.hotkeys.remove(old);
		if (key != null) {
			settings.hotkeys.put(newName, key);
			changed = true;
		}
		if (changed) {
			saveSettings();
		}
		return true;
	}

	// --- Einstellungen ---

	public static synchronized int defaultDelay() {
		return settings.defaultDelayTicks;
	}

	public static synchronized void setDefaultDelay(int ticks) {
		settings.defaultDelayTicks = Math.max(0, ticks);
		saveSettings();
	}

	/** @param index 0-8 */
	public static synchronized String slot(int index) {
		return index >= 0 && index < SLOT_COUNT ? settings.slots[index] : null;
	}

	/** @param index 0-8; name = null leert den Slot */
	public static synchronized void setSlot(int index, String name) {
		if (index >= 0 && index < SLOT_COUNT) {
			settings.slots[index] = name;
			saveSettings();
		}
	}

	// --- Direkt-Tasten (Taste drücken = Macro startet) ---

	/** Tastencode des Macros, -1 = keine Taste. */
	public static synchronized int hotkey(String name) {
		Macro m = get(name);
		Integer key = m == null ? null : settings.hotkeys.get(m.name);
		return key == null ? -1 : key;
	}

	/** Name des Macros, das auf dieser Taste liegt, sonst null. */
	public static synchronized String macroForKey(int key) {
		for (Map.Entry<String, Integer> e : settings.hotkeys.entrySet()) {
			if (e.getValue() != null && e.getValue() == key && MACROS.containsKey(e.getKey())) {
				return MACROS.get(e.getKey()).name;
			}
		}
		return null;
	}

	/** Alle Belegungen (Macro-Name -> Tastencode) als Kopie. */
	public static synchronized Map<String, Integer> hotkeys() {
		return new HashMap<>(settings.hotkeys);
	}

	/** Legt die Taste fest; eine Taste gehört immer nur einem Macro. key &lt; 0 entfernt die Taste. */
	public static synchronized void setHotkey(String name, int key) {
		Macro m = get(name);
		if (m == null) {
			return;
		}
		if (key < 0) {
			settings.hotkeys.remove(m.name);
		} else {
			settings.hotkeys.values().removeIf(v -> v != null && v == key);
			settings.hotkeys.put(m.name, key);
		}
		saveSettings();
	}

	public static final int FAST_CLICK_MAX = 64;
	public static final int FAST_HOLD_MAX = 200;

	/** Zahl der Rechtsklicks pro Klick bei Fast Right Click, 1 bis 64. */
	public static synchronized int fastClickCount() {
		return Math.max(1, Math.min(FAST_CLICK_MAX, settings.fastClickCount));
	}

	public static synchronized void setFastClickCount(int count) {
		settings.fastClickCount = Math.max(1, Math.min(FAST_CLICK_MAX, count));
		saveSettings();
	}

	/** Klicks pro Tick bei gehaltener Taste, 0 bis 200 (0 = aus). */
	public static synchronized int fastClickHold() {
		return Math.max(0, Math.min(FAST_HOLD_MAX, settings.fastClickHold));
	}

	public static synchronized void setFastClickHold(int perTick) {
		settings.fastClickHold = Math.max(0, Math.min(FAST_HOLD_MAX, perTick));
		saveSettings();
	}

	private static void saveSettings() {
		try {
			Files.createDirectories(settingsFile().getParent());
			try (Writer writer = Files.newBufferedWriter(settingsFile(), StandardCharsets.UTF_8)) {
				GSON.toJson(settings, writer);
			}
		} catch (IOException e) {
			MacroKitClient.LOGGER.error("Konnte Einstellungen nicht speichern", e);
		}
	}
}
