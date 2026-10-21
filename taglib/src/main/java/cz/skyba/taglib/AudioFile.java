// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import cz.skyba.taglib.ape.ApeFile;
import cz.skyba.taglib.asf.AsfFile;
import cz.skyba.taglib.dsdiff.DsdiffFile;
import cz.skyba.taglib.dsf.DsfFile;
import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.internal.ConstructionReadStream;
import cz.skyba.taglib.internal.FailureSupport;
import cz.skyba.taglib.internal.PictureTypes;
import cz.skyba.taglib.it.ItFile;
import cz.skyba.taglib.matroska.MatroskaFile;
import cz.skyba.taglib.mod.ModFile;
import cz.skyba.taglib.mp4.Mp4File;
import cz.skyba.taglib.mpc.MpcFile;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.ogg.flac.OggFlacFile;
import cz.skyba.taglib.ogg.opus.OpusFile;
import cz.skyba.taglib.ogg.speex.SpeexFile;
import cz.skyba.taglib.ogg.vorbis.VorbisFile;
import cz.skyba.taglib.riff.aiff.AiffFile;
import cz.skyba.taglib.riff.wav.WavFile;
import cz.skyba.taglib.s3m.S3mFile;
import cz.skyba.taglib.shorten.ShortenFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.toolkit.Variant;
import cz.skyba.taglib.trueaudio.TrueAudioFile;
import cz.skyba.taglib.wavpack.WavPackFile;
import cz.skyba.taglib.xm.XmFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Cleaner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// An editing session for audio metadata with format detection and live tags.
/// Metadata edits are staged until [#save()]. Closing never saves.
/// Path constructors own their file handles; supplied streams are borrowed and remain open
/// when the session closes. The session, its typed file and its live tags must be used by
/// one thread at a time.
// FileBase references are transferred to this owner or borrowed from its State, not locally closed.
@SuppressWarnings("resource")
public final class AudioFile implements AutoCloseable {
  /// A custom format resolver consulted before built-in format detection.
  /// Resolvers are tried in reverse registration order. Return `null` to allow the
  /// next resolver to inspect an input.
  @FunctionalInterface
  public interface FileTypeResolver {
    /// Opens a typed file when this resolver recognizes the input.
    /// A returned file is transferred to the editing session, which closes it when finished.
    ///
    /// @param name    the path to inspect
    /// @param options read options, including the metadata allocation limit
    /// @return a usable file, or `null` if this resolver does not handle the input
    /// @throws IOException if the input is handled but cannot be opened or read
    FileBase createFile(Path name, ReadOptions options) throws IOException;
  }

  /// A format resolver for path and borrowed-stream inputs.
  /// Resolved files follow the editing session's ownership and lifetime rules.
  public abstract static class StreamTypeResolver implements FileTypeResolver {
    /// Opens a typed file over a borrowed stream when this resolver recognizes the input.
    /// The returned file must leave the supplied stream open when closed.
    ///
    /// @param stream  the open, seekable stream to inspect
    /// @param options read options, including the metadata allocation limit
    /// @return a usable file, or `null` if this resolver does not handle the input
    /// @throws IOException if the input is handled but cannot be read
    public abstract FileBase createFileFromStream(
      IOStream stream, ReadOptions options) throws IOException;
  }

  private static final Cleaner CLEANER = Cleaner.create();
  private static final List<FileTypeResolver> RESOLVERS = new ArrayList<>();
  private static final List<String> EXTENSIONS = List.of(
    "mp3", "mp2", "aac", "ogg", "flac", "oga", "opus", "spx", "mpc", "wv", "ape",
    "tta", "m4a", "m4r", "m4b", "m4p", "3g2", "mp4", "m4v", "wma", "asf", "aif",
    "aiff", "afc", "aifc", "wav", "mod", "module", "nst", "wow", "s3m", "it", "xm",
    "dsf", "dff", "dsdiff", "shn", "mkv", "mka", "webm");

  // The cleaner holds only the resource, never the owner. Normal access needs no locks.
  private static final class State implements Runnable {
    private FileBase file;

    State(FileBase file) {
      this.file = file;
    }

    @Override
    public void run() {
      var current = file;
      file = null;
      if (current != null) {
        current.close();
      }
    }
  }

  private final State state;
  private final Cleaner.Cleanable cleanable;

  /// Creates a session that takes ownership of an already-open typed file.
  /// Ownership transfers only if the file is valid. Closing this session closes the supplied
  /// file; any stream borrowed by that file remains open.
  ///
  /// @param file the usable typed file to own
  /// @throws IllegalArgumentException if the file is closed or invalid
  public AudioFile(FileBase file) {
    Objects.requireNonNull(file);
    if (!file.isValid()) {
      throw new IllegalArgumentException("Invalid audio file: " + file.name());
    }
    state = new State(file);
    cleanable = CLEANER.register(this, state);
  }

