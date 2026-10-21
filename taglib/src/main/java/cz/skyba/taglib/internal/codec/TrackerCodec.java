// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.Codec;
import cz.skyba.taglib.internal.Rewrite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static cz.skyba.taglib.internal.Binary.*;

/// MOD/S3M/IT/XM module metadata. Like upstream, tracker duration is unspecified (zero).
public final class TrackerCodec implements Codec {
  private static final Set<Format> FORMATS = Set.of(Format.MOD, Format.S3M, Format.IT, Format.XM);
  private static final Set<String> PROPERTIES = Set.of("TITLE", "COMMENT", "TRACKERNAME");
  private static final Set<String> PROTRACKER_IDS = Set.of("M.K.", "M!K!", "M&K!", "N.T.");
  private final Format format;

  private record ReadResult(AudioMetadata snapshot, Details details) {
  }

  public sealed interface Details permits ModDetails, S3mDetails, ItDetails, XmDetails {
    int channels();

    default AudioProperties common() {
      return new AudioProperties(0, 0, 0, channels(), 0, false);
    }
  }

  public record ModDetails(int channels, long instrumentCount, int lengthInPatterns) implements Details {
  }

  public record S3mDetails(int lengthInPatterns, int channels, boolean stereo, int sampleCount,
                           int patternCount, int flags, int trackerVersion, int fileFormatVersion, int globalVolume,
                           int masterVolume, int tempo, int bpmSpeed) implements Details {
  }

  public record ItDetails(int channels, int lengthInPatterns, int instrumentCount, int sampleCount,
                          int patternCount, int version, int compatibleVersion, int flags, int special,
                          int globalVolume,
                          int mixVolume, int tempo, int bpmSpeed, int panningSeparation,
                          int pitchWheelDepth) implements Details {
  }

  public record XmDetails(int lengthInPatterns, int channels, int version, int restartPosition,
                          int patternCount, int instrumentCount, long sampleCount, int flags, int tempo, int bpmSpeed)
    implements Details {
  }

  public static Details readProperties(BinaryFile file, Format format, ReadStyle style) throws IOException {
    var codec = new TrackerCodec(format);
    Objects.requireNonNull(style);
    return codec.readNativeResult(file).details;
  }

  public TrackerCodec(Format format) {
    if (!FORMATS.contains(format)) {
      throw new IllegalArgumentException();
    }
    this.format = format;
  }

  public Format format() {
    return format;
  }

  private record Field(long offset, int width, boolean nul) {
  }

  private record Layout(
    Field title,
    Field tracker,
    String trackerName,
    List<Field> names,
    int channels,
    int special,
    long messageOffset,
    int messageLength,
    Details details) {
  }

  @Override
  public AudioMetadata read(Path path, ReadOptions options) throws IOException {
    try (var f = new BinaryFile(path)) {
      return read(f, options);
    }
  }

  @Override
  public AudioMetadata read(BinaryFile f, ReadOptions options) throws IOException {
    return read(f, layout(f));
  }

  @Override
  public AudioMetadata readNative(BinaryFile f, ReadOptions options) throws IOException {
    return readNativeResult(f).snapshot;
  }

  private ReadResult readNativeResult(BinaryFile f) throws IOException {
    Layout parsed;
    if (format == Format.S3M) {
      long start = f.initialPosition();
      byte[] title = f.readNative(start, 28);
      byte[] marker = f.readNative(start + 28, 2);
      if (title.length != 28 || marker.length != 2 || marker[0] != 26 || marker[1] != 16) {
        throw new IOException("Invalid S3M signature");
      }
      Layout base = s3m(f, true);
      parsed =
        new Layout(
          new Field(start, 28, true),
          base.tracker(),
          base.trackerName(),
          base.names(),
          base.channels(),
          base.special(),
          base.messageOffset(),
          base.messageLength(),
          base.details());
    } else {
      parsed = layout(f);
    }
    ReadResult result = readResult(f, parsed);
    long end;
    if (format == Format.MOD) {
      end = 21 + parsed.names().size() * 30L;
    } else if (format == Format.IT || format == Format.S3M) {
      if (!parsed.names().isEmpty()) {
        Field last = parsed.names().getLast();
        end = last.offset() + last.width();
      } else {
        long ordersOffset = format == Format.IT ? 192 : 96;
        int count = Binary.u16le(f.read(32, 2), 0);
        int scanned = 0;
        for (; scanned < count; scanned++) {
          if (f.u8(ordersOffset + scanned) == 255) {
            scanned++;
            break;
          }
        }
        end = ordersOffset + scanned + (format == Format.S3M ? parsed.channels() : 0);
      }
    } else {
      // XM StructReader consumes sample/instrument headers, then seeks over audio payloads.
      end = xmEnd(f);
    }
    f.seekNative(end);
    return result;
  }

