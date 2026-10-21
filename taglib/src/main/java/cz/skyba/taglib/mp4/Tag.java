// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.internal.editor.Mp4Structured;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.toolkit.Variant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/// A mutable collection of MP4 metadata items with optional bound storage.
/// Item getters expose live values; their atom-type edits remain in memory until saved. A bound tag's
/// [#save()] and [#strip()] write its metadata atoms immediately. A tag constructed
/// without bound storage can be inspected and edited but cannot be saved directly.
public class Tag extends TagBase {
  private static final Variant EMPTY_VARIANT = new Variant();

  private final TreeMap<String, Item> items = new TreeMap<>();
  private final IOStream file;
  private final Mp4File owner;
  private final Atoms atoms;
  private final ItemFactory factory;
  private final List<Chapter> snapshotChapters;
  private final boolean snapshotValue;

  /// Creates an empty tag without bound storage.
  public Tag() {
    file = null;
    owner = null;
    atoms = null;
    factory = ItemFactory.instance();
    snapshotChapters = List.of();
    snapshotValue = false;
  }

  /// Reads MP4 items using a parsed atom tree and borrows the supplied storage.
  /// The input remains open. Null factories select the shared default factory.
  ///
  /// @param file  the MP4 file whose metadata budget and write hooks to use
  /// @param atoms the parsed atom tree for the same source
  public Tag(Mp4File file, Atoms atoms) {
    this(file, atoms, null);
  }

  /// Reads MP4 items using a parsed atom tree and borrows the supplied storage.
  /// The input remains open. Null factories select the shared default factory.
  ///
  /// @param file    the MP4 file whose metadata budget and write hooks to use
  /// @param atoms   the parsed atom tree for the same source
  /// @param factory the item decoder factory, or null for the default
  public Tag(Mp4File file, Atoms atoms, ItemFactory factory) {
    this(file.metadataStream(), atoms, factory, file);
  }

  /// Reads MP4 items using a parsed atom tree and borrows the supplied storage.
  /// The input remains open. Null factories select the shared default factory.
  ///
  /// @param stream the open borrowed stream
  /// @param atoms  the parsed atom tree for the same source
  public Tag(IOStream stream, Atoms atoms) {
    this(stream, atoms, null);
  }

  /// Reads MP4 items using a parsed atom tree and borrows the supplied storage.
  /// The input remains open. Null factories select the shared default factory.
  ///
  /// @param stream  the open borrowed stream
  /// @param atoms   the parsed atom tree for the same source
  /// @param factory the item decoder factory, or null for the default
  public Tag(IOStream stream, Atoms atoms, ItemFactory factory) {
    this(stream, atoms, factory, null);
  }

  private Tag(IOStream file, Atoms atoms, ItemFactory factory, Mp4File owner) {
    this.file = Objects.requireNonNull(file);
    this.owner = owner;
    this.atoms = Objects.requireNonNull(atoms);
    this.factory = factory != null ? factory : ItemFactory.instance();
    snapshotChapters = List.of();
    snapshotValue = false;
    long retainedBytes = 0;
    Atom ilst = atoms.find("moov", "udta", "meta", "ilst");
    if (ilst != null) {
      for (Atom atom : ilst.children()) {
        retainedBytes = Math.addExact(retainedBytes, Math.max(0, atom.length() - 8));
        if (owner != null) {
          owner.checkMetadataBytes(retainedBytes);
        }
        parse(atom);
      }
    }
    Atom stem = atoms.find("moov", "udta", "stem");
    if (stem != null) {
      retainedBytes = Math.addExact(retainedBytes, Math.max(0, stem.length() - 8));
      if (owner != null) {
        owner.checkMetadataBytes(retainedBytes);
      }
      parse(stem);
    }
  }

  /// Creates an unbound tag snapshot with copied chapters and no Stem metadata.
  ///
  /// @param chapters chapter snapshots to copy
  /// @throws NullPointerException if the chapter list or a chapter is null
  public Tag(List<Chapter> chapters) {
    this(chapters, null);
  }

