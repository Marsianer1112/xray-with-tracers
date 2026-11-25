package dev.macrokit;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.macrokit.model.Macro;
import dev.macrokit.xray.OreType;
import dev.macrokit.xray.XrayManager;
import dev.macrokit.xray.XraySettings;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** Client-Befehle: /macro ... (werden nie an den Server geschickt). */
final class MacroCommands {
	private static final SuggestionProvider<FabricClientCommandSource> NAMES =
		(context, builder) -> SharedSuggestionProvider.suggest(MacroStore.names(), builder);
	private static final SuggestionProvider<FabricClientCommandSource> ORE_NAMES =
		(context, builder) -> SharedSuggestionProvider.suggest(
			List.of("all", "coal", "iron", "copper", "gold", "redstone", "lapis", "diamond", "emerald"), builder);
	private static final SuggestionProvider<FabricClientCommandSource> ORE_MODES =
		(context, builder) -> SharedSuggestionProvider.suggest(List.of("off", "deepslate", "stone", "both"), builder);

	private MacroCommands() {
	}

	static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
			dispatcher.register(build()));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> build() {
		return ClientCommands.literal("macro")
			// /macro add <name> <text...>
			.then(ClientCommands.literal("add")
				.then(ClientCommands.argument("name", StringArgumentType.word())
					.then(ClientCommands.argument("text", StringArgumentType.greedyString())
						.executes(ctx -> result(ctx, Actions.addText(
							StringArgumentType.getString(ctx, "name"),
							StringArgumentType.getString(ctx, "text")))))))
			// /macro run <name> [anzahl]
			.then(ClientCommands.literal("run")
				.then(ClientCommands.argument("name", StringArgumentType.word()).suggests(NAMES)
					.executes(ctx -> result(ctx, Actions.run(mc(), StringArgumentType.getString(ctx, "name"), 1)))
					.then(ClientCommands.argument("times", IntegerArgumentType.integer(1, 100000))
						.executes(ctx -> result(ctx, Actions.run(mc(),
							StringArgumentType.getString(ctx, "name"),
							IntegerArgumentType.getInteger(ctx, "times")))))))
			// /macro loop <name>
			.then(ClientCommands.literal("loop")
				.then(ClientCommands.argument("name", StringArgumentType.word()).suggests(NAMES)
					.executes(ctx -> result(ctx, Actions.run(mc(), StringArgumentType.getString(ctx, "name"), 0)))))
			// /macro stop
			.then(ClientCommands.literal("stop")
				.executes(ctx -> result(ctx, Actions.stopPlayback(mc()))))
			// /macro record start <name> | stop | cancel
			.then(ClientCommands.literal("record")
				.then(ClientCommands.literal("start")
					.then(ClientCommands.argument("name", StringArgumentType.word())
						.executes(ctx -> result(ctx, Actions.startRecording(StringArgumentType.getString(ctx, "name"))))))
				.then(ClientCommands.literal("stop")
					.executes(ctx -> result(ctx, Actions.stopRecording(mc()))))
				.then(ClientCommands.literal("cancel")
					.executes(ctx -> result(ctx, Actions.cancelRecording(mc())))))
			// /macro list | info <name> | delete <name> | rename <alt> <neu>
			.then(ClientCommands.literal("list")
				.executes(MacroCommands::list))
			.then(ClientCommands.literal("info")
				.then(ClientCommands.argument("name", StringArgumentType.word()).suggests(NAMES)
					.executes(MacroCommands::info)))
			.then(ClientCommands.literal("delete")
				.then(ClientCommands.argument("name", StringArgumentType.word()).suggests(NAMES)
					.executes(MacroCommands::delete)))
			.then(ClientCommands.literal("rename")
				.then(ClientCommands.argument("old", StringArgumentType.word()).suggests(NAMES)
					.then(ClientCommands.argument("new", StringArgumentType.word())
						.executes(MacroCommands::rename))))
			// /macro bind <slot> <name> | unbind <slot> | slots
			.then(ClientCommands.literal("bind")
				.then(ClientCommands.argument("slot", IntegerArgumentType.integer(1, MacroStore.SLOT_COUNT))
					.then(ClientCommands.argument("name", StringArgumentType.word()).suggests(NAMES)
						.executes(MacroCommands::bind))))
			.then(ClientCommands.literal("unbind")
				.then(ClientCommands.argument("slot", IntegerArgumentType.integer(1, MacroStore.SLOT_COUNT))
					.executes(MacroCommands::unbind)))
			.then(ClientCommands.literal("slots")
				.executes(MacroCommands::slots))
			// /macro fastclick on|off|toggle|count <1-64>|hold <0-200>
			.then(ClientCommands.literal("fastclick")
				.then(ClientCommands.literal("on").executes(ctx -> {
					FastClick.set(mc(), true);
					return 1;
				}))
				.then(ClientCommands.literal("off").executes(ctx -> {
					FastClick.set(mc(), false);
					return 1;
				}))
				.then(ClientCommands.literal("toggle").executes(ctx -> {
					FastClick.toggle(mc());
					return 1;
				}))
				.then(ClientCommands.literal("hold")
					.then(ClientCommands.argument("perTick", IntegerArgumentType.integer(0, MacroStore.FAST_HOLD_MAX))
						.executes(ctx -> {
							int perTick = IntegerArgumentType.getInteger(ctx, "perTick");
							MacroStore.setFastClickHold(perTick);
							say(ctx, "Fast Right Click: " + FastClick.describeHold(perTick));
							return 1;
						})))
				.then(ClientCommands.literal("count")
					.then(ClientCommands.argument("clicks", IntegerArgumentType.integer(1, MacroStore.FAST_CLICK_MAX))
						.executes(ctx -> {
							int clicks = IntegerArgumentType.getInteger(ctx, "clicks");
							MacroStore.setFastClickCount(clicks);
							say(ctx, "Fast Right Click: " + FastClick.describe(clicks));
							return 1;
						}))))
			// /macro keys | unkey <name>   (Tasten setzt man im Menü: Macros > Taste setzen)
			.then(ClientCommands.literal("keys")
				.executes(MacroCommands::keys))
			.then(ClientCommands.literal("unkey")
				.then(ClientCommands.argument("name", StringArgumentType.word()).suggests(NAMES)
					.executes(MacroCommands::unkey)))
			// /macro delay <ticks>
			.then(ClientCommands.literal("delay")
				.then(ClientCommands.argument("ticks", IntegerArgumentType.integer(0, 1200))
					.executes(MacroCommands::delay)))
			// /macro xray ...
			.then(xray())
			// /macro status | reload
			.then(ClientCommands.literal("status")
				.executes(MacroCommands::status))
			.then(ClientCommands.literal("reload")
				.executes(ctx -> {
					MacroStore.load();
					say(ctx, MacroStore.names().size() + " Macros neu geladen.");
					return 1;
				}));
	}

	/** /macro xray on|off|seed|range|mode|ore|verify|debug|status */
	private static LiteralArgumentBuilder<FabricClientCommandSource> xray() {
		return ClientCommands.literal("xray")
			.then(ClientCommands.literal("on").executes(ctx -> {
				XrayManager.setEnabled(mc(), true);
				return 1;
			}))
			.then(ClientCommands.literal("off").executes(ctx -> {
				XrayManager.setEnabled(mc(), false);
				return 1;
			}))
			.then(ClientCommands.literal("seed")
				.then(ClientCommands.argument("seed", LongArgumentType.longArg())
					.executes(ctx -> {
						long seed = LongArgumentType.getLong(ctx, "seed");
						XrayManager.settings().seed = seed;
						XrayManager.settings().shifts.clear(); // Kalibrierung gehört zum alten Seed nicht mehr
						XrayManager.save();
						XrayManager.invalidate();
						say(ctx, "Xray-Seed gesetzt: " + seed);
						return 1;
					})))
			// /macro xray tracer <0-5>
			.then(ClientCommands.literal("tracer")
				.then(ClientCommands.argument("count", IntegerArgumentType.integer(0, XraySettings.MAX_TRACERS))
					.executes(ctx -> {
						int count = IntegerArgumentType.getInteger(ctx, "count");
						XrayManager.settings().tracerCount = count;
						XrayManager.save();
						say(ctx, count == 0 ? "Tracer aus" : "Tracer zu den " + count + " nächsten Erzen");
						return 1;
					})))
			.then(ClientCommands.literal("range")
				.then(ClientCommands.argument("chunks", IntegerArgumentType.integer(1, XraySettings.MAX_RADIUS))
					.executes(ctx -> {
						int chunks = IntegerArgumentType.getInteger(ctx, "chunks");
						XrayManager.settings().radiusChunks = chunks;
						XrayManager.save();
						XrayManager.invalidate();
						say(ctx, "Xray-Reichweite: " + chunks + " Chunks");
						return 1;
					})))
			.then(ClientCommands.literal("mode")
				.then(ClientCommands.literal("seed").executes(ctx -> {
					XrayManager.settings().scanMode = false;
					XrayManager.save();
					XrayManager.invalidate();
					say(ctx, "Xray-Modus: Seed (berechnet)");
					return 1;
				}))
				.then(ClientCommands.literal("scan").executes(ctx -> {
					XrayManager.settings().scanMode = true;
					XrayManager.save();
					XrayManager.invalidate();
					say(ctx, "Xray-Modus: Scan geladener Chunks");
					return 1;
				})))
			// /macro xray ore <erz|all> <off|deepslate|stone|both>
			.then(ClientCommands.literal("ore")
				.then(ClientCommands.argument("ore", StringArgumentType.word()).suggests(ORE_NAMES)
					.then(ClientCommands.argument("mode", StringArgumentType.word()).suggests(ORE_MODES)
						.executes(MacroCommands::setOre))))
			.then(ClientCommands.literal("verify").executes(ctx -> {
				XrayManager.verify(mc());
				return 1;
			}))
			.then(ClientCommands.literal("debug").executes(ctx -> {
				XrayManager.debug(mc());
				return 1;
			}))
			.then(ClientCommands.literal("status").executes(ctx -> {
				say(ctx, XrayManager.status());
				say(ctx, "Seed: " + XrayManager.settings().seed + ", Reichweite: " + XrayManager.settings().radiusChunks + " Chunks");
				StringBuilder ores = new StringBuilder("Erze: ");
				for (OreType t : OreType.values()) {
					ores.append(t.label).append(' ').append(OreType.modeName(XrayManager.settings().mode(t))).append(", ");
				}
				say(ctx, ores.substring(0, ores.length() - 2));
				return 1;
			}));
	}

	private static int parseOreMode(String s) {
		return switch (s) {
			case "off", "aus" -> 0;
			case "deepslate" -> OreType.DEEPSLATE;
			case "stone", "stein" -> OreType.STONE;
			case "both", "beides" -> OreType.DEEPSLATE | OreType.STONE;
			default -> -1;
		};
	}

	private static int setOre(CommandContext<FabricClientCommandSource> ctx) {
		String oreArg = StringArgumentType.getString(ctx, "ore").toLowerCase(Locale.ROOT);
		int mode = parseOreMode(StringArgumentType.getString(ctx, "mode").toLowerCase(Locale.ROOT));
		if (mode < 0) {
			ctx.getSource().sendError(Component.literal("Modus: off, deepslate, stone oder both"));
			return 0;
		}
		boolean all = oreArg.equals("all") || oreArg.equals("alle");
		int changed = 0;
		for (OreType t : OreType.values()) {
			if (all || t.name().equalsIgnoreCase(oreArg) || t.label.equalsIgnoreCase(oreArg)) {
				XrayManager.settings().setMode(t, mode);
				changed++;
				if (!all) {
					say(ctx, t.label + ": " + OreType.modeName(mode));
				}
			}
		}
		if (changed == 0) {
			ctx.getSource().sendError(Component.literal("Unbekanntes Erz. Erlaubt: all, coal, iron, copper, gold, redstone, lapis, diamond, emerald"));
			return 0;
		}
		if (all) {
			say(ctx, "Alle Erze: " + OreType.modeName(mode));
		}
		XrayManager.save();
		return 1;
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private static void say(CommandContext<FabricClientCommandSource> ctx, String text) {
		ctx.getSource().sendFeedback(Component.literal(text));
	}

	private static int result(CommandContext<FabricClientCommandSource> ctx, String error) {
		if (error != null) {
			ctx.getSource().sendError(Component.literal(error));
			return 0;
		}
		return 1;
	}

	private static int list(CommandContext<FabricClientCommandSource> ctx) {
		List<String> names = MacroStore.names();
		if (names.isEmpty()) {
			say(ctx, "Noch keine Macros. Beispiel: /macro add gruss Hallo zusammen!");
			return 1;
		}
		say(ctx, "Macros (" + names.size() + "):");
		for (String name : names) {
			Macro m = MacroStore.get(name);
			String type = m.isText() ? "Text" : "Aufnahme";
			say(ctx, String.format(Locale.ROOT, " - %s  [%s, %.1f s]", m.name, type, m.length / 20f));
		}
		return 1;
	}

	private static int info(CommandContext<FabricClientCommandSource> ctx) {
		Macro m = MacroStore.get(StringArgumentType.getString(ctx, "name"));
		if (m == null) {
			return result(ctx, "Unbekanntes Macro.");
		}
		say(ctx, "Macro " + m.name + (m.isText() ? " (Text)" : " (Aufnahme)"));
		say(ctx, String.format(Locale.ROOT, " Dauer: %.1f s (%d Ticks), Einträge: %d", m.length / 20f, m.length, m.frames.size()));
		if (m.isText()) {
			say(ctx, " Text: " + m.text);
			say(ctx, " Pause zwischen Zeilen: " + m.delay + " Ticks");
		} else {
			say(ctx, " Blickrichtung: " + (m.relativeLook ? "relativ zur Startrichtung" : "absolut wie aufgenommen"));
		}
		return 1;
	}

	private static int delete(CommandContext<FabricClientCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "name");
		if (!MacroStore.delete(name)) {
			return result(ctx, "Unbekanntes Macro: " + name);
		}
		say(ctx, "Gelöscht: " + name);
		return 1;
	}

	private static int rename(CommandContext<FabricClientCommandSource> ctx) {
		String oldName = StringArgumentType.getString(ctx, "old");
		String newName = StringArgumentType.getString(ctx, "new");
		if (!MacroStore.isValidName(newName)) {
			return result(ctx, "Ungültiger neuer Name.");
		}
		if (MacroStore.get(newName) != null) {
			return result(ctx, "Es gibt schon ein Macro namens " + newName + ".");
		}
		if (!MacroStore.rename(oldName, newName)) {
			return result(ctx, "Konnte nicht umbenennen (gibt es " + oldName + "?).");
		}
		say(ctx, oldName + " heißt jetzt " + newName + ".");
		return 1;
	}

	private static int bind(CommandContext<FabricClientCommandSource> ctx) {
		int slot = IntegerArgumentType.getInteger(ctx, "slot");
		Macro m = MacroStore.get(StringArgumentType.getString(ctx, "name"));
		if (m == null) {
			return result(ctx, "Unbekanntes Macro.");
		}
		MacroStore.setSlot(slot - 1, m.name);
		say(ctx, "Slot " + slot + " startet jetzt " + m.name
			+ ". Taste dafür: Optionen > Steuerung > Tastenbelegung > MacroKit > Macro-Slot " + slot + ".");
		return 1;
	}

	private static int unbind(CommandContext<FabricClientCommandSource> ctx) {
		int slot = IntegerArgumentType.getInteger(ctx, "slot");
		MacroStore.setSlot(slot - 1, null);
		say(ctx, "Slot " + slot + " ist jetzt leer.");
		return 1;
	}

	private static int slots(CommandContext<FabricClientCommandSource> ctx) {
		for (int i = 0; i < MacroStore.SLOT_COUNT; i++) {
			String name = MacroStore.slot(i);
			say(ctx, " Slot " + (i + 1) + ": " + (name == null ? "-" : name));
		}
		return 1;
	}

	private static int keys(CommandContext<FabricClientCommandSource> ctx) {
		int count = 0;
		for (String name : MacroStore.names()) {
			int key = MacroStore.hotkey(name);
			if (key >= 0) {
				say(ctx, " " + Hotkeys.keyName(key) + ": " + name);
				count++;
			}
		}
		if (count == 0) {
			say(ctx, "Noch keine Tasten gesetzt. Im Menü unter Macros auf \"Taste setzen\" klicken.");
		}
		return 1;
	}

	private static int unkey(CommandContext<FabricClientCommandSource> ctx) {
		Macro m = MacroStore.get(StringArgumentType.getString(ctx, "name"));
		if (m == null) {
			return result(ctx, "Unbekanntes Macro.");
		}
		MacroStore.setHotkey(m.name, -1);
		say(ctx, "Taste von " + m.name + " entfernt.");
		return 1;
	}

	private static int delay(CommandContext<FabricClientCommandSource> ctx) {
		int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
		MacroStore.setDefaultDelay(ticks);
		say(ctx, "Neue Text-Macros warten " + ticks + " Ticks (" + ticks / 20f + " s) zwischen den Zeilen.");
		return 1;
	}

	private static int status(CommandContext<FabricClientCommandSource> ctx) {
		if (Recorder.isRecording()) {
			say(ctx, String.format(Locale.ROOT, "Aufnahme läuft: %s (%.1f s)", Recorder.currentName(), Recorder.currentTick() / 20f));
		} else if (MacroPlayer.isActive()) {
			say(ctx, "Macro läuft: " + MacroPlayer.currentName());
		} else {
			say(ctx, "Nichts aktiv.");
		}
		return 1;
	}
}
