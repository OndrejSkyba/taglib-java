// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.matroska;

import cz.skyba.taglib.toolkit.ByteVector;

import java.util.Arrays;
import java.util.Objects;

/// An immutable named text or binary tag with language and target scope. UID fields retain all 64 bits.
public final class SimpleTag {
  /// A Matroska target level that also represents unnamed numeric levels.
  ///
  /// @param value the target-level code
  public record TargetTypeValue(int value) {
    public static final TargetTypeValue None = new TargetTypeValue(0), Shot = new TargetTypeValue(10), Subtrack = new TargetTypeValue(
      20), Track = new TargetTypeValue(30), Part = new TargetTypeValue(40), Album = new TargetTypeValue(
      50), Edition = new TargetTypeValue(60), Collection = new TargetTypeValue(70);
  }

  /// The kind of payload stored in a SimpleTag.
  public enum ValueType {StringType, BinaryType}

  /// An immutable text or binary SimpleTag payload.
  public sealed interface Value permits Text, Binary {
  }

  /// An immutable text payload.
  ///
  /// @param value the non-null text
  public record Text(String value) implements Value {
    /// Creates a non-null text payload.
    ///
    /// @param value the non-null text payload
    /// @throws NullPointerException if the value is null
    public Text {
      Objects.requireNonNull(value);
    }
  }

  /// Binary tag payload with defensive array copies and content-based equality.
  ///
  /// @param value the binary payload, copied on construction
  public record Binary(byte[] value) implements Value {
    public Binary {
      value = Objects.requireNonNull(value).clone();
    }

    @Override
    public byte[] value() {
      return value.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Binary b && Arrays.equals(value, b.value);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(value);
    }
  }

  private record State(String name, Value value, int target, String language, boolean defaultLanguage, long track,
                       long edition,
                       long chapter, long attachment) {
  }

  private final State state;

  /// Creates a text or binary tag.
  ///
  /// @param name            the tag name
  /// @param value           the immutable text or binary payload
  /// @param target          the target level
  /// @param language        the language identifier
  /// @param defaultLanguage whether the language is the default
  /// @param track           the target track UID bit pattern, or zero for no track restriction
  /// @param edition         the target edition UID bit pattern, or zero for no edition restriction
  /// @param chapter         the target chapter UID bit pattern, or zero for no chapter restriction
  /// @param attachment      the target attachment UID bit pattern, or zero for no attachment restriction
  public SimpleTag(
    String name,
    Value value,
    int target,
    String language,
    boolean defaultLanguage,
    long track,
    long edition,
    long chapter,
    long attachment
  ) {
    state = new State(Objects.requireNonNull(name), Objects.requireNonNull(value), target, Objects.requireNonNull(language),
      defaultLanguage, track, edition, chapter, attachment);
  }

  /// Creates a text tag.
  ///
  /// @param name  the tag name
  /// @param value the text value
  public SimpleTag(String name, String value) {
    this(name, new Text(value), 0, "", true, 0, 0, 0, 0);
  }

  /// Creates a binary tag with copied data.
  ///
  /// @param name  the tag name
  /// @param value the binary data to copy
  public SimpleTag(String name, ByteVector value) {
    this(name, new Binary(value.data()), 0, "", true, 0, 0, 0, 0);
  }

  /// Creates a copy of the tag value.
  ///
  /// @param other the value to copy
  public SimpleTag(SimpleTag other) {
    state = Objects.requireNonNull(other).state;
  }

  public String name() {
    return state.name;
  }

  public Value value() {
    return state.value;
  }

  public int targetType() {
    return state.target;
  }

  public TargetTypeValue targetTypeValue() {
    return new TargetTypeValue(state.target);
  }

  public String language() {
    return state.language;
  }

  public boolean defaultLanguage() {
    return state.defaultLanguage;
  }

  public boolean defaultLanguageFlag() {
    return state.defaultLanguage;
  }

  public long trackUid() {
    return state.track;
  }

  public long editionUid() {
    return state.edition;
  }

  public long chapterUid() {
    return state.chapter;
  }

  public long attachmentUid() {
    return state.attachment;
  }

