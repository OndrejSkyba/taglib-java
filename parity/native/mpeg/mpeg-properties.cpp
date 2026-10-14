// Exact pinned MPEG::File construction and MPEG::Properties getter/cursor observations.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <mpegfile.h>
#include <mpegproperties.h>
#include <tbytevectorstream.h>
#include <xingheader.h>
using namespace TagLib;

static void print(const std::string &key, const MPEG::Properties &p, offset_t cursor, bool open) {
  const auto *x = p.xingHeader();
  std::cout << key << '\t' << p.lengthInMilliseconds() << '\t' << p.lengthInSeconds()
      << '\t' << p.bitrate() << '\t' << p.sampleRate() << '\t' << p.channels()
      << '\t' << p.version() << '\t' << p.layer() << '\t' << p.protectionEnabled()
      << '\t' << p.channelMode() << '\t' << p.channelConfiguration()
      << '\t' << p.isADTS() << '\t' << p.isCopyrighted() << '\t' << p.isOriginal()
      << '\t' << (x != nullptr) << '\t' << (x ? static_cast<int>(x->type()) : -1)
      << '\t' << (x ? x->totalFrames() : 0) << '\t' << (x ? x->totalSize() : 0)
      << '\t' << cursor << '\t' << open << '\n';
}

int main(int argc, char **argv) {
  if (argc != 2) return 2;
  std::map<std::string, std::filesystem::path> inputs;
  for (const auto &e: std::filesystem::directory_iterator(std::filesystem::path(argv[1]) / "inputs"))
    inputs[e.path().filename().string()] = e.path();
  for (const auto &[name, path]: inputs) {
    std::ifstream in(path, std::ios::binary);
    std::string raw((std::istreambuf_iterator<char>(in)), {});
    for (const std::string mode: {"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
      auto style = mode == "FAST"
                     ? AudioProperties::Fast
                     : mode == "ACCURATE"
                         ? AudioProperties::Accurate
                         : AudioProperties::Average;
      MPEG::File file(path.c_str(), false, style);
      auto p = mode == "DEFAULT" ? MPEG::Properties(&file) : MPEG::Properties(&file, style);
      print(name + "-" + mode + "-path", p, -1, file.isOpen());
      for (int origin: {0, 7}) {
        ByteVectorStream stream(ByteVector(raw.data(), raw.size()));
        stream.seek(origin);
        MPEG::File borrowed(&stream, false, style);
        auto properties = mode == "DEFAULT" ? MPEG::Properties(&borrowed) : MPEG::Properties(&borrowed, style);
        auto cursor = stream.tell();
        print(name + "-" + mode + "-stream-" + std::to_string(origin), properties, cursor, stream.isOpen());
        stream.data()->clear();
        print(name + "-" + mode + "-snapshot-" + std::to_string(origin), properties, cursor, stream.isOpen());
      }
    }
  }
}
