// Focused TagLib Ogg::File differential oracle; development tooling only.
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <memory>
#include <sstream>
#include <string>

#include "oggfile.h"
#include "oggpageheader.h"
#include "tbytevectorstream.h"
#include "tfilestream.h"

class RawOggFile final : public TagLib::Ogg::File {
public:
  explicit RawOggFile(TagLib::IOStream *stream) : File(stream) {
  }

  explicit RawOggFile(const char *path) : File(path) {
  }

  TagLib::Tag *tag() const override { return nullptr; }
  TagLib::AudioProperties *audioProperties() const override { return nullptr; }
  using File::packet;
  using File::selectStream;
};

static std::string hex(const TagLib::ByteVector &bytes) {
  std::ostringstream out;
  for (unsigned char c: bytes)
    out << std::hex << std::setw(2) << std::setfill('0') << unsigned(c);
  return out.str();
}

static TagLib::ByteVector unhex(const std::string &text) {
  if (text == "-") return {};
  TagLib::ByteVector result(static_cast<unsigned int>(text.size() / 2));
  for (unsigned int i = 0; i < result.size(); i++)
    result[i] = char(std::stoul(text.substr(i * 2, 2), nullptr, 16));
  return result;
}

static TagLib::ByteVector raw(const char *path) {
  std::ifstream in(path, std::ios::binary);
  const std::string bytes((std::istreambuf_iterator<char>(in)), {});
  return TagLib::ByteVector(bytes.data(), static_cast<unsigned int>(bytes.size()));
}

static void header(const TagLib::Ogg::PageHeader *value) {
  if (!value) {
    std::cout << "null";
    return;
  }
  std::cout << "{\"renderHex\":\"" << hex(value->render()) << "\",\"size\":" << value->size()
      << ",\"dataSize\":" << value->dataSize() << ",\"serial\":" << value->streamSerialNumber()
      << ",\"sequence\":" << value->pageSequenceNumber() << ",\"granule\":" << value->absoluteGranularPosition()
      << ",\"first\":" << (value->firstPageOfStream() ? "true" : "false")
      << ",\"last\":" << (value->lastPageOfStream() ? "true" : "false") << '}';
}

int main(int argc, char **argv) {
  if (argc != 4) return 2;
  const std::string mode = argv[1];
  std::unique_ptr<TagLib::IOStream> stream;
  std::unique_ptr<RawOggFile> file;
  if (mode == "path") file = std::make_unique<RawOggFile>(argv[2]);
  else if (mode == "readonly") {
    stream = std::make_unique<TagLib::FileStream>(argv[2], true);
    file = std::make_unique<RawOggFile>(stream.get());
  } else if (mode == "memory") {
    stream = std::make_unique<TagLib::ByteVectorStream>(raw(argv[2]));
    file = std::make_unique<RawOggFile>(stream.get());
  } else return 2;
  std::ifstream recipe(argv[3]);
  std::string operation;
  bool first = true;
  std::cout << '[';
  while (recipe >> operation) {
    if (!first) std::cout << ',';
    first = false;
    std::cout << "{\"operation\":\"" << operation << "\",\"value\":";
    if (operation == "packet") {
      unsigned int index, maximum;
      recipe >> index >> maximum;
      std::cout << '"' << hex(file->packet(index, maximum)) << '"';
    } else if (operation == "set") {
      unsigned int index;
      std::string text;
      recipe >> index >> text;
      file->setPacket(index, unhex(text));
      std::cout << "null";
    } else if (operation == "select") {
      std::string text;
      recipe >> text;
      std::cout << (file->selectStream(unhex(text)) ? "true" : "false");
    } else if (operation == "seek") {
      TagLib::offset_t position;
      recipe >> position;
      file->seek(position);
      std::cout << "null";
    } else if (operation == "save") std::cout << (file->save() ? "true" : "false");
    else if (operation == "first") header(file->firstPageHeader());
    else if (operation == "last") header(file->lastPageHeader());
    else if (operation == "data") {
      if (mode == "memory")
        std::cout << '"' << hex(*static_cast<TagLib::ByteVectorStream *>(stream.get())->data()) << '"';
      else std::cout << '"' << hex(raw(argv[2])) << '"';
    } else return 2;
    std::cout << ",\"cursor\":" << file->tell() << ",\"length\":" << file->length() << '}';
  }
  std::cout << "]\n";
}
