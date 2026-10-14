// Source-rule oracle only: actual pinned UIntElement and sizeVINT rendering.
#include <iostream>
#include <iomanip>
#include <sstream>
#include <stdexcept>
#include <fstream>
#include "ebmluintelement.h"
#include "ebmlutils.h"
#include "tbytevector.h"
#include "matroskafile.h"
#include "matroskaattachments.h"
#include "matroskaattachedfile.h"
#include "tbytevectorstream.h"

static std::string hex(const TagLib::ByteVector &bytes) {
  std::ostringstream out;
  for (unsigned char byte: bytes) out << std::hex << std::setw(2) << std::setfill('0') << unsigned(byte);
  return out.str();
}

int main(int argc, char **argv) {
  try {
    if (argc == 5 && std::string(argv[1]) == "attachment-stream") {
      std::ifstream input(argv[2], std::ios::binary);
      std::string raw((std::istreambuf_iterator<char>(input)), {});
      TagLib::ByteVectorStream stream(TagLib::ByteVector(raw.data(), raw.size()));
      TagLib::Matroska::File file(&stream, false);
      if (!file.isValid()) throw std::runtime_error("Invalid native Matroska stream");
      auto *attachments = file.attachments(true);
      attachments->clear();
      attachments->addAttachedFile(TagLib::Matroska::AttachedFile(
        TagLib::ByteVector(std::stoul(argv[4]), '*'), "cover.png", "image/png",
        std::stoull(argv[3], nullptr, 16), ""));
      if (!file.save()) throw std::runtime_error("Native stream attachment save failed");
      std::cout << stream.tell() << '\t' << stream.length() << '\n';
      std::ofstream output(argv[2], std::ios::binary | std::ios::trunc);
      output.write(stream.data()->data(), stream.data()->size());
      return output ? 0 : 1;
    }
    if (argc == 5 && std::string(argv[1]) == "attachment") {
      TagLib::Matroska::File file(argv[2], false);
      if (!file.isValid()) throw std::runtime_error("Invalid native Matroska source");
      auto *attachments = file.attachments(true);
      attachments->clear();
      attachments->addAttachedFile(TagLib::Matroska::AttachedFile(
        TagLib::ByteVector(std::stoul(argv[4]), '*'), "cover.png", "image/png",
        std::stoull(argv[3], nullptr, 16), ""));
      if (!file.save()) throw std::runtime_error("Native attachment save failed");
      return 0;
    }
    if (argc != 4) throw std::runtime_error("generated-uid-probe UID_HEX SIZE MIN_SIZE_WIDTH");
    TagLib::EBML::UIntElement uid(TagLib::EBML::Element::Id::MkAttachedFileUID);
    uid.setValue(std::stoull(argv[1], nullptr, 16));
    std::cout << hex(uid.render()) << '\n'
        << hex(TagLib::EBML::renderVINT(std::stoull(argv[2]), std::stoi(argv[3]))) << '\n';
    return 0;
  } catch (const std::exception &error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
