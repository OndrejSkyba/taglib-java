// Compare every MP4 property, explicit File/Atoms adapter, cursor and lifetime.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <mp4file.h>
#include <mp4atom.h>
#include <mp4properties.h>
#include <tbytevectorstream.h>
using namespace TagLib;

static void hex(const ByteVector &v) {
  const char digits[] = "0123456789abcdef";
  for (unsigned char c: v)std::cout << digits[c >> 4] << digits[c & 15];
}

static void print(const std::string &key, const MP4::Properties &p, offset_t cursor = -1, bool open = true) {
  std::cout << key << '\t' << p.lengthInMilliseconds() << '\t' << p.lengthInSeconds() << '\t' << p.bitrate() << '\t' <<
      p.sampleRate() << '\t' << p.channels() << '\t' << p.bitsPerSample() << '\t' << p.codec() << '\t';
  hex(p.codecId().data(String::UTF8));
  std::cout << '\t' << p.isEncrypted() << '\t' << cursor << '\t' << open << '\n';
}

static std::unique_ptr<MP4::Properties> properties(MP4::File &file, const std::string &mode) {
  auto cursor = file.tell();
  MP4::Atoms atoms(&file);
  if (!atoms.checkRootLevelAtoms())throw std::runtime_error("Invalid atoms");
  file.seek(cursor);
  if (mode == "DEFAULT")return std::make_unique<MP4::Properties>(&file, &atoms);
  auto style = mode == "FAST"
                 ? AudioProperties::Fast
                 : mode == "ACCURATE"
                     ? AudioProperties::Accurate
                     : AudioProperties::Average;
  return std::make_unique<MP4::Properties>(&file, &atoms, style);
}

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  std::map<std::string, std::filesystem::path> inputs;
  for (const auto &e: std::filesystem::directory_iterator(std::filesystem::path(argv[1]) / "inputs"))
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
      for (const std::string prefix: {"", "live-"}) {
        MP4::File file(path.c_str(), false, style);
        if (!file.isValid())throw std::runtime_error("Invalid recipe " + name);
        auto p = properties(file, mode);
        print(prefix + name + "-" + mode + "-path", *p);
        for (int origin: {0, 7}) {
          ByteVectorStream stream(ByteVector(raw.data(), raw.size()));
          stream.seek(origin);
          MP4::File borrowed(&stream, false, style);
          if (!borrowed.isValid())throw std::runtime_error("Invalid borrowed recipe " + name);
          auto v = properties(borrowed, mode);
          auto cursor = stream.tell();
          print(prefix + name + "-" + mode + "-stream-" + std::to_string(origin), *v, cursor, stream.isOpen());
          stream.data()->clear();
          print(prefix + name + "-" + mode + "-snapshot-" + std::to_string(origin), *v, cursor, stream.isOpen());
        }
      }
      MP4::File owner(path.c_str(), true, style);
      print("owner-" + name + "-" + mode, *owner.audioProperties());
    }
  }
}
