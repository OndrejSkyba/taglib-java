/* Defined ordinary TagLib Ogg owners. No intentional native fault inputs. */
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <vector>
#include "vorbisfile.h"
#include "opusfile.h"
#include "speexfile.h"
#include "oggflacfile.h"
#include "oggpageheader.h"
#include "flacpicture.h"
#include "flacproperties.h"
#include "xiphcomment.h"
#include "fileref.h"
#include "tbytevectorstream.h"
#include "tpropertymap.h"
#include "tvariant.h"
using namespace TagLib;
namespace fs = std::filesystem;
std::string hex(const ByteVector &b) { return String(b.toHex()).to8Bit(true); }
std::string hex(const String &s) { return hex(s.data(String::UTF8)); }

std::string props(const PropertyMap &p) {
  std::string out;
  for (const auto &[k,vs]: p) {
    out += hex(k) + "=";
    for (const auto &v: vs)out += hex(v) + ",";
    out += ";";
  }
  out += "/";
  for (const auto &v: p.unsupportedData())out += hex(v) + ",";
  return out;
}

template<typename... T>
void row(const std::string &key, const T &... values) {
  std::cout << key;
  ((std::cout << '\t' << values), ...);
  std::cout << '\n';
}

ByteVector read(const fs::path &p) {
  std::ifstream in(p, std::ios::binary);
  std::string b((std::istreambuf_iterator<char>(in)), {});
  return ByteVector(b.data(), b.size());
}

void write(const fs::path &p, const ByteVector &b) {
  std::ofstream out(p, std::ios::binary);
  out.write(b.data(), b.size());
}

struct Stream : ByteVectorStream {
  std::string filename, trace;
  bool ro = false;
  int fault = 0, calls = 0;

  Stream(const ByteVector &b, const std::string &n) : ByteVectorStream(b), filename(n) {
  }

  FileName name() const override { return filename.c_str(); }
  bool readOnly() const override { return ro; }

  void edit(std::string s) {
    trace += s;
    if (fault > 0 && ++calls == fault)throw std::runtime_error("ordinary callback failure");
  }

  void insert(const ByteVector &d, offset_t s, size_t r) override {
    edit("I" + std::to_string(s) + "," + std::to_string(r) + "," + std::to_string(d.size()) + ";");
    ByteVectorStream::insert(d, s, r);
  }

  void removeBlock(offset_t s, size_t n) override {
    edit("R" + std::to_string(s) + "," + std::to_string(n) + ";");
    ByteVectorStream::removeBlock(s, n);
  }

  void writeBlock(const ByteVector &d) override {
    edit("W" + std::to_string(tell()) + "," + std::to_string(d.size()) + ";");
    ByteVectorStream::writeBlock(d);
  }

  void truncate(offset_t n) override {
    edit("T" + std::to_string(n) + ";");
    ByteVectorStream::truncate(n);
  }
};

int kind(const std::string &n) {
  auto ext = fs::path(n).extension();
  return ext == ".opus" ? 1 : ext == ".spx" ? 2 : ext == ".oga" ? 3 : 0;
}

std::unique_ptr<Ogg::File> open(const fs::path &p, int codec, bool read, AudioProperties::ReadStyle style) {
  switch (codec) {
    case 0: return std::make_unique<Vorbis::File>(p.c_str(), read, style);
    case 1: return std::make_unique<Ogg::Opus::File>(p.c_str(), read, style);
    case 2: return std::make_unique<Ogg::Speex::File>(p.c_str(), read, style);
    default: return std::make_unique<Ogg::FLAC::File>(p.c_str(), read, style);
  }
}

std::unique_ptr<Ogg::File> open(IOStream *s, int codec, bool read, AudioProperties::ReadStyle style) {
  switch (codec) {
    case 0: return std::make_unique<Vorbis::File>(s, read, style);
    case 1: return std::make_unique<Ogg::Opus::File>(s, read, style);
    case 2: return std::make_unique<Ogg::Speex::File>(s, read, style);
    default: return std::make_unique<Ogg::FLAC::File>(s, read, style);
  }
}

