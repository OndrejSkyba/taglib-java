// Focused TagLib API differential oracle; development tooling only.
#include <iostream>
#include <iomanip>
#include <sstream>
#include <fstream>
#include "fileref.h"
#include "tpropertymap.h"
#include "flacfile.h"
#include "flacproperties.h"
#include "flacpicture.h"
#include "wavfile.h"
#include "wavproperties.h"
#include "oggfile.h"
#include "oggpage.h"
#include "oggpageheader.h"
#include "tbytevectorstream.h"

static std::string hex(const TagLib::ByteVector &bytes) {
  std::ostringstream out;
  for (unsigned char c: bytes) out << std::hex << std::setw(2) << std::setfill('0') << unsigned(c);
  return out.str();
}

class RawOggFile : public TagLib::Ogg::File {
public:
  explicit RawOggFile(TagLib::IOStream *s) : File(s) {
  }

  TagLib::Tag *tag() const override { return nullptr; }
  TagLib::AudioProperties *audioProperties() const override { return nullptr; }
};

static void oggHeader(const TagLib::Ogg::PageHeader &h) {
  std::cout << "{\"valid\":" << (h.isValid() ? "true" : "false") << ",\"size\":" << h.size() << ",\"dataSize\":" << h.
      dataSize()
      << ",\"firstContinued\":" << (h.firstPacketContinued() ? "true" : "false") << ",\"lastCompleted\":" << (
        h.lastPacketCompleted() ? "true" : "false")
      << ",\"firstPage\":" << (h.firstPageOfStream() ? "true" : "false") << ",\"lastPage\":" << (
        h.lastPageOfStream() ? "true" : "false")
      << ",\"granule\":" << h.absoluteGranularPosition() << ",\"serial\":" << h.streamSerialNumber() << ",\"sequence\":"
      << h.pageSequenceNumber() << ",\"packetSizes\":[";
  bool first = true;
  for (int n: h.packetSizes()) {
    if (!first)std::cout << ',';
    std::cout << n;
    first = false;
  }
  std::cout << "],\"renderHex\":\"" << hex(h.render()) << "\"}";
}

static void oggPage(TagLib::Ogg::Page &p) {
  std::cout << "{\"offset\":" << p.fileOffset() << ",\"size\":" << p.size() << ",\"firstPacketIndex\":" << p.
      firstPacketIndex() << ",\"header\":";
  oggHeader(*p.header());
  std::cout << ",\"renderHex\":\"" << hex(p.render()) << "\",\"packetsHex\":[";
  bool first = true;
  for (const auto &b: p.packets()) {
    if (!first)std::cout << ',';
    first = false;
    std::cout << '"' << hex(b) << '"';
  }
  std::cout << "],\"contains\":[";
  p.setFirstPacketIndex(7);
  for (int n = 6; n <= 8 + int(p.packetCount()); n++) {
    if (n > 6)std::cout << ',';
    std::cout << p.containsPacket(n);
  }
  std::cout << "]}";
}

