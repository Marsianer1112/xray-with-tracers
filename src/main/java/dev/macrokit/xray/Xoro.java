package dev.macrokit.xray;

/**
 * Nachbau von Minecrafts XoroshiroRandomSource (Xoroshiro128++), wie sie bei der
 * Weltgenerierung für Features benutzt wird. Dazu die Seed-Regeln von WorldgenRandom.
 */
final class Xoro {
	private long lo;
	private long hi;

	Xoro(long seed) {
		setSeed(seed);
	}

	void setSeed(long seed) {
		long a = seed ^ 0x6A09E667F3BCC909L;
		long b = a + 0x9E3779B97F4A7C15L;
		lo = mix(a);
		hi = mix(b);
		if ((lo | hi) == 0L) {
			lo = 0x9E3779B97F4A7C15L;
			hi = 0x6A09E667F3BCC909L;
		}
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	long nextLong() {
		long l = lo;
		long m = hi;
		long n = Long.rotateLeft(l + m, 17) + l;
		m ^= l;
		lo = Long.rotateLeft(l, 49) ^ m ^ (m << 21);
		hi = Long.rotateLeft(m, 28);
		return n;
	}

	int nextInt() {
		return (int) nextLong();
	}

	int nextInt(int bound) {
		if (bound <= 0) {
			throw new IllegalArgumentException("bound muss > 0 sein");
		}
		long l = Integer.toUnsignedLong(nextInt());
		long m = l * (long) bound;
		long n = m & 0xFFFFFFFFL;
		if (n < (long) bound) {
			int i = Integer.remainderUnsigned(~bound + 1, bound);
			while (n < (long) i) {
				l = Integer.toUnsignedLong(nextInt());
				m = l * (long) bound;
				n = m & 0xFFFFFFFFL;
			}
		}
		return (int) (m >> 32);
	}

	/** Zufallszahl von min bis max, beide eingeschlossen (wie Mth.randomBetweenInclusive). */
	int between(int min, int max) {
		return nextInt(max - min + 1) + min;
	}

	float nextFloat() {
		return (float) (nextLong() >>> 40) * 5.9604645E-8F;
	}

	double nextDouble() {
		return (double) (nextLong() >>> 11) * 1.1102230246251565E-16;
	}

	/** WorldgenRandom.setDecorationSeed: x und z sind Blockkoordinaten der Chunk-Ecke. */
	long setDecorationSeed(long worldSeed, int blockX, int blockZ) {
		setSeed(worldSeed);
		long l = nextLong() | 1L;
		long m = nextLong() | 1L;
		long n = (long) blockX * l + (long) blockZ * m ^ worldSeed;
		setSeed(n);
		return n;
	}

	/** WorldgenRandom.setFeatureSeed. */
	void setFeatureSeed(long decorationSeed, int index, int step) {
		setSeed(decorationSeed + (long) index + (long) (10000 * step));
	}
}
