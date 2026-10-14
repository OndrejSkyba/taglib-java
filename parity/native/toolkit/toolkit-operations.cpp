#include <iostream>
#include <fstream>
#include <limits>
#include <tbytevector.h>
#include <tbytevectorlist.h>
#include <tbytevectorstream.h>
#include <tfilestream.h>
#include <tvariant.h>
#include <tversionnumber.h>
#include <tpropertymap.h>
using namespace TagLib;

static void streamCases(IOStream &s, const char *label) {
  auto report = [&](int step) {
    auto pos = s.tell();
    s.seek(0);
    auto bytes = s.readBlock(s.length());
    s.seek(pos);
    std::cout << label << step << '=' << pos << ':' << s.length() << ':' << bytes.toHex() << '\n';
  };
  report(0);
  s.insert("xx", 1, 1);
  report(1);
  s.insert("y", 1, 2);
  report(2);
  s.removeBlock(1, 1);
  report(3);
  s.seek(7);
  s.writeBlock("!");
  report(4);
  s.truncate(2);
  report(5);
  s.seek(-1, IOStream::End);
  report(6);
}

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  for (unsigned int offset = 0; offset <= 3; offset++)
    for (unsigned int length = 0; length <= 3 - offset; length++)
      std::cout << "slice=" << offset << ',' << length << ':' << ByteVector(ByteVector("abc"), offset, length).toHex()
          << '\n';
  ByteVectorList defaultList{ByteVector("a"), ByteVector(), ByteVector("b")};
  std::cout << "default-join=" << defaultList.toByteVector().toHex() << '\n';
  auto defaultSplit = String(" a  b ").split();
  std::cout << "default-split=" << defaultSplit.size() << ':' << defaultSplit.toString("|") << '\n';
  auto aligned = ByteVectorList::split(ByteVector("a::b:"), ByteVector(":"), 1);
  std::cout << "default-max=" << aligned.size() << ':' << aligned.toByteVector().toHex() << '\n';
  const char *texts[] = {"", "a", "abcab", "OggS*OggS*OggS", "....SggO."};
  const char *patterns[] = {"", "a", "ab", "OggS", "SggO"};
  for (int t = 0; t < 5; t++)
    for (int p = 0; p < 5; p++)
      for (unsigned int off = 0; off < ByteVector(texts[t]).size() + 3; off++)
        for (int align = 0; align < 4; align++) {
          ByteVector v(texts[t]), pat(patterns[p]);
          std::cout << "search=" << t << ',' << p << ',' << off << ',' << align << ':' << v.find(pat, off, align) << ':'
              << v.rfind(pat, off, align) << '\n';
        }
  ByteVector numbers("\x00\xff\x80\x01\xfe\x7f\x81\x11", 8);
  for (unsigned off = 0; off < 10; off++)
    for (unsigned width = 0; width < 6; width++)
      for (bool big: {false, true})
        std::cout << "number=" << off << ',' << width << ',' << big << ':' << numbers.toUInt(off, width, big) << '\n';
  for (const char *value: {"aA==", "aA=X", "aA==x", "aGVsbG8=", "aGVsbG8", "", "!!!!", "YWJj", "YWJj\n"})
    std::cout << "base64=" << ByteVector(value).toHex() << ':' << ByteVector::fromBase64(value).toHex() << '\n';
  for (int max = 0; max < 6; max++) {
    auto split = ByteVectorList::split("a::b:", ":", 1, max);
    std::cout << "split=" << max << ':' << split.size() << ':' << split.toByteVector("|").toHex() << '\n';
  }
  for (unsigned replace: {2, 3}) {
    ByteVectorStream beyond("abc");
    beyond.insert("XY", 5, replace);
    std::cout << "memory-beyond=" << beyond.data()->toHex() << ':' << beyond.tell() << '\n';
  }
  ByteVectorStream memory("abcd");
  streamCases(memory, "memory");
  {
    std::ofstream out(argv[1], std::ios::binary);
    out << "abcd";
  }
  {
    FileStream file(argv[1]);
    streamCases(file, "file");
  }
  VariantList values{
    Variant(), Variant(true), Variant(-4), Variant(5U), Variant(-6LL), Variant(7ULL), Variant(1.23), Variant("te\"st"),
    Variant(StringList{"a", "b"}), Variant(ByteVector("\x01\0\xff", 3)), Variant(ByteVectorList{"ab", "cd"}),
    Variant(VariantList{Variant("nested"), Variant(5)}), Variant(VariantMap{{"b", Variant(true)}, {"a", Variant(1)}})
  };
  for (const auto &v: values) {
    bool ok = false;
    int n = v.toInt(&ok);
    std::cout << "variant=" << v.type() << ':' << v.isEmpty() << ':' << n << ':' << ok << ':' << v << '\n';
  }
  for (double d: {
         0.0, -0.0, 1.2345678, 1000000.0, 0.00000123, 123.450,
         1.234565, 2.345675, 0.00001234565, -1.234565, 1.234555, 999999.5,
         std::numeric_limits<double>::quiet_NaN(), -std::numeric_limits<double>::quiet_NaN(),
         std::numeric_limits<double>::infinity(), -std::numeric_limits<double>::infinity(),
         std::numeric_limits<double>::denorm_min(), std::numeric_limits<double>::max()
       })
    std::cout << "double=" << Variant(d) << '\n';
  PropertyMap props;
  props.insert("title", StringList{"one", "two"});
  props.insert("empty", StringList{});
  props.addUnsupportedData("raw");
  std::cout << "property_text=" << props.toString().data(String::UTF8).toHex() << '\n';
  ByteVector encodings[] = {
    ByteVector("abc"), ByteVector("a\0b", 3), ByteVector("\xc0\x80", 2), ByteVector("\xff\xfe\x41\0", 4),
    ByteVector("\xfe\xff\0\x41", 4), ByteVector("\xc3\xa9\xf0\x9f\x98\x80", 6), ByteVector("\0\xd8", 2)
  };
  for (int i = 0; i < 7; i++)
    for (int type = 0; type < 5; type++) {
      String text(encodings[i], static_cast<String::Type>(type));
      std::cout << "string=" << i << ',' << type << ':' << text.size() << ':' << text.data(String::UTF8).toHex() << ':'
          << text.data(String::UTF16).toHex() << '\n';
    }
  for (const char *input: {
         "", "0", "-1", " +12", "12 ", "12foo", "2147483647", "2147483648", "-2147483648", "9223372036854775808",
         "-9223372036854775809", "18446744073709551615", "-18446744073709551615", "18446744073709551616", "010", "0xff",
         "0x", "0b10", "xyz"
       }) {
    String text(input);
    bool ok = false;
    int iv = text.toInt(&ok);
    std::cout << "integer=" << input << ':' << iv << ':' << ok;
    for (int base: {0, 10, 16, 36}) {
      auto n = text.toLongLong(&ok);
      if (base != 10)n = text.toLongLong(&ok, base);
      std::cout << ':' << n << ':' << ok;
      auto u = text.toULongLong(&ok, base);
      std::cout << ':' << u << ':' << ok;
    }
    std::cout << '\n';
  }
  std::cout << "version=" << VersionNumber(257, 258, 259).toString() << ':' << VersionNumber(257, 258, 259).
      combinedVersion() << '\n';
}
