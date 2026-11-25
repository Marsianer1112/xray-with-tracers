package dev.macrokit;

import dev.macrokit.xray.XrayHud;
import dev.macrokit.xray.XrayManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MacroKitClient implements ClientModInitializer {
	public static final String MOD_ID = "macrokit";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		MacroStore.load();
		XrayManager.load();
		Keys.register();
		Hotkeys.register();
		MacroCommands.register();
		XrayHud.register();

		// Anfang des Ticks: Macro anwenden, bevor das Spiel die Eingaben dieses Ticks liest
		ClientTickEvents.START_CLIENT_TICK.register(MacroPlayer::tick);

		// Ende des Ticks: aufnehmen und Tasten abfragen
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			Recorder.tick(client);
			Keys.tick(client);
			Hotkeys.tick(client);
			XrayManager.tick(client);
		});

		// Eigene Chat-Nachrichten und Befehle für die Aufnahme mitschneiden
		ClientSendMessageEvents.CHAT.register(Recorder::onChat);
		ClientSendMessageEvents.COMMAND.register(Recorder::onCommand);

		LOGGER.info("MacroKit geladen");
	}
}
