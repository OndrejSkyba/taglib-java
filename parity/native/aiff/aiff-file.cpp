// Ordinary AIFF live owner and installed protected RIFF extension operations.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <stdexcept>
#include <aifffile.h>
#include <riff/rifffile.h>
#include <id3v2framefactory.h>
#include <attachedpictureframe.h>
#include <unknownframe.h>
#include <tbytevectorstream.h>
#include <tpropertymap.h>
#include <fileref.h>
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

struct Aiff : RIFF::AIFF::File {
  using RIFF::AIFF::File::File;
  using RIFF::File::riffSize;
  using RIFF::File::chunkCount;
  using RIFF::File::chunkName;
  using RIFF::File::chunkOffset;
  using RIFF::File::chunkDataSize;
  using RIFF::File::chunkDataSize64;
  using RIFF::File::chunkPadding;
};

struct Chunks : RIFF::File {
  Chunks(IOStream *s, bool big) : RIFF::File(s, big ? BigEndian : LittleEndian) {
  }

  Chunks(FileName path, bool big) : RIFF::File(path, big ? BigEndian : LittleEndian) {
  }

  Tag *tag() const override { return nullptr; }
  AudioProperties *audioProperties() const override { return nullptr; }
  bool save() override { return false; }
  using RIFF::File::riffSize;
  using RIFF::File::chunkCount;
  using RIFF::File::chunkName;
  using RIFF::File::chunkOffset;
  using RIFF::File::chunkDataSize;
  using RIFF::File::chunkDataSize64;
  using RIFF::File::chunkPadding;
  using RIFF::File::chunkData;
  using RIFF::File::setChunkData;
  using RIFF::File::removeChunk;
};

struct OverrideTag : Aiff {
  int mode;
  mutable ID3v2::Tag alternate;
  OverrideTag(IOStream *s, int m) : Aiff(s), mode(m) { if (mode == 2)alternate.setTitle("guard"); }
  ID3v2::Tag *tag() const override { return mode == 0 ? nullptr : mode == 3 ? Aiff::tag() : &alternate; }
  ID3v2::Tag *ownedTag() { return Aiff::tag(); }
};

struct Named : ByteVectorStream {
  std::string filename, trace;
  bool ro = false;
  int fault = 0, calls = 0;

  Named(const ByteVector &b, std::string name) : ByteVectorStream(b), filename(name) {
  }

  FileName name() const override { return filename.c_str(); }
  bool readOnly() const override { return ro; }

  void insert(const ByteVector &b, offset_t start, size_t replace) override {
    trace += "I" + std::to_string(start) + "," + std::to_string(replace) + "," + std::to_string(b.size()) + ";";
    if (fault && ++calls == fault)throw std::runtime_error("I/O failure");
    ByteVectorStream::insert(b, start, replace);
  }

  void removeBlock(offset_t start, size_t count) override {
    trace += "R" + std::to_string(start) + "," + std::to_string(count) + ";";
    if (fault && ++calls == fault)throw std::runtime_error("I/O failure");
    ByteVectorStream::removeBlock(start, count);
  }
};

struct Factory : ID3v2::FrameFactory {
  mutable int calls = 0;

  ID3v2::Frame *createFrame(const ByteVector &data, const ID3v2::Header *h) const override {
    calls++;
    return ID3v2::FrameFactory::createFrame(data, h);
  }
};

template<class F>
void layout(const std::string &key, F &f) {
  std::cout << key << "/layout\t" << f.isValid() << '\t' << f.riffSize() << '\t' << f.chunkCount() << '\t';
  for (unsigned i = 0; i < f.chunkCount(); i++)
    std::cout << hex(f.chunkName(i)) << ',' << f.chunkOffset(i) << ',' << f.
        chunkDataSize(i) << ',' << f.chunkDataSize64(i) << ',' << f.chunkPadding(
          i) << ';';
  std::cout << '\t' << (f.isOpen() ? f.tell() : 0) << '\n';
}

void properties(const std::string &key, const RIFF::AIFF::Properties *p) {
  std::cout << key << "/audio\t" << (p != nullptr);
  if (p)
    std::cout << '\t' << p->lengthInMilliseconds() << '\t' << p->lengthInSeconds() << '\t' << p->bitrate() << '\t'
        << p->sampleRate() << '\t' << p->channels() << '\t' << p->bitsPerSample() << '\t' << p->sampleFrames() << '\t'
        << p->isAiffC() << '\t' << hex(p->compressionType()) << '\t' << hex(p->compressionName());
  std::cout << '\n';
}

void state(const std::string &key, Aiff &f) {
  auto *t = f.tag();
  std::cout << key << "/tag\t" << f.isOpen() << '\t' << f.isValid() << '\t' << f.hasID3v2Tag() << '\t' << (
    t != nullptr);
  if (t)
    std::cout << '\t' << t->isEmpty() << '\t' << hex(t->title()) << '\t' << hex(t->artist()) << '\t' <<
        hex(t->album()) << '\t' << hex(t->comment()) << '\t' << hex(t->genre()) << '\t' << t->year() << '\t' << t->
        track() << '\t' << t->header()->majorVersion() << '\t' << props(f.properties());
  std::cout << '\n';
  properties(key, f.audioProperties());
  layout(key, f);
}