int main(int argc, char **argv) {
  if (argc < 3) return 2;
  const std::string command = argv[1];
  if (command == "ogg-read") {
    std::ifstream in(argv[2], std::ios::binary);
    std::string raw((std::istreambuf_iterator<char>(in)), {});
    TagLib::ByteVectorStream s(TagLib::ByteVector(raw.data(), raw.size()));
    RawOggFile f(&s);
    TagLib::Ogg::Page p(&f, 0);
    oggPage(p);
    std::cout << '\n';
    return 0;
  }
  if (command == "ogg-paginate" || command == "ogg-header") {
    std::ifstream in(argv[2]);
    int strategy, sequence, continued, completed, last, count;
    unsigned int serial;
    in >> strategy >> serial >> sequence >> continued >> completed >> last >> count;
    TagLib::ByteVectorList packets;
    TagLib::List<int> lengths;
    for (int i = 0; i < count; i++) {
      int length;
      in >> length;
      lengths.append(length);
      TagLib::ByteVector b(static_cast<unsigned int>(length));
      for (int j = 0; j < length; j++)b[j] = char((i * 17 + j) % 251);
      packets.append(b);
    }
    if (command == "ogg-header") {
      TagLib::Ogg::PageHeader h;
      h.setPacketSizes(lengths);
      h.setFirstPacketContinued(continued);
      h.setLastPacketCompleted(completed);
      h.setFirstPageOfStream(strategy);
      h.setLastPageOfStream(last);
      h.setStreamSerialNumber(serial);
      h.setPageSequenceNumber(sequence);
      h.setAbsoluteGranularPosition(-991);
      oggHeader(h);
      std::cout << '\n';
      return 0;
    }
    auto pages = TagLib::Ogg::Page::paginate(packets, static_cast<TagLib::Ogg::Page::PaginationStrategy>(strategy),
                                             serial, sequence, continued, completed, last);
    std::cout << '[';
    bool first = true;
    for (auto *p: pages) {
      if (!first)std::cout << ',';
      first = false;
      oggPage(*p);
      delete p;
    }
    std::cout << "]\n";
    return 0;
  }
  if (command == "riff-title") {
    TagLib::FileRef f(argv[2]);
    if (f.isNull())return 3;
    TagLib::PropertyMap p;
    p.replace("TITLE", TagLib::StringList("Must remain reachable"));
    f.setProperties(p);
    std::cout << "{\"saved\":" << (f.save() ? "true" : "false") << "}\n";
    return 0;
  }
  if (command == "picture") {
    std::ifstream in(argv[2], std::ios::binary);
    std::string raw((std::istreambuf_iterator<char>(in)), {});
    TagLib::FLAC::Picture p;
    p.setDescription("old description");
    p.setData(TagLib::ByteVector("old", 3));
    bool parsed = p.parse(TagLib::ByteVector(raw.data(), raw.size()));
    std::cout << "{\"parsed\":" << (parsed ? "true" : "false") << ",\"renderHex\":\"" << hex(p.render()) << "\"}\n";
    return 0;
  }
  if (command.rfind("wav-", 0) == 0) {
    TagLib::RIFF::WAV::File f(argv[2]);
    if (!f.isValid())return 3;
    if (command == "wav-set") {
      f.setiXMLData(TagLib::String("<BWFXML>\xC5\xBDlu\xC5\xA5</BWFXML>", TagLib::String::UTF8));
      f.setBEXTData(TagLib::ByteVector("\x00\xFF\x01\x02", 4));
      if (!f.save())return 4;
    }
    if (command == "wav-clear") {
      f.setiXMLData(TagLib::String());
      f.setBEXTData(TagLib::ByteVector());
      if (!f.save())return 4;
    }
    auto a = f.audioProperties();
    std::cout << "{\"xmlHex\":\"" << hex(f.iXMLData().data(TagLib::String::UTF8)) << "\",\"bextHex\":\"" <<
        hex(f.BEXTData()) << "\",\"hasXml\":" << (f.hasiXMLData() ? "true" : "false") << ",\"hasBext\":" << (
          f.hasBEXTData() ? "true" : "false") << ",\"sampleFrames\":" << a->sampleFrames() << ",\"format\":" << a->
        format() << ",\"bitsPerSample\":" << a->bitsPerSample() << "}\n";
    return 0;
  }
  if (command == "tracker") {
    TagLib::FileRef f(argv[2]);
    if (f.isNull() || !f.file()->isValid()) return 3;
    auto p = f.properties();
    p.replace("TRACKERNAME", TagLib::StringList("Replacement tracker"));
    const auto rejected = f.setProperties(p);
    std::cout << "{\"saved\":" << (f.save() ? "true" : "false") << ",\"rejected\":" << rejected.size() << "}\n";
    return 0;
  }
  TagLib::FLAC::File f(argv[2]);
  if (!f.isValid()) return 3;
  if (command == "flac-save" && !f.save()) return 4;
  if (command == "flac-keys") {
    TagLib::PropertyMap p;
    for (const char *key: {"", "=", "\xC3\xA9x", "~", "}", "TITLE", "EMPTY"})
      p.replace(TagLib::String(key, TagLib::String::UTF8),
                key == std::string("EMPTY") ? TagLib::StringList() : TagLib::StringList("value"));
    auto rejected = f.setProperties(p);
    auto keys = [](const TagLib::PropertyMap &map) {
      bool first = true;
      std::cout << '[';
      for (const auto &[k,v]: map) {
        if (!first)std::cout << ',';
        first = false;
        std::cout << '"' << hex(k.data(TagLib::String::UTF8)) << '"';
      }
      std::cout << ']';
    };
    std::cout << "{\"acceptedKeysHex\":";
    keys(f.properties());
    std::cout << ",\"rejectedKeysHex\":";
    keys(rejected);
    std::cout << "}\n";
    return 0;
  }
  if (command == "flac-set") {
    f.setiXMLData(TagLib::String("<BWFXML>\xC5\xBDlu\xC5\xA5</BWFXML>", TagLib::String::UTF8));
    f.setBEXTData(TagLib::ByteVector("\x00\xFF\x01\x02", 4));
    if (!f.save())return 4;
  }
  if (command == "flac-clear") {
    f.setiXMLData(TagLib::String());
    f.setBEXTData(TagLib::ByteVector());
    if (!f.save())return 4;
  }
  auto a = f.audioProperties();
  std::cout << "{\"xmlHex\":\"" << hex(f.iXMLData().data(TagLib::String::UTF8)) << "\",\"bextHex\":\"" <<
      hex(f.BEXTData()) << "\",\"hasXml\":" << (f.hasiXMLData() ? "true" : "false") << ",\"hasBext\":" << (
        f.hasBEXTData() ? "true" : "false") << ",\"sampleFrames\":" << a->sampleFrames() << ",\"signature\":\"" <<
      hex(a->signature()) << "\"}\n";
}
