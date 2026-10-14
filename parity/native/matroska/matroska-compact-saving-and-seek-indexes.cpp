// Independent read/default-Compact-save probe for checksum-pinned TagLib.
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <fstream>
#include <iterator>
#include "tbytevectorstream.h"
#include "matroskafile.h"
#include "matroskatag.h"
#include "matroskaattachments.h"
#include "matroskaattachedfile.h"
#include "tpropertymap.h"
#include "tvariant.h"
#include "fileref.h"

static std::string hex(const TagLib::String &value) {
  std::ostringstream out;
  for (unsigned char c: value.data(TagLib::String::UTF8))
    out << std::hex << std::setw(2) << std::setfill('0') << unsigned(c);
  return out.str();
}

static void dump(TagLib::Matroska::File &file) {
  std::cout << "valid=" << file.isValid() << '\n';
  if (!file.isValid()) return;
  std::cout << "TITLE\t" << hex(file.tag()->title()) << '\n';
  auto properties = file.properties();
  for (const auto &[key, values]: properties) {
    std::cout << "PROP\t" << hex(key);
    for (const auto &value: values) std::cout << '\t' << hex(value);
    std::cout << '\n';
  }
  for (const auto &id: properties.unsupportedData())
    std::cout << "UNSUPPORTED\t" << hex(id) << '\n';
}

static void dump(TagLib::FileRef &file) {
  std::cout << "valid=" << !file.isNull() << '\n';
  if (file.isNull()) return;
  std::cout << "TITLE\t" << hex(file.tag()->title()) << '\n';
  const auto properties = file.properties();
  for (const auto &[key,values]: properties) {
    std::cout << "PROP\t" << hex(key);
    for (const auto &value: values) std::cout << '\t' << hex(value);
    std::cout << '\n';
  }
  for (const auto &id: properties.unsupportedData()) std::cout << "UNSUPPORTED\t" << hex(id) << '\n';
}

class ProbeStream : public TagLib::ByteVectorStream {
public:
  ProbeStream(const TagLib::ByteVector &data, bool readOnly)
    : TagLib::ByteVectorStream(data), readOnly_(readOnly) {
  }

  bool readOnly() const override { return readOnly_; }
  TagLib::FileName name() const override { return "fixture.mka"; }

private:
  bool readOnly_;
};

int main(int argc, char **argv) {
  if (argc < 3) return 2;
  const std::string command = argv[1];
  if (command == "read-stream" || command == "read-readonly-stream" || command == "set-stream") {
    std::ifstream input(argv[2], std::ios::binary);
    std::string bytes((std::istreambuf_iterator<char>(input)), {});
    ProbeStream stream(TagLib::ByteVector(bytes.data(), bytes.size()), command == "read-readonly-stream");
    auto style = argc > 3 && std::string(argv[3]) == "fast"
                   ? TagLib::AudioProperties::Fast
                   : argc > 3 && std::string(argv[3]) == "accurate"
                       ? TagLib::AudioProperties::Accurate
                       : TagLib::AudioProperties::Average;
    stream.seek(argc > 5 ? std::stoi(argv[5]) : 0);
    if (command == "read-readonly-stream") {
      TagLib::FileRef file(&stream, argc > 4 && std::string(argv[4]) == "true", style);
      std::cout << "cursorAfterConstruction=" << stream.tell() << '\n';
      dump(file);
      return 0;
    }
    TagLib::Matroska::File file(&stream, argc > 4 && std::string(argv[4]) == "true", style);
    std::cout << "cursorAfterConstruction=" << stream.tell() << '\n';
    dump(file);
    if (command == "set-stream" && file.isValid()) {
      TagLib::PropertyMap values;
      if (std::string(argv[6]) != "<empty>") values.insert("TITLE", TagLib::String(argv[6], TagLib::String::UTF8));
      file.setProperties(values);
      for (int n = 0; n < std::stoi(argv[7]); ++n) {
        std::cout << "save=" << file.save() << '\n';
        std::cout << "cursorAfterSave=" << stream.tell() << '\n';
      }
      auto data = *stream.data();
      std::ofstream output(argv[2], std::ios::binary | std::ios::trunc);
      output.write(data.data(), data.size());
    }
    return 0;
  }
  const auto style = command == "set-fast" || (argc > 3 && command == "read" && std::string(argv[3]) == "fast")
                       ? TagLib::AudioProperties::Fast
                       : argc > 3 && command == "read" && std::string(argv[3]) == "accurate"
                           ? TagLib::AudioProperties::Accurate
                           : TagLib::AudioProperties::Average;
  if (command == "read-prefix") {
    std::ifstream input(argv[2], std::ios::binary);
    std::string bytes((std::istreambuf_iterator<char>(input)), {});
    TagLib::ByteVectorStream stream(TagLib::ByteVector(bytes.data(), bytes.size()));
    stream.seek(7);
    TagLib::Matroska::File file(&stream, false, style);
    dump(file);
    return 0;
  }
  if (command == "read") {
    TagLib::Matroska::File file(argv[2], false, style);
    dump(file);
    return 0;
  }
  if ((command != "set" && command != "set-picture" && command != "set-fast") || argc != 5) return 2;
  {
    TagLib::Matroska::File file(argv[2], false, style);
    if (!file.isValid()) {
      dump(file);
      return 0;
    }
    TagLib::PropertyMap properties;
    if (std::string(argv[3]) != "<empty>")
      properties.insert("TITLE", TagLib::String(argv[3], TagLib::String::UTF8));
    file.setProperties(properties);
    if (command == "set-picture") {
      TagLib::VariantMap picture;
      picture.insert("data", TagLib::ByteVector("\x89PNG", 4));
      picture.insert("mimeType", TagLib::String("image/png"));
      picture.insert("description", TagLib::String("Front"));
      file.setComplexProperties("PICTURE", {picture});
    }
    for (int n = 0; n < std::stoi(argv[4]); ++n) {
      std::cout << "save=" << file.save() << '\n';
    }
  }
  TagLib::Matroska::File reopened(argv[2], false);
  if (command == "set-picture" && reopened.isValid() && reopened.attachments())
    std::cout << "uid=" << reopened.attachments()->attachedFileList().front().uid() << '\n';
  dump(reopened);
}
