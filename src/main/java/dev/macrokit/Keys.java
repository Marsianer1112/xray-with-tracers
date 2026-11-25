package dev.macrokit;

import com.mojang.blaze3d.platform.InputConstants;
import dev.macrokit.xray.XrayManager;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Tastenbelegungen (änderbar unter Optionen > Steuerung > Tastenbelegung > MacroKit). */
final class Keys {
	private static KeyMapping record;
	private static KeyMapping playLast;
	private static KeyMapping stop;
	private static KeyMapping menu;
	private static KeyMapping xray;
	private static KeyMapping fastClick;
	private static KeyMapping fastHold;
	private static final KeyMapping[] SLOTS = new KeyMapping[MacroStore.SLOT_COUNT];

	private Keys() {
	}

	static void register() {
		KeyMapping.Category category = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath(MacroKitClient.MOD_ID, "macros"));

		record = add("key.macrokit.record", InputConstants.KEY_F7, category);
		playLast = add("key.macrokit.play_last", InputConstants.KEY_F8, category);
		stop = add("key.macrokit.stop", InputConstants.KEY_F9, category);
		menu = add("key.macrokit.menu", InputConstants.KEY_RSHIFT, category);
		xray = add("key.macrokit.xray", InputConstants.KEY_F6, category);
		// -1 = nicht belegt; die Taste legst du selbst in den Steuerungs-Optionen fest (jede beliebige Taste)
		fastClick = add("key.macrokit.fastclick", -1, category);
		// Dauerklick: solange diese Taste oder Maustaste gehalten wird, klickt es extrem schnell (jede Taste/Maustaste wählbar)
		fastHold = add("key.macrokit.fastclick_hold", -1, category);
		FastClick.setHoldKey(fastHold);
		for (int i = 0; i < SLOTS.length; i++) {
			// -1 = nicht belegt; du legst die Taste selbst in den Steuerungs-Optionen fest
			SLOTS[i] = add("key.macrokit.slot." + (i + 1), -1, category);
		}
	}

	private static KeyMapping add(String translationKey, int key, KeyMapping.Category category) {
		return KeyMappingHelper.registerKeyMapping(
			new KeyMapping(translationKey, InputConstants.Type.KEYSYM, key, category));
	}

	/** Wird am Ende jedes Client-Ticks aufgerufen. */
	static void tick(Minecraft mc) {
		while (record.consumeClick()) {
			toggleRecording(mc);
		}
		while (playLast.consumeClick()) {
			playLast(mc);
		}
		while (stop.consumeClick()) {
			stopAll(mc);
		}
		while (menu.consumeClick()) {
			// Nur aus dem Spiel heraus öffnen, nicht über einem anderen Bildschirm (z. B. beim Tippen im Chat)
			if (mc.player != null && mc.gui.screen() == null) {
				mc.gui.setScreen(new MenuScreen(null));
			}
		}
		while (xray.consumeClick()) {
			if (mc.player != null) {
				XrayManager.toggle(mc);
			}
		}
		while (fastClick.consumeClick()) {
			if (mc.player != null && mc.gui.screen() == null) {
				FastClick.toggle(mc);
			}
		}
		while (fastHold.consumeClick()) {
			// Nur das Halten zählt, die einzelnen Klicks werden verworfen
		}
		FastClick.tick(mc);
		for (int i = 0; i < SLOTS.length; i++) {
			while (SLOTS[i].consumeClick()) {
				runSlot(mc, i);
			}
		}
	}

	private static void toggleRecording(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		String error;
		if (Recorder.isRecording()) {
			error = Actions.stopRecording(mc);
		} else {
			String name = "rec-" + LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
			error = Actions.startRecording(name);
		}
		if (error != null) {
			Chat.info(mc, error);
		}
	}

	private static void playLast(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		if (Actions.lastName == null) {
			Chat.info(mc, "Es gibt noch kein zuletzt benutztes Macro.");
			return;
		}
		String error = Actions.run(mc, Actions.lastName, 1);
		if (error != null) {
			Chat.info(mc, error);
		}
	}

	/** Notbremse: stoppt ein laufendes Macro und lässt alle Tasten los. */
	private static void stopAll(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		if (MacroPlayer.isActive()) {
			MacroPlayer.stop(mc, true);
		} else if (Recorder.isRecording()) {
			Chat.info(mc, "Es läuft eine Aufnahme. Beenden mit der Aufnahme-Taste oder /macro record stop.");
		}
	}

	private static void runSlot(Minecraft mc, int index) {
		if (mc.player == null) {
			return;
		}
		String name = MacroStore.slot(index);
		if (name == null) {
			Chat.info(mc, "Slot " + (index + 1) + " ist leer. Belegen mit /macro bind " + (index + 1) + " <name>");
			return;
		}
		String error = Actions.run(mc, name, 1);
		if (error != null) {
			Chat.info(mc, error);
		}
	}
}