  /// Creates an unbound tag snapshot with copied chapters and optional immutable Stem metadata.
  ///
  /// @param chapters chapter snapshots to copy
  /// @param stem     the manifest, or `null` for no Stem metadata
  /// @throws NullPointerException if the chapter list or a chapter is null
  public Tag(List<Chapter> chapters, Stem stem) {
    file = null;
    owner = null;
    atoms = null;
    factory = ItemFactory.instance();
    snapshotChapters = List.copyOf(chapters);
    snapshotValue = true;
    if (stem != null) {
      setItem("stem", new Item(stem));
    }
  }

  /// Compares snapshot-constructed tags by their Nero chapter list and Stem value.
  /// Other tag instances use identity comparison.
  ///
  /// @param other the value to compare
  /// @return whether the tags compare equal
  @Override
  public boolean equals(Object other) {
    return this == other || snapshotValue && other instanceof Tag value && value.snapshotValue
      && snapshotChapters.equals(value.snapshotChapters) && stem().equals(value.stem());
  }

  @Override
  public int hashCode() {
    return snapshotValue ? Objects.hash(snapshotChapters, stem()) : System.identityHashCode(this);
  }

  private void parse(Atom atom) {
    if (atom.length() < 8) {
      throw new InvalidAudioFileException("Invalid MP4 item extent");
    }
    file.seek(atom.offset() + 8);
    var parsed = factory.parseItem(atom, file.readBlock(atom.length() - 8));
    if (parsed.item().isValid()) {
      items.putIfAbsent(parsed.name(), parsed.item());
    }
  }

  /// Reads a snapshot of Nero chapters and Stem metadata from an MP4 path. This operation
  /// does not populate ordinary text items. Temporary storage handles are closed.
  ///
  /// @param path the input file
  /// @return the unbound chapter and Stem view
  /// @throws UncheckedIOException if storage access fails
  public static Tag read(Path path) {
    try {
      return Mp4Structured.read(path);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata" + ": " + path, failure);
    }
  }

  /// Returns Nero chapter values from the bound file or this unbound chapter snapshot.
  /// A bound file can load the values lazily from storage.
  ///
  /// @return chapter values
  /// @throws UncheckedIOException if lazy storage access fails
  public final List<Chapter> neroChapters() {
    return owner != null ? owner.neroChapters() : snapshotChapters;
  }

  /// Looks up immutable Stem manifest data in the live item map.
  ///
  /// @return the manifest, or an empty optional if absent
  public final Optional<Stem> stem() {
    return contains("stem") ? Optional.of(item("stem").toStem()) : Optional.empty();
  }

  /// Writes this view's Nero chapters and Stem metadata to an MP4 path in place. Ordinary text
  /// items are not written by this overload. A failure can leave partial changes.
  ///
  /// @param path the MP4 file to update
  /// @throws UncheckedIOException if storage access fails
  public final void save(Path path) {
    try {
      WriteGuard.run(path, () -> Mp4Structured.write(path, this));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot write audio metadata: " + path, failure);
    }
  }

  /// Returns a sorted, structurally read-only live map. Its item values remain editable.
  ///
  /// @return the live metadata item map
  public final Map<String, Item> itemMap() {
    return Collections.unmodifiableMap(items);
  }

  /// Returns the live item for a name. Looking up an absent name does not create it.
  ///
  /// @param key the item name
  /// @return the live item, or a detached void item when absent
  public final Item item(String key) {
    var value = items.get(Objects.requireNonNull(key));
    return value == null ? new Item() : value;
  }

  /// Stores the supplied item by reference. Subsequent edits to its atom type affect this tag.
  ///
  /// @param key  the item name
  /// @param item the item reference to retain
  public final void setItem(String key, Item item) {
    items.put(Objects.requireNonNull(key), Objects.requireNonNull(item));
  }

  /// Removes a named item in memory.
  ///
  /// @param key the item name
  public final void removeItem(String key) {
    items.remove(key);
  }

  /// Tests whether a named item exists in the live map.
  ///
  /// @param key the item name
  /// @return whether the item exists
  public final boolean contains(String key) {
    return items.containsKey(key);
  }

  private String text(String key) {
    Item value = items.get(key);
    return value == null ? "" : String.join(", ", value.toStringList());
  }

  @Override
  public String title() {
    return text("©nam");
  }

  @Override
  public String artist() {
    return text("©ART");
  }

  @Override
  public String album() {
    return text("©alb");
  }

  @Override
  public String comment() {
    return text("©cmt");
  }

  @Override
  public String genre() {
    return text("©gen");
  }

