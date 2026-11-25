package dev.macrokit;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.lang.reflect.Method;

/**
 * Fast Right Click als Salve: Jeder neue Druck der Benutzen-Taste (normalerweise rechte Maustaste) löst im selben
 * Tick {@link MacroStore#fastClickCount()} Rechtsklicks aus (Standard 32). Ohne neuen Druck passiert nichts, es klickt
 * also nichts von allein. Gehaltene Taste wiederholt nur so schnell wie im normalen Spiel.
 *
 * <p>Dauerklick: Wer die Benutzen-Taste länger als {@link #HOLD_DELAY_TICKS} Ticks hält, löst
 * {@link MacroStore#fastClickHold()} Klicks pro Tick aus (Standard 200). Dasselbe macht eine eigene Taste oder
 * Maustaste (Steuerung &gt; MacroKit &gt; Dauerklick), solange sie gehalten wird, auch ohne den Schalter.
 *
 * <p>Ein Klick führt das Spiel selbst aus; die Mod ruft die Benutzen-Methode des Spiels zusätzlich auf. Die Methode
 * ist privat und wird per Reflection gesucht, damit der Build nicht von Klassendetails abhängt.
 */
final class FastClick {
	/** Mögliche Namen der Methode (Mojang-Name, Yarn-Name). */
	private static final String[] METHOD_NAMES = {"startUseItem", "doItemUse"};
	/** Mehr als so viele Drücke pro Tick werden nicht vorgemerkt (zwei Klicks innerhalb eines Ticks). */
	private static final int MAX_PENDING = 2;
	/** So lange wird die Benutzen-Taste gehalten, bevor der Dauerklick beginnt (ein normaler Klick bleibt eine Salve). */
	static final int HOLD_DELAY_TICKS = 5;

	private static boolean enabled;
	private static boolean wasDown;
	private static int pending;
	private static int heldTicks;
	private static boolean warned;
	private static KeyMapping holdKey;
	private static Method useMethod;
	private static boolean searched;

	private FastClick() {
	}

	static boolean enabled() {
		return enabled;
	}

	/** Die Taste (oder Maustaste) für den Dauerklick aus den Steuerungs-Optionen. */
	static void setHoldKey(KeyMapping key) {
		holdKey = key;
	}

	static void toggle(Minecraft mc) {
		set(mc, !enabled);
	}

	static void set(Minecraft mc, boolean on) {
		if (on && method() == null) {
			Chat.info(mc, "Fast Right Click geht auf dieser Version nicht (Benutzen-Methode nicht gefunden).");
			enabled = false;
			return;
		}
		enabled = on;
		pending = 0;
		Chat.info(mc, "Fast Right Click: "
			+ (on ? "AN (" + describe(MacroStore.fastClickCount()) + ", " + describeHold(MacroStore.fastClickHold()) + ")" : "AUS"));
	}

	static String describeHold(int perTick) {
		return perTick == 0 ? "Halten: aus" : "Halten: " + perTick + " Klicks pro Tick";
	}

	static String describe(int count) {
		return count == 1 ? "1 Klick pro Klick" : count + " Klicks pro Klick";
	}

	/**
	 * Merkt sich einen neuen Druck der Benutzen-Taste. Wird jedes Bild und jeden Tick aufgerufen, damit auch ein
	 * sehr kurzer Druck nicht verloren geht. Ausgeführt wird erst im Tick.
	 */
	static synchronized void poll(Minecraft mc) {
		boolean down = mc.options.keyUse.isDown();
		if (down && !wasDown && enabled && mc.player != null && mc.gui.screen() == null && !MacroPlayer.isActive()
			&& pending < MAX_PENDING) {
			pending++;
		}
		wasDown = down;
	}

	/** Am Ende jedes Ticks, nachdem das Spiel die Klicks selbst verarbeitet hat: die zusätzlichen Klicks ausführen. */
	static void tick(Minecraft mc) {
		int bursts;
		synchronized (FastClick.class) {
			poll(mc);
			bursts = pending;
			pending = 0;
		}
		boolean inGame = mc.player != null && mc.gui.screen() == null;
		boolean useDown = enabled && inGame && mc.options.keyUse.isDown() && !MacroPlayer.isActive();
		heldTicks = useDown ? heldTicks + 1 : 0;

		int hold = MacroStore.fastClickHold();
		int clicks = 0;
		if (inGame && hold > 0 && holdKey != null && holdKey.isDown()) {
			clicks = hold; // eigene Dauerklick-Taste: sofort, auch ohne Schalter
		} else if (hold > 0 && heldTicks > HOLD_DELAY_TICKS) {
			clicks = hold; // Benutzen-Taste gehalten
		} else if (enabled && inGame && bursts > 0) {
			clicks = bursts * (MacroStore.fastClickCount() - 1); // einen Klick hat das Spiel selbst schon gemacht
		}
		if (clicks > 0) {
			click(mc, clicks);
		}
	}

	private static void click(Minecraft mc, int clicks) {
		Method m = method();
		if (m == null) {
			enabled = false;
			if (!warned) {
				warned = true;
				Chat.info(mc, "Fast Right Click geht auf dieser Version nicht (Benutzen-Methode nicht gefunden).");
			}
			return;
		}
		try {
			for (int i = 0; i < clicks; i++) {
				if (mc.player == null || mc.gui.screen() != null) {
					break; // z. B. weil ein Klick eine Kiste oder einen Händler geöffnet hat
				}
				m.invoke(mc);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			MacroKitClient.LOGGER.error("Fast Right Click abgeschaltet", e);
			enabled = false;
			Chat.info(mc, "Fast Right Click wurde abgeschaltet (Fehler beim Klicken, siehe Log).");
		}
	}

	/** Kleine Anzeige oben links, solange es an ist. */
	static void indicator(GuiGraphicsExtractor g, Minecraft mc) {
		if (enabled && mc.player != null && mc.font != null) {
			int hold = MacroStore.fastClickHold();
			String text = "Fast Right Click x" + MacroStore.fastClickCount() + (hold > 0 ? ", halten x" + hold : "");
			g.text(mc.font, text, 4, 4, 0xFFFFE070, true);
		}
	}

	private static Method method() {
		if (!searched) {
			searched = true;
			for (String name : METHOD_NAMES) {
				try {
					Method m = Minecraft.class.getDeclaredMethod(name);
					m.setAccessible(true);
					useMethod = m;
					break;
				} catch (NoSuchMethodException | RuntimeException ignored) {
					// nächsten Namen probieren
				}
			}
			if (useMethod == null) {
				MacroKitClient.LOGGER.error("Methode zum Benutzen (Rechtsklick) nicht gefunden");
			}
		}
		return useMethod;
	}
}