void edit(Aiff &f, int mode, int step) {
  auto *t = f.tag();
  if (mode == 0)return;
  if (mode == 1) {
    t->setTitle("Title");
    t->setArtist("Artist");
    t->setYear(0xffffffffu);
    t->setTrack(0x80000000u);
  } else if (mode == 2) {
    PropertyMap p;
    p.insert("TITLE", {"one", "two"});
    p.insert("DATE", {"2026"});
    p.insert("BAD KEY", {"reject"});
    p.insert("COMMENT", {"comment"});
    f.setProperties(p);
  } else if (mode == 3) { while (!t->frameList().isEmpty())t->removeFrame(t->frameList().front()); } else if (
    mode == 4) {
    auto *a = new ID3v2::AttachedPictureFrame();
    a->setMimeType("image/png");
    a->setDescription("picture");
    a->setPicture(ByteVector("picture bytes"));
    t->addFrame(a);
    t->addFrame(new ID3v2::UnknownFrame(ByteVector("ZZZZ\0\0\0\3\0\0raw", 13)));
  } else if (mode == 5)t->setTitle(step == 0 ? String(std::string(8192, 'x')) : step == 1 ? String("short") : String());
  else {
    t->setTitle(String(std::string(254, 'X')));
    t->setArtist("Artist A");
  }
}

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  std::filesystem::path root(argv[1]);
  std::map<std::string, std::filesystem::path> inputs;
  for (auto &e: std::filesystem::directory_iterator(root / "inputs"))inputs[e.path().filename().string()] = e.path();
  for (auto &[name,path]: inputs) {
    auto data = read(path);
    for (int style = 0; style < 3; style++)
      for (int enabled = 0; enabled < 2; enabled++) {
        auto key = name + "/controls/" + std::to_string(style) + "/" + std::to_string(enabled);
        Aiff pathFile(path.c_str(), enabled, static_cast<AudioProperties::ReadStyle>(style));
        state(key + "/path", pathFile);
        for (int origin: {0, 7}) {
          Named stream(data, name);
          stream.seek(origin);
          Factory factory;
          {
            Aiff f(&stream, enabled, static_cast<AudioProperties::ReadStyle>(style), &factory);
            state(key + "/io/" + std::to_string(origin), f);
            std::cout << key << "/factory/" << origin << '\t' << factory.calls << '\n';
            RIFF::AIFF::Properties p(&f, static_cast<AudioProperties::ReadStyle>(style));
            properties(key + "/direct/" + std::to_string(origin), &p);
            std::cout << key << "/direct-cursor/" << origin << '\t' << f.tell() << '\n';
          }
          std::cout << key << "/lifetime/" << origin << '\t' << stream.isOpen() << '\n';
        }
      }
    for (std::string filename: {"sample.aiff", "sample.unknown"})
      for (int origin: {0, 7}) {
        Named stream(data, filename);
        stream.seek(origin);
        bool supported = RIFF::AIFF::File::isSupported(&stream);
        std::cout << name << "/support/" << filename << '/' << origin << '\t' << supported << '\t' << stream.tell() <<
            '\n';
        FileRef ref(&stream);
        std::cout << name << "/ref/" << filename << '/' << origin << '\t' << ref.isNull() << '\t' << (
          dynamic_cast<RIFF::AIFF::File *>(ref.file()) != nullptr) << '\t' << stream.tell() << '\n';
      }
    for (int mode = 0; mode < 7; mode++)
      for (int transport = 0; transport < 2; transport++) {
        auto key = name + "/edit/" + std::to_string(mode) + "/" + std::to_string(transport);
        auto copy = root / "outputs" / (name + "." + std::to_string(mode) + ".native");
        std::filesystem::copy_file(path, copy, std::filesystem::copy_options::overwrite_existing);
        Named stream(data, name);
        std::unique_ptr<Aiff> f(transport ? new Aiff(&stream) : new Aiff(copy.c_str()));
        auto *t = f->tag();
        auto *h = t->header();
        for (int step = 0; step < 3; step++) {
          edit(*f, mode, step);
          state(key + "/before/" + std::to_string(step), *f);
          auto version = mode == 6 ? (step % 2 == 0 ? ID3v2::v3 : ID3v2::v4) : (mode % 2 == 0 ? ID3v2::v4 : ID3v2::v3);
          bool saved = f->save(version);
          if (!transport)f->seek(f->tell());
          auto bytes = transport ? *stream.data() : read(copy);
          std::cout << key << "/save/" << step << '\t' << saved << '\t' << f->tell() << '\t' << hex(bytes) << '\t' << (
            f->tag() == t) << '\t' << (t->header() == h) << '\n';
          state(key + "/after/" + std::to_string(step), *f);
          ByteVectorStream reload(bytes);
          Aiff reopened(&reload);
          state(key + "/reopen/" + std::to_string(step), reopened);
        }
        std::cout << key << "/trace\t" << stream.trace << '\n';
      }
    for (int big: {0, 1})
      for (int transport: {0, 1}) {
        Named stream(data, name);
        auto copy = root / "outputs" / (name + ".riff." + std::to_string(big) + ".native");
        std::filesystem::copy_file(path, copy, std::filesystem::copy_options::overwrite_existing);
        std::unique_ptr<Chunks> owner(transport ? new Chunks(&stream, big) : new Chunks(copy.c_str(), big));
        auto &f = *owner;
        auto key = name + "/chunks/" + std::to_string(big) + "/" + std::to_string(transport);
        layout(key + "/initial", f);
        for (unsigned i = 0; i < f.chunkCount(); i++)
          std::cout << key << "/data/" << i << '\t' << hex(f.chunkData(i)) << '\t' << f.tell() << '\n';
        for (int operation = 0; operation < 8; operation++) {
          if (operation == 0)f.setChunkData(0, ByteVector("five!"));
          if (operation == 1)f.setChunkData("LIST", ByteVector("one"), true);
          if (operation == 2)f.setChunkData("LIST", ByteVector("two"), true);
          if (operation == 3)f.setChunkData("LIST", ByteVector("replacement"));
          if (operation == 4)f.setChunkData("JUNK", ByteVector("ignored"), true);
          if (operation == 5)f.removeChunk("LIST");
          if (operation == 6)f.removeChunk(0);
          if (operation == 7) {
            f.setChunkData(999999, ByteVector("ignored"));
            f.removeChunk(999999);
          }
          layout(key + "/after/" + std::to_string(operation), f);
          if (!transport)f.seek(f.tell());
          std::cout << key << "/bytes/" << operation << '\t' << hex(transport ? *stream.data() : read(copy)) << '\t' <<
              f.tell() << '\n';
        }
        std::cout << key << "/invalid\t" << f.chunkOffset(999999) << '\t' << f.chunkDataSize(999999) << '\t' << f.
            chunkDataSize64(999999) << '\t' << f.chunkPadding(999999) << '\t' << hex(f.chunkName(999999)) << '\t' <<
            hex(f.chunkData(999999)) << '\t' << f.tell() << '\n';
        std::cout << key << "/trace\t" << stream.trace << '\n';
      }
    {
      Named stream(data, name);
      stream.ro = true;
      Aiff f(&stream);
      f.tag()->setTitle("readonly");
      std::cout << name << "/readonly\t" << f.save() << '\t' << hex(*stream.data()) << '\t' << f.hasID3v2Tag() << '\n';
    }
  }

  auto data = read(root / "inputs/duplicates.aiff");
  for (int mode = 0; mode < 4; mode++) {
    Named stream(data, "subclass.aiff");
    OverrideTag f(&stream, mode);
    auto key = "subclass/" + std::to_string(mode);
    std::cout << key << "/properties-before\t" << props(f.properties()) << '\n';
    PropertyMap p;
    p.insert("TITLE", {"owned"});
    f.setProperties(p);
    f.ownedTag()->addFrame(new ID3v2::UnknownFrame(ByteVector("ZZZZ\0\0\0\3\0\0raw", 13)));
    std::cout << key << "/unsupported\t" << props(f.properties()) << '\n';
    f.removeUnsupportedProperties({"UNKNOWN/ZZZZ"});
    std::cout << key << "/properties-after\t" << props(f.properties()) << '\t' << hex(f.ownedTag()->title()) << '\t' <<
        hex(f.alternate.title()) << '\n';
    bool saved = f.save();
    std::cout << key << "/save/0\t" << saved << '\t' << f.hasID3v2Tag() << '\t' << hex(*stream.data()) << '\t' << hex(
      f.ownedTag()->title()) << '\n';
    state(key + "/after", f);
  }
  for (int fault = 1; fault <= 12; fault++) {
    Named stream(data, "callback.aiff");
    Aiff f(&stream);
    stream.fault = fault;
    f.tag()->setTitle("callback");
    bool threw = false, saved = false;
    try { saved = f.save(); } catch (const std::runtime_error &) { threw = true; }
    auto key = "callback/" + std::to_string(fault);
    std::cout << key << "/result\t" << threw << '\t' << saved << '\t' << f.hasID3v2Tag() << '\t' << f.tell() << '\t' <<
        hex(*stream.data()) << '\t' << stream.trace << '\n';
    layout(key, f);
  }
  std::cout << "support/null\t" << RIFF::AIFF::File::isSupported(nullptr) << '\n';
  Aiff missing((root / "missing.aiff").c_str());
  std::cout << "missing/rejected\t" << !missing.isValid() << '\n';
}
