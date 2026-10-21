// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.internal.id3.Id3v1;
import cz.skyba.taglib.internal.id3.Id3v2;
import cz.skyba.taglib.toolkit.ByteVector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static cz.skyba.taglib.internal.Binary.*;

/// TrueAudio 1 lossless stream headers and ID3 metadata.
public final class TrueAudioCodec implements Codec {
  private int nativeId3Size = -1;
  private long nativeId3OriginalSize;

  public record Details(AudioProperties common, int version, long sampleFrames) {
  }

  private Details nativeDetails = new Details(AudioProperties.EMPTY, 0, 0);

  /// Native ByteVector constructor decoding, shared with the complete file reader.
  public static Details properties(byte[] header, long streamLength) {
    if (header.length < 4 || !ascii(header, 0, 3).equals("TTA")) {
      return new Details(AudioProperties.EMPTY, 0, 0);
    }
    int version = header[3] - '0'; // Native char is signed on the pinned Linux target.
    if (version != 1 || header.length < 18) {
      return new Details(AudioProperties.EMPTY, version, 0);
    }
    int channels = (short) u16le(header, 6), bits = (short) u16le(header, 8);
    int rate = (int) u32le(header, 10);
    long frames = u32le(header, 14), duration = 0;
    int bitrate = 0;
    if (frames > 0 && rate > 0) {
      double length = frames * 1000.0 / rate;
      if (length > 0 && length + 0.5 <= Integer.MAX_VALUE) {
        duration = (long) (length + 0.5);
        double rateBits = streamLength * 8.0 / length;
        if (rateBits >= 0 && rateBits + 0.5 <= Integer.MAX_VALUE) {
          bitrate = (int) (rateBits + 0.5);
        }
      }
    }
    return new Details(new AudioProperties(duration, bitrate, rate, channels, bits, true), version, frames);
  }

