package dev.macrokit;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Direkt-Tasten: Eine Taste startet ein Macro, ohne Menü und ohne /macro run.
 *
 * <p>Der Tastenzustand wird direkt bei GLFW abgefragt (jedes Bild und jeden Tick), damit auch kurze Tastendrücke
 * nicht verloren gehen. Der Zugriff auf GLFW und das Fenster läuft über Reflection, damit er nicht von
 * Klassennamen einzelner Minecraft-Versionen abhängt. Gestartet wird erst im Tick, nie mitten im Zeichnen.
 */
final class Hotkeys {
	static final int FIRST_KEY = 32;
	static final int LAST_KEY = 348;

	private static final boolean[] DOWN = new boolean[LAST_KEY + 1];
	private static final ConcurrentLinkedQueue<Integer> PENDING = new ConcurrentLinkedQueue<>();

	private static Method windowHandle;
	private static boolean windowSearched;
	private static Method glfwGetKey;
	private static Method glfwGetKeyName;
	private static boolean glfwSearched;
	private static boolean warned;

	private Hotkeys() {
	}

	/** Wird jedes Bild aufgerufen: fragt die Tasten ab und zeigt die Fast-Right-Click-Anzeige. */
	static void register() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
			Identifier.fromNamespaceAndPath(MacroKitClient.MOD_ID, "hotkeys"),
			(graphics, deltaTracker) -> {
				Minecraft mc = Minecraft.getInstance();
				poll(mc);
				FastClick.poll(mc);
				FastClick.indicator(graphics, mc);
			});
	}

	/** Am Ende jedes Ticks: Tasten abfragen und die gemerkten Tastendrücke ausführen. */
	static void tick(Minecraft mc) {
		poll(mc);
		Integer key;
		while ((key = PENDING.poll()) != null) {
			fire(mc, key);
		}
	}

	private static void fire(Minecraft mc, int key) {
		if (mc.player == null || Recorder.isRecording()) {
			return;
		}
		String name = MacroStore.macroForKey(key);
		if (name == null) {
			return;
		}
		if (MacroPlayer.isActive() && name.equalsIgnoreCase(MacroPlayer.currentName())) {
			MacroPlayer.stop(mc, true); // dieselbe Taste noch einmal = stoppen
			return;
		}
		String error = Actions.run(mc, name, 1);
		if (error != null) {
			Chat.info(mc, error);
		}
	}

	/**
	 * Merkt sich neue Tastendrücke. Der Zustand der belegten Tasten wird immer nachgeführt, ausgelöst wird aber nur
	 * im Spiel ohne offenen Bildschirm (nicht im Chat, Inventar oder Menü).
	 */
	static synchronized void poll(Minecraft mc) {
		Map<String, Integer> keys = MacroStore.hotkeys();
		if (keys.isEmpty()) {
			return;
		}
		long window = window(mc);
		if (window == 0L || !glfwReady()) {
			warnOnce(mc);
			return;
		}
		boolean canFire = mc.player != null && mc.gui.screen() == null;
		for (Integer boxed : keys.values()) {
			if (boxed == null || boxed < FIRST_KEY || boxed > LAST_KEY) {
				continue;
			}
			int key = boxed;
			boolean down = isDown(window, key);
			if (down && !DOWN[key] && canFire) {
				PENDING.add(key);
			}
			DOWN[key] = down;
		}
	}

	private static void warnOnce(Minecraft mc) {
		if (!warned && mc.player != null) {
			warned = true;
			Chat.info(mc, "Direkt-Tasten gehen auf dieser Version nicht (Zugriff auf das Fenster fehlt). "
				+ "Nutze stattdessen die Slots: /macro bind <1-9> <name>");
		}
	}

	// ---------------------------------------------------------------- Tastenerfassung im Menü

	/** Wartet im Menü auf die nächste neu gedrückte Taste. Schon gehaltene Tasten zählen nicht. */
	static final class Capture {
		private final boolean[] held = new boolean[LAST_KEY + 1];

		Capture(Minecraft mc) {
			long window = window(mc);
			if (window != 0L && glfwReady()) {
				for (int k = FIRST_KEY; k <= LAST_KEY; k++) {
					held[k] = isDown(window, k);
				}
			}
		}

		/** @return Tastencode der neu gedrückten Taste, -1 = noch keine, -2 = Tastenabfrage nicht möglich */
		int poll(Minecraft mc) {
			long window = window(mc);
			if (window == 0L || !glfwReady()) {
				return -2;
			}
			int found = -1;
			for (int k = FIRST_KEY; k <= LAST_KEY; k++) {
				boolean down = isDown(window, k);
				if (down && !held[k] && found < 0) {
					found = k;
				}
				held[k] = down;
			}
			return found;
		}
	}

	/** Diese Tasten bleiben dem Menü vorbehalten und können keinem Macro zugewiesen werden. */
	static boolean isReserved(int key) {
		return key == 32 || key == 256 || key == 257 || key == 258 || (key >= 340 && key <= 347);
	}

	// ---------------------------------------------------------------- Zugriff auf Fenster und GLFW

	/** Fenster-Handle (long) des Spiels; 0 = nicht gefunden. */
	private static long window(Minecraft mc) {
		Object window = mc.getWindow();
		if (window == null) {
			return 0L;
		}
		if (!windowSearched) {
			windowSearched = true;
			for (String name : new String[] {"handle", "getWindow", "window"}) {
				try {
					Method m = window.getClass().getMethod(name);
					if (m.getReturnType() == long.class) {
						windowHandle = m;
						break;
					}
				} catch (NoSuchMethodException ignored) {
					// nächsten Namen probieren
				}
			}
			if (windowHandle == null) {
				MacroKitClient.LOGGER.error("Fenster-Handle nicht gefunden, Direkt-Tasten sind aus");
			}
		}
		if (windowHandle == null) {
			return 0L;
		}
		try {
			return (Long) windowHandle.invoke(window);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return 0L;
		}
	}

	private static boolean glfwReady() {
		if (!glfwSearched) {
			glfwSearched = true;
			try {
				Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW", true, Minecraft.class.getClassLoader());
				glfwGetKey = glfw.getMethod("glfwGetKey", long.class, int.class);
				glfwGetKeyName = glfw.getMethod("glfwGetKeyName", int.class, int.class);
			} catch (ReflectiveOperationException | LinkageError e) {
				MacroKitClient.LOGGER.error("GLFW nicht gefunden, Direkt-Tasten sind aus", e);
			}
		}
		return glfwGetKey != null;
	}

	private static boolean isDown(long window, int key) {
		try {
			return (Integer) glfwGetKey.invoke(null, window, key) == 1; // GLFW_PRESS
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/** Name einer Taste für die Anzeige, z. B. "F10", "Num 5" oder "Pos1". */
	static String keyName(int key) {
		if (key < 0) {
			return "–";
		}
		if ((key >= 'A' && key <= 'Z') || (key >= '0' && key <= '9')) {
			return String.valueOf((char) key);
		}
		if (key >= 290 && key <= 314) {
			return "F" + (key - 289);
		}
		if (key >= 320 && key <= 329) {
			return "Num " + (key - 320);
		}
		switch (key) {
			case 32: return "Leertaste";
			case 256: return "Esc";
			case 257: return "Enter";
			case 258: return "Tab";
			case 259: return "Rück";
			case 260: return "Einfg";
			case 261: return "Entf";
			case 262: return "Rechts";
			case 263: return "Links";
			case 264: return "Runter";
			case 265: return "Hoch";
			case 266: return "Bild hoch";
			case 267: return "Bild runter";
			case 268: return "Pos1";
			case 269: return "Ende";
			case 280: return "Feststell";
			case 281: return "Rollen";
			case 282: return "Num Lock";
			case 283: return "Druck";
			case 284: return "Pause";
			case 330: return "Num .";
			case 331: return "Num /";
			case 332: return "Num *";
			case 333: return "Num -";
			case 334: return "Num +";
			case 335: return "Num Enter";
			case 336: return "Num =";
			case 340: return "Shift links";
			case 341: return "Strg links";
			case 342: return "Alt links";
			case 344: return "Shift rechts";
			case 345: return "Strg rechts";
			case 346: return "Alt rechts";
			case 348: return "Menü";
			default:
				break;
		}
		if (glfwReady()) {
			try {
				Object name = glfwGetKeyName.invoke(null, key, 0);
				if (name instanceof String s && !s.isEmpty()) {
					return s.toUpperCase(Locale.ROOT);
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// fällt auf die Nummer zurück
			}
		}
		return "Taste " + key;
	}
}
