// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.Codec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static cz.skyba.taglib.internal.Binary.*;

/// Audio metadata for Shorten versions 1–3 with an initial verbatim WAV/AIFF header.
public final class ShortenCodec implements Codec {
  public record Details(AudioProperties common, int version, int fileType, long sampleFrames) {
  }

  private record Parsed(AudioMetadata file, Details details) {
  }

  public static Details readProperties(BinaryFile file, ReadStyle style) throws IOException {
    return readProperties(file, new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes()));
  }

  public static Details readProperties(BinaryFile file, ReadOptions options) throws IOException {
    return new ShortenCodec().parse(file, options, file.initialPosition()).details();
  }

  /// PropertyValues' unsigned long frame count is represented by raw long bits on Linux.
  public static Details properties(
    int version, int fileType, int channels, int rate,
    int bits, long samples
  ) {
    double frames = samples >= 0 ? samples : (double) (samples & Long.MAX_VALUE) + 0x1.0p63;
    var audio = new AudioProperties(rate > 0 ? bounded(frames * 1000.0 / rate) : 0,
      bounded((double) rate * bits * channels / 1000.0), rate, channels, bits, true);
    return new Details(audio, version, fileType, samples);
  }

  @Override
  public AudioMetadata read(Path path, ReadOptions options) throws IOException {
    try (var f = new BinaryFile(path)) {
      return read(f, options);
    }
  }

  @Override
  public AudioMetadata read(BinaryFile f, ReadOptions options) throws IOException {
    return parse(f, options, 0).file();
  }

  @Override
  public AudioMetadata readNative(BinaryFile f, ReadOptions options) throws IOException {
    return parse(f, options, f.initialPosition()).file();
  }

  private Parsed parse(BinaryFile f, ReadOptions options, long start) throws IOException {
    if (!new String(f.readNative(start, 4), StandardCharsets.ISO_8859_1)
      .equals("ajkg")) {
      throw new IOException("Invalid Shorten header");
    }
    byte[] versionBytes = f.readNative(start + 4, 1);
    int version = versionBytes.length == 0 ? 0 : versionBytes[0] & 255;
    if (version < 1 || version > 3) {
      throw new IOException("Unsupported Shorten version");
    }
    var input = new Rice(f, start + 5);
    int fileType = (int) input.uint();
    long channels = input.uint(), block = input.uint();
    if (channels < 1 || channels > 8 || block < 1 || block > 65535) {
      throw new IOException("Invalid Shorten channel/block count");
    }
    input.uint();
    input.uint();
    long skip = input.uint();
    if (skip > options.maxMetadataBytes()) {
      throw new IOException("Oversized Shorten header");
    }
    for (long i = 0; i < skip; i++) {
      input.uint();
    }
    if (input.rice(2) != 9) {
      throw new IOException("Missing Shorten verbatim header");
    }
    long size = input.rice(5);
    if (size < 44 || size > 256) {
      throw new IOException("Invalid Shorten verbatim header length");
    }
    byte[] h = new byte[(int) size];
    for (int i = 0; i < h.length; i++) {
      h[i] = (byte) input.rice(8);
    }
    int rate = 0, bits = 0;
    long samples = 0;
    boolean found = false;
    if (ascii(h, 0, 4).equals("RIFF") && ascii(h, 8, 4).equals("WAVE")) {
      long data = 0;
      int align = 0;
      // The released reader advances over the PCM fields themselves, not the declared
      // chunk size. Preserve that behavior for extended/unknown verbatim chunks.
      for (int at = 12; at < h.length; ) {
        String id = at + 4 <= h.length ? ascii(h, at, 4) : "";
        long length = little(h, at + 4, 4);
        at += 8;
        if (id.equals("fmt ")) {
          if (length < 16 || little(h, at, 2) != 1) {
            throw new IOException("Unsupported Shorten PCM header");
          }
          rate = (int) little(h, at + 4, 4);
          align = (int) little(h, at + 12, 2);
          bits = (int) little(h, at + 14, 2);
          at += 16;
          found = true;
        }
        if (id.equals("data")) {
          data = length;
        }
      }
      samples = align > 0 ? data / align : 0;
    } else if (ascii(h, 0, 4).equals("FORM")
      && (ascii(h, 8, 4).equals("AIFF") || ascii(h, 8, 4).equals("AIFC"))) {
      for (int at = 12; at < h.length; ) {
        if (h.length - at < 8) {
          throw new IOException("Truncated Shorten AIFF chunk header");
        }
        String id = ascii(h, at, 4);
        long length = u32be(h, at + 4);
        at += 8;
        long padded = length + (length & 1);
        if (padded > h.length - at) {
          throw new IOException("Truncated Shorten AIFF header");
        }
        if (id.equals("COMM")) {
          if (length < 18) {
            throw new IOException("Invalid Shorten AIFF common header");
          }
          samples = u32be(h, at + 2);
          bits = u16be(h, at + 6);
          int exponent = (short) u16be(h, at + 8) - 16383 - 63;
          if (exponent < -63 || exponent > 63) {
            throw new IOException("Invalid Shorten AIFF sample rate");
          }
          long fraction = u64be(h, at + 10);
          rate =
            (int)
              (exponent >= 0
                ? fraction << exponent
                : (fraction + (1L << (-exponent - 1))) >>> -exponent);
          found = true;
        }
        at += (int) padded;
      }
    }
    if (!found) {
      throw new IOException("Unsupported Shorten verbatim audio header");
    }
    var details = properties(version, fileType, (int) channels, rate, bits, samples);
    return new Parsed(
      new AudioMetadata(Format.SHORTEN, PropertyMap.EMPTY, details.common(), List.of()), details);
  }

  private static int bounded(double value) {
    return value >= 0 && value + 0.5 <= Integer.MAX_VALUE ? (int) (value + 0.5) : 0;
  }

  private static long little(byte[] bytes, int offset, int width) {
    long value = 0;
    for (int i = 0; i < width && offset + i < bytes.length; i++) {
      value |= (long) (bytes[offset + i] & 255) << (8 * i);
    }
    return value;
  }

  @Override
  public PropertyMap unsupportedProperties(PropertyMap properties) {
    return properties;
  }

  @Override
  public void write(
    BinaryFile file, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    throw new UnsupportedOperationException(
      "This format does not support embedded artwork editing");
  }

  @Override
  public void write(BinaryFile file, PropertyMap properties)
    throws IOException {
    throw new UnsupportedOperationException("Shorten does not support metadata writing");
  }

  private static final class Rice {
    private final BinaryFile file;
    private byte[] buffer = new byte[0];
    private int index, bit;
    private long offset;

    Rice(BinaryFile file, long offset) {
      this.file = file;
      this.offset = offset;
    }

    int bit() throws IOException {
      if (index >= buffer.length) {
        // Native asks for 512 bytes and accepts the available complete words. The
        // stream's reported length must not turn an otherwise valid short read into EOF.
        buffer = file.readNative(offset, 512);
        int count = buffer.length;
        offset += count;
        if (count < 4) {
          throw new IOException("Truncated Shorten Rice value");
        }
        if ((count & 3) != 0) {
          buffer = Arrays.copyOf(buffer, count & ~3);
        }
        index = 0;
        bit = 0;
      }
      int value = (buffer[index] >>> (7 - bit)) & 1;
      if (++bit == 8) {
        bit = 0;
        index++;
      }
      return value;
    }

    long rice(int k) throws IOException {
      if (k < 0 || k > 31) {
        throw new IOException("Invalid Shorten Rice exponent");
      }
      long high = 0;
      while (bit() == 0) {
        if (++high > 0xffffffffL >>> k) {
          throw new IOException("Overflowing Shorten Rice value");
        }
      }
      long value = high;
      for (int i = 0; i < k; i++) {
        value = (value << 1) | bit();
      }
      return value;
    }

    long uint() throws IOException {
      return rice((int) rice(2));
    }
  }
}
