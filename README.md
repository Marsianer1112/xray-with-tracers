# MacroKit – Macros, Menü und Xray für Minecraft 26.2 (Fabric, nur Client)

Reine Client-Mod: Auf dem Server muss nichts installiert werden. Nur auf deinem eigenen Server benutzen,
auf fremden Servern ist Xray in der Regel verboten.

## .jar bauen

Eine fertige `.jar` liegt nicht bei, weil beim Erstellen keine Verbindung zu den Minecraft-Servern möglich war.
Du brauchst **JDK 25**. Dann eine von drei Möglichkeiten:

1. **IntelliJ IDEA**: Ordner öffnen, Gradle-Import abwarten, rechts im Gradle-Fenster `Tasks › build › build`.
2. **Kommandozeile**: `gradle build` (Gradle ≥ 9, aktuelle Version von https://gradle.org). Wer lieber den Wrapper
   will: `gradle wrapper --gradle-version 9.5.1`, danach `./gradlew build`.
3. **GitHub**: Projekt in ein neues Repository hochladen. Der Workflow `.github/workflows/build.yml` baut die Mod bei
   jedem Push. Die fertige Datei steht danach unter *Actions › Lauf › Artifacts › macrokit-jar*.

Ergebnis: `build/libs/macrokit-1.0.0.jar` (nicht die `-sources.jar`). In den `mods`-Ordner legen, dazu
**Fabric Loader ≥ 0.19.3** und **Fabric API für 26.2**. Zum Ausprobieren ohne Installation: `gradle runClient`.

Versionen stehen in `gradle.properties`, aktuelle Werte: https://fabricmc.net/develop

## Bedienung

| Taste (änderbar unter Optionen › Steuerung › MacroKit) | Funktion |
|---|---|
| **Rechte Umschalttaste** | Menü öffnen |
| F6 | Xray an/aus |
| F7 | Aufnahme starten/beenden |
| F8 | Zuletzt benutztes Macro nochmal abspielen |
| F9 | Notbremse: Macro stoppen, Tasten loslassen |
| *(unbelegt)* | Fast Right Click an/aus (Taste selbst wählen, siehe unten) |
| *(unbelegt)* | Macro-Slot 1–9 (mit `/macro bind <1-9> <name>` zuweisen) |

### Menü
- **Macros**: Liste mit Start, **Taste setzen**, Loop (endlos) und Löschen (zweimal klicken), Aufnahme starten/beenden, Macro stoppen.
- **Xray**: an/aus, Modus, 8 Erze einzeln (Klick schaltet Aus → Deepslate → Stein → Beides), Reichweite (1–32 Chunks, Standard 6; Klick auf die Zahl springt auf 4, 8, 12, 16, 24, 32), Prüfen und kalibrieren.
- **Fast Right Click**: Schalter an/aus und **Klicks** (Zahl der Klicks pro Klick: 1, 2, 4, … 64).

### Macro direkt mit einer Taste starten
1. Menü öffnen (rechte Umschalttaste) › **Macros**.
2. Beim Macro auf **Taste setzen** klicken und dann die gewünschte Taste drücken. Die Taste steht danach im Knopf.
3. Im Spiel startet diese Taste das Macro sofort, ohne Menü und ohne `/macro run`.

- Dieselbe Taste noch einmal drücken **stoppt** das laufende Macro (praktisch bei Loops).
- Eine Taste gehört nur einem Macro. Wählst du eine belegte Taste, wandert sie zum neuen Macro.
- Entfernen: Beim Taste-Setzen **Entf** oder **Rück** drücken, oder `/macro unkey <name>`. Übersicht: `/macro keys`.
- Nicht möglich: Leertaste, Enter, Tab, Esc, Umschalt/Strg/Alt. Maustasten und Tastenkombinationen gehen nicht.
- Die Taste behält ihre normale Funktion (z. B. W läuft weiter). Nimm deshalb eine Taste, die sonst nichts tut,
  zum Beispiel F10, F12, Einfg, Pos1, Ende, Bild hoch/runter oder den Zahlenblock.
- Im Chat, im Inventar und in anderen Menüs lösen die Tasten nichts aus. Während einer Aufnahme auch nicht.
- Die Tasten werden in `config/macrokit.json` gespeichert. Die Slots 1–9 aus der Tabelle oben gehen weiterhin.

### Fast Right Click (Klick-Salve und Dauerklick)
Ein einzelner Rechtsklick löst mehrere Klicks im selben Tick aus (Standard **32**). Es klickt **nichts von allein**:
ohne Klick passiert nichts, und eine gehaltene Taste wiederholt nur so schnell wie im normalen Spiel.

1. Taste festlegen: Optionen › Steuerung › MacroKit › **Fast Right Click umschalten**. Dort geht jede beliebige Taste
   (sie ist absichtlich unbelegt, damit nichts doppelt belegt ist).
2. Im Spiel die Taste drücken: Im Chat kommt „Fast Right Click: AN (32 Klicks pro Klick)“, oben links steht
   „Fast Right Click x32“.
3. Jeder neue Druck der rechten Maustaste (Benutzen-Taste) macht dann 32 Klicks: einen macht das Spiel, 31 die Mod.
- **Dauerklick (Halten):** Hältst du die rechte Maustaste länger als eine Viertelsekunde (5 Ticks), klickt es **200 Mal
  pro Tick** (4000 pro Sekunde), bis du loslässt. Ein normaler kurzer Klick bleibt eine Salve von 32.
  Zusätzlich gibt es unter Optionen › Steuerung › MacroKit die Belegung **Dauerklick (Taste halten)**: Dort kannst du
  **jede Taste oder Maustaste** wählen. Solange du sie hältst, klickt es sofort mit 200 pro Tick, auch wenn der Schalter
  aus ist. Auch das Dauerklick-Tempo ist einstellbar (Menü: Halten, oder `/macro fastclick hold <0-200>`, 0 = aus).
- Alternativ im Menü (Schalter, **Klicks** und **Halten**) oder per Befehl: `/macro fastclick on|off|toggle`,
  `/macro fastclick count <1-64>` und `/macro fastclick hold <0-200>`.
- Die Zahl wird in `config/macrokit.json` gespeichert. An/Aus **nicht**: nach einem Neustart ist es immer aus.
- Es wirkt nur im Spiel ohne offenen Bildschirm. Während ein Macro abgespielt wird, gibt es keine Salven.
- Mehrere Klicks im selben Tick bringen bei Blöcken nur etwas, wenn der Block danach noch anvisiert werden kann:
  Knöpfe, Hebel, Händler, Werfen, Essen, Behälter und Ähnliches. Ein Block wird nur einmal gesetzt.
- Jeder Klick ist ein Paket zum Server. 200 Klicks pro Tick sind 4000 Pakete pro Sekunde: Die meisten Server (auch ein
  eigener mit Paper) werfen dich dabei wegen zu vieler Pakete raus oder laufen ins Lag. Dann `hold` senken, z. B. auf 20.
  Öffnet ein Klick einen Bildschirm (Kiste, Händler), endet die Serie sofort. Fremde Server können das auch als Cheat erkennen und dich sperren.
- Technisch: Die Mod ruft die Benutzen-Methode des Spiels (`Minecraft.startUseItem`) zusätzlich auf. Findet sie die
  Methode nicht, steht eine Meldung im Chat und Fast Right Click bleibt aus.

### Macro-Befehle (`/macro ...`, laufen nur lokal)
`add <name> <text>`, `run <name> [anzahl]`, `loop <name>`, `stop`, `record start <name>` / `record stop` / `record cancel`,
`list`, `info`, `delete`, `rename`, `keys`, `unkey <name>`, `bind`, `unbind`, `slots`, `delay <ticks>`, `status`, `reload`,
`fastclick on|off|toggle|count <1-64>`.

Text-Macros: Zeilen mit `;;` trennen, `/` am Anfang = Befehl, `@wait 40` / `@wait 2s` / `@wait 500ms` = Pause.
Beispiel: `/macro add heim /home ;; @wait 2s ;; Bin wieder da!`

Aufnahmen spielen **Eingaben** ab (Laufen, Springen, Schleichen, Sprinten, Angreifen/Benutzen, Blickrichtung,
Hotbar-Slot, Chat/Befehle), keine Positionen: an derselben Stelle starten wie bei der Aufnahme.
Vanilla-Server werfen Spieler bei zu schnellem Chat-Spam raus, bei langen Text-Macros `/macro delay 20` nutzen.

## Xray (alle Erze der Oberwelt)

Erze: Kohle, Eisen, Kupfer, Gold, Redstone, Lapis, Diamant, Smaragd. Jedes Erz hat im Menü einen eigenen Knopf,
ein Klick schaltet weiter: **Aus → Deepslate → Stein → Beides**. Standard: Kohle, Redstone, Diamant und Smaragd
als Deepslate, die anderen aus. Die Auswahl wird gespeichert.

**Tracer:** Von der Bildschirmmitte laufen Linien in der Farbe des Erzes zu den nächsten 3 Erzen (Menü: Knopf
„Tracer“ schaltet 1–5 oder aus, Befehl `/macro xray tracer <0-5>`). Liegt ein Erz hinter oder neben dir, zeigt die Linie
zum Bildrand in die Richtung, in die du dich drehen musst. Gezählt werden nur die Erze, die du im Menü eingeschaltet hast.

Die Erze erscheinen als farbige Punkte (Kohle grau, Eisen beige, Kupfer orange, Gold gelb, Redstone rot,
Lapis blau, Diamant cyan, Smaragd grün) mit Entfernung bei den nächsten, auch durch Wände.
Reichweite: Quadrat von 6 Chunks um dich (13 × 13 Chunks), einstellbar von 1 bis 32 (32 = größte Sichtweite im
Spiel). Beide Modi arbeiten nur mit Chunks, die dein Spiel geladen hat: Schickt der Server nur 4 Chunks, gibt es auch
nur dort Markierungen, egal wie hoch die Reichweite steht. Ab etwa 16 Chunks brauchen beide Modi spürbar mehr
Rechenzeit und Arbeitsspeicher, die Liste wird dann seltener neu berechnet. Es werden höchstens
1500 Punkte gezeichnet, die nächsten zuerst (`maxMarkers` in der Einstellungsdatei).
„Deepslate“ zeigt die Erze im Deepslate (unter Y=0, in der Übergangszone Y 0–7 nur wo wirklich Deepslate liegt),
„Stein“ die Erze im normalen Stein (ab Y=0 aufwärts).
Kohle gibt es als Deepslate-Variante praktisch nur ganz knapp unter Y=0, dort sieht man sie selten.

**Modus Seed (Standard)**: rechnet die Erzgenerierung aus dem Weltseed nach, wie OreSim/Seed-Xray.
Der Seed steht auf `6608149111735331168` (ändern: `/macro xray seed <zahl>`). Er funktioniert auch, wenn der
Server Anti-Xray benutzt. Die Daten (Biome, Erzregeln) liest die Mod aus dem Spiel selbst, nichts ist fest eingebaut.
Die Berechnung schaut dabei in die echte Welt (geladene Chunks), so wie das Spiel beim Erzeugen: Eine Ader verbraucht
Zufallszahlen nur dort, wo wirklich Gestein liegt, und Adern, die ganz über dem Gelände liegen, werden gar nicht gesetzt.
Ohne das stimmt nur die erste Ader eines Chunks, alle weiteren liegen falsch. Chunks werden neu berechnet, sobald mehr
Nachbar-Chunks geladen sind (die Adern reichen über den Chunkrand).
Jede berechnete Stelle wird außerdem mit dem echten Block verglichen: Luft, Wasser, Lava und fremde Blöcke werden
ausgeblendet, ebenso Stellen, an denen das Spiel das Erz wegen angrenzender Luft verworfen hätte.
Genauigkeit: Höhlen und andere Erze, die eine Ader in der echten Welt verändert haben, kennt der Seed nicht.
Deshalb gibt es vereinzelt Markierungen ohne Erz, besonders neben Höhlen.

**Modus Scan**: sucht die geladenen Chunks nach echten Erzen ab. Immer korrekt, geht aber nicht
durch Anti-Xray und kennt nur, was der Server schickt. Umschalten im Menü oder `/macro xray mode scan`.

**Prüfen und kalibrieren** (Menü oder `/macro xray verify`): vergleicht die berechneten Positionen mit den echten
Blöcken in den geladenen Chunks (dafür darf der Server kein Anti-Xray haben) und gleicht die Reihenfolge der
Features automatisch an. Die Prüfung läuft über einige Sekunden im Hintergrund, das Ergebnis steht im Chat
(eine Zeile je Erz). Das ist der Test, ob die Berechnung für deine Welt stimmt. Erwartet werden bei den
Erzen `passt` mit grob 50–95 %. Steht überall `keine Übereinstimmung`, stimmt der Seed nicht, oder der Server
verschleiert die Erze, oder die Daten von 26.2 sehen anders aus. Dann den Scan-Modus nehmen.

**Diagnose** (`/macro xray debug`): schreibt `config/macrokit-xray-debug.txt` mit den gelesenen Worldgen-Daten, der
Erz-Reihenfolge, dem Biom an deiner Position und einem Vergleich der berechneten Positionen mit den echten Blöcken im
Chunk, in dem du stehst. Im Chat steht pro Erz „N berechnet, M davon echtes Erz“. Das ist der schnellste Test, ob der
Seed stimmt: Im Singleplayer (kein Anti-Xray) sollte M bei den meisten Erzen nahe bei N liegen. Den Seed deiner Welt
zeigt `/seed`, setzen mit `/macro xray seed <zahl>`.

Weitere Befehle: `/macro xray on|off|range <1-32>|tracer <0-5>|mode seed|scan|verify|debug|status` und
`/macro xray ore <coal|iron|copper|gold|redstone|lapis|diamond|emerald|all> <off|deepslate|stone|both>`,
zum Beispiel `/macro xray ore iron both`.
Einstellungen: `config/macrokit-xray.json`.

Grenzen der Anzeige: Die Punkte werden aus Kamera, Blickrichtung und FOV auf den Bildschirm gerechnet.
Beim Sprinten oder mit Trankeffekten ändert sich das FOV und die Punkte rutschen leicht. In der 3rd-Person-Ansicht
passen sie nicht. Ob die Punkte mit F1 (Oberfläche ausblenden) verschwinden, ist ungetestet; sonst Xray mit F6 ausschalten.
Der Xray gilt nur in der Oberwelt, nicht im Nether oder im Ende.

## Falls der Build Fehler meldet

Das Projekt baut mit Minecraft 26.2 (per GitHub Actions geprüft). Die Rechenlogik des Erzfinders (Zufallszahlen,
Aderform, Reihenfolge) lief getrennt von Minecraft und ergibt plausible Mengen, ist aber nicht mit einer echten Welt
verglichen. Falls der Build nach einem Update von Minecraft oder Fabric API doch Fehler meldet, nennt die
Fehlermeldung die Stelle (Datei und Zeile). Wahrscheinlichste Stellen:

- `InputBits.java`: `KeyMapping.setDown(boolean)`, Felder `Options.keyUp` … `keyUse`
- `Recorder.java`, `MacroPlayer.java`: `Inventory.getSelectedSlot()` / `setSelectedSlot(int)`, `connection.sendChat/sendCommand`
- `MacroKitClient.java`: Paket von `ClientSendMessageEvents`
- `XrayHud.java`: Pakete von `HudElementRegistry` / `VanillaHudElements`, `getEyePosition`
- `FastClick.java`: die private Methode `Minecraft.startUseItem()` (wird per Reflection gesucht, Alternativname `doItemUse`)
- `Hotkeys.java`: Pakete von `HudElementRegistry` / `VanillaHudElements`; Fenster und GLFW werden per Reflection gefunden (`Window.handle()`), gehen die Tasten nicht, steht eine Meldung im Chat und im Log
- `XrayManager.java`: `Blocks.GRASS_BLOCK`, `DIRT`, `SAND`, `TERRACOTTA` und die anderen Geländeblöcke in `Tables`, `ClientLevel.hasChunk(int,int)`, `getBiome(BlockPos)`, `BlockState.getBlock()`, Blöcke wie `Blocks.COPPER_ORE`
- `OreSim.java`: `net.minecraft.util.Mth` (`sin`, `floor`, `ceil`, `lerp`)
- Menü: `Screen`, `Button.builder`, `Minecraft.gui.setScreen`