  private AudioMetadata read(BinaryFile f, Layout layout) throws IOException {
    return readResult(f, layout).snapshot;
  }

  private ReadResult readResult(BinaryFile f, Layout layout) throws IOException {
    var comments = new ArrayList<String>();
    for (Field name : layout.names()) {
      comments.add(text(f, name));
    }
    if (layout.messageLength() > 0) {
      String message =
        text(f, new Field(layout.messageOffset(), layout.messageLength(), false), false)
          .replace('\r', '\n');
      if (!message.isEmpty()) {
        comments.add(message);
      }
    }
    var props =
      PropertyMap.builder()
        .put("TITLE", text(f, layout.title()))
        .put("COMMENT", String.join("\n", comments));
    String tracker = layout.tracker() == null ? layout.trackerName() : text(f, layout.tracker());
    if (!tracker.isEmpty()) {
      props.put("TRACKERNAME", tracker);
    }
    return new ReadResult(new AudioMetadata(
      format,
      props.build(),
      layout.details().common(),
      List.of()), layout.details());
  }

  @Override
  public PropertyMap unsupportedProperties(PropertyMap properties) {
    var rejected = PropertyMap.builder();
    for (var entry : properties.asMap().entrySet()) {
      if (entry.getValue().isEmpty()) {
        continue;
      }
      if (!PROPERTIES.contains(entry.getKey())) {
        rejected.put(entry.getKey(), entry.getValue());
      } else if (entry.getValue().size() > 1) {
        rejected.put(entry.getKey(), entry.getValue().subList(1, entry.getValue().size()));
      }
    }
    return rejected.build();
  }

  @Override
  public void write(
    BinaryFile f, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    throw new UnsupportedOperationException(
      "This format does not support embedded artwork editing");
  }

  @Override
  public void write(BinaryFile f, PropertyMap properties) throws IOException {
    for (var entry : properties.asMap().entrySet()) {
      if (entry.getValue().isEmpty()) {
        continue;
      }
      if (!PROPERTIES.contains(entry.getKey()) || entry.getValue().size() > 1) {
        throw new IOException("Unsupported tracker property: " + entry.getKey());
      }
    }
    var edits = new ArrayList<Rewrite.Edit>();

    Layout layout = format == Format.XM ? xm(f, true) : layout(f);
    put(edits, layout.title(), properties.first("TITLE"));
    if (layout.tracker() != null) {
      put(edits, layout.tracker(), properties.first("TRACKERNAME"));
    }
    // Upstream accepts TRACKERNAME in its common module tag, but only XM stores it.
    String[] lines = properties.first("COMMENT").split("\n", -1);
    for (int i = 0; i < layout.names().size(); i++) {
      put(edits, layout.names().get(i), i < lines.length ? lines[i] : "");
    }
    if (format == Format.IT) {
      String message =
        lines.length > layout.names().size()
          ? String.join("\r", Arrays.copyOfRange(lines, layout.names().size(), lines.length))
          : "";
      byte[] raw = latin1(message);
      byte[] data = Arrays.copyOf(raw, Math.min(raw.length, 7999) + 1);
      long offset = layout.messageOffset();
      int oldLength = layout.messageLength();
      if (oldLength == 0) {
        offset = f.size();
      }
      if (offset + oldLength >= f.size()) {
        byte[] descriptor = new byte[6];
        descriptor[0] = (byte) data.length;
        descriptor[1] = (byte) (data.length >>> 8);
        put32le(descriptor, 2, offset);
        edits.add(
          new Rewrite.Edit(
            46,
            2,
            new byte[]{(byte) (layout.special() | 1), (byte) (layout.special() >>> 8)}));
        edits.add(new Rewrite.Edit(54, 6, descriptor));
        edits.add(new Rewrite.Edit(offset, f.size() - offset, data));
      } else {
        edits.add(new Rewrite.Edit(offset, oldLength, Arrays.copyOf(data, oldLength)));
      }
    }
    // Other tracker formats can only store the lines that fit their existing name fields.

    Rewrite.replaceNative(f, edits);
    if (f.stream() != null) {
      if (format == Format.MOD) {
        f.seekNative(20 + layout.names().size() * 30L);
      } else if (format == Format.XM) {
        f.seekNative(xmSaveEnd(f));
      } else if (format == Format.S3M && layout.names().isEmpty()) {
        f.seekNative(96 + layout.channels());
      }
    }
  }

