// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.internal.ape.ApeTag;
import cz.skyba.taglib.internal.id3.Id3v1;
import cz.skyba.taglib.internal.id3.Id3v2;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.mpeg.XingHeader;
import cz.skyba.taglib.toolkit.ByteVector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static cz.skyba.taglib.internal.Binary.u32be;

/// MPEG layer I/II/III and ADTS elementary streams with ID3 and APE tags.
public final class MpegCodec implements Codec {
  private Id3Location nativeId3Location;
  private long nativeId3OriginalSize;
  private Details audioDetails = Details.EMPTY;

  private record Header(MpegHeaderData data) {
    int bitrate() {
      return data.bitrate();
    }

    int sampleRate() {
      return data.sampleRate();
    }

    int length() {
      return data.frameLength();
    }

    int samples() {
      return data.samplesPerFrame();
    }

    boolean adts() {
      return data.isADTS();
    }

    int channels() {
      int configuration = data.channelConfiguration();
      return configuration == 7 ? 8 : configuration != 0 ? configuration
        : data.channelMode() == 3 ? 1 : 2;
    }
  }

  private record Located(long offset, Header header) {
  }

  public record Details(AudioProperties common, MpegHeaderData header,
                        XingHeader xingHeader) {
    private static final Details EMPTY = new Details(AudioProperties.EMPTY,
      MpegHeaderData.parse(new byte[0], 0), null);
  }