  @Override
  public long year() {
    Item value = items.get("©day");
    return value == null ? 0 : Integer.toUnsignedLong(TextNumbers.parseInt(String.join(" ", value.toStringList())));
  }

  @Override
  public long track() {
    Item value = items.get("trkn");
    return value == null ? 0 : Integer.toUnsignedLong(value.toIntPair().first());
  }

  @Override
  public void setTitle(String value) {
    setTextItem("©nam", value);
  }

  @Override
  public void setArtist(String value) {
    setTextItem("©ART", value);
  }

  @Override
  public void setAlbum(String value) {
    setTextItem("©alb", value);
  }

  @Override
  public void setComment(String value) {
    setTextItem("©cmt", value);
  }

  @Override
  public void setGenre(String value) {
    setTextItem("©gen", value);
  }

  protected final void setTextItem(String key, String value) {
    if (value.isEmpty()) {
      items.remove(key);
    } else {
      setItem(key, Item.textValues(List.of(value)));
    }
  }

  @Override
  public void setYear(long value) {
    value &= 0xffff_ffffL;
    if (value == 0) {
      items.remove("©day");
    } else {
      setItem("©day", Item.textValues(List.of(Integer.toString((int) value))));
    }
  }

  @Override
  public void setTrack(long value) {
    value &= 0xffff_ffffL;
    if (value == 0) {
      items.remove("trkn");
    } else {
      setItem("trkn", new Item((int) value, 0));
    }
  }

  @Override
  public boolean isEmpty() {
    return items.isEmpty();
  }

  /// Exports items through the factory property mappings, reporting unmapped atom names as
  /// unsupported.
  @Override
  public PropertyMap properties() {
    var values = PropertyMap.builder();
    items.forEach((name, item) -> {
      var property = factory.itemToProperty(ByteVector.fromText(name, ISO_8859_1), item);
      if (property.key().isEmpty()) {
        values.addUnsupportedData(name);
      } else {
        values.put(property.key(), property.values());
      }
    });
    return values.build();
  }

  @Override
  public void removeUnsupportedProperties(List<String> values) {
    values.forEach(items::remove);
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap values) {
    PropertyMap previous = properties();
    for (String key : previous.asMap().keySet()) {
      if (!values.containsKey(key) || values.get(key).isEmpty()) {
        var name = factory.nameForPropertyKey(key);
        items.remove(name.decodeTerminatedText(ISO_8859_1));
      }
    }
    var rejected = PropertyMap.builder();
    for (var entry : values.asMap().entrySet()) {
      var parsed = factory.itemFromProperty(entry.getKey(), entry.getValue());
      if (parsed.item().isValid()) {
        var name = parsed.name();
        setItem(name.decodeTerminatedText(ISO_8859_1), parsed.item());
      } else {
        rejected.put(entry.getKey(), entry.getValue());
      }
    }
    return PropertyUpdateResult.fromRejections(values, rejected.build());
  }

  @Override
  public boolean supportsPictureEditing() {
    return true;
  }

  /// Reports PICTURE for a stored cover-art item and STEM for a stored Stem item, including empty
  /// values.
  @Override
  public List<String> complexPropertyKeys() {
    var result = new ArrayList<String>();
    if (items.containsKey("covr")) {
      result.add("PICTURE");
    }
    if (items.containsKey("stem")) {
      result.add("STEM");
    }
    return List.copyOf(result);
  }

  private static String coverMime(CoverArt.Format format) {
    return switch (format) {
      case BMP -> "image/bmp";
      case JPEG -> "image/jpeg";
      case GIF -> "image/gif";
      case PNG -> "image/png";
      case Unknown -> "image/";
    };
  }

  private static CoverArt.Format coverFormat(String mime) {
    return switch (mime) {
      case "image/bmp" -> CoverArt.Format.BMP;
      case "image/png" -> CoverArt.Format.PNG;
      case "image/gif" -> CoverArt.Format.GIF;
      case "image/jpeg" -> CoverArt.Format.JPEG;
      default -> CoverArt.Format.Unknown;
    };
  }

  private static Variant value(Map<String, Variant> values, String name) {
    return values.getOrDefault(name, EMPTY_VARIANT);
  }

