// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.editor;

import cz.skyba.taglib.MetadataEditException;
import cz.skyba.taglib.MetadataReadException;
import cz.skyba.taglib.Picture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static cz.skyba.taglib.internal.Binary.*;

/// Physical MP4 metadata, retaining duplicate atoms, typed data values, and track scope.
public final class Mp4Document extends AbstractDocument {
  private final Mp4Tree tree;
  private final List<Block> blocks = new ArrayList<>();
  private int firstAudio, trackNumber;
  private long metadataBytes;

  private record Key(byte[] namespace, byte[] name) {
    String display() {
      return visibleKey(namespace) + ":" + visibleKey(name);
    }

    boolean editable() {
      return Arrays.equals(namespace, bytes("mdta")) && textKey(name);
    }

    boolean same(Key other) {
      return Arrays.equals(namespace, other.namespace) && Arrays.equals(name, other.name);
    }
  }

  private record OriginalItem(Mp4Items.Item item, byte[] atom) {
  }

  private static final class Record {
    String nativeName, key, property;
    Mp4Items.Item item;
    final OriginalItem original;
    boolean removed, renamed, named = true;
    final Set<Integer> removedPictures = new HashSet<>();

    private Record(OriginalItem original) {
      this.original = original;
      if (original != null) {
        item = original.item;
        nativeName = item.nativeName();
      }
    }

    static Record retained(Mp4Items.Item item, byte[] atom) {
      return new Record(new OriginalItem(item, atom));
    }

    static Record added() {
      return new Record(null);
    }
  }

  private static final class Block {
    final int meta, list, keysOffset, track;
    final boolean keyed, audio;
    final List<Key> keys = new ArrayList<>();
    final List<Record> records = new ArrayList<>();

    private Block(int meta, int list, int keysOffset, int track, boolean keyed, boolean audio, List<Key> keys) {
      this.meta = meta;
      this.list = list;
      this.keysOffset = keysOffset;
      this.track = track;
      this.keyed = keyed;
      this.audio = audio;
      this.keys.addAll(keys);
    }

    static Block retained(int meta, int list, int keysOffset, int track, boolean keyed, boolean audio, List<Key> keys) {
      return new Block(meta, list, keysOffset, track, keyed, audio, keys);
    }

    static Block destination(boolean keyed) {
      return new Block(-1, -1, -1, 0, keyed, true, List.of());
    }

    String label() {
      return (keyed ? "MP4 mdta" : "MP4") + (track == 0 ? "" : " · Track " + track);
    }
  }

  public Mp4Document(Path path) throws IOException {
    super(path);
    tree = new Mp4Tree(this.path);
    readOnlyReason = tree.restriction;
    collect(tree.movie, 0, true);
    // Stable ordering makes later global entries win projection ties without moving their atoms.
    blocks.sort(Comparator.comparingInt(b -> b.track == 0 ? 1 : 0));
    defaultBlock = !blocks.isEmpty() && blocks.getLast().keyed ? "MP4 mdta" : "MP4";
    inspect();
  }

  private void collect(Mp4Tree.Node parent, int track, boolean audio) {
    for (var child : parent.children()) {
      if (child.name().equals("trak")) {
        var handler = child.path("mdia", "hdlr");
        boolean isAudio =
          handler != null
            && handler.size() - handler.header() >= 12
            && ascii(tree.source, handler.body() + 8, 4).equals("soun");
        int nextTrack = ++trackNumber;
        if (isAudio && firstAudio == 0) {
          firstAudio = nextTrack;
        }
        collect(child, nextTrack, isAudio);
      } else if (child.name().equals("meta")) {
        readBlock(child, track, audio);
      } else {
        collect(child, track, audio);
      }
    }
  }

