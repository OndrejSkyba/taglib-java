// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.ape;

import cz.skyba.taglib.Picture;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.Tag;
import cz.skyba.taglib.ape.Item.ItemType;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.UnsupportedDataRemoval;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Variant;

import java.io.IOException;
import java.util.*;

import static cz.skyba.taglib.internal.Binary.ascii;
import static cz.skyba.taglib.internal.Binary.u32le;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * APEv2 text, binary, locator, and artwork items.
 */
public final class ApeTag {
  private static final ItemType[] ITEMTYPE_VALUES = ItemType.values();

  private static final Variant EMPTY_VARIANT = new Variant();

  private ApeTag() {
  }

  private static final Set<String> COMMON_KEYS =
    Set.of("TITLE", "ARTIST", "ALBUM", "COMMENT", "GENRE", "YEAR", "TRACK");

  public record Item(String key, int flags, byte[] value) {
  }

  public record Data(
    long offset, long length, PropertyMap properties, List<Picture> pictures, List<Item> items) {
    /**
     * Native common accessors use YEAR/TRACK rather than arbitrary DATE/TRACKNUMBER items.
     */
    public Tag basic() {
      var tag =
        typed(
          items.stream()
            .filter(item -> COMMON_KEYS.contains(item.key().toUpperCase(Locale.ROOT)))
            .toList());
      return new Tag(
        tag.title(),
        tag.artist(),
        tag.album(),
        tag.comment(),
        tag.genre(),
        tag.year(),
        tag.track());
    }
  }

  public static Data read(BinaryFile f, ReadOptions options) throws IOException {
    long end = f.size();
    if (end >= 128 && f.ascii(end - 128, 3).equals("TAG")) {
      end -= 128;
    }
    if (end < 32 || !f.ascii(end - 32, 8).equals("APETAGEX")) {
      return new Data(end, 0, PropertyMap.EMPTY, List.of(), List.of());
    }
    byte[] foot = f.read(end - 32, 32);
    long size = u32le(foot, 12), count = u32le(foot, 16), flags = u32le(foot, 20);
    if (size < 32 || size > end || size > options.maxMetadataBytes()) {
      throw new IOException("Invalid or oversized APE tag");
    }
    long start = end - size, fullStart = start;
    if ((flags & 0x80000000L) != 0 && start >= 32 && f.ascii(start - 32, 8).equals("APETAGEX")) {
      fullStart -= 32;
    }
    byte[] b = f.read(start, (int) size - 32);
    count = Math.min(count, 50000);
    var itemMap = new TreeMap<String, Item>();
    long position = 0;
    for (long i = 0; i < count; i++) {
      if (position + 11 > b.length) {
        break;
      }
      int at = (int) position;
      long n = u32le(b, at);
      int fl = (int) u32le(b, at + 4);
      at += 8;
      int zero = at;
      while (zero < b.length && b[zero] != 0) {
        zero++;
      }
      if (zero == b.length || n >= b.length || at - 8L > b.length - n) {
        break;
      }
      String key = ascii(b, at, zero - at);
      at = zero + 1;
      byte[] value = Arrays.copyOfRange(b, at, (int) Math.min(b.length, at + n));
      position = at + n;
      if (validKey(key)) {
        itemMap.put(key.toUpperCase(Locale.ROOT), new Item(key, fl & 7, value));
      }
    }
    // Native ItemListMap is case-insensitive, ordered, and replaces earlier duplicate keys.
    var items = new ArrayList<>(itemMap.values());
    var tag = typed(items);
    var pictures = new ArrayList<Picture>();
    for (var picture : tag.complexProperties("PICTURE")) {
      pictures.add(
        new Picture(
          picture.get("pictureType").toText().equals("Back Cover") ? 4 : 3,
          "",
          picture.getOrDefault("description", EMPTY_VARIANT).toText(),
          picture.get("data").toByteVector().data()));
    }
    return new Data(
      fullStart, end - fullStart, tag.properties(), List.copyOf(pictures), List.copyOf(items));
  }

  public static cz.skyba.taglib.ape.Tag typed(List<Item> items) {
    var tag = new cz.skyba.taglib.ape.Tag();
    for (Item raw : items) {
      cz.skyba.taglib.ape.Item item;
      if ((raw.flags >>> 1 & 3) == 0) {
        var values = new ArrayList<String>();
        for (var part :
          new ByteVector(raw.value).split(new ByteVector((byte) 0))) {
          values.add(
            part.decodeTerminatedText(UTF_8));
        }
        item = new cz.skyba.taglib.ape.Item(raw.key, values);
      } else {
        item = new cz.skyba.taglib.ape.Item(raw.key, new ByteVector(raw.value), true);
        item.setType(ITEMTYPE_VALUES[raw.flags >>> 1 & 3]);
      }
      item.setReadOnly((raw.flags & 1) != 0);
      tag.setItem(raw.key, item);
    }
    return tag;
  }

  public static byte[] render(PropertyMap properties, Data original) throws IOException {
    return render(properties, original, null);
  }

  public static byte[] render(PropertyMap properties, Data original, List<Picture> pictures)
    throws IOException {
    var tag = typed(original.items());
    tag.removeUnsupportedProperties(UnsupportedDataRemoval.requested());
    var rejected = tag.replaceProperties(properties).rejected();
    if (!rejected.isEmpty()) {
      throw new IOException("Invalid APE keys: " + rejected.asMap().keySet());
    }
    if (pictures != null) {
      var values = new ArrayList<Map<String, Variant>>();
      for (Picture picture : pictures) {
        String type =
          picture.type() == 4 ? "Back Cover" : picture.type() == 3 ? "Front Cover" : "Other";
        values.add(
          Map.of(
            "pictureType",
            new Variant(type),
            "description",
            new Variant(picture.description()),
            "data",
            new Variant(new ByteVector(picture.data()))));
      }
      tag.setComplexProperties("PICTURE", values);
    }
    return tag.isEmpty() ? new byte[0] : tag.render().toByteArray();
  }

  private static boolean validKey(String key) {
    return cz.skyba.taglib.ape.Tag.checkKey(key);
  }
}