  /// Opens an editing session for a file, taking ownership of its file handle.
  /// Uses [ReadOptions#DEFAULT].
  /// Registered resolvers are consulted before built-in format detection.
  ///
  /// @param path the audio file to open
  /// @throws UncheckedIOException      if storage access or a resolver fails
  /// @throws InvalidAudioFileException if no usable format can be opened
  public AudioFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /// Opens an editing session for a file, taking ownership of its file handle.
  /// Registered resolvers are consulted before built-in format detection.
  ///
  /// @param path    the audio file to open
  /// @param options audio property selection and metadata allocation limit
  /// @throws UncheckedIOException      if storage access or a resolver fails
  /// @throws InvalidAudioFileException if no usable format can be opened
  public AudioFile(Path path, ReadOptions options) {
    this(resolve(path, options));
  }

  /// Opens an editing session over a borrowed stream, leaving it open when the session closes.
  /// Uses [ReadOptions#DEFAULT].
  /// Registered resolvers are consulted before built-in format detection.
  ///
  /// @param stream the open, seekable audio stream
  /// @throws UncheckedIOException      if storage access or a resolver fails
  /// @throws InvalidAudioFileException if no usable format can be opened
  /// @throws IllegalStateException     if the stream is closed
  public AudioFile(IOStream stream) {
    this(stream, ReadOptions.DEFAULT);
  }

  /// Opens an editing session over a borrowed stream, leaving it open when the session closes.
  /// Registered resolvers are consulted before built-in format detection.
  ///
  /// @param stream  the open, seekable audio stream
  /// @param options audio property selection and metadata allocation limit
  /// @throws UncheckedIOException      if storage access or a resolver fails
  /// @throws InvalidAudioFileException if no usable format can be opened
  /// @throws IllegalStateException     if the stream is closed
  public AudioFile(IOStream stream, ReadOptions options) {
    this(resolve(stream, options));
  }

  /// Registers a resolver ahead of previously registered resolvers and built-in detection.
  /// The registry is shared by all sessions. Registration does not transfer ownership of the
  /// resolver, and removing a resolver does not close it or files it opened.
  ///
  /// @param resolver the resolver to register
  /// @param <T>      the resolver type
  /// @return the supplied resolver
  @SuppressWarnings("UnusedReturnValue") // Public convenience API returns the supplied resolver.
  public static synchronized <T extends FileTypeResolver> T addFileTypeResolver(T resolver) {
    RESOLVERS.addFirst(Objects.requireNonNull(resolver));
    return resolver;
  }

  /// Removes all registered custom resolvers from the shared registry.
  /// Existing sessions remain open and built-in format detection remains available.
  public static synchronized void clearFileTypeResolvers() {
    RESOLVERS.clear();
  }

  /// Returns the immutable list of built-in filename extensions, without leading dots.
  /// Custom resolvers can recognize additional names or formats independently of this list.
  ///
  /// @return recognized built-in extensions
  public static List<String> defaultFileExtensions() {
    return EXTENSIONS;
  }

  private static synchronized List<FileTypeResolver> resolvers() {
    return List.copyOf(RESOLVERS);
  }

  private static FileBase pathResolver(Path path, ReadOptions options) throws IOException {
    if (path.toString().isEmpty()) {
      return null;
    }
    for (var resolver : resolvers()) {
      var file = resolver.createFile(path, options);
      if (file != null) {
        return file;
      }
    }
    return null;
  }

  private static FileBase requireValid(FileBase file, String name) {
    if (file == null) {
      throw new InvalidAudioFileException("Unrecognized or unsupported audio container: " + name);
    }
    if (!file.isValid()) {
      var failure = new InvalidAudioFileException("Invalid audio file: " + name);
      FailureSupport.closeOnFailure(file, failure);
      throw failure;
    }
    return file;
  }