  private void readBlock(Mp4Tree.Node meta, int track, boolean audio) {
    var lists = meta.children("ilst");
    var keyAtoms = meta.children("keys");
    if (lists.isEmpty() && keyAtoms.isEmpty()) {
      return;
    }
    if (keyAtoms.size() > 1) {
      throw new MetadataReadException(
        "The MP4 tag names are inconsistent and cannot be read reliably.");
    }
    var keys = new ArrayList<Key>();
    if (!keyAtoms.isEmpty()) {
      byte[] data = tree.payload(keyAtoms.getFirst());
      if (data.length < 8) {
        throw damagedKeys();
      }
      if (u32be(data, 0) != 0) {
        throw new MetadataEditException("The MP4 tag names are stored in an unsupported format.");
      }
      long count = u32be(data, 4);
      if (count > (data.length - 8) / 8) {
        throw damagedKeys();
      }
      if (count > 10000) {
        throw new MetadataEditException("This MP4 contains too many tag names to read.");
      }
      int at = 8;
      for (int i = 0; i < count; i++) {
        if (data.length - at < 8) {
          throw damagedKeys();
        }
        long size = u32be(data, at);
        if (size < 8 || size > data.length - at) {
          throw damagedKeys();
        }
        keys.add(
          new Key(
            Arrays.copyOfRange(data, at + 4, at + 8),
            Arrays.copyOfRange(data, at + 8, at + (int) size)));
        at += (int) size;
      }
      if (at != data.length) {
        throw damagedKeys();
      }
      if (lists.size() > 1) {
        throw new MetadataEditException("This MP4 tag layout is not supported.");
      }
    }
    // A keys table without an ilst is a valid destination for new metadata.
    for (int listIndex = 0; listIndex < Math.max(1, lists.size()); listIndex++) {
      var list = lists.isEmpty() ? null : lists.get(listIndex);
      boolean keyed = !keyAtoms.isEmpty();
      var block = Block.retained(meta.at(), list == null ? -1 : list.at(),
        keyed ? keyAtoms.getFirst().at() : -1, track, keyed, audio, keys);
      if (list != null) {
        for (var atom : list.children()) {
          byte[] data = tree.payload(atom);
          metadataBytes += data.length;
          if (metadataBytes > Mp4Tree.LIMIT) {
            throw new MetadataEditException("MP4 metadata exceeds 64 MB.");
          }
          long index = block.keyed ? u32be(bytes(atom.name()), 0) : 0;
          var item = Mp4Items.parse(block.keyed ? Long.toString(index) : atom.name(), data, block.keyed);
          var record = Record.retained(item, tree.bytes(atom));
          if (block.keyed) {
            if (index == 0 || index > keys.size()) {
              record.named = false;
              record.key = "Unresolved key #" + index;
              readOnlyReason = "Some MP4 tags have missing or damaged names.";
              metadataReadFailed = true;
            } else {
              var key = keys.get((int) index - 1);
              record.key = key.display();
              record.named = key.editable();
            }
          } else {
            record.key = record.nativeName;
          }
          record.property = property(block, record);
          block.records.add(record);
        }
      }
      blocks.add(block);
    }
  }

  private void inspect() {
    for (var block : blocks) {
      for (var record : block.records) {
        boolean projected = block.track == 0 || (block.audio && block.track == firstAudio);
        int precedence =
          (block.track == 0 ? 100 : 0) + (!block.keyed && record.property.isEmpty() ? 0 : 1);
        if (record.item.kind() == Mp4Items.Kind.ARTWORK) {
          for (int i = 0; i < record.item.values().size(); i++) {
            final int index = i;
            var value = record.item.values().get(i);
            String mime = Mp4Items.mime(value.type());
            picture(
              block.label(),
              record.key,
              new Picture(
                3,
                mime.isEmpty() ? "application/octet-stream" : mime,
                "Artwork " + (i + 1),
                value.bytes()),
              "Artwork " + (i + 1),
              () -> record.removedPictures.add(index))
              .projected(projected)
              .precedence(precedence);
          }
          continue;
        }
        var values = record.named ? record.item.text() : List.<String>of();
        boolean renameable = record.named && (block.keyed || record.key.startsWith("----:"));
        boolean pair = record.item.kind() == Mp4Items.Kind.PAIR;
        var entry =
          addEntry(
            block.label(),
            record.key,
            record.property,
            values.isEmpty() ? "binary" : "text",
            values,
            values.isEmpty() ? null : (key, next) -> update(block, record, key, next),
            () -> record.removed = true,
            renameable,
            pair ? "Number / total" : "")
            .multipleValues(block.keyed || record.item.kind() == Mp4Items.Kind.TEXT)
            .projected(projected)
            .precedence(precedence);
        if (pair) {
          entry.setValueCount(2);
        }
      }
    }
  }

  private static void update(Block block, Record record, String nextKey, List<String> values) {
    var next = Mp4Items.edit(record.item, values);
    String target = block.keyed ? parseKey(nextKey).display() : nextKey;
    if (!target.equals(record.key)) {
      if (hasKey(block, target)) {
        throw new MetadataEditException("The destination key already exists.");
      }
      if (block.keyed) {
        record.nativeName = assignKey(block, parseKey(nextKey));
      } else {
        validateFreeform(nextKey);
        record.nativeName = nextKey;
      }
      record.key = target;
      record.renamed = true;
    }
    record.item = next;
  }