bool support(IOStream *s, int codec) {
  switch (codec) {
    case 0: return Vorbis::File::isSupported(s);
    case 1: return Ogg::Opus::File::isSupported(s);
    case 2: return Ogg::Speex::File::isSupported(s);
    default: return Ogg::FLAC::File::isSupported(s);
  }
}

Ogg::XiphComment *tag(File *f) { return dynamic_cast<Ogg::XiphComment *>(f->tag()); }

void state(const std::string &key, Ogg::File *f, int codec) {
  if (!f->isValid()) {
    row(key + "/rejected", true);
    return;
  }
  row(key + "/file", f->isOpen(), f->isValid(), f->tell(), f->length());
  auto *t = tag(f);
  if (!t)row(key + "/tag", false);
  else {
    row(key + "/tag", true, t->isEmpty(), hex(t->title()), hex(t->artist()), hex(t->album()), hex(t->comment()),
        hex(t->genre()), t->year(), t->track(), hex(t->vendorID()), t->fieldCount(), props(f->properties()));
    auto pics = t->pictureList();
    row(key + "/pictures", pics.size());
    int i = 0;
    for (auto *p: pics)row(key + "/picture/" + std::to_string(i++), hex(p->render()));
  }
  auto *a = f->audioProperties();
  if (!a)row(key + "/audio", false);
  else {
    std::ostringstream extra;
    if (auto *x = dynamic_cast<Vorbis::File *>(f)) {
      auto *p = x->audioProperties();
      extra << p->vorbisVersion() << ',' << p->bitrateMaximum() << ',' << p->bitrateNominal() << ',' << p->
          bitrateMinimum();
    } else if (auto *x = dynamic_cast<Ogg::Opus::File *>(f)) {
      auto *p = x->audioProperties();
      extra << p->opusVersion() << ',' << p->inputSampleRate() << ',' << p->outputGain();
    } else if (auto *x = dynamic_cast<Ogg::Speex::File *>(f)) {
      auto *p = x->audioProperties();
      extra << p->speexVersion() << ',' << p->bitrateNominal();
    } else {
      auto *p = dynamic_cast<Ogg::FLAC::File *>(f)->audioProperties();
      extra << p->bitsPerSample() << ',' << p->sampleFrames() << ',' << hex(p->signature());
    }
    row(key + "/audio", true, a->lengthInMilliseconds(), a->bitrate(), a->sampleRate(), a->channels(), extra.str());
  }
  if (codec == 3) {
    auto *x = dynamic_cast<Ogg::FLAC::File *>(f);
    row(key + "/flac", x->hasXiphComment(), x->streamLength());
  }
  for (int i = 0; i < 4; i++)row(key + "/packet/" + std::to_string(i), hex(f->packet(i)));
  auto *first = f->firstPageHeader();
  auto *last = f->lastPageHeader();
  row(key + "/headers", first != nullptr, last != nullptr, first ? first->absoluteGranularPosition() : 0,
      last ? last->absoluteGranularPosition() : 0, last ? last->pageSequenceNumber() : 0, f->tell());
}

void direct(const std::string &key, Ogg::File *f, int codec, AudioProperties::ReadStyle style) {
  std::ostringstream extra;
  std::unique_ptr<AudioProperties> a;
  if (codec == 0) {
    auto p = std::make_unique<Vorbis::Properties>(dynamic_cast<Vorbis::File *>(f), style);
    extra << p->vorbisVersion() << ',' << p->bitrateMaximum() << ',' << p->bitrateNominal() << ',' << p->
        bitrateMinimum();
    a = std::move(p);
  } else if (codec == 1) {
    auto p = std::make_unique<Ogg::Opus::Properties>(dynamic_cast<Ogg::Opus::File *>(f), style);
    extra << p->opusVersion() << ',' << p->inputSampleRate() << ',' << p->outputGain();
    a = std::move(p);
  } else {
    auto p = std::make_unique<Ogg::Speex::Properties>(dynamic_cast<Ogg::Speex::File *>(f), style);
    extra << p->speexVersion() << ',' << p->bitrateNominal();
    a = std::move(p);
  }
  row(key, a->lengthInMilliseconds(), a->bitrate(), a->sampleRate(), a->channels(), extra.str(), f->tell());
}

