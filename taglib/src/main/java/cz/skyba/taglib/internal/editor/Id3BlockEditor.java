// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.editor;

import cz.skyba.taglib.MetadataEditException;
import cz.skyba.taglib.Picture;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.editor.AbstractDocument.EditAction;
import cz.skyba.taglib.internal.editor.AbstractDocument.Removal;
import cz.skyba.taglib.internal.id3.Id3v2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class Id3BlockEditor {
  private static final Set<String> STRUCTURED_FRAME_IDS = Set.of(
    "UFID", "POPM", "PCNT", "SYLT", "RVA2", "ETCO", "CHAP", "CTOC",
    "PCST", "RBUF", "MCDI", "OWNE", "SYTC", "RVRB", "AENC", "LINK",
    "MLLT", "SIGN", "ENCR", "GRID", "ASPI", "SEEK", "EQU2");

  private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;
  private final AbstractDocument document;
  private final long fileSize;

  private static final class FrameSlot {
    Id3v2.Frame frame;
    boolean removed;

    FrameSlot(Id3v2.Frame f) {
      frame = f;
    }
  }

  private final List<FrameSlot> frames = new ArrayList<>();
  final int version, rank, originalBodySize;
  final Runnable changed;
  final boolean projected;

  Id3BlockEditor(AbstractDocument document, long fileSize, byte[] original, Runnable changed, int rank, boolean projected) throws IOException {
    this.document = document;
    this.fileSize = fileSize;
    var parsed = Id3v2.parseForEditing(original);
    version = parsed.version();
    originalBodySize = Id3v2.originalBodySize(original);
    this.changed = changed;
    this.rank = rank;
    this.projected = projected;
    if (version == 2) {
      document.readOnlyReason =
        "This file uses ID3v2.2. Editing requires tag-version conversion, which this editor"
          + " does not perform.";
    }
    for (var frame : parsed.frames()) {
      var slot = new FrameSlot(frame);
      frames.add(slot);
      inspect(slot);
    }
  }

  private void inspect(FrameSlot slot) {
    var frame = slot.frame;
    String id = frame.id();
    byte[] data = frame.data();
    Removal remove =
      () -> {
        slot.removed = true;
        changed.run();
      };
    boolean removalRequired = (frame.flags() & (version == 3 ? 0x8000 : 0x4000)) != 0;
    if (id.equals("APIC") && data != null) {
      Picture pic = Id3v2.picture(data, version);
      if (pic != null) {
        document.picture(
            "ID3v2",
            id,
            pic,
            pic.description() + " (picture type " + pic.type() + ")",
            remove)
          .requiresRemoval(removalRequired)
          .projected(false);
        return;
      }
    }
    String key = id, standard = "", details = "";
    List<String> values = List.of();
    boolean rename = false, multiple = false;
    EditAction edit = null;
    if (data != null
      && (id.startsWith("T") || id.equals("GRP1") || id.equals("MVNM") || id.equals("MVIN"))) {
      values = Id3v2.textFields(data);
      multiple = true;
      if (id.equals("TXXX")) {
        key = values.isEmpty() ? "" : values.getFirst();
        values = values.isEmpty() ? List.of() : values.subList(1, values.size());
        rename = true;
        details = id;
      } else {
        var properties = Id3v2.properties(frame);
        standard =
          id.equals("TIT1")
            ? "GROUPING"
            : properties.size() == 1 ? properties.keySet().iterator().next() : "";
      }
      edit =
        (name, next) -> {
          var fields = new ArrayList<String>();
          if (id.equals("TXXX")) {
            ensureCustom(name, slot, "TXXX");
            fields.add(name);
          }
          fields.addAll(next);
          replace(slot, Id3v2.textData(fields, version));
        };
    } else if (data != null && (id.equals("COMM") || id.equals("USLT")) && data.length >= 4) {
      int enc = data[0] & 255,
        end = Id3v2.terminator(data, 4, enc),
        step = Id3v2.delimiterSize(enc);
      if (end + step <= data.length) {
        String description = Id3v2.decode(data, 4, end, enc, null),
          language = new String(data, 1, 3, LATIN1);
        standard = id.equals("COMM") ? "COMMENT" : "LYRICS";
        values =
          List.of(
            Id3v2.decode(data, end + step, data.length, enc, Id3v2.charset(data, 4, enc)));
        details = description + " / " + language;
        edit =
          (_, next) -> {
            EditorText.single(next);
            byte[] text = Id3v2.textData(List.of(description, next.getFirst()), version);
            byte[] payload = new byte[text.length + 3];
            payload[0] = text[0];
            System.arraycopy(data, 1, payload, 1, 3);
            System.arraycopy(text, 1, payload, 4, text.length - 1);
            replace(slot, payload);
          };
      }
    } else if (data != null && id.startsWith("W")) {
      standard = id.equals("WXXX") ? "" : "URL";
      details = id;
      if (id.equals("WXXX") && data.length > 0) {
        int enc = data[0] & 255, end = Id3v2.terminator(data, 1, enc);
        key = Id3v2.decode(data, 1, end, enc, null);
        int start = Math.min(data.length, end + Id3v2.delimiterSize(enc));
        values = List.of(new String(data, start, data.length - start, LATIN1));
        rename = true;
      } else {
        values = List.of(new String(data, LATIN1));
      }
      edit =
        (name, next) -> {
          EditorText.single(next);
          if (next.getFirst().chars().anyMatch(c -> c > 255)) {
            throw new MetadataEditException("URL requires Latin-1 text");
          }
          byte[] payload;
          if (id.equals("WXXX")) {
            ensureCustom(name, slot, "WXXX");
            var out = new ByteArrayOutputStream();
            byte[] desc = Id3v2.textData(List.of(name, ""), version);
            out.write(desc, 0, desc.length - (version == 4 ? 0 : 2));
            out.writeBytes(next.getFirst().getBytes(LATIN1));
            payload = out.toByteArray();
          } else {
            payload = next.getFirst().getBytes(LATIN1);
          }
          replace(slot, payload);
        };
    }
    String kind =
      edit != null
        ? "text"
        : STRUCTURED_FRAME_IDS
        .contains(id)
        && data != null
        ? "structured"
        : "binary";
    if (edit == null) {
      details = frame.raw().length + " bytes";
    }
    if (removalRequired) {
      edit = null;
      rename = false;
      details = "ID3 flags require this field to be removed before saving other changes.";
    }
    boolean project = projected && (id.startsWith("T") || id.equals("COMM") || id.equals("GRP1"));
    // Described comments are distinct properties; they never override the main COMMENT
    // projection.
    if (id.equals("COMM")
      && !Id3v2.commentDescription(frame).isEmpty()
      && !Id3v2.commentDescription(frame).equalsIgnoreCase("COMMENT")) {
      project = false;
    }
    document.addEntry("ID3v2", key, standard, kind, values, edit, remove, rename, details)
      .multipleValues(multiple)
      .requiresRemoval(removalRequired)
      .projected(project)
      .precedence(rank + (id.equals("TIT1") ? 2 : id.equals("TXXX") ? 0 : 1));
  }

  void replace(FrameSlot slot, byte[] payload) {
    int flags = slot.frame.flags() & (version == 3 ? 0xe000 : 0x7000);
    slot.frame = new Id3v2.Frame(slot.frame.id(), flags, payload, payload);
    changed.run();
  }

  void ensureCustom(String name, FrameSlot except, String type) {
    for (var slot : frames) {
      if (!slot.removed
        && slot != except
        && slot.frame.id().equals(type)
        && slot.frame.data() != null) {
        String existing;
        if (type.equals("TXXX")) {
          var fields = Id3v2.textFields(slot.frame.data());
          existing = fields.isEmpty() ? "" : fields.getFirst();
        } else {
          byte[] d = slot.frame.data();
          int enc = d.length == 0 ? 0 : d[0] & 255;
          existing =
            Id3v2.decode(
              d,
              Math.min(1, d.length),
              Id3v2.terminator(d, Math.min(1, d.length), enc),
              enc,
              null);
        }
        if (existing.equals(name)) {
          throw new MetadataEditException("A custom field with this name already exists");
        }
      }
    }
  }

  void add(String name, List<String> values, boolean custom) throws IOException {
    if (custom) {
      ensureCustom(name, null, "TXXX");
      var fields = new ArrayList<String>();
      fields.add(name);
      fields.addAll(values);
      byte[] d = Id3v2.textData(fields, version);
      frames.add(new FrameSlot(new Id3v2.Frame("TXXX", 0, d, d)));
    } else {
      String normalized = name.equals("GROUPING") ? "WORK" : name;
      for (var slot : frames) {
        if (!slot.removed && Id3v2.properties(slot.frame).containsKey(normalized)) {
          throw new MetadataEditException("Tag already exists; edit its values instead");
        }
      }
      byte[] empty = new byte[10];
      System.arraycopy(Binary.bytes("ID3"), 0, empty, 0, 3);
      empty[3] = (byte) version;
      for (var frame :
        Id3v2.parseForEditing(
            Id3v2.render(PropertyMap.builder().put(normalized, values).build(), empty))
          .frames()) {
        frames.add(new FrameSlot(frame));
      }
    }
    changed.run();
  }

  void pictures(List<Picture> pictures) throws IOException {
    for (var slot : frames) {
      if (slot.frame.id().equals("APIC")) {
        slot.removed = true;
      }
    }
    byte[] empty = new byte[10];
    System.arraycopy(Binary.bytes("ID3"), 0, empty, 0, 3);
    empty[3] = (byte) version;
    for (var f : Id3v2.parseForEditing(Id3v2.render(PropertyMap.EMPTY, empty, pictures)).frames()) {
      frames.add(new FrameSlot(f));
    }
    changed.run();
  }

  byte[] render() throws IOException {
    var out = new ByteArrayOutputStream();
    for (var slot : frames) {
      if (!slot.removed) {
        var f = slot.frame;
        boolean compressed = (f.flags() & (version == 3 ? 0x80 : 8)) != 0 && f.data() != null;
        Id3v2.writeFrame(
          out,
          version,
          Id3v2.frameId(f.id(), version),
          compressed ? f.flags() & (version == 3 ? 0xe000 : 0x7000) : f.flags(),
          compressed ? f.data() : f.raw());
      }
    }
    return out.size() == 0
      ? new byte[0]
      : Id3v2.renderTag(version, out, originalBodySize, fileSize);
  }

  void checkArtworkReplacement() {
    for (var slot : frames) {
      if (!slot.removed && (slot.frame.flags() & (version == 3 ? 0x8000 : 0x4000)) != 0) {
        throw new MetadataEditException("Remove metadata marked for removal before replacing artwork");
      }
    }
  }

}
