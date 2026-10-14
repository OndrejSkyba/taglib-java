// Shorten property values use the private forward-declared input as a Java adapter.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <shortenfile.h>
#include <shortenproperties.h>
#include <shortenutils.h>
#include <tbytevectorstream.h>
using namespace TagLib;

static void print(const std::string &key, const Shorten::Properties &p, offset_t cursor = -1, bool open = true) {
  std::cout << key << '\t' << p.lengthInMilliseconds() << '\t' << p.lengthInSeconds() << '\t' << p.bitrate() << '\t'
      << p.sampleRate() << '\t' << p.channels() << '\t' << p.bitsPerSample() << '\t' << p.shortenVersion() << '\t'
      << p.fileType() << '\t' << p.sampleFrames() << '\t' << cursor << '\t' << open << '\n';
}

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  auto root = std::filesystem::path(argv[1]);
  std::ifstream cases(root / "cases.tsv");
  std::string key;
  int version, type, channels, rate, bits;
  unsigned long frames;
  while (cases >> key >> version >> type >> channels >> rate >> bits >> frames) {
    for (const std::string mode: {"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
      auto style = mode == "FAST"
                     ? AudioProperties::Fast
                     : mode == "ACCURATE"
                         ? AudioProperties::Accurate
                         : AudioProperties::Average;
      Shorten::PropertyValues values{version, type, channels, rate, bits, frames};
      auto p = mode == "DEFAULT"
                 ? std::make_unique<Shorten::Properties>(key == "null" ? nullptr : &values)
                 : std::make_unique<Shorten::Properties>(key == "null" ? nullptr : &values, style);
      print(key + "-" + mode + "-data", *p);
      values = {};
      print(key + "-" + mode + "-snapshot", *p);
    }
  }
  std::map<std::string, std::filesystem::path> inputs;
  for (const auto &e: std::filesystem::directory_iterator(root / "inputs"))
    inputs[e.path().filename().string()] = e.path();
  for (const auto &[name,path]: inputs) {
    std::ifstream in(path, std::ios::binary);
    std::string raw((std::istreambuf_iterator<char>(in)), {});
    for (const std::string mode: {"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
      auto style = mode == "FAST"
                     ? AudioProperties::Fast
                     : mode == "ACCURATE"
                         ? AudioProperties::Accurate
                         : AudioProperties::Average;
      Shorten::File file(path.c_str(), false, style);
      if (!file.isValid())throw std::runtime_error("Invalid recipe " + name);
      print(name + "-" + mode + "-path", *file.audioProperties(), -1, file.isOpen());
      for (int origin: {0, 7}) {
        ByteVector source(origin, 'p');
        source.append(ByteVector(raw.data(), raw.size()));
        ByteVectorStream stream(source);
        stream.seek(origin);
        Shorten::File borrowed(&stream, false, style);
        if (!borrowed.isValid())throw std::runtime_error("Invalid borrowed recipe " + name);
        auto cursor = stream.tell();
        print(name + "-" + mode + "-stream-" + std::to_string(origin), *borrowed.audioProperties(), cursor,
              stream.isOpen());
        stream.data()->clear();
        print(name + "-" + mode + "-snapshot-" + std::to_string(origin), *borrowed.audioProperties(), cursor,
              stream.isOpen());
      }
    }
  }
}
