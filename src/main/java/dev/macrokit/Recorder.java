package dev.macrokit;

import dev.macrokit.model.Frame;
import dev.macrokit.model.Macro;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.Locale;

/**
 * Nimmt pro Client-Tick Tasten, Blickrichtung und Hotbar-Slot auf (nur bei Änderung)
 * sowie alle Chat-Nachrichten und Befehle, die du währenddessen abschickst.
 */
final class Recorder {
	/** Sicherheitsgrenze: 30 Minuten. */
	private static final int MAX_TICKS = 20 * 60 * 30;

	private static boolean recording;
	private static Macro macro;
	private static int tick;
	private static int lastKeys;
	private static int lastSlot;
	private static float startYaw;
	private static float lastYaw;
	private static float lastPitch;

	private Recorder() {
	}

	static boolean isRecording() {
		return recording;
	}

	static String currentName() {
		return macro == null ? null : macro.name;
	}

	static int currentTick() {
		return tick;
	}

	static boolean start(Minecraft mc, String name) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return false;
		}
		macro = new Macro();
		macro.name = name;
		macro.relativeLook = true;
		startYaw = player.getYRot();
		macro.startYaw = startYaw;
		tick = 0;
		lastKeys = -1;
		lastSlot = -1;
		lastYaw = Float.NaN;
		lastPitch = Float.NaN;
		recording = true;
		return true;
	}

	/** Wird am Ende jedes Client-Ticks aufgerufen. */
	static void tick(Minecraft mc) {
		if (!recording) {
			return;
		}
		LocalPlayer player = mc.player;
		if (player == null) {
			cancel();
			return;
		}

		Frame f = new Frame(tick);
		boolean changed = false;

		int keys = InputBits.read(mc.options);
		if (keys != lastKeys) {
			f.keys = keys;
			lastKeys = keys;
			changed = true;
		}

		float yaw = player.getYRot() - startYaw;
		if (Float.isNaN(lastYaw) || Math.abs(yaw - lastYaw) > 0.01f) {
			f.yaw = yaw;
			lastYaw = yaw;
			changed = true;
		}

		float pitch = player.getXRot();
		if (Float.isNaN(lastPitch) || Math.abs(pitch - lastPitch) > 0.01f) {
			f.pitch = pitch;
			lastPitch = pitch;
			changed = true;
		}

		int slot = player.getInventory().getSelectedSlot();
		if (slot != lastSlot) {
			f.slot = slot;
			lastSlot = slot;
			changed = true;
		}

		if (changed) {
			macro.frames.add(f);
		}
		tick++;

		if (tick >= MAX_TICKS) {
			String error = Actions.stopRecording(mc);
			if (error != null) {
				Chat.info(mc, error);
			} else {
				Chat.info(mc, "Aufnahme nach 30 Minuten automatisch beendet.");
			}
		}
	}

	/** Beendet die Aufnahme und liefert das fertige Macro (noch nicht gespeichert). */
	static Macro stop() {
		if (!recording) {
			return null;
		}
		recording = false;
		Macro m = macro;
		macro = null;

		Frame end = new Frame(tick);
		end.keys = 0; // am Ende alle Tasten loslassen
		m.frames.add(end);
		m.length = tick + 1;
		return m;
	}

	static void cancel() {
		recording = false;
		macro = null;
	}

	static void onChat(String message) {
		record(message);
	}

	static void onCommand(String command) {
		record("/" + command);
	}

	private static void record(String text) {
		if (!recording || MacroPlayer.isSending()) {
			return;
		}
		// Befehle von MacroKit selbst (z. B. "/macro record stop") gehören nicht ins Macro
		String lower = text.toLowerCase(Locale.ROOT);
		if (lower.equals("/macro") || lower.startsWith("/macro ")) {
			return;
		}
		Frame f = new Frame(tick);
		f.say = text;
		macro.frames.add(f);
	}
}
