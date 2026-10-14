// Pinned codec-file construction, detailed Ogg properties, cursor and detached lifetime.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <opusfile.h>
#include <vorbisfile.h>
#include <speexfile.h>
#include <tbytevectorstream.h>
using namespace TagLib;

static void fields(const Ogg::Opus::Properties &p) {
  std::cout << p.opusVersion() << '\t' << p.inputSampleRate() << '\t' << p.outputGain();
}

static void fields(const Vorbis::Properties &p) {
  std::cout << p.vorbisVersion() << '\t' << p.bitrateMaximum() << '\t'
      << p.bitrateNominal() << '\t' << p.bitrateMinimum();
}

static void fields(const Ogg::Speex::Properties &p) {
  std::cout << p.speexVersion() << '\t' << p.bitrateNominal();
}

template<class P>
static void print(const std::string &key, const P &p, offset_t cursor, bool open) {
  std::cout << key << '\t' << p.lengthInMilliseconds() << '\t' << p.lengthInSeconds()
      << '\t' << p.bitrate() << '\t' << p.sampleRate() << '\t' << p.channels() << '\t';
  fields(p);
  std::cout << '\t' << cursor << '\t' << open << '\n';
}

template<class F, class P>
static void probe(const std::string &name,
                  const std::filesystem::path &path, const std::string &raw) {
  for (const std::string mode: {"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
    auto style = mode == "FAST"
                   ? AudioProperties::Fast
                   : mode == "ACCURATE"
                       ? AudioProperties::Accurate
                       : AudioProperties::Average;
    F file(path.c_str(), false, style);
    auto p = mode == "DEFAULT" ? P(&file) : P(&file, style);
    print(name + "-" + mode + "-path", p, -1, file.isOpen());
    for (int origin: {0, 7}) {
      ByteVectorStream stream(ByteVector(raw.data(), raw.size()));
      stream.seek(origin);
      F borrowed(&stream, false, style);
      auto properties = mode == "DEFAULT" ? P(&borrowed) : P(&borrowed, style);
      auto cursor = stream.tell();
      print(name + "-" + mode + "-stream-" + std::to_string(origin), properties, cursor, stream.isOpen());
      auto repeated = mode == "DEFAULT" ? P(&borrowed) : P(&borrowed, style);
      print(name + "-" + mode + "-existing-" + std::to_string(origin), repeated, stream.tell(), stream.isOpen());
      stream.data()->clear();
      print(name + "-" + mode + "-snapshot-" + std::to_string(origin), repeated, cursor, stream.isOpen());
    }
  }
}

int main(int argc, char **argv) {
  if (argc != 2) return 2;
  std::map<std::string, std::filesystem::path> inputs;
  for (const auto &e: std::filesystem::directory_iterator(std::filesystem::path(argv[1]) / "inputs"))
    inputs[e.path().filename().string()] = e.path();
  for (const auto &[name, path]: inputs) {
    std::ifstream in(path, std::ios::binary);
    std::string raw((std::istreambuf_iterator<char>(in)), {});
    auto ext = path.extension();
    if (ext == ".opus") probe<Ogg::Opus::File, Ogg::Opus::Properties>(name, path, raw);
    else if (ext == ".spx") probe<Ogg::Speex::File, Ogg::Speex::Properties>(name, path, raw);
    else probe<Vorbis::File, Vorbis::Properties>(name, path, raw);
  }
}