  /// Constructs the native File/property snapshot, including tag-span and cursor effects.
  public static Details readProperties(BinaryFile file, ReadStyle style) throws IOException {
    var codec = new TrueAudioCodec();
    codec.readNative(file, new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes()));
    return codec.nativeDetails;
  }

  public static int initialTagSize(BinaryFile file) throws IOException {
    return file.size() >= 3 && file.ascii(0, 3).equals("ID3")
      ? Id3v2.tagSize(file.read(0, (int) Math.min(10, file.size()))) : 0;
  }

  @Override
  public AudioMetadata read(Path path, ReadOptions options) throws IOException {
    try (var f = new BinaryFile(path)) {
      return read(f, options);
    }
  }

  @Override
  public AudioMetadata read(BinaryFile f, ReadOptions options) throws IOException {
    int tag = initialTagSize(f);
    long originalSpan = tag == 0 ? 0 : NativeTagIO.id3v2(f, 0).length();
    if (tag > options.maxMetadataBytes()) {
      throw new IOException("Oversized ID3 tag");
    }
    var id3 = Id3v2.read(f.read(0, (int) Math.min(tag, f.size())), options);
    boolean v1 = f.size() >= 128 && f.ascii(f.size() - 128, 3).equals("TAG");
    PropertyMap legacy = v1 ? Id3v1.read(f.read(f.size() - 128, 128)) : PropertyMap.EMPTY;
    PropertyMap properties = !id3.empty() ? id3.properties() : legacy;
    AudioProperties audio = AudioProperties.EMPTY;
    if (options.readAudioProperties()) {
      // Properties::read leaves its fields at zero for unsupported versions,
      // short data and foreign signatures; these do not invalidate the File.
      byte[] h = originalSpan > f.size() ? new byte[0] : f.read(originalSpan, (int) Math.min(18, f.size() - originalSpan));
      nativeDetails = properties(h, f.size() - originalSpan - (v1 ? 128 : 0));
      audio = nativeDetails.common();
    }
    return new AudioMetadata(
      Format.TRUEAUDIO,
      properties,
      audio,
      id3.pictures(),
      MpegCodec.mergeTags(id3.basic(), new Tag(legacy)));
  }

  @Override
  public void write(Path path, PropertyMap properties) throws IOException {
    write(path, properties, null);
  }

  @Override
  public AudioMetadata readNative(BinaryFile f, ReadOptions options) throws IOException {
    AudioMetadata result = read(f, options);
    if (f.stream() != null) {
      int tag = initialTagSize(f);
      nativeId3Size = tag;
      nativeId3OriginalSize = tag == 0 ? 0 : NativeTagIO.id3v2(f.stream(), 0).length();
      NativeTagIO.id3v1(f.stream());
      if (options.readAudioProperties()) {
        NativeTagIO.read(f.stream(), nativeId3OriginalSize, 18);
      }
    }
    return result;
  }

  @Override
  public void writeNative(
    BinaryFile f,
    long constructorOffset,
    PropertyMap properties,
    List<Picture> pictures
  )
    throws IOException {
    if (f.stream() == null) {
      write(f, properties, pictures);
      return;
    }
    var stream = f.stream();
    long cursor = stream.tell();
    int tag = nativeId3Size >= 0 ? nativeId3Size : initialTagSize(f);
    long originalSpan = nativeId3Size >= 0 ? nativeId3OriginalSize : tag == 0 ? 0 : NativeTagIO.id3v2(f, 0).length();
    byte[] modern = Id3v2.renderNative(properties, f.read(0, (int) Math.min(tag, f.size())), pictures, f.size(), originalSpan);
    boolean hasV1 = f.size() >= 128 && f.ascii(f.size() - 128, 3).equals("TAG");
    PropertyMap legacyPlan = NativeSaveState.id3v1Properties();
    boolean retainedV1 = hasV1 || legacyPlan != null;
    if (legacyPlan == null) {
      legacyPlan = Id3v1.nativeProperties(properties);
    }
    byte[] legacy = retainedV1 && !legacyPlan.isEmpty() ? Id3v1.render(legacyPlan) : new byte[0];
    // The native save tests the in-memory tag before its LATIN1 renderer. A
    // nonempty Unicode field can produce an empty byte field without deleting
    // the physical footer; this state is lost by reparsing rendered bytes.
    if (hasV1 && legacyPlan.isEmpty()) {
      legacy = new byte[0];
    }
    stream.seek(cursor);
    if (modern.length != 0) {
      stream.insert(new ByteVector(modern), 0, originalSpan);
    } else if (originalSpan != 0) {
      stream.removeBlock(0, originalSpan);
    }
    nativeId3Size = modern.length;
    nativeId3OriginalSize = modern.length;
    long v1 = hasV1 ? f.size() - 128 + modern.length - originalSpan : stream.length();
    if (legacy.length != 0) {
      NativeTagIO.write(stream, v1, legacy);
    } else if (hasV1) {
      stream.truncate(v1);
    }
  }

  @Override
  public void write(
    BinaryFile f, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    var edits = new ArrayList<Rewrite.Edit>();

    int tag = initialTagSize(f);
    long originalSpan = tag == 0 ? 0 : NativeTagIO.id3v2(f, 0).length();
    if (tag > ReadOptions.DEFAULT.maxMetadataBytes()) {
      throw new IOException("Oversized ID3 tag");
    }
    edits.add(
      new Rewrite.Edit(
        0, Math.min(originalSpan, f.size()),
        Id3v2.renderNative(properties, f.read(0, (int) Math.min(tag, f.size())), pictures, f.size(), originalSpan)));
    if (f.size() >= 128 && f.ascii(f.size() - 128, 3).equals("TAG")) {
      PropertyMap legacyPlan = NativeSaveState.id3v1Properties();
      if (legacyPlan == null) {
        legacyPlan = Id3v1.nativeProperties(properties);
      }
      edits.add(
        new Rewrite.Edit(
          f.size() - 128, 128, legacyPlan.isEmpty() ? new byte[0] : Id3v1.render(legacyPlan)));
    }

    Rewrite.replace(f, edits);
  }
}
