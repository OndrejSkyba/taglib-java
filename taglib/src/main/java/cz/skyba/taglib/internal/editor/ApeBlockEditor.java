// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.editor;

import cz.skyba.taglib.MetadataEditException;
import cz.skyba.taglib.Picture;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.ape.ApeTag;
import cz.skyba.taglib.internal.editor.AbstractDocument.Removal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/// Physical APE entries and their original-byte retention policy.
final class ApeBlockEditor {
  private static final Set<String> COVER_ART_KEYS = Set.of("COVER ART (FRONT)", "COVER ART (BACK)");
  private static final Set<String> RESERVED_KEYS = Set.of("ID3", "TAG", "OGGS", "MP+");

  private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;
  private static final Map<String, String> APE_KEYS =
    Map.of(
      "TRACK",
      "TRACKNUMBER",
      "YEAR",
      "DATE",
      "ALBUM ARTIST",
      "ALBUMARTIST",
      "DISC",
      "DISCNUMBER",
      "MIXARTIST",
      "REMIXER",
      "MUSICBRAINZ_ALBUMSTATUS",
      "RELEASESTATUS",
      "MUSICBRAINZ_ALBUMTYPE",
      "RELEASETYPE");

  private static final class ApeSlot {
    String key;
    int flags;
    byte[] value;
    boolean removed;

    ApeSlot(ApeTag.Item i) {
      key = i.key();
      flags = i.flags();
      value = i.value();
    }
  }

  private final List<ApeSlot> items = new ArrayList<>();
  final Runnable changed;
  final int rank;

  ApeBlockEditor(AbstractDocument document, List<ApeTag.Item> original, Runnable changed, int rank) {
    this.changed = changed;
    this.rank = rank;
    for (var item : original) {
      var slot = new ApeSlot(item);
      items.add(slot);
      Removal remove =
        () -> {
          slot.removed = true;
          changed.run();
        };
      String upper = slot.key.toUpperCase(Locale.ROOT);
      int type = (slot.flags >>> 1) & 3;
      if (type == 1 && COVER_ART_KEYS.contains(upper)) {
        int end = 0;
        while (end < slot.value.length && slot.value[end] != 0) {
          end++;
        }
        if (end < slot.value.length) {
          byte[] data = Arrays.copyOfRange(slot.value, end + 1, slot.value.length);
          Picture pic =
            new Picture(
              upper.equals("COVER ART (BACK)") ? 4 : 3,
              EditorText.mime(data),
              new String(slot.value, 0, end, StandardCharsets.UTF_8),
              data);
          document.picture("APE", slot.key, pic, slot.key, remove).projected(false);
          continue;
        }
      }
      if (type != 0) {
        document.addEntry(
            "APE",
            slot.key,
            "",
            "binary",
            List.of(),
            null,
            remove,
            false,
            slot.value.length + " bytes")
          .projected(false);
        continue;
      }
      var values =
        Arrays.asList(new String(slot.value, StandardCharsets.UTF_8).split("\u0000", -1));
      document.addEntry(
          "APE",
          slot.key,
          APE_KEYS.getOrDefault(upper, upper),
          "text",
          values,
          (name, next) -> {
            validApe(name);
            ensureFree(name, slot);
            slot.key = name;
            slot.value = String.join("\u0000", next).getBytes(StandardCharsets.UTF_8);
            changed.run();
          },
          remove,
          true,
          "")
        .multipleValues(true)
        .precedence(rank);
    }
  }

  void ensureFree(String name, ApeSlot except) {
    for (var s : items) {
      if (!s.removed && s != except && s.key.equalsIgnoreCase(name)) {
        throw new MetadataEditException("The destination APE key already exists");
      }
    }
  }

  void add(String name, List<String> values, boolean custom) throws IOException {
    String key = name;
    if (!custom) {
      for (var e : APE_KEYS.entrySet()) {
        if (e.getValue().equals(name)) {
          key = e.getKey();
          break;
        }
      }
    }
    validApe(key);
    ensureFree(key, null);
    items.add(
      new ApeSlot(
        new ApeTag.Item(
          key, 0, String.join("\u0000", values).getBytes(StandardCharsets.UTF_8))));
    changed.run();
  }

  void pictures(List<Picture> pictures) {
    for (var s : items) {
      if (COVER_ART_KEYS.contains(s.key.toUpperCase(Locale.ROOT))) {
        s.removed = true;
      }
    }
    var used = new HashSet<Integer>();
    for (var pic : pictures) {
      int type = pic.type() == 4 ? 4 : 3;
      if (!used.add(type)) {
        throw new MetadataEditException("APE supports one front and one back picture");
      }
      var out = new ByteArrayOutputStream();
      out.writeBytes(pic.description().getBytes(StandardCharsets.UTF_8));
      out.write(0);
      out.writeBytes(pic.data());
      items.add(
        new ApeSlot(
          new ApeTag.Item(
            type == 4 ? "Cover Art (Back)" : "Cover Art (Front)", 2, out.toByteArray())));
    }
    changed.run();
  }

  byte[] render() {
    var out = new ByteArrayOutputStream();
    int count = 0;
    for (var s : items) {
      if (!s.removed) {
        byte[] h = new byte[8];
        Binary.put32le(h, 0, s.value.length);
        Binary.put32le(h, 4, s.flags);
        out.writeBytes(h);
        out.writeBytes(s.key.getBytes(LATIN1));
        out.write(0);
        out.writeBytes(s.value);
        count++;
      }
    }
    if (count == 0) {
      return new byte[0];
    }
    byte[] payload = out.toByteArray(), footer = new byte[32];
    System.arraycopy(Binary.bytes("APETAGEX"), 0, footer, 0, 8);
    Binary.put32le(footer, 8, 2000);
    Binary.put32le(footer, 12, payload.length + 32);
    Binary.put32le(footer, 16, count);
    Binary.put32le(footer, 20, 0x80000000L);
    byte[] header = footer.clone();
    Binary.put32le(header, 20, 0xa0000000L);
    byte[] result = Arrays.copyOf(header, Math.addExact(payload.length, 64));
    System.arraycopy(payload, 0, result, 32, payload.length);
    System.arraycopy(footer, 0, result, 32 + payload.length, 32);
    return result;
  }

  private static void validApe(String key) {
    if (key.length() < 2
      || key.length() > 255
      || !key.chars().allMatch(c -> c >= 32 && c <= 126)
      || RESERVED_KEYS.contains(key.toUpperCase(Locale.ROOT))) {
      throw new MetadataEditException("Invalid APE tag name");
    }
  }

}
