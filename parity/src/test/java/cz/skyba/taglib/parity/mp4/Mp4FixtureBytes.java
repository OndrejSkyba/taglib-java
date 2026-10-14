package cz.skyba.taglib.parity.mp4;

import cz.skyba.taglib.internal.Binary;

import java.io.ByteArrayOutputStream;

public final class Mp4FixtureBytes {
  public static byte[] box(String name, byte[]... payload) throws Exception {
    var out = new ByteArrayOutputStream();
    for (byte[] p : payload) out.write(p);
    byte[] b = new byte[out.size() + 8];
    Binary.put32be(b, 0, b.length);
    System.arraycopy(Binary.bytes(name), 0, b, 4, 4);
    System.arraycopy(out.toByteArray(), 0, b, 8, out.size());
    return b;
  }

  public static byte[] join(byte[]... parts) throws Exception {
    var out = new ByteArrayOutputStream();
    for (byte[] p : parts) out.write(p);
    return out.toByteArray();
  }

  public static int index(byte[] data, byte[] needle) {
    outer:
    for (int i = 0; i <= data.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
      return i;
    }
    return -1;
  }
}
