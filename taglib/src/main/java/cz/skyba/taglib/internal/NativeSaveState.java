// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.asf.Attribute;
import cz.skyba.taglib.internal.id3.Id3v2;
import cz.skyba.taglib.matroska.AttachedFile;
import cz.skyba.taglib.matroska.ChapterEdition;
import cz.skyba.taglib.matroska.SimpleTag;
import cz.skyba.taglib.riff.info.Tag;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/// Constructor-owned state that cannot be reconstructed by rereading the rewritten bytes.
public final class NativeSaveState {
  private static final ScopedValue<Boolean> EMPTY_MATROSKA_TAG = ScopedValue.newInstance();
  private static final ScopedValue<PropertyMap> MPEG_APE_PROPERTIES = ScopedValue.newInstance();
  private static final ScopedValue<Tag> RIFF_INFO_TAG = ScopedValue.newInstance();
  private static final ScopedValue<List<AttachedFile>> MATROSKA_PICTURES = ScopedValue.newInstance();
  private static final ScopedValue<PropertyMap> FLAC_ID3V2_PROPERTIES = ScopedValue.newInstance();
  private static final ScopedValue<PropertyMap> FLAC_ID3V1_PROPERTIES = ScopedValue.newInstance();
  private static final ScopedValue<List<SimpleTag>> MATROSKA_TAGS = ScopedValue.newInstance();
  private static final ScopedValue<Boolean> MP4_SET_PROPERTIES = ScopedValue.newInstance();
  private static final ScopedValue<Boolean> MP4_PICTURE_SET = ScopedValue.newInstance();
  private static final ScopedValue<PropertyMap> DSDIFF_DIIN = ScopedValue.newInstance();
  private static final ScopedValue<List<Attribute>> ASF_ATTRIBUTES = ScopedValue.newInstance();
  private static final ScopedValue<Set<String>> ID3_COMMON_FIELDS = ScopedValue.newInstance();
  private static final ScopedValue<PropertyMap> ID3V1_PROPERTIES = ScopedValue.newInstance();
  private static final ScopedValue<Id3v2.Parsed> ID3V2_FRAMES = ScopedValue.newInstance();
  private static final ScopedValue<List<ChapterEdition>> MATROSKA_CHAPTERS = ScopedValue.newInstance();
  private static final ScopedValue<List<AttachedFile>> MATROSKA_ATTACHMENTS = ScopedValue.newInstance();
  private static final ScopedValue<Mp4Stem> MP4_STEM = ScopedValue.newInstance();
  private static final ScopedValue<Id3v1Fields> ID3V1_FIELDS = ScopedValue.newInstance();

  public record Mp4Stem(boolean present, byte[] manifest) {
    public Mp4Stem {
      manifest = manifest.clone();
    }

    @Override
    public byte[] manifest() {
      return manifest.clone();
    }
  }

  public record Id3v1Fields(String yearText, int genreNumber) {
  }

  private NativeSaveState() {
  }

  public static boolean nativeSession() {
    return EMPTY_MATROSKA_TAG.isBound();
  }

  public static boolean retainedEmptyMatroskaTag() {
    return EMPTY_MATROSKA_TAG.orElse(false);
  }

  public static PropertyMap mpegApeProperties() {
    return nullableValue(MPEG_APE_PROPERTIES);
  }

  public static Tag riffInfoTag() {
    return nullableValue(RIFF_INFO_TAG);
  }

  public static List<AttachedFile> matroskaPictures() {
    return nullableValue(MATROSKA_PICTURES);
  }

  public static PropertyMap flacId3v2Properties() {
    return nullableValue(FLAC_ID3V2_PROPERTIES);
  }

  public static PropertyMap flacId3v1Properties() {
    return nullableValue(FLAC_ID3V1_PROPERTIES);
  }

  public static List<SimpleTag> matroskaSimpleTags() {
    return nullableValue(MATROSKA_TAGS);
  }

  public static boolean mp4SetPropertiesCalled() {
    return MP4_SET_PROPERTIES.orElse(true);
  }

  public static boolean mp4PictureSetCalled() {
    return MP4_PICTURE_SET.orElse(true);
  }

  public static PropertyMap dsdiffDiinProperties() {
    return nullableValue(DSDIFF_DIIN);
  }

  public static List<Attribute> asfAttributes() {
    return nullableValue(ASF_ATTRIBUTES);
  }

  public static Set<String> id3CommonFields() {
    return ID3_COMMON_FIELDS.orElse(Set.of());
  }

  public static PropertyMap id3v1Properties() {
    return nullableValue(ID3V1_PROPERTIES);
  }

  public static Id3v2.Parsed id3v2Frames() {
    return nullableValue(ID3V2_FRAMES);
  }

  public static List<ChapterEdition> matroskaChapters() {
    return nullableValue(MATROSKA_CHAPTERS);
  }

  public static List<AttachedFile> matroskaAttachments() {
    return nullableValue(MATROSKA_ATTACHMENTS);
  }

  public static Mp4Stem mp4Stem() {
    return nullableValue(MP4_STEM);
  }

  public static Id3v1Fields id3v1Fields() {
    return nullableValue(ID3V1_FIELDS);
  }

