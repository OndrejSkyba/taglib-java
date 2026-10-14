// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.id3.v1.Genres;
import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.mp4.ItemFactory.ItemHandlerType;
import cz.skyba.taglib.mp4.ItemFactory.ParsedItem;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Atom payload parsing and rendering shared by the extensible item factory.
 */
final class ItemAtomCodec {
  private static final ByteVector DATA_NAME = new ByteVector("data");
  private static final ByteVector PNG = new ByteVector(new byte[]{(byte) 137, 'P', 'N', 'G', 13, 10, 26, 10});

  private ItemAtomCodec() {
  }

  private static String latin(ByteVector value) {
    return new TagString(value).toString();
  }

  private static ByteVector slice(ByteVector bytes, long at, long size) {
    if (at < 0 || at >= bytes.size()) {
      return new ByteVector();
    }
    if (size < 0) {
      size &= 0xffff_ffffL;
    }
    return bytes.mid((int) at, (int) Math.min(size, bytes.size() - at));
  }

  static List<AtomData> parseData2(ByteVector data) {
    return parseData2(data, -1, false);
  }

  static List<AtomData> parseData2(ByteVector data, int expectedFlags) {
    return parseData2(data, expectedFlags, false);
  }

  static List<AtomData> parseData2(ByteVector data, int expectedFlags, boolean freeForm) {
    var result = new ArrayList<AtomData>();
    long pos = 0;
    int i = 0;
    while (pos < data.size()) {
      int length = (int) data.toUnsignedInt((int) pos, true);
      if (length < 12) {
        return result;
      }
      ByteVector name = slice(data, pos + 4, 4);
      int flags = (int) data.toUnsignedInt((int) (pos + 8), true);
      if (freeForm && i < 2) {
        if (!name.equals(new ByteVector(i == 0 ? "mean" : "name"))) {
          return result;
        }
        result.add(new AtomData(AtomDataType.fromFlags(flags), slice(data, pos + 12, length - 12)));
      } else {
        if (!name.equals(DATA_NAME)) {
          return result;
        }
        if (expectedFlags == -1 || flags == expectedFlags) {
          result.add(new AtomData(AtomDataType.fromFlags(flags), slice(data, pos + 16, length - 16)));
        }
      }
      pos += length;
      i++;
    }
    return result;
  }

  static List<ByteVector> parseData(ByteVector data) {
    return parseData(data, -1, false);
  }

  static List<ByteVector> parseData(ByteVector data, int flags) {
    return parseData(data, flags, false);
  }

  static List<ByteVector> parseData(ByteVector data, int flags, boolean freeForm) {
    return parseData2(data, flags, freeForm).stream()
      .map(d -> new ByteVector(d.data)).collect(Collectors.toCollection(ArrayList::new));
  }

  private static ParsedItem scalar(Atom atom, ByteVector data, ItemHandlerType type) {
    var values = parseData(data);
    Item item = new Item();
    if (!values.isEmpty()) {
      ByteVector value = values.getFirst();
      item = switch (type) {
        case Int -> new Item(value.toShort());
        case UInt -> Item.fromUnsignedInt(value.toUnsignedInt());
        case LongLong -> new Item(value.toLong());
        case Byte -> Item.fromByte(value.at(0));
        case Bool -> new Item(!value.isEmpty() && value.at(0) != 0);
        case IntPair -> new Item(value.toShort(2), value.toShort(4));
        default -> throw new AssertionError(type);
      };
    }
    return new ParsedItem(latin(atom.name()), item);
  }

  static ParsedItem parseInt(Atom a, ByteVector b) {
    return scalar(a, b, ItemHandlerType.Int);
  }

  static ParsedItem parseUInt(Atom a, ByteVector b) {
    return scalar(a, b, ItemHandlerType.UInt);
  }

  static ParsedItem parseLongLong(Atom a, ByteVector b) {
    return scalar(a, b, ItemHandlerType.LongLong);
  }

  static ParsedItem parseByte(Atom a, ByteVector b) {
    return scalar(a, b, ItemHandlerType.Byte);
  }

  static ParsedItem parseBool(Atom a, ByteVector b) {
    return scalar(a, b, ItemHandlerType.Bool);
  }

  static ParsedItem parseIntPair(Atom a, ByteVector b) {
    return scalar(a, b, ItemHandlerType.IntPair);
  }

