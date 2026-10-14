// Pinned tracker tags and protected helpers, Shorten File controls and FileRef read traces.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <vector>
#include <algorithm>
#include <tbytevectorstream.h>
#include <tpropertymap.h>
#include <fileref.h>
#include <mod/modfilebase.h>
#include <mod/modtag.h>
#include <shorten/shortenfile.h>
#include <xm/xmfile.h>
using namespace TagLib;

std::string hex(const ByteVector &b) {
  const char *d = "0123456789abcdef";
  std::string s;
  for (unsigned i = 0; i < b.size(); i++) {
    unsigned char c = b[i];
    s += d[c >> 4];
    s += d[c & 15];
  }
  return s;
}

std::string hex(const String &s) { return hex(s.data(String::UTF8)); }

std::string map(const PropertyMap &p) {
  std::ostringstream s;
  s << p.size();
  for (const auto &v: p) {
    s << ':' << hex(v.first) << ':' << v.second.size();
    for (const auto &t: v.second)s << ':' << hex(t);
  }
  s << ':' << p.unsupportedData().size();
  for (const auto &t: p.unsupportedData())s << ':' << hex(t);
  return s.str();
}

void tag(const std::string &id, const TagLib::Tag &t, const String &tracker = String()) {
  std::cout << id << '\t' << hex(t.title()) << '\t' << hex(t.artist()) << '\t' << hex(t.album()) << '\t' <<
      hex(t.comment()) << '\t' << hex(t.genre()) << '\t' << t.year() << '\t' << t.track() << '\t' << t.isEmpty() << '\t'
      << hex(tracker) << '\t' << map(t.properties()) << '\n';
}

struct Helpers : Mod::FileBase {
  Helpers(IOStream *s) : Mod::FileBase(s) {
  }

  TagLib::Tag *tag() const override { return nullptr; }
  AudioProperties *audioProperties() const override { return nullptr; }
  bool save() override { return false; }
  using Mod::FileBase::readString;
  using Mod::FileBase::readByte;
  using Mod::FileBase::readU16L;
  using Mod::FileBase::readU16B;
  using Mod::FileBase::readU32L;
  using Mod::FileBase::readU32B;
  using Mod::FileBase::writeString;
  using Mod::FileBase::writeByte;
  using Mod::FileBase::writeU16L;
  using Mod::FileBase::writeU16B;
  using Mod::FileBase::writeU32L;
  using Mod::FileBase::writeU32B;
};

struct Named : ByteVectorStream {
  std::string fileName, trace;
  bool once, zeroLength = false;
  offset_t onceOffset = 1080;
  int markers = 0;

  Named(const ByteVector &b, const std::string &n, bool o = false) : ByteVectorStream(b), fileName(n), once(o) {
  }

  FileName name() const override { return fileName.c_str(); }
  offset_t length() override { return zeroLength ? 0 : ByteVectorStream::length(); }

  ByteVector readBlock(size_t count) override {
    auto at = tell();
    auto b = (once && at == onceOffset && ++markers > 1) ? ByteVector() : ByteVectorStream::readBlock(count);
    trace += std::to_string(at) + "," + std::to_string(count) + "," + std::to_string(b.size()) + ";";
    return b;
  }
};

ByteVector read(const std::filesystem::path &p) {
  std::ifstream f(p, std::ios::binary);
  std::string s((std::istreambuf_iterator<char>(f)), {});
  return ByteVector(s.data(), s.size());
}

