// Detailed APE/MPC/WavPack property oracle against the pinned native release.
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include "apefile.h"
#include "mpcfile.h"
#include "wavpackfile.h"
using namespace TagLib;

void common(const AudioProperties *p) {
  if (!p) throw std::runtime_error("Missing native properties");
  std::cout << p->lengthInMilliseconds() << '|' << p->bitrate() << '|' << p->sampleRate() << '|' << p->channels();
}

int main(int argc, char **argv) {
  if (argc != 2) return 2;
  std::ifstream manifest(std::string(argv[1]) + "/files.tsv");
  if (!manifest) return 3;
  std::string line;
  while (std::getline(manifest, line)) {
    auto split = line.find('\t');
    if (split == std::string::npos) return 4;
    auto name = line.substr(0, split), path = line.substr(split + 1);
    std::cout << name << '|';
    if (name.size() >= 4 && name.substr(name.size() - 4) == ".ape") {
      APE::File file(path.c_str());
      auto *p = file.audioProperties();
      common(p);
      std::cout << '|' << p->version() << '|' << p->bitsPerSample() << '|' << p->sampleFrames();
    } else if (name.size() >= 3 && name.substr(name.size() - 3) == ".wv") {
      WavPack::File file(path.c_str());
      auto *p = file.audioProperties();
      common(p);
      std::cout << '|' << p->version() << '|' << p->bitsPerSample() << '|' << p->sampleFrames() << '|' << p->
          isLossless() << '|' << p->isDsd();
    } else {
      MPC::File file(path.c_str());
      auto *p = file.audioProperties();
      common(p);
      std::cout << '|' << p->mpcVersion() << '|' << p->totalFrames() << '|' << p->sampleFrames() << '|' << p->
          trackGain() << '|' << p->trackPeak() << '|' << p->albumGain() << '|' << p->albumPeak();
    }
    std::cout << '\n';
  }
}
