// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import cz.skyba.taglib.ape.ApeFile;
import cz.skyba.taglib.dsdiff.DsdiffFile;
import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.Codec;
import cz.skyba.taglib.internal.WriteGuard;
import cz.skyba.taglib.internal.codec.*;
import cz.skyba.taglib.internal.xiph.OggPages;
import cz.skyba.taglib.mpc.MpcFile;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.riff.aiff.AiffFile;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.trueaudio.TrueAudioFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/// High-level API for reading and editing audio metadata.
///
/// Path operations open and close their own file handles. Stream operations borrow the
/// supplied stream and leave it open. Writes update storage in place and can leave partial
/// changes if an operation fails.
public final class TagLib {
  private TagLib() {
  }

  /// Reads an immutable metadata snapshot from a file.
  /// Opens and closes its own file handle. Uses [ReadOptions#DEFAULT].
  /// Audio properties that were not read are represented by [AudioProperties#EMPTY].
  ///
  /// @param path the audio file to read
  /// @return a snapshot independent of the file handle
  /// @throws UncheckedIOException          if storage access fails
  /// @throws InvalidAudioFileException     if the input is not a readable supported format
  /// @throws UnsupportedOperationException if a custom resolver supplies a format without snapshot support
  public static AudioMetadata read(Path path) {
    return read(path, ReadOptions.DEFAULT);
  }

  /// Reads an immutable metadata snapshot from a file.
  /// Opens and closes its own file handle.
  /// Audio properties that were not read are represented by [AudioProperties#EMPTY].
  ///
  /// @param path    the audio file to read
  /// @param options audio property selection and metadata allocation limit
  /// @return a snapshot independent of the file handle
  /// @throws UncheckedIOException          if storage access fails
  /// @throws InvalidAudioFileException     if the input is not a readable supported format
  /// @throws UnsupportedOperationException if a custom resolver supplies a format without snapshot support
  public static AudioMetadata read(Path path, ReadOptions options) {
    Objects.requireNonNull(path);
    Objects.requireNonNull(options);
    try (var ref = new AudioFile(path, options)) {
      return snapshot(ref, path.toString());
    }
  }

  /// Reads an immutable metadata snapshot from the beginning of a borrowed stream.
  /// Leaves the stream open; its cursor is not restored. Uses [ReadOptions#DEFAULT].
  /// Audio properties that were not read are represented by [AudioProperties#EMPTY].
  ///
  /// @param stream the open, seekable input stream
  /// @return a snapshot independent of the file handle
  /// @throws UncheckedIOException          if storage access fails
  /// @throws InvalidAudioFileException     if the input is not a readable supported format
  /// @throws UnsupportedOperationException if a custom resolver supplies a format without snapshot support
  /// @throws IllegalStateException         if the supplied stream is closed
  public static AudioMetadata read(IOStream stream) {
    return read(stream, ReadOptions.DEFAULT);
  }

  /// Reads an immutable metadata snapshot from the beginning of a borrowed stream.
  /// Leaves the stream open; its cursor is not restored.
  /// Audio properties that were not read are represented by [AudioProperties#EMPTY].
  ///
  /// @param stream  the open, seekable input stream
  /// @param options audio property selection and metadata allocation limit
  /// @return a snapshot independent of the file handle
  /// @throws UncheckedIOException          if storage access fails
  /// @throws InvalidAudioFileException     if the input is not a readable supported format
  /// @throws UnsupportedOperationException if a custom resolver supplies a format without snapshot support
  /// @throws IllegalStateException         if the supplied stream is closed
  public static AudioMetadata read(IOStream stream, ReadOptions options) {
    Objects.requireNonNull(stream);
    Objects.requireNonNull(options);
    stream.seek(0);
    try (var ref = new AudioFile(stream, options)) {
      return snapshot(ref, stream.name());
    }
  }