void tags() {
  std::vector<std::string> texts = {"", "Title", u8"Müller € 🎵", " first\nsecond\rthird "};
  for (unsigned i = 0; i < texts.size(); i++) {
    Mod::Tag t;
    Shorten::Tag sh;
    auto value = String(texts[i], String::UTF8);
    for (TagLib::Tag *b: {static_cast<TagLib::Tag *>(&t), static_cast<TagLib::Tag *>(&sh)}) {
      b->setTitle(value);
      b->setArtist(value);
      b->setAlbum(value);
      b->setComment(value);
      b->setGenre(value);
      b->setYear(0xffffffffU);
      b->setTrack(0xffffffffU);
    }
    t.setTrackerName(value);
    tag("tag/mod/" + std::to_string(i), t, t.trackerName());
    tag("tag/shorten/" + std::to_string(i), sh);
  }
  for (int mask = 0; mask < 64; mask++) {
    PropertyMap p;
    const char *keys[] = {"TITLE", "COMMENT", "TRACKERNAME", "ARTIST", "UNKNOWN", "EMPTY"};
    for (int i = 0; i < 6; i++)
      if (mask & (1 << i)) {
        StringList values;
        if (i != 5) {
          values.append(String("first"));
          if (i % 2 == 0)values.append(String("extra"));
        }
        p[String(keys[i])] = values;
      }
    p.addUnsupportedData(String("opaque"));
    Mod::Tag t;
    t.setTitle("old");
    t.setComment("old");
    t.setTrackerName("old");
    Shorten::Tag sh;
    auto before = map(p);
    auto rejected = t.setProperties(p), shRejected = sh.setProperties(p);
    std::string id = std::to_string(mask);
    std::cout << "import/" << id << '\t' << before << '\t' << map(p) << '\t' << map(rejected) << '\t' << map(shRejected)
        << '\n';
    tag("import/mod/" + id, t, t.trackerName());
    tag("import/shorten/" + id, sh);
  }
}

void helpers() {
  const char seed[] = {static_cast<char>(0xff), 'a', 0, 'b', static_cast<char>(0x80), 0x7f, 0x12, 0x34};
  for (int prefix = 0; prefix <= 8; prefix++)
    for (unsigned long width: {0UL, 1UL, 2UL, 4UL, 8UL, 9UL, 2147483648UL}) {
      ByteVectorStream s(ByteVector(seed, prefix));
      Helpers h(&s);
      String out("unchanged");
      bool ok = h.readString(out, width);
      std::cout << "helper/string/" << prefix << '/' << width << '\t' << ok << '\t' << hex(out) << '\t' << s.tell() <<
          '\n';
    }
  for (int prefix = 0; prefix <= 8; prefix++)
    for (int kind = 0; kind < 5; kind++) {
      ByteVectorStream s(ByteVector(seed, prefix));
      Helpers h(&s);
      unsigned char a = 42;
      unsigned short b = 4242;
      unsigned long c = 424242;
      bool ok = kind == 0
                  ? h.readByte(a)
                  : kind == 1
                      ? h.readU16L(b)
                      : kind == 2
                          ? h.readU16B(b)
                          : kind == 3
                              ? h.readU32L(c)
                              : h.readU32B(c);
      std::cout << "helper/number/" << prefix << '/' << kind << '\t' << ok << '\t' << (kind == 0 ? a : kind < 3 ? b : c)
          << '\t' << s.tell() << '\n';
    }
  for (unsigned long v: {0UL, 1UL, 255UL, 65535UL, 65536UL, 4294967295UL, 4294967296UL, 18446744073709551615UL}) {
    ByteVectorStream s{ByteVector()};
    Helpers h(&s);
    h.writeByte(v);
    h.writeU16L(v);
    h.writeU16B(v);
    h.writeU32L(v);
    h.writeU32B(v);
    std::cout << "helper/write/" << v << '\t' << hex(*s.data()) << '\t' << s.tell() << '\n';
  }
  for (unsigned long width: {0UL, 1UL, 2UL, 4UL, 8UL})
    for (int pad: {0, 127, 255}) {
      ByteVectorStream s{ByteVector()};
      Helpers h(&s);
      h.writeString(String(u8"€ÿx", String::UTF8), width, static_cast<char>(pad));
      std::cout << "helper/write-string/" << width << '/' << pad << '\t' << hex(*s.data()) << '\t' << s.tell() << '\n';
    }
}

void shortenState(const std::string &id, const Shorten::File &f, offset_t cursor) {
  if (!f.isValid()) {
    std::cout << id << "/rejected\t1" << '\n';
    return;
  }
  std::cout << id << '\t' << f.isOpen() << '\t' << f.isValid() << '\t' << (f.tag() != nullptr) << '\t' << (
    f.audioProperties() != nullptr) << '\t' << cursor;
  auto p = f.audioProperties();
  if (p)
    std::cout << '\t' << p->lengthInMilliseconds() << '\t' << p->lengthInSeconds() << '\t' << p->bitrate() << '\t'
        << p->sampleRate() << '\t' << p->channels() << '\t' << p->bitsPerSample() << '\t' << p->shortenVersion() << '\t'
        << p->fileType() << '\t' << p->sampleFrames();
  std::cout << '\n';
}