  private Layout layout(BinaryFile f) throws IOException {
    return switch (format) {
      case MOD -> mod(f);
      case S3M -> s3m(f);
      case IT -> it(f);
      case XM -> xm(f);
      default -> throw new AssertionError();
    };
  }

  private static boolean asciiDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static Layout mod(BinaryFile f) throws IOException {
    byte[] identifier = f.readNative(1080, 4);
    if (identifier.length != 4) {
      throw new IOException("Truncated MOD identifier");
    }
    String id = new String(identifier, StandardCharsets.ISO_8859_1);
    int channels = 4, count = 31;
    String tracker;
    if (PROTRACKER_IDS.contains(id)) {
      tracker = "ProTracker";
    } else if ((id.startsWith("FLT") || id.startsWith("TDZ")) && asciiDigit(id.charAt(3))) {
      tracker = "StarTrekker";
      channels = id.charAt(3) - '0';
    } else if (asciiDigit(id.charAt(0)) && id.endsWith("CHN")) {
      tracker = "StarTrekker";
      channels = id.charAt(0) - '0';
    } else if (id.equals("CD81") || id.equals("OKTA")) {
      tracker = "Atari Oktalyzer";
      channels = 8;
    } else if (asciiDigit(id.charAt(0)) && asciiDigit(id.charAt(1)) && (id.endsWith("CH") || id.endsWith("CN"))) {
      tracker = "TakeTracker";
      channels = Integer.parseInt(id.substring(0, 2));
    } else {
      if (id.startsWith("FLT")
        || id.startsWith("TDZ")
        || id.endsWith("CHN")
        || id.endsWith("CH")
        || id.endsWith("CN")) {
        throw new IOException("Invalid MOD channel identifier");
      }
      tracker = "NoiseTracker";
      count = 15;
    }
    var names = new ArrayList<Field>();
    for (int i = 0; i < count; i++) {
      names.add(new Field(20 + i * 30L, 22, false));
    }
    return new Layout(new Field(0, 20, false), null, tracker, names, channels, 0, 0, 0,
      new ModDetails(channels, count, f.u8(20 + count * 30L)));
  }

  private static Layout s3m(BinaryFile f) throws IOException {
    return s3m(f, false);
  }

  private static Layout s3m(BinaryFile f, boolean markerRead) throws IOException {
    if (f.size() < 96
      || (!markerRead && (f.u8(28) != 26 || f.u8(29) != 16))
      || !f.ascii(44, 4).equals("SCRM")) {
      throw new IOException("Invalid S3M header");
    }
    byte[] h = f.read(0, 96);
    int orders = u16le(h, 32), samples = u16le(h, 34), channels = 0;
    int length = readOrders(f, 96, orders);
    for (int i = 64; i < 96; i++) {
      if ((h[i] & 255) != 255) {
        channels++;
      }
    }
    var names = new ArrayList<Field>();
    for (int i = 0; i < samples; i++) {
      long offset = u16le(f.read(96L + orders + i * 2L, 2), 0) * 16L;
      check(f, offset, 76);
      names.add(new Field(offset + 48, 28, true));
    }
    return new Layout(new Field(0, 28, true), null, "ScreamTracker III", names, channels, 0, 0, 0,
      new S3mDetails(length, channels, (h[51] & 0x80) != 0, samples, u16le(h, 36),
        u16le(h, 38), u16le(h, 40), u16le(h, 42), h[48] & 255, h[51] & 127,
        h[50] & 255, h[49] & 255));
  }