void complex(const std::string &key, File *f) {
  std::string keys;
  for (auto &k: f->complexPropertyKeys())keys += hex(k) + ",";
  row(key + "/keys", keys);
  for (std::string k: {"PICTURE", "picture", "OTHER"}) {
    auto values = f->complexProperties(k.c_str());
    row(key + "/complex/" + k, values.size());
    int i = 0;
    for (const auto &map: values) {
      std::string out;
      for (const auto &[n,v]: map) {
        out += hex(n) + "=";
        if (n == "data")out += "b" + hex(v.value<ByteVector>());
        else if (n == "width" || n == "height" || n == "colorDepth" || n == "numColors")
          out += "i" + std::to_string(v.value<int>());
        else out += "s" + hex(v.value<String>());
        out += ";";
      }
      row(key + "/complex/" + k + "/" + std::to_string(i++), out);
    }
  }
}

void edit(Ogg::File *f, int mode, int step) {
  auto *t = tag(f);
  if (mode == 1) {
    t->setTitle(step == 0 ? "Title" : "changed");
    t->setArtist("Artist");
    t->setAlbum("Album");
    t->setComment("Comment");
    t->setGenre("Jazz");
    t->setYear(0xffffffffU);
    t->setTrack(0x80000000U);
  }
  if (mode == 3)t->setTitle(step == 0 ? String(std::string(65536, 'x')) : step == 1 ? String("short") : String());
  if (mode == 4) {
    if (step == 0) {
      auto *p = new FLAC::Picture();
      p->setData(ByteVector("\0\1\xff", 3));
      p->setMimeType("image/png");
      p->setDescription("picture");
      p->setType(FLAC::Picture::FrontCover);
      p->setWidth(5);
      p->setHeight(6);
      p->setColorDepth(24);
      p->setNumColors(7);
      t->addPicture(p);
    } else if (step == 1) {
      auto *p = t->pictureList().front();
      p->setDescription("mutated");
      p->setWidth(9);
    } else t->removeAllPictures();
  }
  if (mode == 5) {
    t->addField("title", "first");
    t->addField("TITLE", "first", false);
    t->addField("title", "second", false);
    t->addField("COMMENT", "comment");
    t->addField("DESCRIPTION", "description");
    if (step == 1)t->removeFields("TITLE", "first");
    if (step == 2)t->removeAllFields();
  }
  if (mode == 6) {
    f->setPacket(1, ByteVector("raw comment"));
    t->setTitle("live overwrites raw");
    f->setPacket(99, ByteVector("ignored"));
  }
}

