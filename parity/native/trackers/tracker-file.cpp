// Pinned native live tracker Files: partial state, controls and complete repeated saves.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <vector>
#include <algorithm>
#include "tbytevectorstream.h"
#include "mod/modfile.h"
#include "s3m/s3mfile.h"
#include "it/itfile.h"
#include "xm/xmfile.h"
using namespace TagLib;

void emit(const std::string &id, const Mod::Properties &p, long long cursor) {
  std::cout << id << '\t' << static_cast<long long>(p.lengthInMilliseconds()) << '\t' << static_cast<long long>(p.
        lengthInSeconds()) << '\t' << static_cast<long long>(p.bitrate()) << '\t' << static_cast<long long>(p.
        sampleRate())
      << '\t' << static_cast<long long>(p.channels()) << '\t' << static_cast<long long>(p.instrumentCount()) << '\t' <<
      static_cast<long long>(p.lengthInPatterns()) << '\t' << cursor << '\n';
}

void emit(const std::string &id, const S3M::Properties &p, long long cursor) {
  std::cout << id << '\t' << static_cast<long long>(p.lengthInMilliseconds()) << '\t' << static_cast<long long>(p.
        lengthInSeconds()) << '\t' << static_cast<long long>(p.bitrate()) << '\t' << static_cast<long long>(p.
        sampleRate())
      << '\t' << static_cast<long long>(p.lengthInPatterns()) << '\t' << static_cast<long long>(p.channels()) << '\t' <<
      static_cast<long long>(p.stereo()) << '\t' << static_cast<long long>(p.sampleCount()) << '\t' << static_cast<long
        long>(p.patternCount()) << '\t' << static_cast<long long>(p.flags()) << '\t' << static_cast<long long>(p.
        trackerVersion()) << '\t' << static_cast<long long>(p.fileFormatVersion()) << '\t' << static_cast<long long>(p.
        globalVolume()) << '\t' << static_cast<long long>(p.masterVolume()) << '\t' << static_cast<long long>(p.tempo())
      <<
      '\t' << static_cast<long long>(p.bpmSpeed()) << '\t' << cursor << '\n';
}

void emit(const std::string &id, const IT::Properties &p, long long cursor) {
  std::cout << id << '\t' << static_cast<long long>(p.lengthInMilliseconds()) << '\t' << static_cast<long long>(p.
        lengthInSeconds()) << '\t' << static_cast<long long>(p.bitrate()) << '\t' << static_cast<long long>(p.
        sampleRate())
      << '\t' << static_cast<long long>(p.channels()) << '\t' << static_cast<long long>(p.lengthInPatterns()) << '\t' <<
      static_cast<long long>(p.instrumentCount()) << '\t' << static_cast<long long>(p.sampleCount()) << '\t' <<
      static_cast<
        long long>(p.patternCount()) << '\t' << static_cast<long long>(p.version()) << '\t' << static_cast<long long>(p.
        compatibleVersion()) << '\t' << static_cast<long long>(p.flags()) << '\t' << static_cast<long long>(p.special())
      <<
      '\t' << static_cast<long long>(p.globalVolume()) << '\t' << static_cast<long long>(p.mixVolume()) << '\t' <<
      static_cast<long long>(p.tempo()) << '\t' << static_cast<long long>(p.bpmSpeed()) << '\t' << static_cast<long
        long>(p.
        panningSeparation()) << '\t' << static_cast<long long>(p.pitchWheelDepth()) << '\t' << static_cast<long long>(p.
        stereo()) << '\t' << cursor << '\n';
}

void emit(const std::string &id, const XM::Properties &p, long long cursor) {
  std::cout << id << '\t' << static_cast<long long>(p.lengthInMilliseconds()) << '\t' << static_cast<long long>(p.
        lengthInSeconds()) << '\t' << static_cast<long long>(p.bitrate()) << '\t' << static_cast<long long>(p.
        sampleRate())
      << '\t' << static_cast<long long>(p.lengthInPatterns()) << '\t' << static_cast<long long>(p.channels()) << '\t' <<
      static_cast<long long>(p.version()) << '\t' << static_cast<long long>(p.restartPosition()) << '\t' << static_cast<
        long
        long>(p.patternCount()) << '\t' << static_cast<long long>(p.instrumentCount()) << '\t' << static_cast<long long>
      (p.
        sampleCount()) << '\t' << static_cast<long long>(p.flags()) << '\t' << static_cast<long long>(p.tempo()) << '\t'
      <<
      static_cast<long long>(p.bpmSpeed()) << '\t' << cursor << '\n';
}

