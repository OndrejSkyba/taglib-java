// Development-only MPC File state/operation protocol against pinned TagLib.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <vector>
#include <algorithm>
#include "mpc/mpcfile.h"
#include "ape/apetag.h"
#include "ape/apefooter.h"
#include "mpeg/id3v1/id3v1tag.h"
#include "tbytevectorstream.h"
#include "tpropertymap.h"
#include "fileref.h"
#include "tfilestream.h"
using namespace TagLib;

std::string hex(const ByteVector &b) {
  auto h = b.toHex();
  return {h.data(), h.size()};
}

std::string hex(const String &s) { return hex(s.data(String::UTF8)); }

std::string list(const StringList &v) {
  std::string s = std::to_string(v.size()) + ":";
  for (auto &a: v)s += hex(a) + ",";
  return s;
}

std::string props(const PropertyMap &p) {
  std::string s;
  for (auto &[k,v]: p)s += hex(k) + "=" + list(v) + ";";
  return s + "/" + list(p.unsupportedData());
}

ByteVector read(const std::filesystem::path &p) {
  std::ifstream f(p, std::ios::binary);
  std::string s((std::istreambuf_iterator<char>(f)), {});
  return ByteVector(s.data(), s.size());
}

void state(const std::string &id, MPC::File &f) {
  auto *t = f.tag();
  auto *a = f.APETag();
  auto *v = f.ID3v1Tag();
  auto *p = f.audioProperties();
  std::cout << id << "/tag\t" << f.isOpen() << '\t' << f.isValid() << '\t' << t->isEmpty() << '\t' << hex(t->title()) <<
      '\t' << hex(t->artist()) << '\t' << hex(t->album()) << '\t' << hex(t->comment()) << '\t' << hex(t->genre()) <<
      '\t' << t->year() << '\t' << t->track() << '\n';
  std::cout << id << "/properties\t" << props(f.properties()) << '\n';
  std::cout << id << "/children\t" << f.hasAPETag() << '\t' << f.hasID3v1Tag() << '\t' << (a != nullptr) << '\t' << (
    v != nullptr) << '\t';
  if (a) {
    auto footer = a->footer();
    std::cout << props(a->properties()) << '|' << footer->version() << '|' << footer->tagSize() << '|' << footer->
        completeTagSize() << '|' << footer->itemCount() << '|' << footer->headerPresent() << '|' << footer->
        footerPresent() << '|' << footer->isHeader() << '|';
    for (auto &[k,i]: a->itemListMap())
      std::cout << hex(k) << ':' << hex(i.key()) << ':' << i.type() << ':' << i.
          isReadOnly() << ':' << list(i.values()) << ':' << hex(i.binaryData()) << ';';
  } else std::cout << "-";
  std::cout << '\t' << (v ? props(v->properties()) : "-") << '\n';
  std::cout << id << "/audio\t" << (p != nullptr);
  if (p)
    std::cout << '\t' << p->lengthInMilliseconds() << '\t' << p->lengthInSeconds() << '\t' << p->bitrate() << '\t'
        << p->sampleRate() << '\t' << p->channels() << '\t' << p->mpcVersion() << '\t' << p->sampleFrames() << '\t' << p
        ->totalFrames() << '\t' << p->trackGain() << '\t' << p->trackPeak() << '\t' << p->albumGain() << '\t' << p->
        albumPeak();
  std::cout << '\n';
}

struct Named : ByteVectorStream {
  std::string fileName;
  bool once;
  int descriptors = 0;

  Named(const ByteVector &b, std::string n, bool o) : ByteVectorStream(b), fileName(n), once(o) {
  }

  FileName name() const override { return fileName.c_str(); }

  ByteVector readBlock(size_t count) override {
    auto at = tell();
    auto b = once && at == 4 && count == 52 && ++descriptors > 1 ? ByteVector() : ByteVectorStream::readBlock(count);
    return b;
  }
};

void edit(MPC::File &f, int mode) {
  if (mode < 8) {
    f.strip(mode);
    return;
  }
  if (mode == 8) {
    f.ID3v1Tag(true)->setTitle("created legacy");
    f.APETag(true)->setTitle("created APE");
    f.tag()->setArtist("broadcast");
    return;
  }
  if (mode == 9) {
    PropertyMap p;
    p.insert("TITLE", {"one", "two"});
    p.insert("DATE", {"2026"});
    p.insert("ALBUMARTIST", {"other"});
    p.insert("ID3", {"invalid"});
    std::cout << "rejection\t" << props(f.setProperties(p)) << '\n';
    return;
  }
  f.APETag(true)->setData("OPAQUE", ByteVector("retained"));
  f.tag()->setTitle("Edited title");
  f.tag()->setYear(0xffffffffu);
  f.tag()->setTrack(0x80000000u);
}

// This is not a native save comparison: ByteVectorStream::insert passes a negative
// length to memmove when growing a retained replacement span outside its data.
// Do not execute that unsafe native operation; the Java adaptation is tested.
// The corresponding Java branch must reject before touching bytes or live state.
bool undefinedMemoryGrowth(MPC::File &f, const ByteVector &data) {
  if (f.hasAPETag() || !f.APETag() || f.APETag()->isEmpty())return false;
  long long footerAt = static_cast<long long>(data.size()) - (f.hasID3v1Tag() ? 160 : 32);
  if (footerAt < 0 || data.mid(footerAt, 8) != "APETAGEX")return false;
  unsigned int size = data.toUInt(footerAt + 12, false), flags = data.toUInt(footerAt + 20, false);
  unsigned int replacement = size + ((flags & 0x80000000u) ? 32u : 0u);
  if (footerAt + 32 >= replacement)return false;
  long long rendered = 64;
  for (auto &[k,item]: f.APETag()->itemListMap()) {
    APE::Item copy(item);
    rendered += copy.render().size();
  }
  return rendered > replacement && replacement > (f.ID3v1Tag() && !f.ID3v1Tag()->isEmpty() ? 128u : 0u);
}