  private static AudioMetadata snapshot(AudioFile ref, String name) {
    Format format = AudioFile.builtinFormat(ref.file());
    if (format == null) {
      throw new UnsupportedOperationException("Snapshot reads are not supported for resolved file: " + name);
    }
    var audio = ref.audioProperties();
    var tag = ref.tag();
    return new AudioMetadata(
      format,
      ref.properties(),
      audio == null ? AudioProperties.EMPTY : audio,
      ref.pictures(),
      new Tag(Objects.requireNonNull(tag).title(), tag.artist(), tag.album(), tag.comment(), tag.genre(), tag.year(),
        tag.track()));
  }

  static FileBase openForFileRef(IOStream stream, ReadOptions options, boolean owned)
    throws IOException {
    Objects.requireNonNull(options);
    Codec selected = codecForName(stream.name());
    if (selected != null) {
      try (var file = new BinaryFile(stream)) {
        return typedFile(stream, selected, file, options, owned);
      } catch (InvalidAudioFileException failure) {
        // AudioFile retries content detection after an invalid extension-selected constructor.
        // Its isSupported probes restore the cursor; the failed constructor does not.
      }
    }
    long position = stream.tell();
    try (var file = new BinaryFile(stream)) {
      try {
        selected = sniffForFileRef(file, stream.name());
        // The pinned native content detector has no tracker probes. Java snapshot reads
        // retain their additional IT signature recognition separately.
        if (selected instanceof TrackerCodec) {
          throw new InvalidAudioFileException("No tracker content detector");
        }
      } finally {
        stream.seek(position);
      }
    }
    try (var file = new BinaryFile(stream)) {
      return typedFile(stream, selected, file, options, owned);
    }
  }

  private static FileBase typedFile(
    IOStream stream, Codec selected, BinaryFile file, ReadOptions options, boolean owned)
    throws IOException {
    Format specializedFormat = specializedFormat(selected, file);
    if (selected instanceof OggCodec ogg) {
      specializedFormat = ogg.fileRefFormat(file);
    }
    if (specializedFormat == null) {
      throw new InvalidAudioFileException("No typed file implementation for " + stream.name());
    }
    try {
      return AudioFile.specializedFile(stream, owned, options, specializedFormat);
    } catch (InvalidAudioFileException failure) {
      if (selected instanceof OggCodec ogg && ogg.fileRefTryVorbis()) {
        return AudioFile.specializedFile(stream, owned, options, Format.OGG_VORBIS);
      }
      throw failure;
    }
  }

  private static Format specializedFormat(Codec selected, BinaryFile file) throws IOException {
    return switch (selected) {
      case TrackerCodec tracker -> tracker.format();
      case ShortenCodec _ -> Format.SHORTEN;
      case ApeCodec ape -> ape.format();
      case DsdCodec dsd -> dsd.format();
      case MpegCodec _ -> Format.MPEG;
      case FlacCodec _ -> Format.FLAC;
      case RiffCodec _ -> RiffCodec.nativeAiff(file) ? Format.AIFF : Format.WAV;
      case Mp4Codec _ -> Format.MP4;
      case MatroskaCodec _ -> Format.MATROSKA;
      case AsfCodec _ -> Format.ASF;
      case TrueAudioCodec _ -> Format.TRUEAUDIO;
      case null, default -> null;
    };
  }