  public static void runMatroska(
    List<ChapterEdition> chapters, List<AttachedFile> attachments, WriteGuard.Operation action
  ) throws IOException {
    withOptionalState(MATROSKA_CHAPTERS, snapshot(chapters), () ->
      withOptionalState(MATROSKA_ATTACHMENTS, snapshot(attachments), action));
  }

  public static void runMp4Stem(Mp4Stem stem, WriteGuard.Operation action) throws IOException {
    withOptionalState(MP4_STEM, stem, action);
  }

  public static void runId3v1(Id3v1Fields fields, WriteGuard.Operation action) throws IOException {
    withOptionalState(ID3V1_FIELDS, fields, action);
  }

  public static <T> T callId3v1(
    Id3v1Fields fields, WriteGuard.IOOperation<T> action
  ) throws IOException {
    return callWithOptionalState(ID3V1_FIELDS, fields, action);
  }

  public static void runId3(Id3v2.Parsed frames, WriteGuard.Operation action) throws IOException {
    withOptionalState(ID3V2_FRAMES, frames, action);
  }

  public static void runTagState(
    List<Attribute> asfAttributes, Set<String> commonFields,
    PropertyMap id3v1Properties, WriteGuard.Operation action
  ) throws IOException {
    withOptionalState(ASF_ATTRIBUTES, snapshot(asfAttributes), () ->
      withOptionalState(ID3V1_PROPERTIES, id3v1Properties, () ->
        withState(ID3_COMMON_FIELDS, Set.copyOf(commonFields), action)));
  }

  public static void runDsdiff(PropertyMap diin, WriteGuard.Operation action) throws IOException {
    withOptionalState(DSDIFF_DIIN, diin, action);
  }

  public static void runMp4(boolean setPropertiesCalled, WriteGuard.Operation action) throws IOException {
    withState(MP4_SET_PROPERTIES, setPropertiesCalled, action);
  }

  public static void runMp4(
    boolean setPropertiesCalled, boolean pictureSetCalled, WriteGuard.Operation action
  ) throws IOException {
    invoke(ScopedValue.where(MP4_SET_PROPERTIES, setPropertiesCalled)
      .where(MP4_PICTURE_SET, pictureSetCalled), action);
  }

  public static void run(List<SimpleTag> matroskaTags, WriteGuard.Operation action) throws IOException {
    withOptionalState(MATROSKA_TAGS, snapshot(matroskaTags), action);
  }

  public static void run(
    PropertyMap flacId3v2Properties, PropertyMap flacId3v1Properties, WriteGuard.Operation action
  ) throws IOException {
    withOptionalState(FLAC_ID3V2_PROPERTIES, flacId3v2Properties, () ->
      withOptionalState(FLAC_ID3V1_PROPERTIES, flacId3v1Properties, action));
  }

  public static void run(boolean retainedEmptyMatroskaTag, WriteGuard.Operation action) throws IOException {
    run(retainedEmptyMatroskaTag, null, action);
  }

  public static void run(
    boolean retainedEmptyMatroskaTag, PropertyMap mpegApeProperties,
    Tag riffInfoTag, List<AttachedFile> matroskaPictures, WriteGuard.Operation action
  ) throws IOException {
    withOptionalState(MATROSKA_PICTURES, snapshot(matroskaPictures), () ->
      run(retainedEmptyMatroskaTag, mpegApeProperties, riffInfoTag, action));
  }

  public static void run(
    boolean retainedEmptyMatroskaTag, PropertyMap mpegApeProperties,
    Tag riffInfoTag, WriteGuard.Operation action
  ) throws IOException {
    withOptionalState(RIFF_INFO_TAG, riffInfoTag, () ->
      run(retainedEmptyMatroskaTag, mpegApeProperties, action));
  }

  public static void run(
    boolean retainedEmptyMatroskaTag, PropertyMap mpegApeProperties, WriteGuard.Operation action
  ) throws IOException {
    withOptionalState(MPEG_APE_PROPERTIES, mpegApeProperties, () ->
      withState(EMPTY_MATROSKA_TAG, retainedEmptyMatroskaTag, action));
  }

  private static <T> T nullableValue(ScopedValue<T> key) {
    // ScopedValue.orElse requires a nonnull fallback, even when a binding exists.
    return key.isBound() ? key.get() : null;
  }

  private static <T> List<T> snapshot(List<T> values) {
    return values == null ? null : List.copyOf(values);
  }

  /// A missing override inherits the enclosing scope; it must not bind a null or empty value.
  /// Composite operations nest these calls so collection snapshots retain their evaluation order.
  private static <T> void withOptionalState(
    ScopedValue<T> key, T value, WriteGuard.Operation action
  ) throws IOException {
    if (value == null) {
      action.run();
    } else {
      withState(key, value, action);
    }
  }

  private static <K, T> T callWithOptionalState(
    ScopedValue<K> key, K value, WriteGuard.IOOperation<T> action
  ) throws IOException {
    return value == null ? action.run() : ScopedValue.where(key, value).call(action::run);
  }

  private static <T> void withState(
    ScopedValue<T> key, T value, WriteGuard.Operation action
  ) throws IOException {
    invoke(ScopedValue.where(key, value), action);
  }

  private static void invoke(ScopedValue.Carrier binding, WriteGuard.Operation action) throws IOException {
    binding.call(() -> {
      action.run();
      return null;
    });
  }
}
