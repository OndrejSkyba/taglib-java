// TagLib installed ID3v2 header/footer/extended-header/synchdata protocol.
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include "id3v2header.h"
#include "id3v2footer.h"
#include "id3v2extendedheader.h"
#include "id3v2synchdata.h"
using namespace TagLib;

static ByteVector bytes(const std::string &hex) {
  ByteVector data;
  if (hex != "-")
    for (size_t i = 0; i < hex.size(); i += 2)
      data.append(
        static_cast<char>(std::stoul(hex.substr(i, 2), nullptr, 16)));
  return data;
}

static std::string hex(const ByteVector &data) {
  const auto value = data.toHex();
  return {value.data(), value.size()};
}

static void state(const std::string &id, const ID3v2::Header &h) {
  std::cout << id << '\t' << h.majorVersion() << '\t' << h.revisionNumber() << '\t' << h.unsynchronisation() << '\t' <<
      h.extendedHeader() << '\t' << h.experimentalIndicator() << '\t' << h.footerPresent() << '\t' << h.tagSize() <<
      '\t' << h.completeTagSize() << '\n';
}

class HeaderChild : public ID3v2::Header {
public:
  void parsed(const ByteVector &v) { parse(v); }
};

class ExtendedChild : public ID3v2::ExtendedHeader {
public:
  void parsed(const ByteVector &v) { parse(v); }
};

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  ID3v2::Header defaults;
  state("default", defaults);
  std::cout << "static\t" << ID3v2::Header::size() << '\t' << ID3v2::Footer::size() << '\t' << hex(
    ID3v2::Header::fileIdentifier()) << '\n';
  std::ifstream input(argv[1]);
  std::string line;
  const ByteVector seed = bytes("4944330307f000000063");
  while (std::getline(input, line)) {
    std::stringstream stream(line);
    std::vector<std::string> fields;
    std::string part;
    while (std::getline(stream, part, '\t'))fields.push_back(part);
    if (fields.size() != 5)return 3;
    const auto &id = fields[0];
    const auto data = bytes(fields[2]);
    const auto major = static_cast<unsigned int>(std::stoull(fields[3]));
    const auto size = static_cast<unsigned int>(std::stoull(fields[4]));
    if (fields[1] == "H") {
      ID3v2::Header ctor(data);
      state(id + "/ctor", ctor);
      ID3v2::Header set;
      set.setData(data);
      state(id + "/set", set);
      set.setMajorVersion(major);
      set.setTagSize(size);
      state(id + "/override", set);
      std::cout << id << "/override-render\t" << hex(set.render()) << '\n';
      state(id + "/override-after", set);
      ID3v2::Header retained(seed);
      retained.setData(data);
      state(id + "/retained", retained);
      HeaderChild child;
      child.setData(seed);
      child.parsed(data);
      state(id + "/protected", child);
      std::cout << id << "/render\t" << hex(ctor.render()) << '\n';
      state(id + "/after", ctor);
      ID3v2::Header footerHeader(data);
      ID3v2::Footer footer;
      std::cout << id << "/footer\t" << hex(footer.render(&footerHeader)) << '\n';
      state(id + "/footer-after", footerHeader);
      std::cout << id << "/repeat\t" << hex(ctor.render()) << '\t' << hex(footer.render(&footerHeader)) << '\n';
    } else if (fields[1] == "S") {
      ID3v2::ExtendedHeader ext;
      auto before = ext.size();
      ext.setData(data);
      ExtendedChild child;
      child.setData(bytes("00000063"));
      child.parsed(data);
      std::cout << id << "/synch\t" << ID3v2::SynchData::toUInt(data) << '\t' << hex(ID3v2::SynchData::fromUInt(major))
          << '\t' << hex(ID3v2::SynchData::decode(data)) << '\t' << before << '\t' << ext.size() << '\t' << child.size()
          << '\t' << hex(data) << '\n';
    } else if (fields[1] == "D") {
      auto decoded = ID3v2::SynchData::decode(data);
      std::cout << id << "/decode\t" << hex(decoded) << '\t' << hex(ID3v2::SynchData::decode(decoded)) << '\t' <<
          hex(data) << '\n';
    } else return 4;
  }
  return input.eof() ? 0 : 5;
}