  private static Codec sniffForFileRef(BinaryFile file, String name) throws IOException {
    Codec selected = null;
    try {
      selected = sniff(file, name);
    } catch (IOException | InvalidAudioFileException unrecognized) { /* Try native APE support. */ }
    // Native FLAC detection skips a leading ID3v2 tag before searching its header window.
    // Preserve an Ogg container already selected by the unskipped prefix probes.
    if (!(selected instanceof OggCodec) && FlacFile.isSupported(file.stream())) {
      return new FlacCodec();
    }
    boolean beforeMpc = selected instanceof OggCodec || selected instanceof FlacCodec;
    if (!beforeMpc && MpcFile.isSupported(file.stream())) {
      return new ApeCodec(Format.MPC);
    }
    // Native APE detection precedes the later container probes, but follows Ogg,
    // FLAC, Musepack and WavPack. Keep those already selected predecessors.
    boolean earlier = selected instanceof OggCodec || selected instanceof FlacCodec
      || selected instanceof ApeCodec ape && ape.format() != Format.APE;
    if (!earlier && ApeFile.isSupported(file.stream())) {
      return new ApeCodec(Format.APE);
    }
    if (!earlier && TrueAudioFile.isSupported(file.stream())) {
      return new TrueAudioCodec();
    }
    if (selected instanceof RiffCodec && RiffCodec.nativeAiff(file)
      && !AiffFile.isSupported(file.stream())) {
      selected = null;
    }
    if (selected instanceof DsdCodec dsd && dsd.format() == Format.DSDIFF
      && !DsdiffFile.isSupported(file.stream())) {
      selected = null;
    }
    if (selected instanceof MpegCodec && !MpegFile.isSupported(file.stream())) {
      selected = null;
    }
    if (selected == null) {
      throw new InvalidAudioFileException("Unrecognized or unsupported audio container: " + name);
    }
    return selected;
  }

  /// Builds metadata edits without opening the audio file. Use `set` to replace
  /// text values and `clear` to remove a property. Saving merges the edits into the file's current
  /// properties and uses the replacement and default save behavior of [#write(Path, PropertyMap)].
  /// Changes are applied only by `save()`; `build()` creates an immutable request
  /// for later saving.
  ///
  /// ```java
  /// TagLib.edit(path)
  ///   .set("TITLE", "New title")
  ///   .set("ARTIST", "Artist A", "Artist B")
  ///   .clear("COMMENT")
  ///   .save();
  /// ```
  ///
  /// @param path the audio file to edit when saving
  /// @return a mutable builder for metadata edits
  /// @throws NullPointerException if the path is null
  /// @see MetadataEdit
  public static MetadataEdit.Builder edit(Path path) {
    return new MetadataEdit.Builder(path);
  }

  static void applyEdits(Path path, PropertyMap updates, List<PictureEdit> pictureEdits) {
    if (updates.isEmpty() && pictureEdits.isEmpty()) {
      return;
    }
    try {
      WriteGuard.run(path, () -> {
        try (var file = new AudioFile(path, ReadOptions.TAGS_ONLY)) {
          if (!updates.isEmpty()) {
            var properties = file.properties().toBuilder();
            updates.asMap().forEach((key, values) -> {
              if (values.isEmpty()) {
                properties.remove(key);
              } else {
                properties.put(key, values);
              }
            });
            var result = file.replaceProperties(properties.build());
            if (!result.rejected().isEmpty()) {
              throw new MetadataEditException("Unsupported metadata values for properties: "
                + result.rejected().asMap().keySet());
            }
          }
          if (!pictureEdits.isEmpty()) {
            var existing = file.pictures();
            var pictures = new ArrayList<>(existing);
            pictureEdits.forEach(edit -> edit.apply(pictures));
            requirePictureTypes(file, pictures);
            if (!pictures.equals(existing) || !file.supportsPictureEditing()) {
              file.replacePictures(pictures);
            }
          }
          file.save();
        }
      });
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot update audio metadata: " + path, failure);
    }
  }

  private static void requirePictureTypes(AudioFile file, List<Picture> pictures) {
    Format format = AudioFile.builtinFormat(file.file());
    for (var picture : pictures) {
      boolean supported = switch (format) {
        case MP4, MATROSKA -> picture.type() == PictureType.OTHER.code();
        case APE, MPC, WAVPACK -> picture.type() == PictureType.FRONT_COVER.code()
          || picture.type() == PictureType.BACK_COVER.code();
        case null, default -> true;
      };
      if (!supported) {
        throw new MetadataEditException("Picture role " + picture.type() + " is not supported by " + format);
      }
    }
  }