int main(int argc, char **argv) {
  fs::path root = argv[1];
  std::vector<fs::path> inputs;
  for (auto &p: fs::directory_iterator(root / "inputs"))inputs.push_back(p.path());
  std::sort(inputs.begin(), inputs.end());
  for (auto &path: inputs) {
    std::string name = path.filename();
    int codec = kind(name);
    auto bytes = read(path);
    for (int style = 0; style < 3; style++)
      for (int enabled = 0; enabled < 2; enabled++) {
        std::string key = name + "/controls/" + std::to_string(style) + "/" + std::to_string(enabled);
        auto mode = static_cast<AudioProperties::ReadStyle>(style);
        {
          auto f = open(path, codec, enabled, mode);
          state(key + "/path", f.get(), codec);
        }
        for (int origin: {0, 7}) {
          Stream stream(bytes, name);
          stream.seek(origin);
          {
            auto f = open(&stream, codec, enabled, mode);
            state(key + "/io/" + std::to_string(origin), f.get(), codec);
            if (f->isValid() && codec < 3)direct(key + "/direct/" + std::to_string(origin), f.get(), codec, mode);
          }
          row(key + "/lifetime/" + std::to_string(origin), stream.isOpen());
        }
      }
    for (auto filename: {name, std::string("sample.unknown")}) {
      Stream stream(bytes, filename);
      stream.seek(7);
      row(name + "/support/" + filename, support(&stream, codec), stream.tell());
      stream.seek(0);
      {
        FileRef ref(&stream);
        int actual = dynamic_cast<Vorbis::File *>(ref.file())
                       ? 0
                       : dynamic_cast<Ogg::Opus::File *>(ref.file())
                           ? 1
                           : dynamic_cast<Ogg::Speex::File *>(ref.file())
                               ? 2
                               : dynamic_cast<Ogg::FLAC::File *>(ref.file())
                                   ? 3
                                   : -1;
        row(name + "/ref/" + filename, ref.isNull(), actual, stream.tell());
      }
    }
    {
      auto f = open(path, codec, false, AudioProperties::Average);
      if (!f->isValid())continue;
    }
    for (int transport = 0; transport < 2; transport++)
      for (int mode = 0; mode < 7; mode++) {
        std::string key = name + "/edit/" + std::to_string(transport) + "/" + std::to_string(mode);
        Stream stream(bytes, name);
        auto flat = key;
        std::replace(flat.begin(), flat.end(), '/', '_');
        auto output = root / "outputs" / (flat + ".native");
        auto named = fs::path(
          output.string() + "." + (codec == 0 ? "ogg" : codec == 1 ? "opus" : codec == 2 ? "spx" : "oga"));
        fs::copy_file(path, named, fs::copy_options::overwrite_existing);
        {
          auto f = transport == 0
                     ? open(named, codec, true, AudioProperties::Average)
                     : open(&stream, codec, true, AudioProperties::Average);
          auto *original = f->tag();
          for (int step = 0; step < 3; step++) {
            if (mode == 2) {
              PropertyMap p;
              p["TITLE"] = StringList("one").append("two");
              p["COMMENT"] = StringList("comment");
              p["CUSTOM"] = StringList("custom");
              p["A=B"] = StringList("invalid");
              row(key + "/rejected/" + std::to_string(step), props(f->setProperties(p)));
            } else edit(f.get(), mode, step);
            state(key + "/before/" + std::to_string(step), f.get(), codec);
            complex(key + "/before/" + std::to_string(step), f.get());
            bool saved = f->save();
            if (transport == 0)f->seek(f->tell());
            auto data = transport == 0 ? read(named) : *stream.data();
            row(key + "/save/" + std::to_string(step), saved, f->tell(), hex(data), f->tag() == original);
            state(key + "/after/" + std::to_string(step), f.get(), codec);
            ByteVectorStream again(data);
            auto reopened = open(&again, codec, true, AudioProperties::Average);
            state(key + "/reopen/" + std::to_string(step), reopened.get(), codec);
          }
          row(key + "/trace", stream.trace);
          write(output, transport == 0 ? read(named) : *stream.data());
        }
        fs::remove(named);
      }
    Stream ro(bytes, name);
    ro.ro = true;
    {
      auto f = open(&ro, codec, false, AudioProperties::Average);
      auto *original = f->tag();
      original->setTitle("readonly");
      bool saved = f->save();
      row(name + "/readonly", saved, hex(*ro.data()), f->tag() == original);
      ro.ro = false;
      bool retried = f->save();
      row(name + "/readonly-retry", retried, hex(*ro.data()));
    }
  }
  return 0;
}
