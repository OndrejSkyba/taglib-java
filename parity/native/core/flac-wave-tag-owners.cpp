/* Ordinary TagLib owner operations. No intentional native faults. */
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <vector>
#include "flacfile.h"
#include "wavfile.h"
#include "xiphcomment.h"
#include "id3v1tag.h"
#include "id3v2tag.h"
#include "id3v2framefactory.h"
#include "infotag.h"
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
  std::string data((std::istreambuf_iterator<char>(in)), {});
  return ByteVector(data.data(), data.size());
}

void write(const fs::path &p, const ByteVector &data) {
  std::ofstream out(p, std::ios::binary);
  out.write(data.data(), data.size());
}

struct Stream : ByteVectorStream {
  std::string filename, trace;
  bool ro = false;
  int fault = 0, calls = 0;

  Stream(const ByteVector &b, const std::string &name) : ByteVectorStream(b), filename(name) {
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

struct Factory : ID3v2::FrameFactory {
  mutable int calls = 0;

  ID3v2::Frame *createFrame(const ByteVector &b, const ID3v2::Header *h) const override {
    calls++;
    return ID3v2::FrameFactory::createFrame(b, h);
  }
};

std::unique_ptr<File> open(const fs::path &p, bool read, AudioProperties::ReadStyle style) {
  return p.extension() == ".flac"
           ? std::unique_ptr<File>(new FLAC::File(p.c_str(), read, style))
           : std::unique_ptr<File>(new RIFF::WAV::File(p.c_str(), read, style));
}

std::unique_ptr<File>
open(IOStream *s, bool flac, bool read, AudioProperties::ReadStyle style, ID3v2::FrameFactory *ff) {
  return flac
           ? std::unique_ptr<File>(new FLAC::File(s, read, style, ff))
           : std::unique_ptr<File>(new RIFF::WAV::File(s, read, style, ff));
}

ID3v2::Tag *id3(File *f, bool create) {
  if (auto *a = dynamic_cast<FLAC::File *>(f))return a->ID3v2Tag(create);
  return dynamic_cast<RIFF::WAV::File *>(f)->ID3v2Tag();
}

void tag(const std::string &key, Tag *t) {
  if (!t) {
    row(key, false);
    return;
  }
  row(key, true, t->isEmpty(), hex(t->title()), hex(t->artist()), hex(t->album()), hex(t->comment()), hex(t->genre()),
      t->year(), t->track(), props(t->properties()));
}

void state(const std::string &key, File *f) {
  if (!f->isValid()) {
    row(key + "/rejected", true);
    return;
  }
  row(key + "/file", f->isOpen(), f->isValid(), f->tell(), f->length());
  tag(key + "/union", f->tag());
  tag(key + "/id3", id3(f, false));
  if (auto *a = dynamic_cast<FLAC::File *>(f)) {
    tag(key + "/xiph", a->xiphComment());
    tag(key + "/v1", a->ID3v1Tag());
    row(key + "/presence", a->hasXiphComment(), a->hasID3v1Tag(), a->hasID3v2Tag(), a->hasiXMLData(), a->hasBEXTData(),
        hex(a->iXMLData()), hex(a->BEXTData()));
    auto *p = a->audioProperties();
    if (!p)row(key + "/audio", false);
    else
      row(key + "/audio", true, p->lengthInMilliseconds(), p->bitrate(), p->sampleRate(), p->channels(),
          p->bitsPerSample(), p->sampleFrames(), hex(p->signature()));
    auto pics = a->pictureList();
    row(key + "/pictures", pics.size());
    int i = 0;
    for (auto *pic: pics) {
      row(key + "/picture/" + std::to_string(i++), int(pic->type()), pic->width(), pic->height(), pic->colorDepth(),
          pic->numColors(), hex(pic->mimeType()), hex(pic->description()), hex(pic->data()));
    }
  } else {
    auto *w = dynamic_cast<RIFF::WAV::File *>(f);
    tag(key + "/info", w->InfoTag());
    row(key + "/presence", w->hasID3v2Tag(), w->hasInfoTag(), w->hasiXMLData(), w->hasBEXTData(), hex(w->iXMLData()),
        hex(w->BEXTData()));
    auto *p = w->audioProperties();
    if (!p)row(key + "/audio", false);
    else
      row(key + "/audio", true, p->lengthInMilliseconds(), p->bitrate(), p->sampleRate(), p->channels(),
          p->bitsPerSample(), p->sampleFrames(), p->format());
  }
  row(key + "/properties", props(f->properties()));
}

void strip(File *f, int mask) {
  if (auto *a = dynamic_cast<FLAC::File *>(f))a->strip(mask);
  else dynamic_cast<RIFF::WAV::File *>(f)->strip(static_cast<RIFF::WAV::File::TagTypes>(mask));
}

void edit(File *f, int mode, int step) {
  if (mode == 0)return;
  if (mode == 1) {
    f->tag()->setTitle(step == 0 ? "Title" : "changed");
    f->tag()->setArtist("Artist");
    f->tag()->setAlbum("Album");
    f->tag()->setComment("Comment");
    f->tag()->setGenre("Jazz");
    f->tag()->setYear(0xffffffffU);
    f->tag()->setTrack(0x80000000U);
  }
  if (mode == 3) {
    id3(f, true)->setTitle("ID3 title");
    id3(f, true)->setArtist("ID3 artist");
    if (auto *a = dynamic_cast<FLAC::File *>(f)) {
      a->ID3v1Tag(true)->setTitle("v1 title");
      a->xiphComment()->setTitle("Xiph title");
    } else dynamic_cast<RIFF::WAV::File *>(f)->InfoTag()->setTitle("Info title");
  }
  if (mode == 4) {
    std::string v = step == 0 ? std::string(8192, 'x') : step == 1 ? "short" : "";
    f->tag()->setTitle(v.c_str());
  }
  if (mode == 5) {
    if (auto *a = dynamic_cast<FLAC::File *>(f)) {
      a->setiXMLData(step < 2 ? String("<xml>Žluť</xml>", String::UTF8) : String());
      a->setBEXTData(step < 2 ? ByteVector("broadcast") : ByteVector());
      auto *p = new FLAC::Picture();
      p->setData(ByteVector("\0\1\xff", 3));
      p->setMimeType("image/png");
      p->setDescription("physical");
      p->setWidth(3);
      p->setHeight(2);
      p->setColorDepth(24);
      p->setType(FLAC::Picture::FrontCover);
      if (step == 0) {
        a->addPicture(p);
        auto *x = new FLAC::Picture();
        x->setData(ByteVector("\7\10", 2));
        a->xiphComment()->addPicture(x);
      } else if (step == 1 && !a->pictureList().isEmpty()) {
        a->pictureList().front()->setDescription("mutated");
        a->removePicture(p, false);
        delete p;
      } else {
        delete p;
        a->removePictures();
      }
    } else {
      auto *w = dynamic_cast<RIFF::WAV::File *>(f);
      w->setiXMLData(step < 2 ? String("<xml>Žluť</xml>", String::UTF8) : String());
      w->setBEXTData(step < 2 ? ByteVector("broadcast") : ByteVector());
    }
  }
  if (mode == 6)strip(f, step == 0 ? 0 : step == 1 ? dynamic_cast<FLAC::File *>(f) ? 4 : 1 : 65535);
}

void complex(const std::string &key, File *f) {
  std::string text;
  for (auto &n: f->complexPropertyKeys())text += hex(n) + ",";
  row(key + "/keys", text);
  for (std::string k: {"PICTURE", "picture", "OTHER"}) {
    auto values = f->complexProperties(k.c_str());
    row(key + "/complex/" + k, values.size());
    int i = 0;
    for (const auto &map: values) {
      std::string out;
      for (const auto &[name,v]: map) {
        out += hex(name) + "=";
        if (name == "data")out += "b" + hex(v.value<ByteVector>());
        else if (name == "width" || name == "height" || name == "colorDepth" || name == "numColors")
          out += "i" + std::to_string(v.value<int>());
        else out += "s" + hex(v.value<String>());
        out += ";";
      }
      row(key + "/complex/" + k + "/" + std::to_string(i++), out);
    }
  }
}

int main(int argc, char **argv) {
  fs::path root = argv[1];
  std::vector<fs::path> inputs;
  for (auto &p: fs::directory_iterator(root / "inputs"))inputs.push_back(p.path());
  std::sort(inputs.begin(), inputs.end());
  for (auto &path: inputs) {
    std::string name = path.filename();
    bool flac = path.extension() == ".flac";
    auto bytes = read(path);
    for (int style = 0; style < 3; style++)
      for (int enabled = 0; enabled < 2; enabled++) {
        std::string key = name + "/controls/" + std::to_string(style) + "/" + std::to_string(enabled);
        auto mode = static_cast<AudioProperties::ReadStyle>(style);
        {
          auto f = open(path, enabled, mode);
          state(key + "/path", f.get());
        }
        for (int origin: {0, 7}) {
          Stream stream(bytes, name);
          stream.seek(origin);
          Factory factory;
          {
            auto f = open(&stream, flac, enabled, mode, &factory);
            state(key + "/io/" + std::to_string(origin), f.get());
            row(key + "/factory/" + std::to_string(origin), factory.calls);
            if (f->isValid() && !flac) {
              RIFF::WAV::Properties p(dynamic_cast<RIFF::WAV::File *>(f.get()), mode);
              row(key + "/direct/" + std::to_string(origin), p.lengthInMilliseconds(), p.bitrate(), p.sampleRate(),
                  p.channels(), p.bitsPerSample(), p.sampleFrames(), p.format(), f->tell());
            }
          }
          row(key + "/lifetime/" + std::to_string(origin), stream.isOpen());
        }
      }
    for (auto filename: {name, std::string("sample.unknown")}) {
      Stream stream(bytes, filename);
      stream.seek(7);
      row(name + "/support/" + filename,
          flac ? FLAC::File::isSupported(&stream) : RIFF::WAV::File::isSupported(&stream), stream.tell());
      stream.seek(0);
      {
        FileRef ref(&stream);
        row(name + "/ref/" + filename, ref.isNull(),
            dynamic_cast<FLAC::File *>(ref.file()) != nullptr || dynamic_cast<RIFF::WAV::File *>(ref.file()) != nullptr,
            stream.tell());
      }
    }
    {
      auto check = open(path, false, AudioProperties::Average);
      if (!check->isValid())continue;
    }
    std::vector<int> masks = flac
                               ? std::vector<int>{0, 1, 2, 3, 4, 5, 6, 7, 65535, 0xfff0}
                               : std::vector<int>{0, 1, 2, 3, 65535, 0xfff0};
    for (int transport = 0; transport < 2; transport++)
      for (int mode: {0, 1, 2, 3, 4, 5, 6})
        for (int mask: masks)
          for (int retain = 0; retain < 2; retain++) {
            if (flac && retain != 0)continue;
            if (mode != 3 && mode != 6 && (mask != 65535 || retain != 0))continue;
            std::string key = name + "/edit/" + std::to_string(transport) + "/" + std::to_string(mode) + "/" +
                              std::to_string(mask) + "/" + std::to_string(retain);
            Stream stream(bytes, name);
            std::string flat = key;
            std::replace(flat.begin(), flat.end(), '/', '_');
            auto copy = root / "outputs" / (flat + ".native");
            auto named = fs::path(copy.string() + (flac ? ".flac" : ".wav"));
            fs::copy_file(path, named, fs::copy_options::overwrite_existing);
            {
              auto f = transport == 0
                         ? open(named, true, AudioProperties::Average)
                         : open(&stream, flac, true, AudioProperties::Average, nullptr);
              auto *unionTag = f->tag();
              for (int step = 0; step < 3; step++) {
                if (mode == 2) {
                  PropertyMap p;
                  p["TITLE"] = StringList("one").append("two");
                  p["COMMENT"] = StringList("comment");
                  p["CUSTOM"] = StringList("custom");
                  p["A=B"] = StringList("invalid");
                  row(key + "/rejected/" + std::to_string(step), props(f->setProperties(p)));
                } else edit(f.get(), mode, step);
                if (flac && mode == 6)strip(f.get(), mask);
                state(key + "/before/" + std::to_string(step), f.get());
                complex(key + "/before/" + std::to_string(step), f.get());
                bool saved = flac
                               ? f->save()
                               : dynamic_cast<RIFF::WAV::File *>(f.get())->save(
                                 static_cast<RIFF::WAV::File::TagTypes>(mask),
                                 retain == 0 ? File::StripOthers : File::StripNone,
                                 step % 2 == 0 ? ID3v2::v3 : ID3v2::v4);
                if (transport == 0)f->seek(f->tell());
                auto output = transport == 0 ? read(named) : *stream.data();
                row(key + "/save/" + std::to_string(step), saved, f->tell(), hex(output), f->tag() == unionTag);
                state(key + "/after/" + std::to_string(step), f.get());
                ByteVectorStream reopenedStream(output);
                auto reopened = open(&reopenedStream, flac, true, AudioProperties::Average, nullptr);
                state(key + "/reopen/" + std::to_string(step), reopened.get());
              }
              row(key + "/trace", stream.trace);
              if (transport == 0)fs::copy_file(named, copy, fs::copy_options::overwrite_existing);
              else write(copy, *stream.data());
            }
            fs::remove(named);
          }
    Stream ro(bytes, name);
    ro.ro = true;
    {
      auto f = open(&ro, flac, false, AudioProperties::Average, nullptr);
      row(name + "/readonly", f->save(), hex(*ro.data()));
    }
  }
  return 0;
}
