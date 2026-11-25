package dev.macrokit;

import dev.macrokit.model.Frame;
import dev.macrokit.model.Macro;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/** Spielt ein Macro Tick für Tick ab. */
final class MacroPlayer {
	private static Macro macro;
	private static int tick;
	private static int index;
	/** 0 = endlos, sonst verbleibende Durchläufe. */
	private static int loopsLeft;
	/** Wird bei jedem Start/Stopp erhöht, damit ein Macro, das ein anderes startet, sicher abbricht. */
	private static int generation;
	private static float startYaw;
	private static int appliedKeys;
	private static boolean sending;

	private MacroPlayer() {
	}

	static boolean isActive() {
		return macro != null;
	}

	static boolean isSending() {
		return sending;
	}

	static String currentName() {
		return macro == null ? null : macro.name;
	}

	/** @param times 0 = endlos wiederholen */
	static void start(Minecraft mc, Macro m, int times) {
		stop(mc, false);
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		macro = m;
		tick = 0;
		index = 0;
		loopsLeft = Math.max(times, 0);
		startYaw = player.getYRot();
		generation++;
	}

	static void stop(Minecraft mc, boolean announce) {
		if (macro == null) {
			return;
		}
		String name = macro.name;
		macro = null;
		generation++;
		if (appliedKeys != 0) {
			InputBits.release(mc.options, appliedKeys);
			appliedKeys = 0;
		}
		if (announce) {
			Chat.info(mc, "Macro gestoppt: " + name);
		}
	}

	/** Wird zu Beginn jedes Client-Ticks aufgerufen (bevor die Eingaben ausgewertet werden). */
	static void tick(Minecraft mc) {
		if (macro == null) {
			return;
		}
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			stop(mc, false);
			return;
		}

		int gen = generation;
		List<Frame> frames = macro.frames;
		while (index < frames.size() && frames.get(index).tick <= tick) {
			apply(mc, player, frames.get(index));
			index++;
			if (gen != generation) {
				return; // ein Befehl im Macro hat ein Macro gestartet oder gestoppt
			}
		}

		tick++;
		if (tick >= macro.length && index >= frames.size()) {
			if (loopsLeft == 1) {
				String name = macro.name;
				stop(mc, false);
				Chat.info(mc, "Macro fertig: " + name);
			} else {
				if (loopsLeft > 1) {
					loopsLeft--;
				}
				tick = 0;
				index = 0;
				startYaw = player.getYRot();
			}
		}
	}

	private static void apply(Minecraft mc, LocalPlayer player, Frame f) {
		if (f.keys != null) {
			appliedKeys = f.keys;
			InputBits.apply(mc.options, f.keys);
		}
		if (f.yaw != null) {
			float base = macro.relativeLook ? startYaw : macro.startYaw;
			player.setYRot(base + f.yaw);
		}
		if (f.pitch != null) {
			player.setXRot(f.pitch);
		}
		if (f.slot != null && f.slot >= 0 && f.slot < 9) {
			player.getInventory().setSelectedSlot(f.slot);
		}
		if (f.say != null) {
			send(player, f.say);
		}
	}

	/** Sendet Chat oder Befehl (mit "/" am Anfang) so, als hättest du ihn getippt. */
	static void send(LocalPlayer player, String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		sending = true;
		try {
			if (text.startsWith("/")) {
				player.connection.sendCommand(text.substring(1));
			} else {
				player.connection.sendChat(text);
			}
		} finally {
			sending = false;
		}
	}
}
