// Constructor-owned physical ID3 setter contracts against pinned TagLib.
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <fstream>
#include <iterator>
#include "fileref.h"
#include "tag.h"
#include "mpeg/id3v2/id3v2header.h"
#include "mpeg/id3v2/id3v2tag.h"
#include "tpropertymap.h"
#include "tvariant.h"

static std::string hex(const TagLib::String &value) {
  std::ostringstream out;
  for (unsigned char byte: value.data(TagLib::String::UTF8))
    out << std::hex << std::setw(2) << std::setfill('0') << unsigned(byte);
  return out.str();
}

static void properties(const TagLib::Tag &tag, const TagLib::PropertyMap &values) {
  std::cout << "TITLE\t" << hex(tag.title()) << '\n';
  std::cout << "COMMENT\t" << hex(tag.comment()) << '\n';
  std::cout << "GENRE\t" << hex(tag.genre()) << '\n';
  for (const auto &[key, list]: values) {
    std::cout << "PROP\t" << hex(key);
    for (const auto &value: list) std::cout << '\t' << hex(value);
    std::cout << '\n';
  }
  for (const auto &id: values.unsupportedData()) std::cout << "UNSUPPORTED\t" << hex(id) << '\n';
}

static void properties(TagLib::FileRef &file) { properties(*file.tag(), file.properties()); }

int main(int argc, char **argv) {
  if (argc == 3 && std::string(argv[1]) == "selected") {
    TagLib::FileRef file(argv[2], false);
    if (file.isNull())return 3;
    std::cout << "valid=1\n";
    properties(file);
    for (int round = 0; round < 2; round++)std::cout << "save=" << file.save() << '\n';
    return 0;
  }
  if (argc == 3 && std::string(argv[1]) == "header") {
    std::ifstream input(argv[2], std::ios::binary);
    std::string bytes((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    TagLib::ID3v2::Header header(TagLib::ByteVector(bytes.data(), bytes.size()));
    TagLib::FileRef file(argv[2], false);
    if (file.isNull())return 3;
    TagLib::ID3v2::Tag tag(file.file(), 0);
    const unsigned flags = (header.unsynchronisation() ? 128 : 0) | (header.extendedHeader() ? 64 : 0)
                           | (header.experimentalIndicator() ? 32 : 0) | (header.footerPresent() ? 16 : 0);
    std::cout << "major=" << header.majorVersion() << "\nflags=" << flags
        << "\ndeclared=" << header.completeTagSize()
        << "\nspan=" << tag.header()->completeTagSize()
        << "\nempty=" << tag.isEmpty() << '\n';
    properties(tag, tag.properties());
    return 0;
  }
  if (argc != 4) return 2;
  TagLib::FileRef file(argv[1], false);
  if (file.isNull()) {
    std::cout << "valid=0\n";
    return 0;
  }
  std::string scenario = argv[2];
  TagLib::PropertyMap requested;
  if (scenario == "empty") {
    requested.insert("ARTIST", TagLib::StringList());
    requested.insert("CUSTOM", TagLib::StringList());
    requested.insert("TITLE", TagLib::StringList(TagLib::String("")));
    file.setProperties(requested);
  } else if (scenario == "empty-description") {
    requested.insert("", TagLib::StringList("first").append("second"));
    file.setProperties(requested);
  } else if (scenario == "properties-common") {
    requested.insert("TITLE", TagLib::StringList("property").append("second"));
    file.setProperties(requested);
    file.tag()->setTitle("Common");
  } else if (scenario == "common-properties") {
    file.tag()->setTitle("Common");
    requested.insert("TITLE", TagLib::StringList("property").append("second"));
    file.setProperties(requested);
  } else if (scenario == "title") file.tag()->setTitle("Common");
  else if (scenario == "comment") file.tag()->setComment("Comment");
  else if (scenario == "genre") file.tag()->setGenre("Rock");
  else if (scenario == "year") file.tag()->setYear(2147483648U);
  else if (scenario == "save-only") {
  } else if (scenario == "general-object" || scenario == "general-object-empty" || scenario == "complex-unknown") {
    TagLib::List<TagLib::VariantMap> objects;
    if (scenario != "general-object-empty") {
      TagLib::VariantMap object;
      object.insert("data", TagLib::ByteVector("\x00\x01\xff", 3));
      object.insert("mimeType", TagLib::String("application/x-object"));
      object.insert("description", TagLib::String("D\xc3\xa9j\xc3\xa0", TagLib::String::UTF8));
      object.insert("fileName", TagLib::String("\xe5\x9b\xbe.bin", TagLib::String::UTF8));
      objects.append(object);
    }
    std::cout << "complexSupported=" << file.setComplexProperties(
      scenario == "complex-unknown" ? "UNKNOWNKEY" : "GENERALOBJECT", objects) << '\n';
  } else return 2;
  std::cout << "valid=1\n";
  properties(file);
  for (int i = 0; i < std::stoi(argv[3]); ++i) std::cout << "save=" << file.save() << '\n';
}