  private static Layout it(BinaryFile f) throws IOException {
    if (f.size() < 192 || !f.ascii(0, 4).equals("IMPM")) {
      throw new IOException("Invalid IT header");
    }
    byte[] h = f.read(0, 192);
    int orders = u16le(h, 32),
      instruments = u16le(h, 34),
      samples = u16le(h, 36),
      special = u16le(h, 46),
      channels = 0;
    for (int i = 0; i < 64; i++) {
      if ((h[64 + i] & 255) < 128 && h[128 + i] > 0) {
        channels++;
      }
    }
    int length = readOrders(f, 192, orders);
    var names = new ArrayList<Field>();
    for (int i = 0; i < instruments + samples; i++) {
      long offset = f.u32le(192L + orders + i * 4L);
      boolean instrument = i < instruments;
      if (!f.ascii(offset, 4).equals(instrument ? "IMPI" : "IMPS")) {
        throw new IOException("Invalid IT instrument/sample header");
      }
      long name = offset + (instrument ? 32 : 20);
      check(f, name, 26);
      names.add(new Field(name, 26, true));
    }
    int messageLength = (special & 1) != 0 ? u16le(h, 54) : 0;
    long messageOffset = messageLength > 0 ? u32le(h, 56) : 0;
    check(f, messageOffset, messageLength);
    return new Layout(
      new Field(4, 26, true),
      null,
      "Impulse Tracker",
      names,
      channels,
      special,
      messageOffset,
      messageLength,
      new ItDetails(channels, length, instruments, samples, u16le(h, 38), u16le(h, 40),
        u16le(h, 42), u16le(h, 44), special, h[48] & 255, h[49] & 255, h[51] & 255,
        h[50] & 255, h[52] & 255, h[53] & 255));
  }

  private static Layout xm(BinaryFile f) throws IOException {
    return xm(f, false);
  }

  private static Layout xm(BinaryFile f, boolean writing) throws IOException {
    if (f.size() < 64) {
      throw new IOException("Truncated XM header");
    }
    byte[] magic = f.read(0, 17);
    if (!ascii(magic, 0, 17).equals("Extended Module: ") && !Arrays.equals(magic, new byte[17])) {
      throw new IOException("Invalid XM header");
    }
    if (f.u8(37) != 0x1a && f.u8(37) != 0) {
      throw new IOException("Invalid XM escape marker");
    }
    long headerSize = f.u32le(60);
    if (headerSize < 4) {
      throw new IOException("Invalid XM header size");
    }
    byte[] h = f.read(64, (int) Math.min(16, headerSize - 4));
    int channels = shortField(h, 4), patterns = shortField(h, 6), instruments = shortField(h, 8);
    long at = 60 + headerSize;
    for (int i = 0; i < patterns; i++) {
      long size = f.u32le(at);
      if (size < 4) {
        throw new IOException("Invalid XM pattern size");
      }
      int packed = writing ? (size >= 9 ? u16le(f.read(at + 7, 2), 0) : 0)
        : shortField(f.read(at + 4, (int) Math.min(5, size - 4)), 3);
      at = advance(at, size + packed);
    }
    var names = new ArrayList<Field>();
    var samples = new ArrayList<Field>();
    long sampleCount = 0;
    for (int i = 0; i < instruments; i++) {
      long size = f.u32le(at);
      if (size < 4) {
        throw new IOException("Invalid XM instrument size");
      }
      names.add(new Field(at + 4, (int) Math.min(22, size - 4), false));
      int count = writing ? (size >= 29 ? u16le(f.read(at + 27, 2), 0) : 0)
        : shortField(f.read(at + 4, (int) Math.min(25, size - 4)), 23);
      sampleCount = (sampleCount + count) & 0xffff_ffffL;
      long sampleSize = 0;
      if (count > 0) {
        if (size < 33) {
          throw new IOException("Truncated XM sample header size");
        }
        sampleSize = f.u32le(at + 29);
        if (sampleSize == 0 && !writing) {
          sampleSize = 40;
        }
      }
      at = advance(at, size);
      long sampleBytes = 0;
      for (int j = 0; j < count; j++) {
        if (writing) {
          if (sampleSize >= 5) {
            sampleBytes += f.u32le(at);
          }
        } else {
          byte[] sampleFields = f.read(at, (int) Math.min(40, sampleSize));
          sampleBytes += uintField(Arrays.copyOf(sampleFields, Math.min(4, sampleFields.length)));
        }
        if (!writing || sampleSize > 18) {
          samples.add(
            new Field(
              at + Math.min(18, sampleSize),
              Math.clamp(sampleSize - 18, 0, 22),
              false));
        }
        at = advance(at, sampleSize);
      }
      at = advance(at, sampleBytes);
    }
    names.addAll(samples);
    return new Layout(
      new Field(17, 20, false), new Field(38, 20, false), "", names, channels, 0, 0, 0,
      new XmDetails(shortField(h, 0), channels, u16le(f.read(58, 2), 0), shortField(h, 2),
        patterns, instruments, sampleCount, shortField(h, 10), shortField(h, 12), shortField(h, 14)));
  }

