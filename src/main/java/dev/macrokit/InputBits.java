package dev.macrokit;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;

/**
 * Übersetzt zwischen den Spieltasten und einer Bitmaske.
 * Bit 0 = vorwärts, 1 = zurück, 2 = links, 3 = rechts, 4 = springen,
 * 5 = schleichen, 6 = sprinten, 7 = angreifen/abbauen, 8 = benutzen.
 */
final class InputBits {
	private InputBits() {
	}

	private static KeyMapping[] mappings(Options o) {
		return new KeyMapping[] {
			o.keyUp, o.keyDown, o.keyLeft, o.keyRight,
			o.keyJump, o.keyShift, o.keySprint,
			o.keyAttack, o.keyUse
		};
	}

	/** Liest, welche der Tasten gerade gedrückt sind. */
	static int read(Options o) {
		KeyMapping[] m = mappings(o);
		int bits = 0;
		for (int i = 0; i < m.length; i++) {
			if (m[i].isDown()) {
				bits |= 1 << i;
			}
		}
		return bits;
	}

	/** Setzt alle Tasten passend zur Bitmaske (gedrückt / losgelassen). */
	static void apply(Options o, int bits) {
		KeyMapping[] m = mappings(o);
		for (int i = 0; i < m.length; i++) {
			m[i].setDown((bits & (1 << i)) != 0);
		}
	}

	/** Lässt nur die Tasten los, die in der Bitmaske gesetzt sind. */
	static void release(Options o, int bits) {
		KeyMapping[] m = mappings(o);
		for (int i = 0; i < m.length; i++) {
			if ((bits & (1 << i)) != 0) {
				m[i].setDown(false);
			}
		}
	}
}
