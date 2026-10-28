// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.xiph;

import cz.skyba.taglib.Picture;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.internal.TextBytes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

/// Xiph comments, including the FLAC picture representation used by Vorbis and Opus.
public record XiphComment(String vendor, PropertyMap properties, List<Picture> pictures) {
  public XiphComment {
    pictures = List.copyOf(pictures);
  }

  public static XiphComment empty() {
    return new XiphComment("", PropertyMap.builder().build(), List.of());
  }

  public static byte[] withoutPictureWhitespace(byte[] data) {
    int size = 0;
    for (byte value : data) {
      if (!pictureWhitespace(value)) {
        size++;
      }
    }
    if (size == data.length) {
      return data;
    }
    byte[] compact = new byte[size];
    int position = 0;
    for (byte value : data) {
      if (!pictureWhitespace(value)) {
        compact[position++] = value;
      }
    }
    return compact;
  }

  private static boolean pictureWhitespace(byte value) {
    return value == ' ' || value == '\t' || value == '\r' || value == '\n';
  }

  public static boolean validKey(String key) {
    return !key.isEmpty() && key.chars().allMatch(c -> c >= 0x20 && c <= 0x7d && c != '=');
  }

  public static PropertyMap unsupportedProperties(PropertyMap properties) {
    var rejected = PropertyMap.builder();
    properties.asMap().forEach((key, values) -> {
      if (!validKey(key)) {
        rejected.put(key, values);
      }
    });
    return rejected.build();
  }

  /// Matches upstream's tolerant parsing: return fields preceding a truncated field.
  public static XiphComment parse(byte[] bytes) {
    var fields = PropertyMap.builder();
    var pictures = new ArrayList<Picture>();
    if (bytes.length < 8) {
      return empty();
    }
    long vendorLength = Binary.u32le(bytes, 0);
    if (vendorLength > bytes.length - 4) {
      return empty();
    }
    int pos = 4 + (int) vendorLength;
    String vendor = utf8(bytes, 4, (int) vendorLength);
    if (bytes.length - pos < 4) {
      return new XiphComment(vendor, fields.build(), pictures);
    }
    long count = Binary.u32le(bytes, pos);
    pos += 4;
    if (count > 50_000 || count > (bytes.length - 8) / 4) {
      return new XiphComment(vendor, fields.build(), pictures);
    }
    for (int i = 0; i < count && pos <= bytes.length - 4; i++) {
      long length = Binary.u32le(bytes, pos);
      pos += 4;
      if (length > bytes.length - pos) {
        break;
      }
      int sep = 0;
      while (sep < length && bytes[pos + sep] != '=') {
        sep++;
      }
      String key = utf8(bytes, pos, sep).toUpperCase(Locale.ROOT);
      String value = sep < length ? utf8(bytes, pos + sep + 1, (int) length - sep - 1) : "";
      pos += (int) length;
      if (sep == length) {
        continue;
      }
      if (!validKey(key)) {
        continue;
      }
      if (key.equals("METADATA_BLOCK_PICTURE") || key.equals("COVERART")) {
        try {
          byte[] data = Base64.getDecoder().decode(withoutPictureWhitespace(value.getBytes(UTF_8)));
          if (data.length == 0) {
            continue;
          }
          pictures.add(
            key.equals("COVERART") ? new Picture(0, "image/", "", data) : parsePicture(data));
        } catch (IllegalArgumentException | IOException ignored) {
          // TagLib discards malformed pictures while retaining valid text fields.
        }
      } else if (!value.isEmpty()) {
        fields.add(key, value);
      }
    }
    return new XiphComment(vendor, fields.build(), pictures);
  }

  public byte[] render(PropertyMap replacement, boolean framingBit) throws IOException {
    return render(replacement, framingBit, null);
  }