  @Override
  public List<Map<String, Variant>> complexProperties(String key) {
    String upper = Ascii.upper(key);
    var result = new ArrayList<Map<String, Variant>>();
    if (upper.equals("PICTURE")) {
      Item item = items.get("covr");
      if (item == null) return List.of();
      for (CoverArt cover : item.toCoverArtList()) {
        String mime = coverMime(cover.format());
        var values = new TreeMap<String, Variant>();
        values.put("data", new Variant(cover.data()));
        values.put("mimeType", new Variant(mime));
        result.add(Collections.unmodifiableMap(values));
      }
    } else if (upper.equals("STEM") && items.containsKey("stem")) {
      result.add(Map.of("manifest", new Variant(items.get("stem").toStem().byteVector())));
    }
    return List.copyOf(result);
  }

  @Override
  public boolean setComplexProperties(String key, List<Map<String, Variant>> values) {
    String upper = Ascii.upper(key);
    if (upper.equals("PICTURE")) {
      var covers = new ArrayList<CoverArt>();
      for (var picture : values) {
        CoverArt.Format format = coverFormat(value(picture, "mimeType").toText());
        covers.add(new CoverArt(format, value(picture, "data").toByteVector()));
      }
      setItem("covr", Item.coverArt(covers));
    } else if (upper.equals("STEM")) {
      if (values.isEmpty()) {
        items.remove("stem");
      } else {
        setItem("stem", new Item(new Stem(value(values.getFirst(), "manifest").toByteVector())));
      }
    } else {
      return false;
    }
    return true;
  }

  /// Writes pending item edits to bound storage immediately, updating atom sizes and offsets.
  /// A failure can leave partial storage changes.
  ///
  /// @throws IllegalStateException if storage is unbound or closed
  /// @throws TagLibException       if storage is read-only
  /// @throws UncheckedIOException  if storage access fails
  public final void save() {
    requireWritable();
    var editor = new ItemAtomEditor(file, atoms);
    var ilstOutput = new ByteVectorOutput();
    var stemOutput = new ByteVectorOutput();
    for (var entry : items.entrySet()) {
      ByteVector encoded = factory.renderItem(entry.getKey(), entry.getValue());
      if (entry.getKey().equals("stem")) {
        stemOutput.append(encoded);
      } else {
        ilstOutput.append(encoded);
      }
    }
    var ilst = Mp4Save.atom("ilst", ilstOutput.toByteVector());
    var stem = stemOutput.toByteVector();
    List<Atom> path = atoms.path("moov", "udta", "meta", "ilst");
    if (path.size() == 4) {
      editor.saveExisting(ilst, path);
    } else {
      var handler = Mp4Save.atom("hdlr",
        new ByteVector(8, (byte) 0).append(new ByteVector("mdirappl")).append(new ByteVector(9, (byte) 0)));
      editor.saveNew(Mp4Save.atom("meta", new ByteVector(4, (byte) 0).append(handler).append(ilst).append(ItemAtomEditor.padding(ilst, -1))));
    }
    path = atoms.path("moov", "udta", "stem");
    if (path.size() == 3) {
      editor.saveExisting(stem, path);
    } else if (!stem.isEmpty()) {
      editor.saveNew(stem);
    }
  }

  /// Clears live items and removes their bound ilst and Stem atoms immediately.
  /// A failure can leave partial storage changes.
  ///
  /// @throws IllegalStateException if storage is unbound or closed
  /// @throws TagLibException       if storage is read-only
  /// @throws UncheckedIOException  if storage access fails
  public final void strip() {
    requireWritable();
    items.clear();
    var editor = new ItemAtomEditor(file, atoms);
    List<Atom> path = atoms.path("moov", "udta", "meta", "ilst");
    if (path.size() == 4) {
      editor.saveExisting(new ByteVector(), path);
    }
    path = atoms.path("moov", "udta", "stem");
    if (path.size() == 3) {
      editor.saveExisting(new ByteVector(), path);
    }
  }

  private void requireWritable() {
    if (file == null || !file.isOpen()) {
      throw new IllegalStateException("MP4 tag is not bound to an open file");
    }
    if (file.readOnly()) {
      throw new TagLibException("MP4 tag is bound to a read-only file: " + file.name());
    }
    if (atoms == null) {
      throw new IllegalStateException("MP4 tag has no atom layout: " + file.name());
    }
  }
}
