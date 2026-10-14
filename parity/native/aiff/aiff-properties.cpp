// Every declared AIFF property field, constructor style, borrowed cursor and lifetime.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <aifffile.h>
#include <aiffproperties.h>
#include <tbytevectorstream.h>
using namespace TagLib;

static void hex(const ByteVector &value) {
  const char digits[] = "0123456789abcdef";
  for (unsigned char c: value)std::cout << digits[c >> 4] << digits[c & 15];
}

static void print(const std::string &key, const RIFF::AIFF::Properties &p, offset_t cursor, bool open) {
  std::cout << key << '\t' << p.lengthInMilliseconds() << '\t' << p.lengthInSeconds() << '\t' << p.bitrate()
      << '\t' << p.sampleRate() << '\t' << p.channels() << '\t' << p.bitsPerSample() << '\t' << p.sampleFrames()
      << '\t' << p.isAiffC() << '\t';
  hex(p.compressionType());
  std::cout << '\t';
  hex(p.compressionName().data(String::UTF8));
  std::cout << '\t' << cursor << '\t' << open << '\n';
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
      RIFF::AIFF::File file(path.c_str(), false, style);
      if (!file.isValid())throw std::runtime_error("Invalid recipe " + name);
      // AIFF Properties has no default style argument; Java Path default adapts File's Average.
      RIFF::AIFF::Properties p(&file, style);
      print(name + "-" + mode + "-path", p, -1, file.isOpen());
      ByteVectorStream stream(ByteVector(raw.data(), raw.size()));
      RIFF::AIFF::File borrowed(&stream, false, style);
      RIFF::AIFF::Properties v(&borrowed, style);
      auto cursor = stream.tell();
      print(name + "-" + mode + "-stream", v, cursor, stream.isOpen());
      stream.data()->clear();
      print(name + "-" + mode + "-snapshot", v, cursor, stream.isOpen());
    }
  }
}