  /// Reads the specialized MPEG codec directly, without AudioFile extension/sniff dispatch.
  public static Details readProperties(BinaryFile file, ReadStyle style) throws IOException {
    var codec = new MpegCodec();
    codec.readNative(file, new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes()));
    return codec.audioDetails;
  }

  public record Id3Location(long offset, int size) {
  }

  /// Mirrors upstream AudioFile sniffing: two coherent headers within 1 KiB after a leading ID3 tag.
  public static boolean isSupported(Path path) throws IOException {
    try (var file = new BinaryFile(path)) {
      return isSupported(file);
    }
  }

  public static boolean isSupported(BinaryFile file) throws IOException {
    long begin = file.size() >= 10 ? Id3v2.tagSize(file.read(0, 10)) : 0;
    if (begin > file.size() - 4) {
      return false;
    }
    byte[] bytes = file.read(begin, (int) Math.min(1030, file.size() - begin));
    for (int i = 0; i < Math.min(1024, bytes.length - 3); i++) {
      Header first = header(bytes, i);
      if (first == null) {
        continue;
      }
      long next = begin + i + first.length();
      if (next > file.size() - 4) {
        continue;
      }
      if (first.length() > 0
        && matchingHeaders(bytes, i, file.read(next, 4), 0)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public AudioMetadata read(Path path, ReadOptions options) throws IOException {
    try (var file = new BinaryFile(path)) {
      return read(file, options);
    }
  }

  @Override
  public AudioMetadata read(BinaryFile file, ReadOptions options) throws IOException {
    Id3Location location = initialTag(file, options);
    long originalId3Span = location.size() == 0 ? 0 : NativeTagIO.id3v2(file, location.offset()).length();
    Id3v2.TagData id3 = Id3v2.read(file.read(location.offset(), location.size()), options);
    ApeTag.Data ape = ApeTag.read(file, options);
    boolean hasV1 = file.size() >= 128 && file.ascii(file.size() - 128, 3).equals("TAG");
    PropertyMap v1 = hasV1 ? Id3v1.read(file.read(file.size() - 128, 128)) : PropertyMap.EMPTY;
    PropertyMap properties =
      !id3.empty() ? id3.properties() : !ape.items().isEmpty() ? ape.properties() : v1;
    List<Picture> pictures = id3.pictures().isEmpty() ? ape.pictures() : id3.pictures();
    long end = ape.length() > 0 ? ape.offset() : file.size() - (hasV1 ? 128 : 0);
    audioDetails = options.readAudioProperties()
      ? properties(file, location.offset() + originalId3Span, end, options.readStyle(), false)
      : Details.EMPTY;
    AudioProperties audio = audioDetails.common();
    return new AudioMetadata(
      Format.MPEG, properties, audio, pictures, mergeTags(id3.basic(), ape.basic(), new Tag(v1)));
  }

  @Override
  public void write(Path path, PropertyMap properties) throws IOException {
    write(path, properties, null);
  }

  @Override
  public AudioMetadata readNative(BinaryFile file, ReadOptions options) throws IOException {
    AudioMetadata result = read(file, options);
    if (file.stream() == null) {
      return result;
    }
    var location = initialTag(file, options);
    nativeId3Location = location;
    nativeId3OriginalSize = 0;
    long begin = 0;
    if (location.size() > 0) {
      nativeId3OriginalSize = NativeTagIO.id3v2(file.stream(), location.offset()).length();
      begin = location.offset() + nativeId3OriginalSize;
    }
    long v1 = NativeTagIO.id3v1(file.stream());
    long footer = NativeTagIO.ape(file.stream(), v1);
    long end = footer < 0 ? v1 : ApeTag.read(file, options).offset();
    // ApeTag.read is an independent parser; restore the native detector/body cursor.
    NativeTagIO.ape(file.stream(), v1);
    if (options.readAudioProperties()) {
      audioDetails = properties(file, begin, end < 0 ? file.size() : end, options.readStyle(), true);
    }
    return new AudioMetadata(result.format(), result.properties(), audioDetails.common(),
      result.pictures(), result.tag());
  }

  private static Header nativeHeader(BinaryFile file, long offset, boolean checkLength)
    throws IOException {
    byte[] first = NativeTagIO.read(file.stream(), offset, 4);
    if (first.length < 4) {
      return null;
    }
    byte[] bytes = first;
    if (MpegHeaderData.needsADTSLength(first)) {
      byte[] tail = file.stream().readBlock(2).data();
      bytes = Arrays.copyOf(first, 4 + tail.length);
      System.arraycopy(tail, 0, bytes, 4, tail.length);
    }
    Header h = header(bytes, 0);
    if (h == null || !checkLength) {
      return h;
    }
    if (h.length() == 0) {
      return null;
    }
    byte[] next = NativeTagIO.read(file.stream(), offset + h.length(), 4);
    if (next.length < 4 || (u32be(first, 0) & 0xfffe0c00L) != (u32be(next, 0) & 0xfffe0c00L)) {
      return null;
    }
    return h;
  }

  private static long nativeFrame(BinaryFile file, long position, boolean reverse)
    throws IOException {
    int previous = 0;
    while (!reverse || position > 0) {
      int length = reverse ? (int) Math.min(position, 1024) : 1024;
      if (reverse) {
        position -= length;
      }
      byte[] buffer = NativeTagIO.read(file.stream(), position, length);
      if (buffer.length == 0) {
        return -1;
      }
      for (int i = reverse ? buffer.length - 1 : 0;
           reverse ? i >= 0 : i < buffer.length;
           i += reverse ? -1 : 1) {
        int current = buffer[i] & 255;
        int first = reverse ? current : previous, second = reverse ? previous : current;
        previous = current;
        if (first != 255 || second == 255 || (second & 224) != 224) {
          continue;
        }
        long at = position + i - (reverse ? 0 : 1);
        Header h = nativeHeader(file, at, true);
        if (h != null) {
          return reverse ? at + h.length() : at;
        }
      }
      if (!reverse) {
        position += 1024;
      }
    }
    return -1;
  }

  @Override
  public void write(
    BinaryFile file,
    PropertyMap properties,
    List<Picture> pictures
  )
    throws IOException {
    writeCore(file, properties, pictures, false);
  }

  @Override
  public void writeNative(
    BinaryFile file,
    long constructorOffset,
    PropertyMap properties,
    List<Picture> pictures
  )
    throws IOException {
    writeCore(file, properties, pictures, file.stream() != null);
  }

  private void writeCore(
    BinaryFile file,
    PropertyMap properties,
    List<Picture> pictures,
    boolean nativeProtocol
  )
    throws IOException {
    List<Rewrite.Edit> edits = new ArrayList<>();
    long savedCursor = file.stream() == null ? 0 : file.stream().tell();

    Id3Location location = nativeProtocol && nativeId3Location != null
      ? nativeId3Location : initialTag(file, ReadOptions.DEFAULT);
    long nativeOriginalSize =
      nativeProtocol && nativeId3Location != null ? nativeId3OriginalSize : location.size() == 0 ? 0 : nativeProtocol
        ? NativeTagIO.id3v2(file.stream(), location.offset()).length()
        : NativeTagIO.id3v2(file, location.offset()).length();
    // Upstream always creates both tag objects on read. setProperties updates ID3v1's
    // untruncated common values, then save fills missing common fields in both directions.
    PropertyMap legacyPlan = NativeSaveState.id3v1Properties();
    Tag legacy = legacyFromProperties(legacyPlan == null ? properties : legacyPlan);
    var rendered =
      Id3v2.renderMpeg(
        properties,
        file.read(location.offset(), location.size()),
        pictures,
        legacy,
        file.size(),
        nativeOriginalSize);
    byte[] modern = rendered.bytes();
    legacy = normalizeLegacy(mergeTags(legacy, rendered.basic()));
    PropertyMap legacyProperties = commonProperties(legacy);
    edits.add(new Rewrite.Edit(location.offset(), Math.min(nativeOriginalSize, file.size() - location.offset()), modern));
    boolean hadLegacy = file.size() >= 128 && file.ascii(file.size() - 128, 3).equals("TAG");
    byte[] legacyBytes = legacyProperties.isEmpty() ? new byte[0] : Id3v1.render(legacyProperties);
    if (hadLegacy || !legacyProperties.isEmpty()) {
      edits.add(
        new Rewrite.Edit(file.size() - (hadLegacy ? 128 : 0), hadLegacy ? 128 : 0, legacyBytes));
    }
    var ape = ApeTag.read(file, ReadOptions.DEFAULT);
    PropertyMap apeProperties = NativeSaveState.mpegApeProperties();
    byte[] apeBytes =
      ape.length() > 0 || apeProperties != null
        ? ApeTag.render(apeProperties == null ? ape.properties() : apeProperties, ape, pictures)
        : new byte[0];
    if (ape.length() > 0 || apeBytes.length > 0) {
      edits.add(new Rewrite.Edit(ape.offset(), ape.length(), apeBytes));
    }

    if (nativeProtocol) {
      var apeSpan = NativeTagIO.apeSpan(file, ape);
      var stream = file.stream();
      stream.seek(savedCursor);
      long delta = modern.length - nativeOriginalSize;
      if (modern.length > 0) {
        stream.insert(
          new ByteVector(modern),
          location.offset(),
          Math.clamp(stream.length() - location.offset(), 0L, nativeOriginalSize));
      } else if (nativeOriginalSize > 0) {
        stream.removeBlock(location.offset(), nativeOriginalSize);
      }
      nativeId3OriginalSize = modern.length;
      nativeId3Location = new Id3Location(modern.length == 0 ? 0 : location.offset(), modern.length);
      long legacyAt = hadLegacy ? file.size() - 128 + delta : stream.length();
      if (legacyBytes.length > 0) {
        NativeTagIO.write(stream, legacyAt, legacyBytes);
      } else if (hadLegacy) {
        stream.truncate(legacyAt);
      }
      long apeAt = apeSpan.offset() + delta;
      if (apeBytes.length > 0) {
        stream.insert(new ByteVector(apeBytes), apeAt, apeSpan.length());
      } else if (apeSpan.length() > 0) {
        stream.removeBlock(apeAt, apeSpan.length());
      }
      return;
    }

    Rewrite.replace(file, edits);
  }

  private static Tag legacyFromProperties(PropertyMap properties) {
    return normalizeLegacy(
      new Tag(
        properties.first("TITLE"),
        properties.first("ARTIST"),
        properties.first("ALBUM"),
        properties.first("COMMENT"),
        properties.first("GENRE"),
        strictNumber(properties.first("DATE")),
        strictNumber(properties.first("TRACKNUMBER"))));
  }

  private static long strictNumber(String value) {
    var converted = TextNumbers.extractInt(value);
    return converted.ok() ? Integer.toUnsignedLong(converted.value()) : 0;
  }

  private static Tag normalizeLegacy(Tag tag) {
    String genre = "";
    for (int i = 0; i < 255; i++) {
      if (Id3v1.genre(i).equals(tag.genre())) {
        genre = tag.genre();
        break;
      }
    }
    return new Tag(
      tag.title(),
      tag.artist(),
      tag.album(),
      tag.comment(),
      genre,
      tag.year(),
      tag.track() < 256 ? tag.track() : 0);
  }

  private static PropertyMap commonProperties(Tag tag) {
    var properties = PropertyMap.builder();
    if (!tag.title().isEmpty()) {
      properties.put("TITLE", tag.title());
    }
    if (!tag.artist().isEmpty()) {
      properties.put("ARTIST", tag.artist());
    }
    if (!tag.album().isEmpty()) {
      properties.put("ALBUM", tag.album());
    }
    if (!tag.comment().isEmpty()) {
      properties.put("COMMENT", tag.comment());
    }
    if (!tag.genre().isEmpty()) {
      properties.put("GENRE", tag.genre());
    }
    // Native ID3v1 setters pass unsigned arguments through String::number(int).
    if (tag.year() > 0) {
      properties.put("DATE", Integer.toString((int) tag.year()));
    }
    if (tag.track() > 0) {
      properties.put("TRACKNUMBER", Integer.toString((int) tag.track()));
    }
    return properties.build();
  }

  static Tag mergeTags(Tag... tags) {
    String title = "", artist = "", album = "", comment = "", genre = "";
    long year = 0, track = 0;
    for (Tag tag : tags) {
      if (title.isEmpty()) {
        title = tag.title();
      }
      if (artist.isEmpty()) {
        artist = tag.artist();
      }
      if (album.isEmpty()) {
        album = tag.album();
      }
      if (comment.isEmpty()) {
        comment = tag.comment();
      }
      if (genre.isEmpty()) {
        genre = tag.genre();
      }
      if (year == 0) {
        year = tag.year();
      }
      if (track == 0) {
        track = tag.track();
      }
    }
    return new Tag(title, artist, album, comment, genre, year, track);
  }

  public static Id3Location initialTag(BinaryFile file, ReadOptions options) throws IOException {
    long offset = 0;
    if (file.size() < 3) {
      return new Id3Location(0, 0);
    }
    if (!file.ascii(0, 3).equals("ID3")) {
      if (options.readStyle() == ReadStyle.FAST) {
        return new Id3Location(0, 0);
      }
      Located first = find(file, 0, file.size(), false);
      long end = first == null ? file.size() : first.offset();
      offset = -1;
      search:
      for (long start = 0; start + 3 <= end; start += 65536) {
        byte[] block = file.read(start, (int) Math.min(65545, end - start));
        for (int i = 0; i + 3 <= block.length; i++) {
          if (block[i] == 'I' && block[i + 1] == 'D' && block[i + 2] == '3') {
            offset = start + i;
            break search;
          }
        }
      }
      if (offset < 0) {
        return new Id3Location(0, 0);
      }
    }
    int size = Math.max(10, Id3v2.tagSize(file.read(offset, (int) Math.min(10, file.size() - offset))));
    if (size > options.maxMetadataBytes()) {
      throw new IOException("Oversized ID3 tag");
    }
    return new Id3Location(offset, (int) Math.min(size, file.size() - offset));
  }

  /// One property calculation for positional reads and native borrowed-stream cursor effects.
  private static Details properties(
    BinaryFile file, long begin, long end, ReadStyle style, boolean nativeProtocol)
    throws IOException {
    return properties(new PropertySource() {
      public Located first() throws IOException {
        return locate(file, begin, file.size(), false, nativeProtocol);
      }

      public Located next(long at) throws IOException {
        return locate(file, at, file.size(), false, nativeProtocol);
      }

      public Located last() throws IOException {
        return locate(file, 0, end == file.size() ? end : Math.max(0, end - 1), true, nativeProtocol);
      }

      public byte[] frame(Located at) throws IOException {
        return nativeProtocol ? NativeTagIO.read(file.stream(), at.offset(), at.header().length())
          : file.read(at.offset(), (int) Math.min(at.header().length(), file.size() - at.offset()));
      }
    }, style);
  }

  private interface PropertySource {
    Located first() throws IOException;

    Located next(long position) throws IOException;

    Located last() throws IOException;

    byte[] frame(Located at) throws IOException;
  }

  /// Shares the property arithmetic while retaining the actual MpegFile's cached offsets/read hooks.
  public static Details readProperties(MpegFile file, ReadStyle style) {
    try {
      return properties(new PropertySource() {
        private Located at(long offset) {
          if (offset < 0) {
            return null;
          }
          file.seek(offset);
          var first = file.readBlock(4);
          var bytes = MpegHeaderData.needsADTSLength(first.data())
            ? new ByteVector(first).append(file.readBlock(2)).data()
            : first.data();
          return new Located(offset, new Header(MpegHeaderData.parse(bytes, 0)));
        }

        public Located first() {
          return at(file.firstFrameOffset());
        }

        public Located next(long position) {
          return at(file.nextFrameOffset(position));
        }

        public Located last() {
          return at(file.lastFrameOffset());
        }

        public byte[] frame(Located at) {
          file.seek(at.offset());
          return file.readBlock(at.header().length()).toByteArray();
        }
      }, style);
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
  }

  private static Details properties(PropertySource source, ReadStyle style) throws IOException {
    Located first = source.first();
    if (first == null) {
      return Details.EMPTY;
    }
    Header header = first.header();
    long duration = 0;
    int bitrate = header.bitrate();
    byte[] frame = source.frame(first);
    var xing = new XingHeader(new ByteVector(frame));
    if (!xing.isValid()) {
      xing = null;
    }
    if (xing != null && header.samples() > 0 && header.sampleRate() > 0) {
      double length = header.samples() * 1000.0 / header.sampleRate() * xing.totalFrames();
      bitrate = 0;
      if (length > 0 && length < Integer.MAX_VALUE) {
        duration = (long) (length + 0.5);
        double rate = xing.totalSize() * 8.0 / length;
        if (rate >= 0 && rate < Integer.MAX_VALUE) {
          bitrate = (int) (rate + 0.5);
        }
      }
    } else {
      if (header.adts()) {
        if (style == ReadStyle.FAST) {
          bitrate = 0;
        } else {
          long offset = first.offset(), totalBytes = header.length(), count = 1;
          long lastAverage = 0, average = 0;
          int stable = 0;
          Header current = header;
          while (true) {
            Located next = source.next(Math.addExact(offset, current.length()));
            if (next == null || next.offset() <= offset) {
              break;
            }
            offset = next.offset();
            current = next.header();
            totalBytes = Math.addExact(totalBytes, current.length());
            count++;
            average = totalBytes / count;
            if (style != ReadStyle.ACCURATE) {
              stable = average == lastAverage ? stable + 1 : 0;
              if (stable >= 10) {
                break;
              }
              lastAverage = average;
            }
          }
          bitrate = header.samples() != 0
            ? (int) (Math.multiplyExact(Math.multiplyExact(average, 8), header.sampleRate())
            / 1000 / header.samples()) : 0;
        }
      }
      if (bitrate > 0) {
        // Native reverse discovery returns the following frame, whose unchecked header
        // may be invalid. Its partially decoded frame length still participates.
        Located last = source.last();
        if (last != null) {
          long streamLength = Math.addExact(last.offset() - first.offset(), last.header().length());
          double length = streamLength * 8.0 / bitrate;
          if (length > 0 && length < Integer.MAX_VALUE) {
            duration = (long) (length + 0.5);
          }
        }
      }
    }
    return new Details(new AudioProperties(duration, bitrate, header.sampleRate(),
      header.channels(), 0, false), header.data(), xing);
  }

  private static Located locate(
    BinaryFile file, long begin, long end, boolean reverse, boolean nativeProtocol)
    throws IOException {
    if (!nativeProtocol) {
      return find(file, begin, end, reverse);
    }
    long offset = nativeFrame(file, reverse ? end : begin, reverse);
    if (offset < 0) {
      return null;
    }
    Header parsed = nativeHeader(file, offset, false);
    return new Located(offset, parsed == null
      ? new Header(MpegHeaderData.parse(new byte[0], 0)) : parsed);
  }

  private static boolean matchingHeaders(byte[] first, int at, byte[] next, int nextAt) {
    return at + 4 <= first.length && nextAt + 4 <= next.length
      && (u32be(first, at) & 0xfffe0c00L) == (u32be(next, nextAt) & 0xfffe0c00L);
  }

  /// Chunked scanning bounds memory and avoids a system call per potential sync byte.
  private static Located find(BinaryFile file, long begin, long end, boolean backwards)
    throws IOException {
    final int block = 65536;
    if (end - begin < 4) {
      return null;
    }
    if (backwards) {
      for (long upper = end; upper > begin; ) {
        long lower = Math.max(begin, upper - block);
        byte[] data = file.read(lower, (int) Math.min(end - lower, upper - lower + 8));
        for (int i = (int) (upper - lower) - 1; i >= 0; i--) {
          Header header = header(data, i);
          if (header != null && header.length() > 0) {
            long next = lower + i + header.length();
            if (next + 4 > file.size()) {
              continue;
            }
            byte[] nextBytes = file.read(next, (int) Math.min(6, file.size() - next));
            if (matchingHeaders(data, i, nextBytes, 0)) {
              Header following = header(nextBytes, 0);
              // Header(false) at the returned last-frame position may be
              // invalid (for example a zero bitrate index), with length zero.
              return new Located(next, following == null
                ? new Header(MpegHeaderData.parse(new byte[0], 0)) : following);
            }
          }
        }
        upper = lower;
      }
    } else {
      for (long lower = begin; lower + 4 <= end; lower += block) {
        byte[] data = file.read(lower, (int) Math.min(end - lower, block + 8));
        for (int i = 0; i < Math.min(block, data.length - 3); i++) {
          Header header = header(data, i);
          if (header == null || header.length() == 0) {
            continue;
          }
          long next = lower + i + header.length();
          if (next + 4 > file.size()) {
            continue;
          }
          byte[] nextBytes = next - lower + 4 <= data.length ? data : file.read(next, 4);
          int nextAt = nextBytes == data ? (int) (next - lower) : 0;
          if (!matchingHeaders(data, i, nextBytes, nextAt)) {
            continue;
          }
          return new Located(lower + i, header);
        }
      }
    }
    return null;
  }

  private static Header header(byte[] bytes, int at) {
    if (at < 0 || at > bytes.length - 4 || !MpegHeaderData.isFrameSync(bytes, at)) {
      return null;
    }
    var parsed = MpegHeaderData.parse(bytes, at);
    if (!parsed.valid()) {
      return null;
    }
    return new Header(parsed);
  }
}