  /// Writes the complete text property map in place, preserving existing pictures,
  /// format-specific opaque metadata and audio. Omitted text keys are removed;
  /// supported values are saved even when other values are rejected.
  /// A failed write can leave partial changes.
  ///
  /// @param path       the audio file to update
  /// @param properties the complete replacement text property map
  /// @return values accepted for saving and rejected as unrepresentable;
  /// accepted values are not a read-back snapshot
  /// @throws UncheckedIOException if storage access fails
  /// @throws TagLibException      if opening or saving is rejected
  public static WriteResult write(Path path, PropertyMap properties) {
    Objects.requireNonNull(path);
    Objects.requireNonNull(properties);
    return writePath(path, properties, null);
  }

  /// Writes the complete text property map and embedded pictures in place, preserving
  /// format-specific opaque metadata and audio. Omitted text keys are removed;
  /// supported values are saved even when other values are rejected.
  /// Use [#write(Path, PropertyMap)] to preserve existing pictures.
  /// A failed write can leave partial changes.
  ///
  /// @param path       the audio file to update
  /// @param properties the complete replacement text property map
  /// @param pictures   the non-null replacement pictures; an empty list removes artwork
  /// @return text values accepted for saving and rejected as unrepresentable;
  /// accepted values are not a read-back snapshot
  /// @throws UncheckedIOException          if storage access fails
  /// @throws TagLibException               if opening or saving is rejected
  /// @throws UnsupportedOperationException if the format does not support writing pictures
  /// @throws NullPointerException          if an argument or a picture list element is null
  public static WriteResult write(Path path, PropertyMap properties, List<Picture> pictures) {
    Objects.requireNonNull(path);
    Objects.requireNonNull(properties);
    var copy = List.copyOf(pictures);
    return writePath(path, properties, copy);
  }

  /// Writes the complete text property map through a borrowed stream, preserving
  /// existing pictures, format-specific opaque metadata and audio. Omitted text keys
  /// are removed; supported values are saved even when other values are rejected.
  /// The stream remains open. A failed write can leave partial changes.
  ///
  /// @param stream     the caller-owned audio stream to update in place
  /// @param properties the complete replacement text property map
  /// @return values accepted for saving and rejected as unrepresentable;
  /// accepted values are not a read-back snapshot
  /// @throws UncheckedIOException if storage access fails
  /// @throws TagLibException      if opening or saving is rejected
  public static WriteResult write(IOStream stream, PropertyMap properties) {
    Objects.requireNonNull(stream);
    Objects.requireNonNull(properties);
    return writeStream(stream, properties, null);
  }

  /// Writes the complete text property map and embedded pictures through a borrowed
  /// stream, preserving format-specific opaque metadata and audio. Omitted text keys
  /// are removed; supported values are saved even when other values are rejected.
  /// Use [#write(IOStream, PropertyMap)] to preserve existing pictures.
  /// The stream remains open. A failed write can leave partial changes.
  ///
  /// @param stream     the caller-owned audio stream to update in place
  /// @param properties the complete replacement text property map
  /// @param pictures   the non-null replacement pictures; an empty list removes artwork
  /// @return text values accepted for saving and rejected as unrepresentable;
  /// accepted values are not a read-back snapshot
  /// @throws UncheckedIOException          if storage access fails
  /// @throws TagLibException               if opening or saving is rejected
  /// @throws UnsupportedOperationException if the format does not support writing pictures
  /// @throws NullPointerException          if an argument or a picture list element is null
  public static WriteResult write(
    IOStream stream, PropertyMap properties, List<Picture> pictures) {
    Objects.requireNonNull(stream);
    Objects.requireNonNull(properties);
    var copy = List.copyOf(pictures);
    return writeStream(stream, properties, copy);
  }

  private static WriteResult writeStream(
    IOStream stream, PropertyMap properties, List<Picture> pictures) {
    try (var ref = new AudioFile(stream, ReadOptions.TAGS_ONLY)) {
      return apply(ref, properties, pictures);
    }
  }

  private static WriteResult writePath(
    Path path, PropertyMap properties, List<Picture> pictures) {
    try {
      return WriteGuard.call(path, () -> apply(path, properties, pictures));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot write audio metadata: " + path, failure);
    }
  }