int main(int argc, char **argv) {
  std::filesystem::path root(argv[1]);

  std::filesystem::create_directories(root / "outputs");
  std::vector<std::filesystem::path> paths;
  for (auto &p: std::filesystem::directory_iterator(root / "inputs"))paths.push_back(p.path());
  std::sort(paths.begin(), paths.end());
  for (auto &input: paths) {
    auto id = input.filename().string();
    auto data = read(input);
    for (int style = 0; style < 3; style++)
      for (int enabled = 0; enabled < 2; enabled++) {
        auto base = id + "/controls/" + std::to_string(style) + "/" + std::to_string(enabled);
        MPC::File f(input.c_str(), enabled, static_cast<AudioProperties::ReadStyle>(style));
        state(base + "/path", f);
        for (int origin: {0, 7}) {
          ByteVectorStream s(data);
          s.seek(origin);
          MPC::File m(&s, enabled, static_cast<AudioProperties::ReadStyle>(style));
          state(base + "/io/" + std::to_string(origin), m);
        }
      }
    for (int origin: {0, 7})
      for (long long length: {
             -128LL, -1LL, 0LL, 1LL, 32LL, 128LL, 2147483647LL, 9223372036854775807LL
           })
        for (int style = 0; style < 3; style++) {
          ByteVectorStream s(data);
          MPC::File f(&s, false);
          f.seek(origin);
          MPC::Properties p(&f, length, static_cast<AudioProperties::ReadStyle>(style));
          std::cout << id << "/direct-props/" << origin << "/" << length << "/" << style << '\t' << p.
              lengthInMilliseconds()
              << '\t' << p.lengthInSeconds() << '\t' << p.bitrate() << '\t' << p.sampleRate() << '\t' << p.channels() <<
              '\t' << p.mpcVersion() << '\t' << p.sampleFrames() << '\t' << p.totalFrames() << '\t' << p.trackGain() <<
              '\t'
              << p.trackPeak() << '\t' << p.albumGain() << '\t' << p.albumPeak() << '\t' << f.tell() << '\n';
        }
    for (int origin: {0, 7}) {
      ByteVectorStream s(data);
      s.seek(origin);
      auto supported = MPC::File::isSupported(&s);
      std::cout << id << "/support/" << origin << '\t' << supported << '\t' << s.tell() << '\n';
    }
    for (const std::string &name: std::vector<std::string>{"sample.mpc", "sample.unknown"})
      for (int origin: {0, 7})
        for (
          int enabled: {0, 1}) {
          Named stream(data, name, false);
          stream.seek(origin);
          FileRef ref(&stream, enabled);
          auto *f = dynamic_cast<MPC::File *>(ref.file());
          auto base = id + "/ref/" + name + "/" + std::to_string(origin) + "/" + std::to_string(enabled);
          std::cout << base << '\t' << ref.isNull() << '\t' << (f != nullptr) << '\n';
          if (f)state(base, *f);
        }
    for (int mode = 0; mode < 11; mode++) {
      auto base = id + "/edit/" + std::to_string(mode);
      ByteVectorStream s(data);
      MPC::File m(&s);
      edit(m, mode);
      state(base + "/io", m);
      if (undefinedMemoryGrowth(m, data)) { std::cout << base << "/io/undefined-input\t" << hex(data) << '\n'; } else {
        for (int n = 0; n < 2; n++) {
          bool saved = m.save();
          std::cout << base << "/io/save/" << n << '\t' << saved << '\t' << m.tell() << '\t' << hex(*s.data()) << '\n';
          state(base + "/io/after/" + std::to_string(n), m);
        }
      }
      auto copy = root / "outputs" / (id + "." + std::to_string(mode) + ".native");
      std::filesystem::copy_file(input, copy, std::filesystem::copy_options::overwrite_existing);
      MPC::File f(copy.c_str());
      edit(f, mode);
      state(base + "/path", f);
      for (int n = 0; n < 2; n++) {
        bool saved = f.save();
        f.seek(f.tell());
        std::cout << base << "/path/save/" << n << '\t' << saved << '\t' << f.tell() << '\t' << hex(read(copy)) << '\n';
        state(base + "/path/after/" + std::to_string(n), f);
      }
    }
  }
  for (long long length: {-10518287262LL, -10518287260LL, -10518287258LL}) {
    ByteVectorStream s(read(root / "inputs/sv7-prefix-056.mpc"));
    MPC::File f(&s, false);
    f.seek(0);
    MPC::Properties p(&f, length);
    std::cout << "bitrate/lower-bound/" << length << '\t' << p.bitrate() << '\t' << f.tell() << '\n';
  }
  std::cout << "support/null\t" << MPC::File::isSupported(nullptr) << '\n';
  FileStream closed((root / "missing.mpc").c_str());
  std::cout << "open-stream/rejected\t" << !closed.isOpen() << '\n';
  MPC::File missing((root / "missing.mpc").c_str());
  std::cout << "open-file/rejected\t" << !missing.isValid() << '\n';
  auto data = read(root / "inputs/sv7-prefix-056.mpc");
  Named stream(data, "sample.mpc", true);
  FileRef ref(&stream);
  auto *f = dynamic_cast<MPC::File *>(ref.file());
  std::cout << "single-constructor/ref\t" << ref.isNull() << '\t' << (f != nullptr) << '\t' << stream.descriptors <<
      '\n';
  if (f)state("single-constructor/ref", *f);
}