  static ParsedItem parseTextOrInt(Atom atom, ByteVector bytes) {
    var data = parseData2(bytes);
    if (data.isEmpty()) {
      return new ParsedItem(latin(atom.name()), new Item());
    }
    var value = data.getFirst();
    return new ParsedItem(latin(atom.name()), value.type.equals(AtomDataType.TypeUTF8)
      ? Item.textValues(List.of(new TagString(value.data, TagString.Type.UTF8).toString())) : new Item(value.data.toShort()));
  }

  static ParsedItem parseText(Atom atom, ByteVector bytes) {
    return parseText(atom, bytes, 1);
  }

  static ParsedItem parseText(Atom atom, ByteVector bytes, int flags) {
    var data = parseData(bytes, flags);
    return new ParsedItem(latin(atom.name()), data.isEmpty() ? new Item()
      : Item.textValues(data.stream()
      .map(value -> new TagString(value, TagString.Type.UTF8).toString()).toList()));
  }

  static ParsedItem parseGnre(ByteVector bytes) {
    var data = parseData(bytes);
    int index = data.isEmpty() ? 0 : data.getFirst().toShort();
    return new ParsedItem("©gen",
      index > 0 ? Item.textValues(List.of(Genres.genre(index - 1))) : new Item());
  }

  static ParsedItem parseFreeForm(Atom atom, ByteVector bytes) {
    var data = parseData2(bytes, -1, true);
    if (data.size() <= 2) {
      return new ParsedItem(latin(atom.name()), new Item());
    }
    String name =
      "----:" + new TagString(data.get(0).data, TagString.Type.UTF8) + ":" + new TagString(data.get(1).data, TagString.Type.UTF8);
    AtomDataType type = data.get(2).type;
    var values = data.subList(2, data.size()).stream().map(d -> d.data).toList();
    Item item = type.equals(AtomDataType.TypeUTF8)
      ? Item.textValues(values.stream()
      .map(value -> new TagString(value, TagString.Type.UTF8).toString()).toList())
      : new Item(values);
    item.setAtomDataType(type);
    return new ParsedItem(name, item);
  }

  private static CoverArt.Format imageFormat(ByteVector value) {
    if (value.size() >= 2 && (value.at(0) & 255) == 255 && (value.at(1) & 255) == 216) {
      return CoverArt.Format.JPEG;
    }
    if (value.size() >= 8 && value.startsWith(PNG)) {
      return CoverArt.Format.PNG;
    }
    if (value.size() >= 6 && value.startsWith(new ByteVector("GIF8"))) {
      return CoverArt.Format.GIF;
    }
    if (value.size() >= 14 && value.startsWith(new ByteVector("BM")) && value.toUnsignedInt(6, false) == 0) {
      return CoverArt.Format.BMP;
    }
    return CoverArt.Format.Unknown;
  }

  private static CoverArt.Format coverArtFormat(int flags, ByteVector payload) {
    return switch (flags) {
      case 13 -> CoverArt.Format.JPEG;
      case 14 -> CoverArt.Format.PNG;
      case 27 -> CoverArt.Format.BMP;
      case 12 -> CoverArt.Format.GIF;
      case 0 -> CoverArt.Format.Unknown;
      default -> imageFormat(payload);
    };
  }

  static ParsedItem parseCovr(Atom atom, ByteVector bytes) {
    var covers = new ArrayList<CoverArt>();
    long pos = 0;
    while (pos < bytes.size()) {
      int length = (int) bytes.toUnsignedInt((int) pos, true);
      if (length < 12 || !bytes.containsAt(DATA_NAME, (int) (pos + 4))) {
        break;
      }
      int flags = (int) bytes.toUnsignedInt((int) (pos + 8), true);
      ByteVector payload = slice(bytes, pos + 16, length - 16);
      CoverArt.Format format = coverArtFormat(flags, payload);
      covers.add(new CoverArt(format, payload));
      pos += length;
    }
    return new ParsedItem(latin(atom.name()), covers.isEmpty() ? new Item() : Item.coverArt(covers));
  }

  static ParsedItem parseStem(Atom atom, ByteVector bytes) {
    return new ParsedItem(latin(atom.name()), new Item(new Stem(bytes)));
  }

  static ByteVector renderAtom(ByteVector name, ByteVector data) {
    if (data.size() > Integer.MAX_VALUE - 8 - name.size()) {
      throw new IllegalArgumentException("MP4 rendered atom exceeds Java array bound");
    }
    return new ByteVectorOutput().number(data.size() + 8L, 4, true).append(name).append(data).toByteVector();
  }