  @Override
  protected void add(String key, List<String> values, boolean custom) throws IOException {
    var block = destination();
    var record = Record.added();
    if (block.keyed) {
      var descriptor = parseKey(custom ? key : asciiLower(key));
      record.key = descriptor.display();
      record.nativeName = assignKey(block, descriptor);
      record.item = Mp4Items.keyedText(record.nativeName, values);
    } else {
      if (custom) {
        record.nativeName = key.startsWith("----:") ? key : Mp4Items.FREE + key;
        validateFreeform(record.nativeName);
        for (var value : values) {
          Mp4Items.validateText(value);
        }
        record.item = Mp4Items.text(record.nativeName, values);
      } else {
        record.item = Mp4Items.fromProperty(key, values);
        record.nativeName = record.item.nativeName();
      }
      record.key = record.nativeName;
    }
    if (hasKey(block, record.key)) {
      throw new MetadataEditException("Tag already exists; edit its values instead.");
    }
    record.property = property(block, record);
    block.records.add(record);
  }

  @Override
  protected void replacePicturesChanges(List<Picture> pictures) {
    requireWritable();
    var values = new ArrayList<Mp4Items.Value>();
    long total = 0;
    var block = destination();
    for (var picture : pictures) {
      byte[] data = picture.data();
      total += data.length + 16L;
      if (total > Mp4Tree.LIMIT) {
        throw new MetadataEditException("MP4 artwork exceeds 64 MB.");
      }
      long type =
        block.keyed
          ? (picture.mimeType().equals("image/png") ? 14 : 13)
          : switch (picture.mimeType()) {
          case "image/jpeg" -> 13;
          case "image/png" -> 14;
          case "image/gif" -> 12;
          case "image/bmp" -> 27;
          default -> 0;
        };
      values.add(new Mp4Items.Value(data, type, 0));
    }
    for (var existing : blocks) {
      for (var record : existing.records) {
        if (record.item.kind() == Mp4Items.Kind.ARTWORK) {
          record.removed = true;
        }
      }
    }
    if (values.isEmpty()) {
      return;
    }
    var record = Record.added();
    if (block.keyed) {
      record.key = "mdta:com.apple.quicktime.artwork";
      record.nativeName = assignKey(block, parseKey(record.key));
    } else {
      record.key = record.nativeName = "covr";
    }
    record.property = "PICTURE";
    record.item =
      new Mp4Items.Item(
        record.nativeName, Mp4Items.Kind.ARTWORK, List.of(), List.copyOf(values), block.keyed);
    block.records.add(record);
  }

  @Override
  protected void saveChanges() throws IOException {
    var plan = new Mp4EditPlan();
    var replacements = plan.replacements;
    for (var block : blocks) {
      boolean changed = false;
      var list = new ByteArrayOutputStream();
      for (var record : block.records) {
        if (!record.removedPictures.isEmpty() && !record.removed) {
          var values = new ArrayList<Mp4Items.Value>();
          for (int i = 0; i < record.item.values().size(); i++) {
            if (!record.removedPictures.contains(i)) {
              values.add(record.item.values().get(i));
            }
          }
          record.removed = values.isEmpty();
          record.item =
            new Mp4Items.Item(
              record.item.nativeName(),
              record.item.kind(),
              List.of(),
              List.copyOf(values),
              block.keyed);
        }
        boolean edited =
          record.renamed || record.original == null || !record.item.same(record.original.item);
        changed |= edited || record.removed;
        if (!record.removed) {
          byte[] bytes =
            edited ? Mp4Items.render(record.nativeName, record.item) : record.original.atom;
          if (bytes.length > Mp4Tree.LIMIT - list.size()) {
            throw new MetadataEditException("MP4 metadata exceeds 64 MB.");
          }
          list.writeBytes(bytes);
        }
      }
      if (!changed) {
        continue;
      }
      byte[] rendered = Mp4Items.atom("ilst", list.toByteArray());
      if (block.meta < 0) {
        byte[] meta = newMeta(block, rendered);
        var udta = tree.movie.child("udta");
        plan.append(
          udta == null ? tree.movie.at() : udta.at(),
          udta == null ? Mp4Items.atom("udta", meta) : meta);
      } else {
        if (block.list >= 0) {
          replacements.put(block.list, rendered);
        } else {
          plan.append(block.meta, rendered);
        }
        if (block.keyed) {
          replacements.put(block.keysOffset, renderKeys(block));
        }
      }
    }
    tree.save(path, plan);
  }

