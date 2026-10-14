// Independent data and API comparisons against the selected upstream source.
#include <iomanip>
#include <iostream>
#include <cstdint>
#include <cstring>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include <apeitem.h>
#include <fileref.h>
#include <apetag.h>
#include <asfpicture.h>
#include <flacpicture.h>
#include <id3v2framefactory.h>
#include <id3v2header.h>
#include <oggfile.h>
#include <oggpageheader.h>
#include <tbytevectorstream.h>
#include <tfilestream.h>
#include <tvariant.h>
#include <xiphcomment.h>
#include <frames/textidentificationframe.h>
#include <frames/attachedpictureframe.h>
#include <frames/relativevolumeframe.h>

using namespace TagLib;

std::string hex(const std::string &value) {
  std::ostringstream out;
  for (unsigned char c: value) out << std::hex << std::setw(2) << std::setfill('0') << unsigned(c);
  return out.str();
}

void value(const std::string &key, const std::string &text) {
  std::cout << key << '=' << hex(text) << '\n';
}

void binary(const std::string &key, const ByteVector &data) {
  value(key, std::string(data.data(), data.size()));
}

ByteVector unhex(const std::string &text) {
  if (text.size() % 2) throw std::invalid_argument("Odd hex length");
  ByteVector out;
  for (size_t i = 0; i < text.size(); i += 2) out.append(static_cast<char>(std::stoul(text.substr(i, 2), nullptr, 16)));
  return out;
}

struct OggOwner : Ogg::File {
  explicit OggOwner(IOStream *stream) : Ogg::File(stream) {
  }

  TagLib::Tag *tag() const override { return nullptr; }
  AudioProperties *audioProperties() const override { return nullptr; }
};

struct StreamExtension : FileStream {
  static unsigned int nativeBufferSize() { return bufferSize(); }
};

struct ResolverConsumer : FileRef::StreamTypeResolver {
  mutable bool read = false;
  mutable AudioProperties::ReadStyle style = AudioProperties::Fast;

  File *createFile(FileName, bool requested, AudioProperties::ReadStyle selected) const override {
    read = requested;
    style = selected;
    return nullptr;
  }

  File *createFileFromStream(IOStream *, bool requested, AudioProperties::ReadStyle selected) const override {
    read = requested;
    style = selected;
    return nullptr;
  }
};

