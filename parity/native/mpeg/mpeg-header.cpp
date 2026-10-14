// TagLib MPEG Header/XingHeader snapshots, getters, copies and cursor behavior.
#include <iostream>
#include <fstream>
#include <filesystem>
#include <map>
#include <tfile.h>
#include <tbytevectorstream.h>
#include <mpegheader.h>
#include <mpegutils.h>
#include <xingheader.h>
using namespace TagLib;

class MemoryFile : public File {
public:
  explicit MemoryFile(IOStream *s) : File(s) {
  }

  TagLib::Tag *tag() const override { return nullptr; }
  AudioProperties *audioProperties() const override { return nullptr; }
  bool save() override { return false; }
  void invalidate() { setValid(false); }
};

static void header(const std::string &name, const MPEG::Header &h, offset_t cursor) {
  std::cout << name << '\t' << h.isValid() << '\t' << h.version() << '\t' << h.layer()
      << '\t' << h.protectionEnabled() << '\t' << h.bitrate() << '\t' << h.sampleRate()
      << '\t' << h.isPadded() << '\t' << h.channelMode() << '\t' << h.channelConfiguration()
      << '\t' << h.isADTS() << '\t' << h.isCopyrighted() << '\t' << h.isOriginal()
      << '\t' << h.frameLength() << '\t' << h.samplesPerFrame() << '\t' << cursor << '\n';
}

int main(int argc, char **argv) {
  if (argc != 2) return 2;
  for (unsigned int first = 0; first < 256; ++first) {
    for (unsigned int second = 0; second < 256; ++second) {
      ByteVector pair(2, '\0');
      pair[0] = first;
      pair[1] = second;
      std::cout << "sync-" << first << '-' << second << '\t' << MPEG::isFrameSync(pair) << '\n';
    }
  }
  for (const std::string kind: {"headers", "xing"}) {
    std::map<std::string, std::filesystem::path> inputs;
    for (const auto &e: std::filesystem::directory_iterator(std::filesystem::path(argv[1]) / kind))
      inputs[e.path().filename().string()] = e.path();
    for (const auto &[name, path]: inputs) {
      std::ifstream in(path, std::ios::binary);
      std::string raw((std::istreambuf_iterator<char>(in)), {});
      ByteVector data(raw.data(), static_cast<unsigned int>(raw.size()));
      if (kind == "xing") {
        MPEG::XingHeader h(data);
        std::cout << name << '\t' << h.isValid() << '\t' << h.type() << '\t'
            << h.totalFrames() << '\t' << h.totalSize() << '\n';
      } else {
        for (int origin: {0, 7})
          for (bool check: {false, true}) {
            ByteVectorStream stream(ByteVector(origin, '!') + data);
            MemoryFile file(&stream);
            if (name == "invalid-file.bin") file.invalidate();
            // The default argument is exercised on the checked path.
            auto h = check ? MPEG::Header(&file, origin) : MPEG::Header(&file, origin, false);
            auto cursor = stream.tell();
            auto key = name + "-" + std::to_string(origin) + "-" + std::to_string(check);
            header(key, h, cursor);
            MPEG::Header copy(h);
            stream.data()->clear();
            header(key + "-copy", copy, cursor);
          }
      }
    }
  }
}