  public ValueType type() {
    return state.value instanceof Text ? ValueType.StringType : ValueType.BinaryType;
  }

  @Override
  public String toString() {
    return state.value instanceof Text(var value) ? value : "";
  }

  /// Returns a copied binary payload, or empty bytes for a text value.
  ///
  /// @return copied binary bytes
  public ByteVector toByteVector() {
    return state.value instanceof Binary v ? new ByteVector(v.value) : new ByteVector();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SimpleTag t && state.equals(t.state);
  }

  @Override
  public int hashCode() {
    return state.hashCode();
  }

  /// Creates a text tag.
  ///
  /// @param name   the tag name
  /// @param value  the text value
  /// @param target the target level
  public SimpleTag(String name, String value, TargetTypeValue target) {
    this(name, new Text(value), target.value(), "", true, 0, 0, 0, 0);
  }

  /// Creates a text tag.
  ///
  /// @param name     the tag name
  /// @param value    the text value
  /// @param target   the target level
  /// @param language the language identifier
  public SimpleTag(String name, String value, TargetTypeValue target, String language) {
    this(name, new Text(value), target.value(), language, true, 0, 0, 0, 0);
  }

  /// Creates a text tag.
  ///
  /// @param name            the tag name
  /// @param value           the text value
  /// @param target          the target level
  /// @param language        the language identifier
  /// @param defaultLanguage whether the language is the default
  public SimpleTag(String name, String value, TargetTypeValue target, String language, boolean defaultLanguage) {
    this(name, new Text(value), target.value(), language, defaultLanguage, 0, 0, 0, 0);
  }

  /// Creates a text tag.
  ///
  /// @param name            the tag name
  /// @param value           the text value
  /// @param target          the target level
  /// @param language        the language identifier
  /// @param defaultLanguage whether the language is the default
  /// @param track           the target track UID bit pattern, or zero for no track restriction
  public SimpleTag(String name, String value, TargetTypeValue target, String language, boolean defaultLanguage, long track) {
    this(name, new Text(value), target.value(), language, defaultLanguage, track, 0, 0, 0);
  }

  /// Creates a text tag.
  ///
  /// @param name            the tag name
  /// @param value           the text value
  /// @param target          the target level
  /// @param language        the language identifier
  /// @param defaultLanguage whether the language is the default
  /// @param track           the target track UID bit pattern, or zero for no track restriction
  /// @param edition         the target edition UID bit pattern, or zero for no edition restriction
  public SimpleTag(
    String name,
    String value,
    TargetTypeValue target,
    String language,
    boolean defaultLanguage,
    long track,
    long edition
  ) {
    this(name, new Text(value), target.value(), language, defaultLanguage, track, edition, 0, 0);
  }

  /// Creates a text tag.
  ///
  /// @param name            the tag name
  /// @param value           the text value
  /// @param target          the target level
  /// @param language        the language identifier
  /// @param defaultLanguage whether the language is the default
  /// @param track           the target track UID bit pattern, or zero for no track restriction
  /// @param edition         the target edition UID bit pattern, or zero for no edition restriction
  /// @param chapter         the target chapter UID bit pattern, or zero for no chapter restriction
  public SimpleTag(
    String name,
    String value,
    TargetTypeValue target,
    String language,
    boolean defaultLanguage,
    long track,
    long edition,
    long chapter
  ) {
    this(name, new Text(value), target.value(), language, defaultLanguage, track, edition, chapter, 0);
  }

  /// Creates a text tag.
  ///
  /// @param name            the tag name
  /// @param value           the text value
  /// @param target          the target level
  /// @param language        the language identifier
  /// @param defaultLanguage whether the language is the default
  /// @param track           the target track UID bit pattern, or zero for no track restriction
  /// @param edition         the target edition UID bit pattern, or zero for no edition restriction
  /// @param chapter         the target chapter UID bit pattern, or zero for no chapter restriction
  /// @param attachment      the target attachment UID bit pattern, or zero for no attachment restriction
  public SimpleTag(
    String name,
    String value,
    TargetTypeValue target,
    String language,
    boolean defaultLanguage,
    long track,
    long edition,
    long chapter,
    long attachment
  ) {
    this(name, new Text(value), target.value(), language, defaultLanguage, track, edition, chapter, attachment);
  }

