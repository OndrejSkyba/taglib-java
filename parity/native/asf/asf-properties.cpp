// Native ASF property setters and complete-file decoding against TagLib.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <limits>
#include <asffile.h>
#include <asfproperties.h>
#include <tbytevectorstream.h>
using namespace TagLib;

static void hex(const ByteVector &value) {
  const char digits[] = "0123456789abcdef";
  for (unsigned char c: value)std::cout << digits[c >> 4] << digits[c & 15];
}

static void print(const std::string &key, const ASF::Properties &p, offset_t cursor = -1, bool open = true) {
  std::cout << key << '\t' << p.lengthInMilliseconds() << '\t' << p.lengthInSeconds() << '\t' << p.bitrate()
      << '\t' << p.sampleRate() << '\t' << p.channels() << '\t' << p.bitsPerSample() << '\t' << p.codec() << '\t';
  hex(p.codecName().data(String::UTF8));
  std::cout << '\t';
  hex(p.codecDescription().data(String::UTF8));
  std::cout << '\t' << p.isEncrypted() << '\t' << cursor << '\t' << open << '\n';
}

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  ASF::Properties p;
  print("default", p);
  for (int value: {
         std::numeric_limits<int>::min(), -1001, -999, -1, 0, 1, 999, 1000, std::numeric_limits<int>::max()
       }) {
    p.setLengthInMilliseconds(value);
    print("length-" + std::to_string(value), p);
    p.setBitrate(value);
    print("bitrate-" + std::to_string(value), p);
    p.setSampleRate(value);
    print("rate-" + std::to_string(value), p);
    p.setChannels(value);
    print("channels-" + std::to_string(value), p);
    p.setBitsPerSample(value);
    print("bits-" + std::to_string(value), p);
  }
  for (int value: {-1, 0, 1, 0x15f, 0x160, 0x161, 0x162, 0x163, 0x164, 65535}) {
    p.setCodec(value);
    print("codec-" + std::to_string(value), p);
  }
  String embedded("axb");
  embedded[1] = 0;
  const String texts[] = {
    String(), String(" \t name\n "), String(ByteVector("a\0b", 3), String::UTF8),
    String(ByteVector("\xC3\xA9\xE9\x9F\xB3", 5), String::UTF8), embedded
  };
  for (int i = 0; i < 5; i++) {
    p.setCodecName(texts[i]);
    print("name-" + std::to_string(i), p);
    p.setCodecDescription(texts[i]);
    print("description-" + std::to_string(i), p);
  }
  p.setEncrypted(true);
  print("encrypted", p);
  p.setEncrypted(false);
  print("unencrypted", p);
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
      ASF::File file(path.c_str(), false, style);
      if (!file.isValid())throw std::runtime_error("Invalid recipe " + name);
      print(name + "-" + mode + "-path", *file.audioProperties(), -1, file.isOpen());
      for (int origin: {0, 7}) {
        ByteVector source(origin, 'p');
        source.append(ByteVector(raw.data(), raw.size()));
        ByteVectorStream stream(source);
        stream.seek(origin);
        ASF::File borrowed(&stream, false, style);
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