int main(int argc, char **argv) {
  if (argc != 3) return 2;
  const std::string operation = argv[1], input = argv[2];
  if (operation == "resolver-defaults") {
    ResolverConsumer consumer;
    FileRef::FileTypeResolver &file = consumer;
    FileRef::StreamTypeResolver &stream = consumer;
    ByteVectorStream data{ByteVector()};
    if (input == "file:1") file.createFile("unused");
    else if (input == "file:2") file.createFile("unused", false);
    else if (input == "stream:1") stream.createFileFromStream(&data);
    else if (input == "stream:2") stream.createFileFromStream(&data, false);
    else return 2;
    value("present", "1");
    value("readAudioProperties", consumer.read ? "1" : "0");
    value("readStyle", std::to_string(consumer.style));
  } else if (operation == "picture-type") {
    const auto separator = input.find(':');
    const std::string family = input.substr(0, separator);
    const int code = std::stoi(input.substr(separator + 1));
    // 0..31 is the defined range of the native unscoped picture enum.
    if (code < 0 || code > 31) return 2;
    value("present", "1");
    if (family == "flac") {
      FLAC::Picture picture;
      picture.setType(static_cast<FLAC::Picture::Type>(code));
      picture.setData(ByteVector("x"));
      value("type", std::to_string(picture.type()));
      value("name", FLAC::Picture::typeToString(picture.type()).to8Bit(true));
      value("normalized", std::to_string(FLAC::Picture::typeFromUInt(code)));
      binary("render", picture.render());
    } else if (family == "apic") {
      ID3v2::AttachedPictureFrame picture;
      picture.setType(static_cast<ID3v2::AttachedPictureFrame::Type>(code));
      picture.setPicture(ByteVector("x"));
      value("type", std::to_string(picture.type()));
      value("name", ID3v2::AttachedPictureFrame::typeToString(picture.type()).to8Bit(true));
      value("normalized", std::to_string(ID3v2::AttachedPictureFrame::typeFromUInt(code)));
      binary("render", picture.render());
    } else if (family == "asf") {
      ASF::Picture picture;
      picture.setType(static_cast<ASF::Picture::Type>(code));
      value("type", std::to_string(picture.type()));
      value("name", ASF::Picture::typeToString(picture.type()).to8Bit(true));
      value("normalized", std::to_string(ASF::Picture::typeFromUInt(code)));
    } else return 2;
  } else if (operation == "rva-channel") {
    const int code = std::stoi(input);
    // The native ChannelType enum has the defined range 0..15; higher values are excluded.
    if (code < 0 || code > 15) return 2;
    ID3v2::RelativeVolumeFrame frame;
    frame.setIdentification("x");
    frame.setVolumeAdjustmentIndex(256, static_cast<ID3v2::RelativeVolumeFrame::ChannelType>(code));
    value("present", "1");
    for (const auto channel: frame.channels()) value("channel", std::to_string(channel));
    binary("render", frame.render());
  } else if (operation == "stream-buffer-size") {
    value("present", "1");
    value("size", std::to_string(StreamExtension::nativeBufferSize()));
  } else if (operation == "variant-double") {
    double number;
    if (input.rfind("bits:", 0) == 0) {
      const std::uint64_t bits = std::stoull(input.substr(5), nullptr, 16);
      std::memcpy(&number, &bits, sizeof(number));
    } else {
      number = std::stod(input);
    }
    binary("bits", ByteVector::fromFloat64BE(number));
    std::ostringstream out;
    out << Variant(number);
    value("render", out.str());
  } else if (operation == "variant-bytes") {
    std::ostringstream out;
    out << Variant(unhex(input));
    value("render", out.str());
  } else if (operation == "byte-numbers") {
    auto separator = input.find(':');
    const unsigned int offset = std::stoul(input.substr(0, separator));
    const ByteVector data = unhex(input.substr(separator + 1));
    value("uint", std::to_string(data.toUInt(offset)));
    value("ushort", std::to_string(data.toUShort(offset, false)));
    value("ulonglong", std::to_string(data.toULongLong(offset)));
  } else if (operation == "base64") {
    const ByteVector decoded = ByteVector::fromBase64(unhex(input));
    binary("decoded", decoded);
    binary("encoded", decoded.toBase64());
  } else if (operation == "string") {
    auto separator = input.find(':');
    String text(unhex(input.substr(separator + 1)), static_cast<String::Type>(std::stoi(input.substr(0, separator))));
    value("text", text.to8Bit(true));
    binary("utf8", text.data(String::UTF8));
  } else if (operation == "ape-item") {
    APE::Item item;
    item.parse(unhex(input));
    value("key", item.key().to8Bit(true));
    value("type", std::to_string(item.type()));
    binary("render", item.render());
  } else if (operation == "flac-picture") {
    FLAC::Picture picture;
    value("valid", picture.parse(unhex(input)) ? "1" : "0");
    binary("render", picture.render());
  } else if (operation == "ogg-header") {
    ByteVectorStream stream(unhex(input));
    OggOwner owner(&stream);
    Ogg::PageHeader header(&owner, 0);
    value("valid", header.isValid() ? "1" : "0");
    value("size", std::to_string(header.size()));
    value("dataSize", std::to_string(header.dataSize()));
  } else if (operation == "id3-header") {
    ID3v2::Header header(unhex(input));
    value("version", std::to_string(header.majorVersion()));
    value("tagSize", std::to_string(header.tagSize()));
    binary("render", header.render());
  } else if (operation == "id3-frame") {
    auto separator = input.find(':');
    ID3v2::Header header;
    header.setMajorVersion(std::stoul(input.substr(0, separator)));
    std::unique_ptr<ID3v2::Frame> frame(
      ID3v2::FrameFactory::instance()->createFrame(unhex(input.substr(separator + 1)), &header));
    value("present", frame ? "1" : "0");
    if (frame) {
      binary("id", frame->frameID());
      value("text", frame->toString().to8Bit(true));
      for (const auto &field: frame->toStringList()) value("field", field.to8Bit(true));
      binary("render", frame->render());
    }
  } else if (operation == "id3-encoding") {
    ID3v2::TextIdentificationFrame frame("TIT2", String::Latin1);
    frame.header()->setVersion(std::stoul(input));
    frame.setText(String("图", String::UTF8));
    binary("render", frame.render());
  } else {
    return 2;
  }
}