  /// Creates a binary tag with copied data.
  ///
  /// @param name   the tag name
  /// @param value  the binary data to copy
  /// @param target the target level
  public SimpleTag(String name, ByteVector value, TargetTypeValue target) {
    this(name, new Binary(value.data()), target.value(), "", true, 0, 0, 0, 0);
  }

  /// Creates a binary tag with copied data.
  ///
  /// @param name     the tag name
  /// @param value    the binary data to copy
  /// @param target   the target level
  /// @param language the language identifier
  public SimpleTag(String name, ByteVector value, TargetTypeValue target, String language) {
    this(name, new Binary(value.data()), target.value(), language, true, 0, 0, 0, 0);
  }

  /// Creates a binary tag with copied data.
  ///
  /// @param name            the tag name
  /// @param value           the binary data to copy
  /// @param target          the target level
  /// @param language        the language identifier
  /// @param defaultLanguage whether the language is the default
  public SimpleTag(String name, ByteVector value, TargetTypeValue target, String language, boolean defaultLanguage) {
    this(name, new Binary(value.data()), target.value(), language, defaultLanguage, 0, 0, 0, 0);
  }

  /// Creates a binary tag with copied data.
  ///
  /// @param name            the tag name
  /// @param value           the binary data to copy
  /// @param target          the target level
  /// @param language        the language identifier
  /// @param defaultLanguage whether the language is the default
  /// @param track           the target track UID bit pattern, or zero for no track restriction
  public SimpleTag(String name, ByteVector value, TargetTypeValue target, String language, boolean defaultLanguage, long track) {
    this(name, new Binary(value.data()), target.value(), language, defaultLanguage, track, 0, 0, 0);
  }

  /// Creates a binary tag with copied data.
  ///
  /// @param name            the tag name
  /// @param value           the binary data to copy
  /// @param target          the target level
  /// @param language        the language identifier
  /// @param defaultLanguage whether the language is the default
  /// @param track           the target track UID bit pattern, or zero for no track restriction
  /// @param edition         the target edition UID bit pattern, or zero for no edition restriction
  public SimpleTag(
    String name,
    ByteVector value,
    TargetTypeValue target,
    String language,
    boolean defaultLanguage,
    long track,
    long edition
  ) {
    this(name, new Binary(value.data()), target.value(), language, defaultLanguage, track, edition, 0, 0);
  }

  /// Creates a binary tag with copied data.
  ///
  /// @param name            the tag name
  /// @param value           the binary data to copy
  /// @param target          the target level
  /// @param language        the language identifier
  /// @param defaultLanguage whether the language is the default
  /// @param track           the target track UID bit pattern, or zero for no track restriction
  /// @param edition         the target edition UID bit pattern, or zero for no edition restriction
  /// @param chapter         the target chapter UID bit pattern, or zero for no chapter restriction
  public SimpleTag(
    String name,
    ByteVector value,
    TargetTypeValue target,
    String language,
    boolean defaultLanguage,
    long track,
    long edition,
    long chapter
  ) {
    this(name, new Binary(value.data()), target.value(), language, defaultLanguage, track, edition, chapter, 0);
  }

  /// Creates a binary tag with copied data.
  ///
  /// @param name            the tag name
  /// @param value           the binary data to copy
  /// @param target          the target level
  /// @param language        the language identifier
  /// @param defaultLanguage whether the language is the default
  /// @param track           the target track UID bit pattern, or zero for no track restriction
  /// @param edition         the target edition UID bit pattern, or zero for no edition restriction
  /// @param chapter         the target chapter UID bit pattern, or zero for no chapter restriction
  /// @param attachment      the target attachment UID bit pattern, or zero for no attachment restriction
  public SimpleTag(
    String name,
    ByteVector value,
    TargetTypeValue target,
    String language,
    boolean defaultLanguage,
    long track,
    long edition,
    long chapter,
    long attachment
  ) {
    this(name, new Binary(value.data()), target.value(), language, defaultLanguage, track, edition, chapter, attachment);
  }
}
