// Native installed tracker property operations, pinned TagLib.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
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

void mutate(Mod::Properties &p, int field, long long value) {
  switch (field) {
    case 0: p.setChannels(static_cast<int>(value));
      break;
    case 1: p.setInstrumentCount(static_cast<unsigned int>(value));
      break;
    case 2: p.setLengthInPatterns(static_cast<unsigned char>(value));
      break;
    default: throw std::runtime_error("unknown setter");
  }
}

void operations_mod(const std::string &base, int field, long long value, int style) {
  Mod::Properties p(static_cast<AudioProperties::ReadStyle>(style));
  if (field >= 0) mutate(p, field, value);
  else if (field == -2) for (int index = 0; index < 3; index++)mutate(p, index, value);
  emit(base + "/" + std::to_string(style), p, -1);
}

void modFileResult(const std::string &id, const Mod::File &file, long long cursor) {
  if (file.isValid())emit(id, *file.audioProperties(), cursor);
  else std::cout << id << "\tREJECT\t" << cursor << '\n';
}

void file_mod(const std::filesystem::path &path, const ByteVector &data) {
  std::string name = path.filename().string();
  const bool expectedValid = data.size() >= 1084;
  for (int style = 0; style < 3; style++)
    for (int enabled = 0; enabled < 2; enabled++) {
      auto readStyle = static_cast<AudioProperties::ReadStyle>(style);
      Mod::File file(path.c_str(), enabled, readStyle);
      if (file.isValid() != expectedValid)throw std::runtime_error("unexpected MOD validity: " + name);
      modFileResult(name + "/path/" + std::to_string(enabled) + "/" + std::to_string(style), file, -1);
      for (int origin: std::vector<int>{0, 7})
        for (int snapshot = 0; snapshot < 2; snapshot++) {
          ByteVectorStream stream(data);
          stream.seek(origin);
          Mod::File borrowed(&stream, enabled, readStyle);
          if (borrowed.isValid() != expectedValid)throw std::runtime_error("unexpected MOD borrowed validity: " + name);
          if (snapshot)stream.data()->clear();
          modFileResult(
            name + "/io/" + std::to_string(enabled) + "/" + std::to_string(style) + "/" + std::to_string(origin) + "/" +
            std::to_string(snapshot), borrowed, stream.tell());
        }
    }
  Mod::File defaults(path.c_str());
  modFileResult(name + "/path/default", defaults, -1);
  for (int snapshot = 0; snapshot < 2; snapshot++) {
    ByteVectorStream stream(data);
    Mod::File f(&stream);
    if (snapshot)stream.data()->clear();
    modFileResult(name + "/io/default/" + std::to_string(snapshot), f, stream.tell());
  }
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

void mutate(S3M::Properties &p, int field, long long value) {
  switch (field) {
    case 0: p.setLengthInPatterns(static_cast<unsigned short>(value));
      break;
    case 1: p.setChannels(static_cast<int>(value));
      break;
    case 2: p.setStereo(value != 0);
      break;
    case 3: p.setSampleCount(static_cast<unsigned short>(value));
      break;
    case 4: p.setPatternCount(static_cast<unsigned short>(value));
      break;
    case 5: p.setFlags(static_cast<unsigned short>(value));
      break;
    case 6: p.setTrackerVersion(static_cast<unsigned short>(value));
      break;
    case 7: p.setFileFormatVersion(static_cast<unsigned short>(value));
      break;
    case 8: p.setGlobalVolume(static_cast<unsigned char>(value));
      break;
    case 9: p.setMasterVolume(static_cast<unsigned char>(value));
      break;
    case 10: p.setTempo(static_cast<unsigned char>(value));
      break;
    case 11: p.setBpmSpeed(static_cast<unsigned char>(value));
      break;
    default: throw std::runtime_error("unknown setter");
  }
}

void operations_s3m(const std::string &base, int field, long long value, int style) {
  S3M::Properties p(static_cast<AudioProperties::ReadStyle>(style));
  if (field >= 0) mutate(p, field, value);
  else if (field == -2) for (int index = 0; index < 12; index++)mutate(p, index, value);
  emit(base + "/" + std::to_string(style), p, -1);
}

void file_s3m(const std::filesystem::path &path, const ByteVector &data) {
  std::string name = path.filename().string();
  std::vector<int> origins{0};
  if (data.size() > 36 && data[35] == 26 && data[36] == 16) origins.push_back(7);
  for (int style = 0; style < 3; style++)
    for (int enabled = 0; enabled < 2; enabled++) {
      auto readStyle = static_cast<AudioProperties::ReadStyle>(style);
      S3M::File file(path.c_str(), enabled, readStyle);
      if (!file.isValid())throw std::runtime_error("unexpected invalid fixture: " + name);
      emit(name + "/path/" + std::to_string(enabled) + "/" + std::to_string(style), *file.audioProperties(), -1);
      for (int origin: origins)
        for (int snapshot = 0; snapshot < 2; snapshot++) {
          ByteVectorStream stream(data);
          stream.seek(origin);
          S3M::File borrowed(&stream, enabled, readStyle);
          if (!borrowed.isValid())throw std::runtime_error("unexpected invalid borrowed fixture: " + name);
          if (snapshot)stream.data()->clear();
          emit(name + "/io/" + std::to_string(enabled) + "/" + std::to_string(style) + "/" + std::to_string(origin) +
               "/" + std::to_string(snapshot), *borrowed.audioProperties(), stream.tell());
        }
    }
  S3M::File defaults(path.c_str());
  emit(name + "/path/default", *defaults.audioProperties(), -1);
  for (int snapshot = 0; snapshot < 2; snapshot++) {
    ByteVectorStream stream(data);
    S3M::File f(&stream);
    if (snapshot)stream.data()->clear();
    emit(name + "/io/default/" + std::to_string(snapshot), *f.audioProperties(), stream.tell());
  }
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

void mutate(IT::Properties &p, int field, long long value) {
  switch (field) {
    case 0: p.setChannels(static_cast<int>(value));
      break;
    case 1: p.setLengthInPatterns(static_cast<unsigned short>(value));
      break;
    case 2: p.setInstrumentCount(static_cast<unsigned short>(value));
      break;
    case 3: p.setSampleCount(static_cast<unsigned short>(value));
      break;
    case 4: p.setPatternCount(static_cast<unsigned short>(value));
      break;
    case 5: p.setVersion(static_cast<unsigned short>(value));
      break;
    case 6: p.setCompatibleVersion(static_cast<unsigned short>(value));
      break;
    case 7: p.setFlags(static_cast<unsigned short>(value));
      break;
    case 8: p.setSpecial(static_cast<unsigned short>(value));
      break;
    case 9: p.setGlobalVolume(static_cast<unsigned char>(value));
      break;
    case 10: p.setMixVolume(static_cast<unsigned char>(value));
      break;
    case 11: p.setTempo(static_cast<unsigned char>(value));
      break;
    case 12: p.setBpmSpeed(static_cast<unsigned char>(value));
      break;
    case 13: p.setPanningSeparation(static_cast<unsigned char>(value));
      break;
    case 14: p.setPitchWheelDepth(static_cast<unsigned char>(value));
      break;
    default: throw std::runtime_error("unknown setter");
  }
}

void operations_it(const std::string &base, int field, long long value, int style) {
  IT::Properties p(static_cast<AudioProperties::ReadStyle>(style));
  if (field >= 0) mutate(p, field, value);
  else if (field == -2) for (int index = 0; index < 15; index++)mutate(p, index, value);
  emit(base + "/" + std::to_string(style), p, -1);
}

void file_it(const std::filesystem::path &path, const ByteVector &data) {
  std::string name = path.filename().string();
  for (int style = 0; style < 3; style++)
    for (int enabled = 0; enabled < 2; enabled++) {
      auto readStyle = static_cast<AudioProperties::ReadStyle>(style);
      IT::File file(path.c_str(), enabled, readStyle);
      if (!file.isValid())throw std::runtime_error("unexpected invalid fixture: " + name);
      emit(name + "/path/" + std::to_string(enabled) + "/" + std::to_string(style), *file.audioProperties(), -1);
      for (int origin: std::vector<int>{0, 7})
        for (int snapshot = 0; snapshot < 2; snapshot++) {
          ByteVectorStream stream(data);
          stream.seek(origin);
          IT::File borrowed(&stream, enabled, readStyle);
          if (!borrowed.isValid())throw std::runtime_error("unexpected invalid borrowed fixture: " + name);
          if (snapshot)stream.data()->clear();
          emit(name + "/io/" + std::to_string(enabled) + "/" + std::to_string(style) + "/" + std::to_string(origin) +
               "/" + std::to_string(snapshot), *borrowed.audioProperties(), stream.tell());
        }
    }
  IT::File defaults(path.c_str());
  emit(name + "/path/default", *defaults.audioProperties(), -1);
  for (int snapshot = 0; snapshot < 2; snapshot++) {
    ByteVectorStream stream(data);
    IT::File f(&stream);
    if (snapshot)stream.data()->clear();
    emit(name + "/io/default/" + std::to_string(snapshot), *f.audioProperties(), stream.tell());
  }
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

void mutate(XM::Properties &p, int field, long long value) {
  switch (field) {
    case 0: p.setLengthInPatterns(static_cast<unsigned short>(value));
      break;
    case 1: p.setChannels(static_cast<int>(value));
      break;
    case 2: p.setVersion(static_cast<unsigned short>(value));
      break;
    case 3: p.setRestartPosition(static_cast<unsigned short>(value));
      break;
    case 4: p.setPatternCount(static_cast<unsigned short>(value));
      break;
    case 5: p.setInstrumentCount(static_cast<unsigned short>(value));
      break;
    case 6: p.setSampleCount(static_cast<unsigned int>(value));
      break;
    case 7: p.setFlags(static_cast<unsigned short>(value));
      break;
    case 8: p.setTempo(static_cast<unsigned short>(value));
      break;
    case 9: p.setBpmSpeed(static_cast<unsigned short>(value));
      break;
    default: throw std::runtime_error("unknown setter");
  }
}

void operations_xm(const std::string &base, int field, long long value, int style) {
  XM::Properties p(static_cast<AudioProperties::ReadStyle>(style));
  if (field >= 0) mutate(p, field, value);
  else if (field == -2) for (int index = 0; index < 10; index++)mutate(p, index, value);
  emit(base + "/" + std::to_string(style), p, -1);
}

void file_xm(const std::filesystem::path &path, const ByteVector &data) {
  std::string name = path.filename().string();
  for (int style = 0; style < 3; style++)
    for (int enabled = 0; enabled < 2; enabled++) {
      auto readStyle = static_cast<AudioProperties::ReadStyle>(style);
      XM::File file(path.c_str(), enabled, readStyle);
      if (!file.isValid())throw std::runtime_error("unexpected invalid fixture: " + name);
      emit(name + "/path/" + std::to_string(enabled) + "/" + std::to_string(style), *file.audioProperties(), -1);
      for (int origin: std::vector<int>{0, 7})
        for (int snapshot = 0; snapshot < 2; snapshot++) {
          ByteVectorStream stream(data);
          stream.seek(origin);
          XM::File borrowed(&stream, enabled, readStyle);
          if (!borrowed.isValid())throw std::runtime_error("unexpected invalid borrowed fixture: " + name);
          if (snapshot)stream.data()->clear();
          emit(name + "/io/" + std::to_string(enabled) + "/" + std::to_string(style) + "/" + std::to_string(origin) +
               "/" + std::to_string(snapshot), *borrowed.audioProperties(), stream.tell());
        }
    }
  XM::File defaults(path.c_str());
  emit(name + "/path/default", *defaults.audioProperties(), -1);
  for (int snapshot = 0; snapshot < 2; snapshot++) {
    ByteVectorStream stream(data);
    XM::File f(&stream);
    if (snapshot)stream.data()->clear();
    emit(name + "/io/default/" + std::to_string(snapshot), *f.audioProperties(), stream.tell());
  }
}

int main(int argc, char **argv) {
  try {
    if (argc != 2)return 2;
    std::filesystem::path root(argv[1]);
    std::cout << "constants/mod" << '\n';
    for (int style = 0; style < 3; style++)operations_mod("default/mod", -1, 0, style);
    std::cout << "constants/s3m" << '\t' << S3M::Properties::ST2Vibrato << '\t' << S3M::Properties::ST2Tempo << '\t' <<
        S3M::Properties::AmigaSlides << '\t' << S3M::Properties::Vol0MixOptimizations << '\t' <<
        S3M::Properties::AmigaLimits << '\t' << S3M::Properties::EnableFilter << '\t' << S3M::Properties::CustomData <<
        '\n';
    for (int style = 0; style < 3; style++)operations_s3m("default/s3m", -1, 0, style);
    std::cout << "constants/it" << '\t' << IT::Properties::Stereo << '\t' << IT::Properties::Vol0MixOptimizations <<
        '\t' << IT::Properties::UseInstruments << '\t' << IT::Properties::LinearSlides << '\t' <<
        IT::Properties::OldEffects << '\t' << IT::Properties::LinkEffects << '\t' <<
        IT::Properties::UseMidiPitchController << '\t' << IT::Properties::RequestEmbeddedMidiConf << '\t' <<
        IT::Properties::MessageAttached << '\t' << IT::Properties::MidiConfEmbedded << '\n';
    for (int style = 0; style < 3; style++)operations_it("default/it", -1, 0, style);
    std::cout << "constants/xm" << '\t' << XM::Properties::LinearFreqTable << '\n';
    for (int style = 0; style < 3; style++)operations_xm("default/xm", -1, 0, style);
    std::ifstream cases(root / "cases.tsv");
    std::string family;
    int field;
    long long value;
    while (cases >> family >> field >> value) {
      std::string base = "setter/" + family + "/" + std::to_string(field) + "/" + std::to_string(value);
      for (int style = 0; style < 3; style++) {
        if (family == "mod")operations_mod(base, field, value, style);
        else if (family == "s3m")operations_s3m(base, field, value, style);
        else if (family == "it")operations_it(base, field, value, style);
        else if (family == "xm")operations_xm(base, field, value, style);
        else throw std::runtime_error("unknown family");
      }
    }
    std::vector<std::filesystem::path> inputs;
    for (auto &e: std::filesystem::directory_iterator(root / "inputs"))
      if (e.is_regular_file())
        inputs.
            push_back(e.path());
    std::sort(inputs.begin(), inputs.end());
    for (const auto &path: inputs) {
      std::ifstream f(path, std::ios::binary);
      std::string bytes((std::istreambuf_iterator<char>(f)), {});
      ByteVector data(bytes.data(), bytes.size());
      auto ext = path.extension().string();
      if (ext == ".mod")file_mod(path, data);
      else if (ext == ".s3m")file_s3m(path, data);
      else if (ext == ".it")file_it(path, data);
      else if (ext == ".xm")file_xm(path, data);
      else throw std::runtime_error("unknown input");
    }
    return 0;
  } catch (const std::exception &e) {
    std::cerr << e.what() << "\n";
    return 1;
  }
}
