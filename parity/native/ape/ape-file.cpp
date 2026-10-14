// Development-only APE File state/operation protocol against pinned TagLib.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <vector>
#include <algorithm>
#include "ape/apefile.h"
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

void state(const std::string &id, APE::File &f) {
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
        << p->sampleRate() << '\t' << p->channels() << '\t' << p->bitsPerSample() << '\t' << p->version() << '\t' << p->
        sampleFrames();
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
    auto b = once && at == 8 && count == 44 && ++descriptors > 1 ? ByteVector() : ByteVectorStream::readBlock(count);
    return b;
  }
};

void edit(APE::File &f, int mode) {
  if (mode < 4) {
    f.strip(mode);
    return;
  }
  if (mode == 4) {
    f.ID3v1Tag(true)->setTitle("created legacy");
    f.APETag(true)->setTitle("created APE");
    f.tag()->setArtist("broadcast");
    return;
  }
  if (mode == 5) {
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

int main(int argc, char **argv) {
  std::filesystem::path root(argv[1]);
  std::filesystem::create_directories(root / "outputs");
  std::vector<std::filesystem::path> paths;
  for (auto &p: std::filesystem::directory_iterator(root / "inputs"))paths.push_back(p.path());
  std::sort(paths.begin(), paths.end());
  for (auto &input: paths) {
    auto id = input.filename().string();
    bool wrappedDescriptor = id == "descriptor-51.ape";
    auto data = read(input);
    for (int style = 0; style < 3; style++)
      for (int enabled = 0; enabled < 2; enabled++) {
        auto base = id + "/controls/" + std::to_string(style) + "/" + std::to_string(enabled);
        APE::File f(input.c_str(), enabled, static_cast<AudioProperties::ReadStyle>(style));
        state(base + "/path", f);
        for (int origin: {0, 7}) {
          if (wrappedDescriptor && enabled)continue;
          ByteVectorStream s(data);
          s.seek(origin);
          APE::File m(&s, enabled, static_cast<AudioProperties::ReadStyle>(style));
          state(base + "/io/" + std::to_string(origin), m);
        }
      }
    for (int origin: {0, 7})
      for (long long length: {-1LL, 0LL, 1LL, 128LL, 2147483647LL, 9223372036854775807LL})
        for (
          int style = 0; style < 3; style++) {
          if (wrappedDescriptor)continue;
          ByteVectorStream s(data);
          APE::File f(&s, false);
          f.seek(origin);
          APE::Properties p(&f, length, static_cast<AudioProperties::ReadStyle>(style));
          std::cout << id << "/direct-props/" << origin << "/" << length << "/" << style << '\t' << p.
              lengthInMilliseconds()
              << '\t' << p.lengthInSeconds() << '\t' << p.bitrate() << '\t' << p.sampleRate() << '\t' << p.channels() <<
              '\t' << p.bitsPerSample() << '\t' << p.version() << '\t' << p.sampleFrames() << '\n';
        }
    for (int origin: {0, 7}) {
      ByteVectorStream s(data);
      s.seek(origin);
      auto supported = APE::File::isSupported(&s);
      std::cout << id << "/support/" << origin << '\t' << supported << '\t' << s.tell() << '\n';
    }
    for (const std::string &name: std::vector<std::string>{"sample.ape", "sample.unknown"})
      for (int origin: {0, 7})
        for (
          int enabled: {0, 1}) {
          if (wrappedDescriptor && enabled)continue;
          Named stream(data, name, false);
          stream.seek(origin);
          FileRef ref(&stream, enabled);
          auto *f = dynamic_cast<APE::File *>(ref.file());
          auto base = id + "/ref/" + name + "/" + std::to_string(origin) + "/" + std::to_string(enabled);
          std::cout << base << '\t' << ref.isNull() << '\t' << (f != nullptr) << '\n';
          if (f)state(base, *f);
        }
    for (int mode = 0; mode < 7; mode++) {
      auto base = id + "/edit/" + std::to_string(mode);
      ByteVectorStream s(data);
      APE::File m(&s, !wrappedDescriptor);
      m.seek(0);
      edit(m, mode);
      state(base + "/io", m);
      for (int n = 0; n < 2; n++) {
        bool saved = m.save();
        std::cout << base << "/io/save/" << n << '\t' << saved << '\t' << m.tell() << '\t' << hex(*s.data()) << '\n';
        state(base + "/io/after/" + std::to_string(n), m);
      }
      auto copy = root / "outputs" / (id + "." + std::to_string(mode) + ".native");
      std::filesystem::copy_file(input, copy, std::filesystem::copy_options::overwrite_existing);
      APE::File f(copy.c_str(), !wrappedDescriptor);
      f.seek(0);
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
  std::cout << "support/null\t" << APE::File::isSupported(nullptr) << '\n';
  FileStream closed((root / "missing.ape").c_str());
  std::cout << "open-stream/rejected\t" << !closed.isOpen() << '\n';
  APE::File missing((root / "missing.ape").c_str());
  std::cout << "open-file/rejected\t" << !missing.isValid() << '\n';
  auto data = read(root / "inputs/prefix-128.ape");
  Named stream(data, "sample.ape", true);
  FileRef ref(&stream);
  auto *f = dynamic_cast<APE::File *>(ref.file());
  std::cout << "single-constructor/ref\t" << ref.isNull() << '\t' << (f != nullptr) << '\t' << stream.descriptors <<
      '\n';
  if (f)state("single-constructor/ref", *f);
}