  private static long xmEnd(BinaryFile f) throws IOException {
    long header = f.u32le(60), at = 60 + header;
    byte[] data = f.read(64, (int) Math.min(16, header - 4));
    int patterns = shortField(data, 6), instruments = shortField(data, 8);
    for (int i = 0; i < patterns; i++) {
      long n = f.u32le(at);
      int packed = n > 7 ? shortField(f.read(at + 7, (int) Math.min(2, n - 7)), 0) : 0;
      at = advance(at, n + packed);
    }
    for (int i = 0; i < instruments; i++) {
      long n = f.u32le(at);
      int count = n >= 29 ? Binary.u16le(f.read(at + 27, 2), 0) : 0;
      long sampleHeader = count > 0 && n >= 33 ? f.u32le(at + 29) : 40;
      if (sampleHeader == 0) {
        sampleHeader = 40;
      }
      at = advance(at, n);
      long audio = 0;
      for (int j = 0; j < count; j++) {
        audio += uintField(f.read(at, (int) Math.min(4, sampleHeader)));
        at = advance(at, sampleHeader);
      }
      at = advance(at, audio);
    }
    return at;
  }

  private static long xmSaveEnd(BinaryFile f) throws IOException {
    long header = f.u32le(60), cursor = 74, at = 60 + header;
    int patterns = Binary.u16le(f.read(70, 2), 0), instruments = Binary.u16le(f.read(72, 2), 0);
    for (int i = 0; i < patterns; i++) {
      long size = f.u32le(at);
      int packed = Binary.u16le(f.read(at + 7, 2), 0);
      cursor = at + 9;
      at += size + packed;
    }
    for (int i = 0; i < instruments; i++) {
      long size = f.u32le(at);
      cursor = at + 4 + Math.min(22, size - 4);
      int samples = 0;
      long sampleHeader = 0, audio = 0;
      if (size >= 29) {
        samples = Binary.u16le(f.read(at + 27, 2), 0);
        cursor = at + 29;
      }
      if (samples > 0) {
        sampleHeader = f.u32le(at + 29);
        cursor = at + 33;
      }
      at += size;
      for (int j = 0; j < samples; j++) {
        if (sampleHeader > 4) {
          audio += f.u32le(at);
          cursor = at + 4;
          if (sampleHeader > 18) {
            cursor = at + 18 + Math.min(22, sampleHeader - 18);
          }
        }
        at += sampleHeader;
      }
      at += audio;
    }
    return cursor;
  }

  private static int shortField(byte[] bytes, int offset) {
    int value = offset < bytes.length ? bytes[offset] & 255 : 0;
    if (offset + 1 < bytes.length) {
      value |= (bytes[offset + 1] & 255) << 8;
    }
    return value;
  }

  private static long uintField(byte[] bytes) {
    long value = 0;
    for (int i = 0; i < bytes.length; i++) {
      value |= (long) (bytes[i] & 255) << (i * 8);
    }
    return value;
  }

  private static long advance(long offset, long amount) throws IOException {
    if (amount < 0 || offset > Long.MAX_VALUE - amount) {
      throw new IOException("Tracker offset exceeds signed file range");
    }
    return offset + amount;
  }

  private static String text(BinaryFile f, Field field) throws IOException {
    return text(f, field, true);
  }

  private static String text(BinaryFile f, Field field, boolean replaceFill) throws IOException {
    byte[] bytes = f.read(field.offset(), field.width());
    int length = 0;
    while (length < bytes.length && bytes[length] != 0) {
      length++;
    }
    if (replaceFill) {
      for (int i = 0; i < length; i++) {
        if (bytes[i] == (byte) 255) {
          bytes[i] = 32;
        }
      }
    }
    return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
  }

  private static void put(List<Rewrite.Edit> edits, Field field, String value) {
    byte[] bytes = latin1(value), result = new byte[field.width()];
    System.arraycopy(
      bytes,
      0,
      result,
      0,
      Math.clamp(result.length - (field.nul() ? 1 : 0), 0, bytes.length));
    edits.add(new Rewrite.Edit(field.offset(), field.width(), result));
  }

  private static byte[] latin1(String value) {
    byte[] bytes = new byte[value.length()];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) value.charAt(i);
    }
    return bytes;
  }

  private static int readOrders(BinaryFile file, long offset, int count) throws IOException {
    int length = 0;
    for (int i = 0; i < count; i++) {
      int order = file.u8(offset + i);
      if (order == 255) {
        break;
      }
      if (order != 254) {
        length++;
      }
    }
    return length;
  }

  private static void check(BinaryFile f, long offset, long length) throws IOException {
    if (offset < 0 || length < 0 || offset > f.size() || length > f.size() - offset) {
      throw new IOException("Truncated tracker structure");
    }
  }
}
