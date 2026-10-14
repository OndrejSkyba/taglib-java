// Independent native public FileRef control for Ogg FLAC's 24-bit comment limit.
#include <fstream>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <memory>
#include <sstream>
#include <string>
#include "fileref.h"
#include "tag.h"
#include "tpropertymap.h"
#include "tvariant.h"
#include "tbytevectorstream.h"

static std::string quote(const TagLib::String &value) {
  std::ostringstream out;
  out << '"';
  for (unsigned char c: value.to8Bit(true)) {
    if (c == '"' || c == '\\') out << '\\' << c;
    else if (c < 32) out << "\\u00" << std::hex << std::setw(2) << std::setfill('0') << unsigned(c) << std::dec;
    else out << c;
  }
  return out.str() + '"';
}

static std::string hash(const TagLib::ByteVector &bytes) {
  uint64_t value = 14695981039346656037ULL;
  for (unsigned char c: bytes) value = (value ^ c) * 1099511628211ULL;
  std::ostringstream out;
  out << '"' << std::hex << std::setw(16) << std::setfill('0') << value << '"';
  return out.str();
}

static void state(TagLib::FileRef &file) {
  if (file.isNull()) {
    std::cout << "{\"valid\":false}";
    return;
  }
  std::cout << "{\"valid\":true,\"properties\":{";
  bool first = true;
  for (const auto &[key, values]: file.properties()) {
    if (!first) std::cout << ',';
    first = false;
    std::cout << quote(key) << ":[";
    bool firstValue = true;
    for (const auto &value: values) {
      if (!firstValue) std::cout << ',';
      firstValue = false;
      auto bytes = value.data(TagLib::String::UTF8);
      std::cout << "{\"size\":" << bytes.size() << ",\"fnv1a64\":" << hash(bytes) << '}';
    }
    std::cout << ']';
  }
  auto *tag = file.tag();
  std::cout << "},\"common\":{\"title\":" << quote(tag->title()) << ",\"artist\":" << quote(tag->artist())
      << ",\"album\":" << quote(tag->album()) << ",\"comment\":" << quote(tag->comment())
      << ",\"genre\":" << quote(tag->genre()) << ",\"year\":" << tag->year() << ",\"track\":" << tag->track()
      << "},\"isEmpty\":" << (tag->isEmpty() ? "true" : "false") << ",\"complexKeys\":[";
  first = true;
  for (const auto &key: file.complexPropertyKeys()) {
    if (!first) std::cout << ',';
    first = false;
    std::cout << quote(key);
  }
  std::cout << "],\"pictures\":[";
  first = true;
  for (const auto &p: file.complexProperties("PICTURE")) {
    if (!first) std::cout << ',';
    first = false;
    auto bytes = p.value("data").value<TagLib::ByteVector>();
    std::cout << "{\"size\":" << bytes.size() << ",\"fnv1a64\":" << hash(bytes)
        << ",\"mimeType\":" << quote(p.value("mimeType").toString())
        << ",\"description\":" << quote(p.value("description").toString())
        << ",\"pictureType\":" << quote(p.value("pictureType").toString())
        << ",\"width\":" << p.value("width").toInt() << ",\"height\":" << p.value("height").toInt()
        << ",\"colorDepth\":" << p.value("colorDepth").toInt() << ",\"numColors\":" << p.value("numColors").toInt() <<
        '}';
  }
  std::cout << "]}";
}

class NamedMemory : public TagLib::ByteVectorStream {
  std::string path;

public:
  NamedMemory(const TagLib::ByteVector &bytes, const char *name) : ByteVectorStream(bytes), path(name) {
  }

  TagLib::FileName name() const override { return path.c_str(); }
};

int main(int argc, char **argv) {
  if (argc != 5) return 2;
  const auto pictureSize = std::stoul(argv[2]);
  const auto customSize = std::stoul(argv[3]);
  const auto origin = std::stol(argv[4]);
  std::unique_ptr<NamedMemory> memory;
  if (origin >= 0) {
    std::ifstream input(argv[1], std::ios::binary);
    std::string bytes((std::istreambuf_iterator<char>(input)), {});
    memory = std::make_unique<NamedMemory>(TagLib::ByteVector(bytes.data(), bytes.size()), argv[1]);
    memory->seek(origin);
  }
  {
    TagLib::FileRef file = memory ? TagLib::FileRef(memory.get(), false) : TagLib::FileRef(argv[1], false);
    if (file.isNull()) return 3;
    std::cout << "{\"cursorAfterConstruction\":" << (memory ? memory->tell() : 0);
    TagLib::PropertyMap properties;
    properties.insert("TITLE", TagLib::StringList("Limit"));
    if (customSize) properties.insert("CUSTOM", TagLib::StringList(TagLib::String(std::string(customSize, 'x'))));
    file.setProperties(properties);
    TagLib::VariantMap picture;
    picture.insert("data", TagLib::ByteVector(pictureSize, 'p'));
    picture.insert("mimeType", TagLib::String("image/png"));
    picture.insert("description", TagLib::String("Limit picture"));
    picture.insert("pictureType", TagLib::String("Front Cover"));
    picture.insert("width", 1);
    picture.insert("height", 2);
    picture.insert("colorDepth", 8);
    picture.insert("numColors", 0);
    TagLib::List<TagLib::VariantMap> pictures;
    pictures.append(picture);
    std::cout << ",\"pictureSupported\":" << (file.setComplexProperties("PICTURE", pictures) ? "true" : "false")
        << ",\"before\":";
    state(file);
    std::cout << ",\"cursorBeforeSave\":" << (memory ? memory->tell() : 0);
    bool saved = file.save();
    std::cout << ",\"savedFirst\":" << (saved ? "true" : "false") << ",\"cursorAfterFirst\":" << (
          memory ? memory->tell() : 0)
        << ",\"afterFirst\":";
    state(file);
    saved = file.save();
    std::cout << ",\"savedSecond\":" << (saved ? "true" : "false") << ",\"cursorAfterSecond\":" << (
          memory ? memory->tell() : 0)
        << ",\"afterSecond\":";
    state(file);
  }
  if (memory) {
    std::ofstream output(argv[1], std::ios::binary | std::ios::trunc);
    output.write(memory->data()->data(), memory->data()->size());
    std::cout << ",\"borrowedOpen\":" << (memory->isOpen() ? "true" : "false");
  }
  TagLib::FileRef reopened(argv[1], false);
  std::cout << ",\"reopened\":";
  state(reopened);
  std::cout << "}\n";
}