std::string hex(const ByteVector &b) {
  const char *d = "0123456789abcdef";
  std::string s;
  for (unsigned int i = 0; i < b.size(); i++) {
    unsigned char c = b[i];
    s += d[c >> 4];
    s += d[c & 15];
  }
  return s;
}

std::string hex(const String &s) { return hex(s.data(String::UTF8)); }

ByteVector read(const std::filesystem::path &p) {
  std::ifstream f(p, std::ios::binary);
  std::string s((std::istreambuf_iterator<char>(f)), {});
  return ByteVector(s.data(), static_cast<unsigned int>(s.size()));
}

template<typename F>
void state(const std::string &id, const F &file, long long cursor) {
  if (!file.isValid()) {
    std::cout << id << "/rejected\t1" << '\n';
    return;
  }
  auto tag = file.tag();
  std::cout << id << "/tag\t" << file.isValid() << '\t' << hex(tag->title()) << '\t' << hex(tag->comment()) << '\t' <<
      hex(tag->trackerName()) << '\t' << cursor << '\n';
  emit(id + "/props", *file.audioProperties(), cursor);
}

template<typename F>
void run(const std::filesystem::path &input, const std::filesystem::path &out) {
  auto data = read(input);
  auto id = input.filename().string();
  for (int origin: {0, 7}) {
    ByteVectorStream stream(data);
    stream.seek(origin);
    F file(&stream);
    auto base = id + "/io/" + std::to_string(origin);
    state(base, file, stream.tell());
    if (!file.isValid())continue;
    file.tag()->setTitle("Edited title");
    file.tag()->setComment("first\nsecond\nthird\nmessage");
    file.tag()->setTrackerName("Edited tracker");
    for (int repeat = 0; repeat < 2; repeat++) {
      bool saved = file.save();
      std::cout << base << "/save/" << repeat << '\t' << saved << '\t' << stream.tell() << '\t' << hex(*stream.data())
          << '\n';
      state(base + "/memory/" + std::to_string(repeat), file, stream.tell());
    }
  }
  for (int style = 0; style < 3; style++)
    for (int enabled = 0; enabled < 2; enabled++) {
      auto base = id + "/controls/" + std::to_string(style) + "/" + std::to_string(enabled);
      F pathFile(input.c_str(), enabled, static_cast<AudioProperties::ReadStyle>(style));
      state(base + "/path", pathFile, pathFile.tell());
      for (int origin: {0, 7}) {
        ByteVectorStream stream(data);
        stream.seek(origin);
        F file(&stream, enabled, static_cast<AudioProperties::ReadStyle>(style));
        state(base + "/io/" + std::to_string(origin), file, stream.tell());
      }
    }
  auto copy = out / (id + ".native");
  std::filesystem::copy_file(input, copy, std::filesystem::copy_options::overwrite_existing);
  F file(copy.c_str());
  state(id + "/path", file, file.tell());
  if (!file.isValid())return;
  file.tag()->setTitle("Edited title");
  file.tag()->setComment("first\nsecond\nthird\nmessage");
  file.tag()->setTrackerName("Edited tracker");
  for (int repeat = 0; repeat < 2; repeat++) {
    bool saved = file.save();
    file.seek(file.tell());
    std::cout << id << "/save/" << repeat << '\t' << saved << '\t' << file.tell() << '\t' << hex(read(copy)) << '\n';
    state(id + "/memory/" + std::to_string(repeat), file, file.tell());
  }
}

int main(int argc, char **argv) {
  std::filesystem::path root(argv[1]);
  std::filesystem::create_directories(root / "outputs");
  std::vector<std::filesystem::path> paths;
  for (auto &p: std::filesystem::directory_iterator(root / "inputs"))paths.push_back(p.path());
  std::sort(paths.begin(), paths.end());
  for (auto &p: paths) {
    auto ext = p.extension();
    if (ext == ".mod")run<Mod::File>(p, root / "outputs");
    else if (ext == ".s3m")run<S3M::File>(p, root / "outputs");
    else if (ext == ".it")run<IT::File>(p, root / "outputs");
    else if (ext == ".xm")run<XM::File>(p, root / "outputs");
    else throw std::runtime_error("format");
  }
}
