// Native Matroska decoded fields, empty direct construction, and lazy bitrate lifetime.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <matroskafile.h>
#include <matroskaproperties.h>
#include <tbytevectorstream.h>
using namespace TagLib;

static void hex(const ByteVector &v) {
  const char digits[] = "0123456789abcdef";
  for (unsigned char c: v)std::cout << digits[c >> 4] << digits[c & 15];
}

static void print(const std::string &key, const Matroska::Properties &p, offset_t cursor = -1, bool open = true) {
  std::cout << key << '\t' << p.lengthInMilliseconds() << '\t' << p.lengthInSeconds() << '\t' << p.bitrate() << '\t' <<
      p.sampleRate() << '\t' << p.channels() << '\t' << p.bitsPerSample() << '\t';
  hex(p.docType().data(String::UTF8));
  std::cout << '\t' << p.docTypeVersion() << '\t';
  hex(p.codecName().data(String::UTF8));
  std::cout << '\t';
  hex(p.title().data(String::UTF8));
  std::cout << '\t' << cursor << '\t' << open << '\n';
}

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  for (const std::string mode: {"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
    auto style = mode == "FAST"
                   ? AudioProperties::Fast
                   : mode == "ACCURATE"
                       ? AudioProperties::Accurate
                       : AudioProperties::Average;
    auto p = mode == "DEFAULT"
               ? std::make_unique<Matroska::Properties>(nullptr)
               : std::make_unique<Matroska::Properties>(nullptr, style);
    print("null-" + mode, *p);
  }
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
      Matroska::File file(path.c_str(), true, style);
      if (!file.isValid())throw std::runtime_error("Invalid recipe " + name);
      print(name + "-" + mode + "-path", *file.audioProperties());
      auto empty = mode == "DEFAULT"
                     ? std::make_unique<Matroska::Properties>(&file)
                     : std::make_unique<Matroska::Properties>(&file, style);
      print(name + "-" + mode + "-direct", *empty);
      for (int origin: {0, 7}) {
        ByteVector source(origin, 'p');
        source.append(ByteVector(raw.data(), raw.size()));
        ByteVectorStream stream(source);
        stream.seek(origin);
        Matroska::File borrowed(&stream, true, style);
        if (!borrowed.isValid())throw std::runtime_error("Invalid borrowed recipe " + name);
        auto cursor = stream.tell();
        print(name + "-" + mode + "-stream-" + std::to_string(origin), *borrowed.audioProperties(), cursor,
              stream.isOpen());
        stream.data()->clear();
        print(name + "-" + mode + "-snapshot-" + std::to_string(origin), *borrowed.audioProperties(), cursor,
              stream.isOpen());
        ByteVectorStream lazy(source);
        lazy.seek(origin);
        Matroska::File uncached(&lazy, true, style);
        if (!uncached.isValid())throw std::runtime_error("Invalid lazy recipe " + name);
        auto last = lazy.tell();
        lazy.data()->clear();
        print(name + "-" + mode + "-lazy-clear-" + std::to_string(origin), *uncached.audioProperties(), last,
              lazy.isOpen());
        lazy.data()->append(ByteVector(1000, 'x'));
        print(name + "-" + mode + "-lazy-grow-" + std::to_string(origin), *uncached.audioProperties(), last,
              lazy.isOpen());
      }
    }
  }
}
