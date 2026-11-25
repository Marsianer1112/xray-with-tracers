package dev.macrokit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/** Meldungen im eigenen Chat (nur für dich sichtbar, wird nicht an den Server gesendet). */
public final class Chat {
	private Chat() {
	}

	public static void info(Minecraft mc, String text) {
		LocalPlayer player = mc.player;
		if (player != null) {
			player.sendSystemMessage(Component.literal("[MacroKit] " + text));
		}
	}
}
