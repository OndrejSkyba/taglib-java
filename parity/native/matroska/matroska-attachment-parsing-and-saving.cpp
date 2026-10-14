// Focused attachment/deferred-read oracle for the checksum-verified TagLib release.
#include <fstream>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <sstream>
#include <stdexcept>
#include <vector>
#include "matroskafile.h"
#include "matroskaattachedfile.h"
#include "matroskaattachments.h"
#include "tbytevectorstream.h"
#include "tstringlist.h"
#include "tvariant.h"

static std::string hex(const TagLib::ByteVector &value) {
  std::ostringstream out;
  for (unsigned char c: value) out << std::hex << std::setw(2) << std::setfill('0') << unsigned(c);
  return out.str();
}

static std::string hex(const TagLib::String &value) { return hex(value.data(TagLib::String::UTF8)); }

static void dump(TagLib::Matroska::File &file) {
  for (const auto &key: file.complexPropertyKeys()) std::cout << "KEY\t" << hex(key) << '\n';
  if (auto *attachments = file.attachments())
    for (const auto &value: attachments->attachedFileList()) {
      std::cout << "FILE\t" << hex(value.fileName()) << '\t' << hex(value.mediaType()) << '\t'
          << hex(value.description()) << '\t' << value.uid() << '\t' << hex(value.data()) << '\n';
    }
}

class CountingStream final : public TagLib::ByteVectorStream {
public:
  CountingStream(const TagLib::ByteVector &data, TagLib::offset_t start, TagLib::offset_t size)
    : TagLib::ByteVectorStream(data), start(start), end(start + size) {
  }

  TagLib::ByteVector readBlock(size_t size) override {
    const auto position = tell();
    if (position < end && position + static_cast<TagLib::offset_t>(size) > start) ++payloadReads;
    return TagLib::ByteVectorStream::readBlock(size);
  }

  int payloadReads = 0;

private:
  TagLib::offset_t start, end;
};

int main(int argc, char **argv) {
  try {
    if (argc < 3) throw std::runtime_error("matroska-attachment-probe read|deferred|set PATH [START SIZE]");
    const std::string command = argv[1];
    if (command == "deferred") {
      if (argc != 5) throw std::runtime_error("Missing payload range");
      std::ifstream input(argv[2], std::ios::binary);
      std::vector<char> bytes{std::istreambuf_iterator<char>(input), {}};
      CountingStream stream(TagLib::ByteVector(bytes.data(), bytes.size()), std::stoll(argv[3]), std::stoll(argv[4]));
      TagLib::Matroska::File file(&stream, false);
      if (!file.isValid()) throw std::runtime_error("Invalid Matroska fixture");
      file.complexPropertyKeys();
      std::cout << "before=" << stream.payloadReads << '\n';
      stream.seek(3);
      file.attachments();
      std::cout << "loaded=" << stream.payloadReads << "\nposition=" << stream.tell() << '\n';
      file.attachments();
      std::cout << "again=" << stream.payloadReads << '\n';
    } else {
      TagLib::Matroska::File file(argv[2], false);
      if (!file.isValid()) throw std::runtime_error("Invalid Matroska fixture");
      if (command == "set") {
        auto *attachments = file.attachments(true);
        attachments->clear();
        attachments->addAttachedFile(TagLib::Matroska::AttachedFile(TagLib::ByteVector("\0\xff\x07", 3),
                                                                    TagLib::String("font-č.ttf", TagLib::String::UTF8),
                                                                    "font/ttf", ~0ULL,
                                                                    TagLib::String("Písmo", TagLib::String::UTF8)));
        attachments->addAttachedFile(TagLib::Matroska::AttachedFile(TagLib::ByteVector("\x89PNG", 4),
                                                                    "cover.png", "image/png", 7, "Front"));
        if (!file.save()) throw std::runtime_error("Attachment save failed");
      } else if (command == "query") {
        if (argc != 4) throw std::runtime_error("Missing attachment selector");
        for (const auto &property: file.complexProperties(TagLib::String(argv[3], TagLib::String::UTF8))) {
          std::cout << "FILE\t" << hex(property.value("fileName").toString()) << '\t'
              << hex(property.value("mimeType").toString()) << '\t'
              << hex(property.value("description").toString()) << '\t'
              << property.value("uid").toULongLong() << '\t'
              << hex(property.value("data").toByteVector()) << '\n';
        }
      } else if (command != "read") throw std::runtime_error("Unknown command");
      if (command != "query") dump(file);
    }
    return 0;
  } catch (const std::exception &error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