  private static WriteResult apply(
    Path path, PropertyMap properties, List<Picture> pictures) {
    try (var ref = new AudioFile(path, ReadOptions.TAGS_ONLY)) {
      return apply(ref, properties, pictures);
    }
  }

  private static WriteResult apply(
    AudioFile ref, PropertyMap properties, List<Picture> pictures) {
    var update = ref.replaceProperties(properties);
    if (pictures != null) {
      ref.replacePictures(pictures);
    }
    ref.save();
    return new WriteResult(update.accepted(), update.rejected());
  }

  private static Codec codecForName(String name) {
    int dot = name.lastIndexOf('.');
    String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    return switch (ext) {
      case "mp3", "mp2", "aac" -> new MpegCodec();
      case "flac" -> new FlacCodec();
      case "ogg" -> new OggCodec(Format.OGG_VORBIS);
      case "oga" -> new OggCodec(Format.OGG_FLAC, true);
      case "opus" -> new OggCodec(Format.OGG_OPUS);
      case "spx" -> new OggCodec(Format.OGG_SPEEX);
      case "m4a", "m4r", "m4b", "m4p", "mp4", "3g2", "m4v" -> new Mp4Codec();
      case "tta" -> new TrueAudioCodec();
      case "shn" -> new ShortenCodec();
      case "mod", "module", "nst", "wow" -> new TrackerCodec(Format.MOD);
      case "s3m" -> new TrackerCodec(Format.S3M);
      case "it" -> new TrackerCodec(Format.IT);
      case "xm" -> new TrackerCodec(Format.XM);
      case "wav", "aif", "aiff", "aifc", "afc" -> new RiffCodec();
      case "dsf" -> new DsdCodec(Format.DSF);
      case "dff", "dsdiff" -> new DsdCodec(Format.DSDIFF);
      case "wma", "asf" -> new AsfCodec();
      case "mka", "mkv", "webm" -> new MatroskaCodec();
      case "ape" -> new ApeCodec(Format.APE);
      case "wv" -> new ApeCodec(Format.WAVPACK);
      case "mpc" -> new ApeCodec(Format.MPC);
      default -> null;
    };
  }

  private static Codec sniff(BinaryFile f, String name) throws IOException {
    if (f.size() < 4) {
      throw new InvalidAudioFileException("Unrecognized audio file");
    }
    String magic = f.ascii(0, 4);
    byte[] prefix = f.read(0, (int) Math.min(1024, f.size()));
    String header = Binary.ascii(prefix, 0, prefix.length);
    if (header.contains("OggS")) {
      try {
        return new OggCodec(OggPages.detect(f));
      } catch (IOException ignored) {
      }
    }
    if (header.contains("fLaC")) {
      return new FlacCodec();
    }
    return switch (magic) {
      case "TTA1" -> new TrueAudioCodec();
      case "ajkg" -> new ShortenCodec();
      case "IMPM" -> new TrackerCodec(Format.IT);
      case "RIFF", "RF64", "BW64", "FORM" -> new RiffCodec();
      case "DSD " -> new DsdCodec(Format.DSF);
      case "FRM8" -> new DsdCodec(Format.DSDIFF);
      case "fLaC" -> new FlacCodec();
      case "OggS" -> new OggCodec();
      case "MAC " -> new ApeCodec(Format.APE);
      case "wvpk" -> new ApeCodec(Format.WAVPACK);
      case "MPCK" -> new ApeCodec(Format.MPC);
      default -> {
        if (f.u32be(0) == 0x1a45dfa3L) {
          yield new MatroskaCodec();
        }
        if (f.u32be(0) == 0x3026b275L) {
          yield new AsfCodec();
        }
        // MP4 media can contain coherent MPEG frames. Native content detection gives
        // the enclosing MP4 container priority so saves update its metadata and offsets.
        if (f.size() >= 8 && f.ascii(4, 4).equals("ftyp")) {
          yield new Mp4Codec();
        }
        if (MpegCodec.isSupported(f)) {
          yield new MpegCodec();
        }
        throw new InvalidAudioFileException("Unrecognized or unsupported audio container: " + name);
      }
    };
  }
}
