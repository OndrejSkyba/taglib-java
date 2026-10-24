// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.editor;

import cz.skyba.taglib.MetadataEditException;
import cz.skyba.taglib.Picture;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.xiph.XiphComment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class XiphBlockEditor {
  private static final Set<String> ART_KEYS = Set.of("METADATA_BLOCK_PICTURE", "COVERART", "COVERARTMIME");


  private static final class XField {
    String key, value;
    byte[] raw;
    boolean removed;

    XField(byte[] raw) {
      this.raw = raw;
      String text = new String(raw, StandardCharsets.UTF_8);
      int eq = text.indexOf('=');
      if (eq > 0) {
        key = text.substring(0, eq).toUpperCase(Locale.ROOT);
        value = text.substring(eq + 1);
      }
    }
  }

  final byte[] vendor, suffix;
  private final List<XField> fields = new ArrayList<>();
  final Runnable changed;
  final boolean framing;
  final int rank;

  XiphBlockEditor(AbstractDocument document, byte[] raw, boolean framing, Runnable changed, int rank) throws IOException {
    this.changed = changed;
    this.framing = framing;
    this.rank = rank;
    if (raw.length == 0) {
      vendor = Binary.bytes("TagLib Java");
      suffix = new byte[0];
      return;
    }
    if (raw.length < 8) {
      throw new IOException("Truncated Vorbis comment");
    }
    long vendorSize = Binary.u32le(raw, 0);
    if (vendorSize > raw.length - 8) {
      throw new IOException("Truncated Vorbis vendor");
    }
    vendor = Arrays.copyOfRange(raw, 4, 4 + (int) vendorSize);
    int at = 4 + (int) vendorSize;
    long count = Binary.u32le(raw, at);
    at += 4;
    if (count > 50000) {
      throw new IOException("Too many Vorbis fields");
    }
    for (int i = 0; i < count; i++) {
      if (at > raw.length - 4) {
        throw new IOException("Truncated Vorbis field length");
      }
      long size = Binary.u32le(raw, at);
      at += 4;
      if (size > raw.length - at) {
        throw new IOException("Truncated Vorbis field");
      }
      fields.add(new XField(Arrays.copyOfRange(raw, at, at + (int) size)));
      at += (int) size;
    }
    suffix = Arrays.copyOfRange(raw, at, raw.length);
    var groups = new LinkedHashMap<String, List<XField>>();
    for (var field : fields) {
      if (field.key == null || !XiphComment.validKey(field.key)) {
        document.addEntry(
            "Vorbis",
            "INVALID",
            "",
            "binary",
            List.of(),
            null,
            () -> {
              field.removed = true;
              changed.run();
            },
            false,
            "Unrecognized comment bytes")
          .projected(false);
        continue;
      }
      if (artKey(field.key) && !field.key.equals("COVERARTMIME")) {
        try {
          byte[] data = Base64.getDecoder().decode(field.value);
          Picture pic =
            field.key.equals("COVERART")
              ? new Picture(0, EditorText.mime(data), "", data)
              : XiphComment.parsePicture(data);
          document.picture(
              "Vorbis",
              field.key,
              pic,
              pic.description(),
              () -> {
                field.removed = true;
                changed.run();
              })
            .projected(false);
        } catch (IllegalArgumentException | IOException e) {
          document.addEntry(
              "Vorbis",
              field.key,
              "",
              "binary",
              List.of(),
              null,
              () -> {
                field.removed = true;
                changed.run();
              },
              false,
              "Unrecognized artwork bytes")
            .projected(false);
        }
        continue;
      }
      groups.computeIfAbsent(field.key, _ -> new ArrayList<>()).add(field);
    }
    for (var group : groups.entrySet()) {
      String key = group.getKey();
      var members = group.getValue();
      String standard =
        key.equals("VERSION")
          ? "SUBTITLE"
          : key.equals("SUBTITLE") ? "" : key.equals("KEY") ? "INITIALKEY" : key;
      document.addEntry(
          "Vorbis",
          key,
          standard,
          "text",
          members.stream().map(f -> f.value).toList(),
          (name, next) -> {
            validateXiph(name, next);
            String upper = name.toUpperCase(Locale.ROOT);
            ensureFree(upper, members);
            replaceGroup(members, upper, next);
          },
          () -> {
            members.forEach(f -> f.removed = true);
            changed.run();
          },
          true,
          "")
        .multipleValues(true)
        .precedence(rank + (key.equals("SUBTITLE") ? 0 : 1))
        .projected(!key.equals("COVERARTMIME"));
    }
  }

  void ensureFree(String name, List<XField> except) {
    for (var f : fields) {
      if (!f.removed && !except.contains(f) && name.equals(f.key)) {
        throw new MetadataEditException("The destination Vorbis key already exists");
      }
    }
  }

  void replaceGroup(List<XField> members, String key, List<String> values) {
    int insert = members.isEmpty() ? fields.size() : fields.indexOf(members.getFirst());
    members.forEach(f -> f.removed = true);
    for (String value : values) {
      fields.add(insert++, new XField((key + "=" + value).getBytes(StandardCharsets.UTF_8)));
    }
    changed.run();
  }

  void add(String name, List<String> values, boolean custom) throws IOException {
    String key = name.toUpperCase(Locale.ROOT);
    if (!custom) {
      if (key.equals("SUBTITLE")) {
        key = "VERSION";
      }
      if (key.equals("INITIALKEY")) {
        key = "KEY";
      }
    }
    validateXiph(key, values);
    ensureFree(key, List.of());
    replaceGroup(List.of(), key, values);
  }

  void pictures(List<Picture> pictures) {
    for (var f : fields) {
      if (f.key != null && artKey(f.key)) {
        f.removed = true;
      }
    }
    for (var p : pictures) {
      fields.add(
        new XField(
          ("METADATA_BLOCK_PICTURE="
            + Base64.getEncoder().encodeToString(XiphComment.renderPicture(p)))
            .getBytes(StandardCharsets.UTF_8)));
    }
    changed.run();
  }

  byte[] render() {
    var out = new ByteArrayOutputStream();
    writeLittleEndian32(out, vendor.length);
    out.writeBytes(vendor);
    writeLittleEndian32(out, fields.stream().filter(f -> !f.removed).count());
    for (var f : fields) {
      if (!f.removed) {
        writeLittleEndian32(out, f.raw.length);
        out.writeBytes(f.raw);
      }
    }
    if (suffix.length > 0) {
      out.writeBytes(suffix);
    } else if (framing) {
      out.write(1);
    }
    return out.toByteArray();
  }

  private static boolean artKey(String name) {
    return ART_KEYS.contains(name);
  }

  private static void validateXiph(String name, List<String> values) {
    String upper = name.toUpperCase(Locale.ROOT);
    if (!XiphComment.validKey(upper) || artKey(upper)) {
      throw new MetadataEditException("Invalid Vorbis text field name");
    }
    if (values.stream().anyMatch(String::isEmpty)) {
      throw new MetadataEditException("Remove empty Vorbis values instead of clearing them");
    }
  }

  private static void writeLittleEndian32(ByteArrayOutputStream out, long value) {
    byte[] bytes = new byte[4];
    Binary.put32le(bytes, 0, value);
    out.writeBytes(bytes);
  }

}