int main(int argc, char **argv) {
  auto root = std::filesystem::path(argv[1]);
  tags();
  helpers();
  std::vector<std::filesystem::path> paths;
  for (auto &p: std::filesystem::directory_iterator(root / "inputs"))paths.push_back(p.path());
  std::sort(paths.begin(), paths.end());
  for (auto &p: paths) {
    auto id = p.filename().string();
    auto data = read(p);
    for (int origin: {0, 7}) {
      ByteVector pref(origin, 'p');
      pref.append(data);
      Named s(pref, "input.shn");
      s.seek(origin);
      Shorten::File f(&s);
      shortenState(id + "/default/io/" + std::to_string(origin), f, s.tell());
      std::cout << id << "/trace/" << origin << '\t' << s.trace << '\n';
      if (f.isValid())
        std::cout << id << "/save/" << origin << '\t' << f.save() << '\t' << s.tell() << '\t' << hex(*s.data()) << '\n';
      auto before = s.tell();
      std::cout << id << "/supported/" << origin << '\t' << Shorten::File::isSupported(&s) << '\t' << before << '\t' <<
          s.tell() << '\n';
    }
    for (int style = 0; style < 3; style++)
      for (int enabled = 0; enabled < 2; enabled++) {
        auto base = id + "/controls/" + std::to_string(style) + "/" + std::to_string(enabled);
        Shorten::File f(p.c_str(), enabled, static_cast<AudioProperties::ReadStyle>(style));
        shortenState(base + "/path", f, f.tell());
        for (int origin: {0, 7}) {
          ByteVector pref(origin, 'p');
          pref.append(data);
          ByteVectorStream s(pref);
          s.seek(origin);
          Shorten::File f(&s, enabled, static_cast<AudioProperties::ReadStyle>(style));
          shortenState(base + "/io/" + std::to_string(origin), f, s.tell());
        }
      }
    Shorten::File f(p.c_str());
    shortenState(id + "/default/path", f, f.tell());
  }
  Shorten::File missing((root / "missing.shn").c_str());
  // Native unopened FileStream::tell() calls ftell(nullptr); compare defined handles only.
  shortenState("shorten/missing", missing, -1);
  auto mod = read(root / "valid.mod");
  for (const std::string ext: {"mod", "module", "nst", "wow"})
    for (int origin: {0, 7}) {
      Named s(mod, "input." + ext, true);
      s.seek(origin);
      FileRef r(&s);
      std::cout << "fileref/" << ext << '/' << origin << '\t' << r.isNull() << '\t' << (r.file() != nullptr) << '\t' <<
          s.
          tell() << '\t' << s.trace << '\n';
      if (!r.isNull())
        tag("fileref/tag/" + ext + "/" + std::to_string(origin), *r.tag(),
            static_cast<Mod::Tag *>(r.tag())->trackerName());
    }
  auto it = read(root / "valid.it");
  Named unnamed(it, "input.dat");
  FileRef ref(&unnamed);
  std::cout << "fileref/it-unknown\t" << ref.isNull() << '\t' << (ref.file() != nullptr) << '\n';
  auto shn = read(root / "inputs/header-2-3.shn");
  for (int zero: {0, 1}) {
    Named s(shn, "input.shn");
    s.zeroLength = zero;
    FileRef ref(&s);
    std::cout << "fileref/shorten/" << zero << '\t' << ref.isNull() << '\t' << (
      dynamic_cast<Shorten::File *>(ref.file()) != nullptr) << '\t' << s.tell() << '\t' << s.trace << '\n';
    if (ref.isNull())throw std::runtime_error("Shorten ref");
    shortenState("fileref/shorten-state/" + std::to_string(zero), *static_cast<Shorten::File *>(ref.file()), s.tell());
  }
  auto xm = read(root / "valid.xm");
  Named once(xm, "input.xm", true);
  XM::File file(&once);
  if (!file.isValid())throw std::runtime_error("XM ref");
  once.onceOffset = 60;
  once.markers = 0;
  once.trace.clear();
  file.tag()->setTitle("Changed");
  auto saved = file.save();
  std::cout << "xm/one-read-save\t" << saved << '\t' << once.tell() << '\t' << hex(*once.data()) << '\n';
  std::cout << "xm/save-trace\t" << once.trace << '\n';
}