  /// A null picture replacement preserves artwork; an empty list removes it.
  public byte[] render(
    PropertyMap replacement, boolean framingBit, List<Picture> replacementPictures)
    throws IOException {
    var entries = new ArrayList<byte[]>();
    for (var entry : replacement.asMap().entrySet()) {
      if (!validKey(entry.getKey())) {
        throw new IOException("Invalid Xiph property key: " + entry.getKey());
      }
      if (replacementPictures != null
        && (entry.getKey().equalsIgnoreCase("METADATA_BLOCK_PICTURE")
        || entry.getKey().equalsIgnoreCase("COVERART")
        || entry.getKey().equalsIgnoreCase("COVERARTMIME"))) {
        continue;
      }
      for (String value : entry.getValue()) {
        if (!value.isEmpty()) {
          var field = new ByteArrayOutputStream();
          field.writeBytes(utf8(entry.getKey().toUpperCase(Locale.ROOT)));
          field.write('=');
          field.writeBytes(utf8(value));
          entries.add(field.toByteArray());
        }
      }
    }
    for (Picture picture : replacementPictures == null ? pictures : replacementPictures) {
      entries.add(
        utf8("METADATA_BLOCK_PICTURE=" + Base64.getEncoder().encodeToString(renderPicture(picture))));
    }
    if (entries.size() > 50_000) {
      throw new IOException("Too many Xiph comment fields");
    }
    var out = new ByteArrayOutputStream();
    writeString(out, vendor);
    write32(out, entries.size());
    for (byte[] entry : entries) {
      write32(out, entry.length);
      out.writeBytes(entry);
    }
    if (framingBit) {
      out.write(1);
    }
    return out.toByteArray();
  }

  public static Picture parsePicture(byte[] data) throws IOException {
    if (data.length < 32) {
      throw new IOException("Truncated FLAC picture");
    }
    int type = (int) Binary.u32be(data, 0);
    int pos = 8;
    long mimeSize = Binary.u32be(data, 4);
    if (mimeSize > data.length - pos - 24) {
      throw new IOException("Truncated picture MIME type");
    }
    String mime = utf8(data, pos, (int) mimeSize);
    pos += (int) mimeSize;
    long descriptionSize = Binary.u32be(data, pos);
    pos += 4;
    if (descriptionSize > data.length - pos - 20) {
      throw new IOException("Truncated picture description");
    }
    String description = utf8(data, pos, (int) descriptionSize);
    pos += (int) descriptionSize;
    int width = (int) Binary.u32be(data, pos);
    int height = (int) Binary.u32be(data, pos + 4);
    int depth = (int) Binary.u32be(data, pos + 8);
    int colors = (int) Binary.u32be(data, pos + 12);
    long size = Binary.u32be(data, pos + 16);
    pos += 20;
    if (size > data.length - pos) {
      throw new IOException("Truncated picture data");
    }
    return new Picture(
      type >= 0 && type <= 20 ? type : 0,
      mime,
      description,
      Arrays.copyOfRange(data, pos, pos + (int) size),
      width,
      height,
      depth,
      colors);
  }

  public static byte[] renderPicture(Picture picture) {
    return renderPicture(picture.type(), picture.mimeType(), picture.description(), picture.data(),
      picture.width(), picture.height(), picture.colorDepth(), picture.indexedColors());
  }

  public static byte[] renderPicture(int type, String mime, String description, byte[] data,
                                     int width, int height, int depth, int colors) {
    var out = new ByteVectorOutput();
    byte[] mimeBytes = utf8(mime), descriptionBytes = utf8(description);
    out.number(type, 4, true).number(mimeBytes.length, 4, true).append(mimeBytes)
      .number(descriptionBytes.length, 4, true).append(descriptionBytes)
      .number(width, 4, true).number(height, 4, true).number(depth, 4, true).number(colors, 4, true)
      .number(data.length, 4, true).append(data);
    return out.toByteArray();
  }

  private static void writeString(ByteArrayOutputStream out, String value) {
    byte[] data = utf8(value);
    write32(out, data.length);
    out.writeBytes(data);
  }

  private static String utf8(byte[] data, int offset, int length) {
    return TextBytes.decodeTerminated(data, offset, length, UTF_8);
  }

  private static byte[] utf8(String value) {
    return TextBytes.encode(value, UTF_8);
  }

  private static void write32(ByteArrayOutputStream out, int value) {
    byte[] data = new byte[4];
    Binary.put32le(data, 0, value);
    out.writeBytes(data);
  }
}