  private Block destination() {
    for (int i = blocks.size() - 1; i >= 0; i--) {
      if (blocks.get(i).track == 0) {
        return blocks.get(i);
      }
    }
    var block = Block.destination(!blocks.isEmpty() && blocks.getLast().keyed);
    blocks.add(block);
    return block;
  }

  private static boolean hasKey(Block block, String key) {
    return block.records.stream().anyMatch(r -> !r.removed && r.key.equals(key));
  }

  private static String property(Block block, Record record) {
    return block.keyed
      ? record.named ? Mp4Items.keyedProperty(record.key) : ""
      : Mp4Items.property(record.nativeName);
  }

  private static Key parseKey(String key) {
    String name = key.startsWith("mdta:") ? key.substring(5) : key;
    Mp4Items.validateText(name);
    if (name.isEmpty()) {
      throw new MetadataEditException("Invalid tag name.");
    }
    return new Key(bytes("mdta"), name.getBytes(StandardCharsets.UTF_8));
  }

  private static String assignKey(Block block, Key key) {
    for (int i = 0; i < block.keys.size(); i++) {
      if (block.keys.get(i).same(key)) {
        return Integer.toString(i + 1);
      }
    }
    if (block.keys.size() >= 10000) {
      throw new MetadataEditException("Too many MP4 metadata keys.");
    }
    block.keys.add(key);
    return Integer.toString(block.keys.size());
  }

  private static byte[] renderKeys(Block block) {
    var out = new ByteArrayOutputStream();
    byte[] prefix = new byte[8];
    put32be(prefix, 4, block.keys.size());
    out.writeBytes(prefix);
    for (var key : block.keys) {
      if (key.name.length > Mp4Tree.LIMIT - out.size() - 8) {
        throw new MetadataEditException("MP4 metadata exceeds 64 MB.");
      }
      byte[] size = new byte[4];
      put32be(size, 0, key.name.length + 8L);
      out.writeBytes(size);
      out.writeBytes(key.namespace);
      out.writeBytes(key.name);
    }
    return Mp4Items.atom("keys", out.toByteArray());
  }

  private static byte[] newMeta(Block block, byte[] list) {
    list = Mp4Tree.paddedList(list, 0);
    byte[] handler =
      Mp4Items.atom(
        "hdlr",
        Mp4Items.join(
          Mp4Items.join(new byte[8], bytes(block.keyed ? "mdta" : "mdir")), new byte[13]));
    var body = new ByteArrayOutputStream();
    body.writeBytes(new byte[4]);
    body.writeBytes(handler);
    body.writeBytes(block.keyed ? renderKeys(block) : new byte[0]);
    body.writeBytes(list);
    if (body.size() > Mp4Tree.LIMIT) {
      throw new MetadataEditException("MP4 metadata exceeds 64 MB.");
    }
    return Mp4Items.atom("meta", body.toByteArray());
  }

  private static void validateFreeform(String key) {
    Mp4Items.validateText(key);
    String[] parts = key.split(":", -1);
    if (parts.length != 3 || !parts[0].equals("----") || parts[1].isEmpty() || parts[2].isEmpty()) {
      throw new MetadataEditException("MP4 custom keys use ----:namespace:name.");
    }
  }

  private static String asciiLower(String value) {
    var out = new StringBuilder(value);
    for (int i = 0; i < out.length(); i++) {
      char c = out.charAt(i);
      if (c >= 'A' && c <= 'Z') {
        out.setCharAt(i, (char) (c + 32));
      }
    }
    return out.toString();
  }

  private static boolean textKey(byte[] value) {
    if (value.length == 0) {
      return false;
    }
    try {
      return Mp4Items.strictText(value, StandardCharsets.UTF_8).indexOf('\0') < 0;
    } catch (MetadataEditException _) {
      return false;
    }
  }

  private static String visibleKey(byte[] value) {
    return textKey(value)
      ? new String(value, StandardCharsets.UTF_8)
      : "0x" + HexFormat.of().formatHex(value);
  }

  private static MetadataReadException damagedKeys() {
    return new MetadataReadException("The MP4 tag names are damaged.");
  }
}