  private static void appendData(ByteVectorOutput output, int flags, ByteVector value) {
    if (value.size() > Integer.MAX_VALUE - 20) {
      throw new IllegalArgumentException("MP4 rendered atom exceeds Java array bound");
    }
    output.number(value.size() + 16L, 4, true).append(DATA_NAME).number(flags, 4, true).zeros(4).append(value);
  }

  static ByteVector renderData(ByteVector name, int flags, List<ByteVector> values) {
    var data = new ByteVectorOutput();
    for (ByteVector value : values) {
      appendData(data, flags, value);
    }
    return renderAtom(name, data.toByteVector());
  }

  private static ByteVector number(ByteVector name, ByteVector data, int flags) {
    var output = new ByteVectorOutput();
    appendData(output, flags, data);
    return renderAtom(name, output.toByteVector());
  }

  static ByteVector renderBool(ByteVector name, Item item) {
    return number(name, new ByteVector(1, (byte) (item.toBoolean() ? 1 : 0)), 21);
  }

  static ByteVector renderInt(ByteVector name, Item item) {
    return number(name, ByteVector.fromShort((short) item.toInt()), 21);
  }

  static ByteVector renderUInt(ByteVector name, Item item) {
    return number(name, ByteVector.fromUnsignedInt(item.toUnsignedInt()), 21);
  }

  static ByteVector renderLongLong(ByteVector name, Item item) {
    return number(name, ByteVector.fromLong(item.toLong()), 21);
  }

  static ByteVector renderByte(ByteVector name, Item item) {
    return number(name, new ByteVector(1, (byte) item.toByte()), 21);
  }

  static ByteVector renderIntPair(ByteVector name, Item item) {
    return number(name, pair(item).append(new ByteVector(2, (byte) 0)), 0);
  }

  static ByteVector renderIntPairNoTrailing(ByteVector name, Item item) {
    return number(name, pair(item), 0);
  }

  private static ByteVector pair(Item item) {
    return new ByteVector(2, (byte) 0).append(ByteVector.fromShort((short) item.toIntPair().first()))
      .append(ByteVector.fromShort((short) item.toIntPair().second()));
  }

  static ByteVector renderTextOrInt(ByteVector name, Item item) {
    return item.textValues().isEmpty() ? renderInt(name, item) : renderText(name, item);
  }

  static ByteVector renderText(ByteVector name, Item item) {
    return renderText(name, item, 1);
  }

  static ByteVector renderText(ByteVector name, Item item, int flags) {
    var data = new ByteVectorOutput();
    for (String value : item.textValues()) appendData(data, flags, new TagString(value).data(TagString.Type.UTF8));
    return renderAtom(name, data.toByteVector());
  }

  static ByteVector renderCovr(ByteVector name, Item item) {
    var data = new ByteVectorOutput();
    for (CoverArt cover : item.coverValues()) {
      appendData(data, cover.format().value(), cover.data());
    }
    return renderAtom(name, data.toByteVector());
  }

  static ByteVector renderStem(ByteVector name, Item item) {
    return renderAtom(name, item.toStem().byteVector());
  }

  static ByteVector renderFreeForm(String name, Item item) {
    String[] parts = name.split(":", -1);
    if (parts.length != 3) {
      return new ByteVector();
    }
    var data = new ByteVectorOutput().append(renderAtom(new ByteVector("mean"), ByteVector.fromUnsignedInt(0).append(new TagString(parts[1]).data(TagString.Type.UTF8))));
    data.append(renderAtom(new ByteVector("name"), ByteVector.fromUnsignedInt(0).append(new TagString(parts[2]).data(TagString.Type.UTF8))));
    AtomDataType type = item.atomDataType();
    if (type.equals(AtomDataType.TypeUndefined)) {
      type = item.textValues().isEmpty() ? AtomDataType.TypeImplicit : AtomDataType.TypeUTF8;
    }
    if (type.equals(AtomDataType.TypeUTF8)) {
      for (String text : item.textValues())
        appendData(data, type.value(), new TagString(text).data(TagString.Type.UTF8));
    } else {
      for (ByteVector value : item.byteValues()) appendData(data, type.value(), value);
    }
    return renderAtom(new ByteVector("----"), data.toByteVector());
  }
}