  private static FileBase resolve(Path path, ReadOptions options) {
    Objects.requireNonNull(path);
    Objects.requireNonNull(options);
    try {
      var custom = pathResolver(path, options);
      if (custom != null) {
        return requireValid(custom, path.toString());
      }
      var stream = new FileStream(path);
      try {
        return requireValid(TagLib.openForFileRef(stream, options, true), path.toString());
      } catch (IOException | RuntimeException | Error failure) {
        FailureSupport.closeOnFailure(stream, failure);
        throw failure;
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot open audio file: " + path, failure);
    }
  }

  private static FileBase resolve(IOStream stream, ReadOptions options) {
    Objects.requireNonNull(stream);
    Objects.requireNonNull(options);
    try {
      for (var resolver : resolvers()) {
        if (resolver instanceof StreamTypeResolver streamResolver) {
          var file = streamResolver.createFileFromStream(stream, options);
          if (file != null) {
            return requireValid(file, stream.name());
          }
        }
      }
      var custom = pathResolver(Path.of(stream.name()), options);
      return requireValid(custom != null ? custom
        : TagLib.openForFileRef(stream, options, false), stream.name());
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot open audio stream: " + stream.name(), failure);
    }
  }

  /// Built-in detection selects one typed file; stream constructors borrow and path constructors
  /// adopt the stream.
  static FileBase specializedFile(
    IOStream stream, boolean owned, ReadOptions options,
    Format format
  ) {
    return switch (format) {
      case OGG_VORBIS -> new VorbisFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case OGG_OPUS -> new OpusFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case OGG_SPEEX -> new SpeexFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case OGG_FLAC -> new OggFlacFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case FLAC -> new FlacFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case WAV -> new WavFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case AIFF -> new AiffFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case MATROSKA -> new MatroskaFile(ConstructionReadStream.wrapForFileRef(stream, owned), options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case MP4 -> new Mp4File(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case ASF -> new AsfFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case MPEG -> new MpegFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case TRUEAUDIO -> new TrueAudioFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case DSDIFF -> new DsdiffFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case DSF -> new DsfFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case MPC -> new MpcFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case WAVPACK -> new WavPackFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case APE -> new ApeFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case MOD -> new ModFile(ConstructionReadStream.wrapForFileRef(stream, owned), options.readStyle()) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case S3M -> new S3mFile(ConstructionReadStream.wrapForFileRef(stream, owned), options.readStyle()) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case IT -> new ItFile(ConstructionReadStream.wrapForFileRef(stream, owned), options.readStyle()) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case XM -> new XmFile(ConstructionReadStream.wrapForFileRef(stream, owned), options.readStyle()) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
      case SHORTEN -> new ShortenFile(stream, options) {
        @Override
        public void close() {
          super.close();
          if (owned) {
            stream.close();
          }
        }
      };
    };
  }

  /// Maps a built-in typed owner to the facade's closed format vocabulary. Custom resolvers are
  /// intentionally not assigned a format that they did not declare.
  static Format builtinFormat(FileBase file) {
    return switch (file) {
      case VorbisFile _ -> Format.OGG_VORBIS;
      case OpusFile _ -> Format.OGG_OPUS;
      case SpeexFile _ -> Format.OGG_SPEEX;
      case OggFlacFile _ -> Format.OGG_FLAC;
      case FlacFile _ -> Format.FLAC;
      case WavFile _ -> Format.WAV;
      case AiffFile _ -> Format.AIFF;
      case MatroskaFile _ -> Format.MATROSKA;
      case Mp4File _ -> Format.MP4;
      case AsfFile _ -> Format.ASF;
      case MpegFile _ -> Format.MPEG;
      case TrueAudioFile _ -> Format.TRUEAUDIO;
      case DsdiffFile _ -> Format.DSDIFF;
      case DsfFile _ -> Format.DSF;
      case MpcFile _ -> Format.MPC;
      case WavPackFile _ -> Format.WAVPACK;
      case ApeFile _ -> Format.APE;
      case ModFile _ -> Format.MOD;
      case S3mFile _ -> Format.S3M;
      case ItFile _ -> Format.IT;
      case XmFile _ -> Format.XM;
      case ShortenFile _ -> Format.SHORTEN;
      case null, default -> null;
    };
  }

  /// Returns the live typed file owned by this session. Its lifetime ends when the session closes.
  ///
  /// @return the typed file
  /// @throws IllegalStateException if the session is closed
  public FileBase file() {
    var file = state.file;
    if (file == null) {
      throw new IllegalStateException("Audio file is closed");
    }
    return file;
  }

  public boolean isOpen() {
    var file = state.file;
    return file != null && file.isOpen();
  }

  /// Returns the live mutable tag view. Edits through this view are staged until save().
  ///
  /// @return the mutable tag
  /// @throws IllegalStateException if the session is closed
  public TagBase tag() {
    return file().tag();
  }

  /// Returns the parsed audio properties, or `null` if unavailable or not requested.
  ///
  /// @return audio properties, or `null`
  /// @throws IllegalStateException if the session is closed
  public AudioProperties audioProperties() {
    return file().audioProperties();
  }

  /// Returns an immutable snapshot of the text properties exposed by the live tag.
  ///
  /// @return a text property snapshot
  /// @throws IllegalStateException if the session is closed
  public PropertyMap properties() {
    return file().properties();
  }

  /// Replaces the supported text properties in memory. Omitted keys are cleared; rejected values
  /// are reported without preventing other values from being accepted. This does not save.
  /// Unsupported-data identifiers do not request removal of opaque metadata; use
  /// [#removeUnsupportedProperties(List)] for that operation.
  public PropertyUpdateResult replaceProperties(PropertyMap properties) {
    return file().replaceProperties(properties);
  }

  /// Stages removal of opaque metadata identified by [PropertyMap#unsupportedData()].
  /// Unrecognized identifiers are handled by the selected format. Call [#save()] to persist removal.
  ///
  /// @param properties opaque metadata identifiers to remove
  /// @throws IllegalStateException if the session is closed
  public void removeUnsupportedProperties(List<String> properties) {
    file().removeUnsupportedProperties(properties);
  }

  private static final Variant EMPTY_VARIANT = new Variant();

  private static List<Picture> snapshotPictures(List<Map<String, Variant>> values) {
    return values.stream().map(value -> new Picture(
      PictureTypes.codeForName(value.getOrDefault("pictureType", EMPTY_VARIANT).toText()),
      value.getOrDefault("mimeType", EMPTY_VARIANT).toText(),
      value.getOrDefault("description", EMPTY_VARIANT).toText(),
      value.getOrDefault("data", EMPTY_VARIANT).toByteVector().data(),
      value.getOrDefault("width", EMPTY_VARIANT).toInt(),
      value.getOrDefault("height", EMPTY_VARIANT).toInt(),
      value.getOrDefault("colorDepth", EMPTY_VARIANT).toInt(),
      value.getOrDefault("numColors", EMPTY_VARIANT).toInt())).toList();
  }

  private static List<Map<String, Variant>> complexPictures(List<Picture> pictures) {
    return pictures.stream().map(picture -> Map.of(
      "pictureType", new Variant(PictureTypes.nameForCode(picture.type())),
      "mimeType", new Variant(picture.mimeType()),
      "description", new Variant(picture.description()),
      "data", new Variant(new ByteVector(picture.data())),
      "width", new Variant(picture.width()),
      "height", new Variant(picture.height()),
      "colorDepth", new Variant(picture.colorDepth()),
      "numColors", new Variant(picture.indexedColors()))).toList();
  }

  /// Returns immutable artwork snapshots. An empty list means no artwork is present.
  public List<Picture> pictures() {
    var values = file().complexProperties("PICTURE");
    return values.isEmpty() ? List.of() : snapshotPictures(values);
  }

  /// Reports artwork editing support independently of existing artwork or storage writability.
  public boolean supportsPictureEditing() {
    return file().supportsPictureEditing();
  }

  /// Stages replacement artwork; an empty list removes it. Call [#save()] to persist it.
  /// Format-specific limits and normalization are the same as for complex properties.
  ///
  /// @throws UnsupportedOperationException if the file does not accept artwork editing
  /// @throws NullPointerException          if the list or any picture is null
  /// @throws IllegalStateException         if this owner is closed
  public void replacePictures(List<Picture> pictures) {
    var file = file();
    if (!file.setComplexProperties("PICTURE", complexPictures(pictures))) {
      throw new UnsupportedOperationException(
        "Artwork editing is not implemented for " + file.getClass().getSimpleName());
    }
  }

  /// Returns the complex-property categories currently present, not editing capabilities.
  public List<String> complexPropertyKeys() {
    return file().complexPropertyKeys();
  }

  /// Returns structured values for a complex metadata category.
  /// An unsupported or absent category returns an empty list. The structure and fields are
  /// format-specific; use [#pictures()] for format-independent artwork snapshots.
  ///
  /// @param key the category name, such as `PICTURE`
  /// @return the values exposed for the category
  /// @throws IllegalStateException if the session is closed
  public List<Map<String, Variant>> complexProperties(String key) {
    return file().complexProperties(key);
  }

  /// Stages replacement of a complex metadata category using format-specific fields.
  /// An empty list requests removal. Call [#save()] to persist accepted changes.
  ///
  /// @param key    the category name
  /// @param values replacement structured values
  /// @return `true` if the format accepts the category; `false` if unsupported
  /// @throws IllegalStateException if the session is closed
  public boolean setComplexProperties(String key, List<Map<String, Variant>> values) {
    return file().setComplexProperties(key, values);
  }

  /// Saves staged edits in place. A failure may leave partial changes on the underlying storage.
  ///
  /// @throws UncheckedIOException  if storage access fails
  /// @throws TagLibException       if the edits cannot be saved
  /// @throws IllegalStateException if this owner is closed
  public void save() {
    file().save();
  }

  /// Closes this session and its owned typed file without saving staged changes.
  /// Borrowed streams remain open. Repeated calls have no effect.
  ///
  /// @throws UncheckedIOException if closing owned storage fails
  @Override
  public void close() {
    cleanable.clean();
  }
}
