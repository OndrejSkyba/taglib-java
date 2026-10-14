package cz.skyba.taglib.parity.fixtures;

/**
 * MT19937 with integer seeding and bit extraction matching the original fixture recipes.
 */
final class FixtureRandom {
  private final int[] state = new int[624];
  private int index = 624;

  FixtureRandom(long seed) {
    state[0] = 19650218;
    for (int i = 1; i < state.length; i++)
      state[i] = 1812433253 * (state[i - 1] ^ (state[i - 1] >>> 30)) + i;
    int at = 1;
    for (int n = 0; n < 624; n++) {
      state[at] = (state[at] ^ ((state[at - 1] ^ (state[at - 1] >>> 30)) * 1664525)) + (int) seed;
      if (++at == 624) {
        state[0] = state[623];
        at = 1;
      }
    }
    for (int n = 0; n < 623; n++) {
      state[at] = (state[at] ^ ((state[at - 1] ^ (state[at - 1] >>> 30)) * 1566083941)) - at;
      if (++at == 624) {
        state[0] = state[623];
        at = 1;
      }
    }
    state[0] = 0x80000000;
  }

  private int word() {
    if (index == 624) {
      for (int i = 0; i < 624; i++) {
        int joined = (state[i] & 0x80000000) | (state[(i + 1) % 624] & 0x7fffffff);
        state[i] = state[(i + 397) % 624] ^ (joined >>> 1) ^ ((joined & 1) == 0 ? 0 : 0x9908b0df);
      }
      index = 0;
    }
    int value = state[index++];
    value ^= value >>> 11;
    value ^= (value << 7) & 0x9d2c5680;
    value ^= (value << 15) & 0xefc60000;
    return value ^ (value >>> 18);
  }

  long bits(int count) {
    if (count == 0) return 0;
    if (count <= 32) return Integer.toUnsignedLong(word() >>> (32 - count));
    long low = Integer.toUnsignedLong(word());
    return low | (Integer.toUnsignedLong(word() >>> (64 - count)) << 32);
  }

  int below(int bound) {
    int width = 32 - Integer.numberOfLeadingZeros(bound);
    int value;
    do {
      value = (int) bits(width);
    } while (value >= bound);
    return value;
  }

  int between(int start, int end) {
    return start + below(end - start);
  }

  <T> T choose(T[] values) {
    return values[below(values.length)];
  }

  byte[] bytes(int count) {
    byte[] data = new byte[count];
    for (int offset = 0; offset < count; offset += 4) {
      int width = Math.min(4, count - offset);
      int value = word() >>> (32 - width * 8);
      for (int i = 0; i < width; i++) data[offset + i] = (byte) (value >>> (8 * i));
    }
    return data;
  }
}
