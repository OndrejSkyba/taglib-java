// All declared DSF/DSDIFF/TrueAudio getters, native data constructors and codec File adapters.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <vector>
#include <dsfproperties.h>
#include <dsffile.h>
#include <dsdiffproperties.h>
#include <dsdifffile.h>
#include <trueaudioproperties.h>
#include <trueaudiofile.h>
#include <tbytevectorstream.h>
using namespace TagLib;

static ByteVector hex(const std::string &s) {
  ByteVector value;
  for (size_t i = 0; i < s.size(); i += 2) value.append(static_cast<char>(std::stoul(s.substr(i, 2), nullptr, 16)));
  return value;
}

static void fields(const DSF::Properties &p) {
  std::cout << p.formatVersion() << '\t' << p.formatID() << '\t' << p.channelType() << '\t'
      << p.sampleCount() << '\t' << p.blockSizePerChannel();
}

static void fields(const DSDIFF::Properties &p) { std::cout << p.sampleCount(); }
static void fields(const TrueAudio::Properties &p) { std::cout << p.ttaVersion() << '\t' << p.sampleFrames(); }

template<class P>
static void print(const std::string &key, const P &p, offset_t cursor = -1, bool open = true) {
  std::cout << key << '\t' << p.lengthInMilliseconds() << '\t' << p.lengthInSeconds() << '\t' << p.bitrate()
      << '\t' << p.sampleRate() << '\t' << p.channels() << '\t' << p.bitsPerSample() << '\t';
  fields(p);
  std::cout << '\t' << cursor << '\t' << open << '\n';
}

template<class F>
static void file(const std::string &name, const std::filesystem::path &path) {
  std::ifstream in(path, std::ios::binary);
  std::string raw((std::istreambuf_iterator<char>(in)), {});
  for (const std::string mode: {"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
    auto style = mode == "FAST"
                   ? AudioProperties::Fast
                   : mode == "ACCURATE"
                       ? AudioProperties::Accurate
                       : AudioProperties::Average;
    F f(path.c_str(), true, style);
    if (!f.isValid() || !f.audioProperties())throw std::runtime_error("Invalid fixture " + name);
    print(name + "-" + mode + "-path", *f.audioProperties());
    ByteVectorStream stream(ByteVector(raw.data(), raw.size()));
    F borrowed(&stream, true, style);
    if (!borrowed.isValid() || !borrowed.audioProperties())throw std::runtime_error("Invalid borrowed fixture " + name);
    auto cursor = stream.tell();
    print(name + "-" + mode + "-stream", *borrowed.audioProperties(), cursor, stream.isOpen());
    stream.data()->clear();
    print(name + "-" + mode + "-snapshot", *borrowed.audioProperties(), cursor, stream.isOpen());
  }
}

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  std::filesystem::path root(argv[1]);
  std::ifstream rows(root / "cases.tsv");
  std::string line;
  while (std::getline(rows, line)) {
    std::vector<std::string> values;
    std::stringstream input(line);
    std::string v;
    while (std::getline(input, v, '\t'))values.push_back(v);
    if (!line.empty() && line.back() == '\t') values.emplace_back();
    if (values.size() < 3) throw std::runtime_error("Malformed probe recipe");
    for (const std::string mode: {"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
      auto style = mode == "FAST"
                     ? AudioProperties::Fast
                     : mode == "ACCURATE"
                         ? AudioProperties::Accurate
                         : AudioProperties::Average;
      auto key = values[0] + "-" + mode;
      if (values[1] == "dsf") {
        auto data = hex(values[2]);
        DSF::Properties p(data, style);
        print(key, p);
        data.clear();
        print(key + "-snapshot", p);
      } else if (values[1] == "tta") {
        auto data = hex(values[2]);
        auto length = std::stoll(values[3]);
        auto p = mode == "DEFAULT" ? TrueAudio::Properties(data, length) : TrueAudio::Properties(data, length, style);
        print(key, p);
        data.clear();
        print(key + "-snapshot", p);
      } else {
        DSDIFF::Properties p(std::stoul(values[2]), std::stoul(values[3]), std::stoull(values[4]), std::stoi(values[5]),
                             style);
        print(key, p);
      }
    }
  }
  std::map<std::string, std::filesystem::path> paths;
  for (const auto &e: std::filesystem::directory_iterator(root / "inputs"))
    paths[e.path().filename().string()] = e.path();
  for (const auto &[name,path]: paths) {
    if (path.extension() == ".dsf")file<DSF::File>(name, path);
    else if (path.extension() == ".tta")file<TrueAudio::File>(name, path);
    else file<DSDIFF::File>(name, path);
  }
}
