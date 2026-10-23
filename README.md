# TagLib Java

TagLib Java is an independent Java adaptation of [TagLib](https://github.com/taglib/taglib), an
established C++ library for reading and editing metadata in popular audio formats.

- **Upstream project:** [TagLib](https://github.com/taglib/taglib)
- **Current Parity target:** 2.3.2

## Supported Formats

TagLib Java supports various metadata containers such
as [ID3v1][], [ID3v2][] and [Vorbis][] comments for MP3, MP4, AAC,
[Ogg][], [Opus][], [FLAC][], [Speex][], [APE][], [MPC][], [WavPack][],
[WAV][], [AIFF][], [TrueAudio][], [Matroska][], [WebM][], ASF, WMA, DSF, DFF and
tracker (MOD, XM, S3M, IT) files.

## Use

The library requires Java 25 at runtime.

```xml
<dependency>
  <groupId>cz.skyba</groupId>
  <artifactId>taglib</artifactId>
  <version>1.1.0</version>
</dependency>
```

### Examples

Read tags and audio properties:

```java
var metadata = TagLib.read(Path.of("song.flac"));
IO.println(metadata.tag().title());
IO.println(metadata.properties().get(PropertyKey.ARTIST));
IO.println(metadata.audioProperties().lengthInSeconds());
```

Update selected fields, preserving other properties:

```java
TagLib.edit(Path.of("song.flac"))
    .set(PropertyKey.TITLE, "New title")
    .set(PropertyKey.ARTIST, "Artist A", "Artist B")
    .remove(PropertyKey.COMMENT)
    .save();
```

Set a front cover and remove back covers, preserving other artwork:

```java
TagLib.edit(Path.of("song.flac"))
    .setPicture(PictureType.FRONT_COVER, Path.of("cover.jpg"))
    .removePictures(PictureType.BACK_COVER)
    .save();
```

## Benchmark

Benchmark usage, methodology and current results are in [BENCHMARK.md](BENCHMARK.md).

## License

TagLib Java is distributed under the [GNU Lesser General Public License][]
(LGPL 2.1) or [Mozilla Public License 2.0][].

This is an independent Java adaptation, not an official TagLib release or a
one-to-one translation. The original TagLib project and its
[contributors](https://github.com/taglib/taglib/blob/master/AUTHORS) are credited
for the algorithms, format behavior and field mappings that informed this work.

[ID3v1]: https://id3.org/ID3v1
[ID3v2]: https://id3.org/Home
[Vorbis]: https://xiph.org/vorbis/
[Ogg]: https://www.xiph.org/ogg/
[Opus]: https://opus-codec.org/
[FLAC]: https://xiph.org/flac/
[Speex]: https://www.speex.org/
[APE]: https://www.monkeysaudio.com/
[MPC]: https://musepack.net/
[WavPack]: https://www.wavpack.com/
[WAV]: https://www.mmsp.ece.mcgill.ca/Documents/AudioFormats/WAVE/WAVE.html
[AIFF]: https://www.mmsp.ece.mcgill.ca/Documents/AudioFormats/AIFF/AIFF.html
[TrueAudio]: https://sourceforge.net/projects/tta/
[Matroska]: https://www.matroska.org/
[WebM]: https://www.webmproject.org/
[GNU Lesser General Public License]: https://www.gnu.org/licenses/lgpl.html
[Mozilla Public License 2.0]: https://www.mozilla.org/MPL/2.0/
