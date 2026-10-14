// ID3v1 and RIFF INFO reparsing, handlers, and tag-owner saves.
#include <fstream>
#include <iostream>
#include <filesystem>
#include <memory>
#include <stdexcept>
#include <tbytevectorstream.h>
#include <tpropertymap.h>
#include <fileref.h>
#include <id3v1tag.h>
#include <id3v1genres.h>
#include <infotag.h>
#include <mpegfile.h>
#include <flacfile.h>
#include <trueaudiofile.h>
#include <apefile.h>
#include <apetag.h>
#include <wavpackfile.h>
#include <mpcfile.h>
#include <wavfile.h>
using namespace TagLib;
namespace fs = std::filesystem;

static ByteVector read(const fs::path &p) {
  std::ifstream in(p, std::ios::binary);
  std::string s((std::istreambuf_iterator<char>(in)), {});
  return ByteVector(s.data(), s.size());
}

static std::string hex(const ByteVector &b) {
  auto h = b.toHex();
  return {h.data(), h.size()};
}

static std::string text(const String &s) { return hex(s.data(String::UTF16BE)); }

static std::string props(const PropertyMap &p) {
  std::string s;
  for (const auto &[k,v]: p) {
    s += text(k) + "=";
    for (const auto &w: v)s += text(w) + ",";
    s += ";";
  }
  s += "/";
  for (const auto &w: p.unsupportedData())s += text(w) + ",";
  return s;
}

static std::string common(const TagLib::Tag &t) {
  return text(t.title()) + ":" + text(t.artist()) + ":" + text(t.album()) + ":" + text(t.comment()) + ":" +
         text(t.genre()) + ":" + std::to_string(t.year()) + ":" + std::to_string(t.track()) + ":" +
         std::to_string(t.isEmpty()) + ":" + props(t.properties());
}

struct IdHandler : ID3v1::StringHandler {
  int mode;
  mutable int parsed = 0, rendered = 0;

  explicit IdHandler(int m) : mode(m) {
  }

  String parse(const ByteVector &b) const override {
    parsed++;
    auto s = mode == 1 ? String(b, String::UTF8).stripWhiteSpace() : StringHandler::parse(b);
    return mode == 2 ? s + "!" + String::number(parsed) : s;
  }

  ByteVector render(const String &s) const override {
    rendered++;
    return (mode == 2 ? s + "~" + String::number(rendered) : s).data(mode == 1 ? String::UTF8 : String::Latin1);
  }
};

struct InfoHandler : RIFF::Info::StringHandler {
  int mode;
  mutable int parsed = 0, rendered = 0;

  explicit InfoHandler(int m) : mode(m) {
  }

  String parse(const ByteVector &b) const override {
    parsed++;
    auto s = String(b, mode == 2 ? String::Latin1 : String::UTF8);
    return mode == 2 ? s + "!" + String::number(parsed) : s;
  }

  ByteVector render(const String &s) const override {
    rendered++;
    return (mode == 2 ? s + "~" + String::number(rendered) : s).data(mode == 2 ? String::Latin1 : String::UTF8);
  }
};

struct Bare : File {
  explicit Bare(IOStream *s) : File(s) {
  }

  TagLib::Tag *tag() const override { return nullptr; }
  AudioProperties *audioProperties() const override { return nullptr; }
  bool save() override { return false; }
};

struct Id : ID3v1::Tag {
  Id(File *f, offset_t o) : Tag(f, o) {
  }

  Id() = default;

  void apply(const ByteVector &b) { parse(b); }
  void again() { read(); }
};

struct Info : RIFF::Info::Tag {
  Info() = default;

  explicit Info(const ByteVector &b) : Tag(b) {
  }

  void apply(const ByteVector &b) { parse(b); }
};

static void idstate(const std::string &key, ID3v1::Tag &t, const IdHandler &h) {
  auto out = t.render();
  std::cout << key << '\t' << common(t) << ':' << t.genreNumber() << ':' << h.parsed << ':' << h.rendered << '\t' <<
      hex(out) << '\n';
}

static void infostate(const std::string &key, RIFF::Info::Tag &t, const InfoHandler &h) {
  auto out = t.render();
  std::cout << key << '\t' << common(t) << ':' << h.parsed << ':' << h.rendered << ':';
  for (const auto &[k,v]: t.fieldListMap())std::cout << hex(k) << '=' << text(v) << ';';
  std::cout << '\t' << hex(out) << '\n';
}

struct Named : ByteVectorStream {
  std::string fileName_;

  Named(const ByteVector &b, const fs::path &p) : ByteVectorStream(b), fileName_(p.string()) {
  }

  FileName name() const override { return fileName_.c_str(); }
};

static std::unique_ptr<File> open(const fs::path &p, IOStream *s, int kind) {
  auto style = AudioProperties::Average;
  switch (kind) {
    case 0: return s
                     ? std::unique_ptr<File>(new MPEG::File(s, false, style))
                     : std::unique_ptr<File>(new MPEG::File(p.c_str(), false, style));
    case 1: return s
                     ? std::unique_ptr<File>(new TrueAudio::File(s, false, style))
                     : std::unique_ptr<File>(new TrueAudio::File(p.c_str(), false, style));
    case 2: return s
                     ? std::unique_ptr<File>(new WavPack::File(s, false, style))
                     : std::unique_ptr<File>(new WavPack::File(p.c_str(), false, style));
    case 3: return s
                     ? std::unique_ptr<File>(new APE::File(s, false, style))
                     : std::unique_ptr<File>(new APE::File(p.c_str(), false, style));
    case 4: return s
                     ? std::unique_ptr<File>(new MPC::File(s, false, style))
                     : std::unique_ptr<File>(new MPC::File(p.c_str(), false, style));
    case 5: return s
                     ? std::unique_ptr<File>(new FLAC::File(s, false, style))
                     : std::unique_ptr<File>(new FLAC::File(p.c_str(), false, style));
    default: return s
                      ? std::unique_ptr<File>(new RIFF::WAV::File(s, false, style))
                      : std::unique_ptr<File>(new RIFF::WAV::File(p.c_str(), false, style));
  }
}

static TagLib::Tag *child(File *f, int kind) {
  switch (kind) {
    case 0: return dynamic_cast<MPEG::File *>(f)->ID3v1Tag();
    case 1: return dynamic_cast<TrueAudio::File *>(f)->ID3v1Tag();
    case 2: return dynamic_cast<WavPack::File *>(f)->ID3v1Tag();
    case 3: return dynamic_cast<APE::File *>(f)->ID3v1Tag();
    case 4: return dynamic_cast<MPC::File *>(f)->ID3v1Tag();
    case 5: return dynamic_cast<FLAC::File *>(f)->ID3v1Tag();
    default: return dynamic_cast<RIFF::WAV::File *>(f)->InfoTag();
  }
}

static ByteVector liveBytes(File *f) {
  auto position = f->tell();
  auto size = f->length();
  f->seek(0);
  auto bytes = f->readBlock(size);
  f->seek(position);
  return bytes;
}

static void parents(const fs::path &root) {
  std::ifstream list(root / "parent-cases.tsv");
  std::string name;
  int kind = 0;
  while (std::getline(list, name)) {
    auto original = read(root / "parents" / name);
    for (int mode = 0; mode < 3; mode++)
      for (bool memory: {false, true})
        for (bool wrapper: {false, true}) {
          IdHandler ih(mode);
          InfoHandler fh(mode);
          ID3v1::Tag::setStringHandler(mode ? &ih : nullptr);
          RIFF::Info::Tag::setStringHandler(mode ? &fh : nullptr);
          auto key = "parent/" + std::to_string(kind) + "/" + std::to_string(mode) + "/" + std::to_string(memory) + "/"
                     +
                     std::to_string(wrapper);
          auto target = root / "outputs" / "native" / (
                          std::to_string(kind) + "-" + std::to_string(mode) + "-" + std::to_string(memory) + "-" +
                          std::to_string(wrapper) + "-" + name);
          fs::create_directories(target.parent_path());
          fs::copy_file(root / "parents" / name, target, fs::copy_options::overwrite_existing);
          Named stream(original, name);
          std::unique_ptr<File> direct;
          std::unique_ptr<FileRef> ref;
          File *f;
          if (wrapper) {
            ref = memory ? std::make_unique<FileRef>(&stream, false) : std::make_unique<FileRef>(target.c_str(), false);
            f = ref->file();
          } else {
            direct = open(target, memory ? &stream : nullptr, kind);
            f = direct.get();
          }
          if (!f || !f->isValid())throw std::runtime_error("Ordinary parent rejected");
          auto *c = child(f, kind);
          auto *tag = f->tag();
          auto snapshot = [&](const std::string &step) {
            std::cout << key << '/' << step << '\t' << common(*tag) << '\t' << common(*c) << '\t' << ih.parsed << ':' <<
                ih.
                rendered << ':' << fh.parsed << ':' << fh.rendered << ':' << (child(f, kind) == c) << ':' << (
                  f->tag() == tag) << '\n';
          };
          snapshot("read");
          for (int round = 0; round < 2; round++) {
            if (round == 0) {
              c->setArtist(" Artist caf\xe9 ");
              c->setYear(4294967295U);
            } else {
              RIFF::Info::Tag::setStringHandler(nullptr);
              ID3v1::Tag::setStringHandler(nullptr);
              c->setTitle("Second");
              c->setTrack(255);
            }
            if (!f->save())throw std::runtime_error("Ordinary parent save rejected");
            snapshot("saved" + std::to_string(round));
            auto bytes = memory ? *stream.data() : liveBytes(f);
            std::cout << key << "/save/" << round << "/bytes\t" << hex(bytes) << '\n';
          }
          direct.reset();
          ref.reset();
          std::cout << key << "/borrowed-open\t" << stream.isOpen() << '\n';
          if (memory) {
            std::ofstream out(target, std::ios::binary);
            out.write(stream.data()->data(), stream.data()->size());
          }
          auto reread = open(target, nullptr, kind);
          std::cout << key << "/reread\t" << common(*reread->tag()) << '\t' << common(*child(reread.get(), kind)) <<
              '\n';
          ID3v1::Tag::setStringHandler(nullptr);
          RIFF::Info::Tag::setStringHandler(nullptr);
        }
    kind++;
  }
}

static ByteVector field(const char *id, const char *s) {
  ByteVector b(s);
  b.append('\0');
  ByteVector out(id);
  out.append(ByteVector::fromUInt(b.size(), false));
  out.append(b);
  if (b.size() % 2)out.append('\0');
  return out;
}

static void replacementHelpers(const fs::path &root) {
  auto raw = read(root / "inputs" / "id3-short-128");
  struct IdNext : ID3v1::StringHandler {
    mutable int calls = 0;

    String parse(const ByteVector &) const override {
      calls++;
      return "replacement";
    }

    ByteVector render(const String &) const override {
      calls++;
      return ByteVector("B");
    }
  } idNext;
  struct IdFirst : ID3v1::StringHandler {
    IdNext *next;
    mutable int calls = 0;
    ByteVector shared = ByteVector(40, 'A');

    explicit IdFirst(IdNext *n) : next(n) {
    }

    String parse(const ByteVector &) const override {
      calls++;
      ID3v1::Tag::setStringHandler(next);
      return "first";
    }

    ByteVector render(const String &) const override {
      calls++;
      ID3v1::Tag::setStringHandler(next);
      return shared;
    }
  } idFirst(&idNext);
  ID3v1::Tag::setStringHandler(&idFirst);
  Id replaced;
  replaced.apply(raw);
  ID3v1::Tag::setStringHandler(nullptr);
  std::cout << "callback/id3/replace-parse\t" << idFirst.calls << ':' << idNext.calls << ':' << common(replaced) << '\t'
      << hex(raw) << '\n';
  idFirst.calls = idNext.calls = 0;
  Id rendered;
  rendered.setTitle("unchanged");
  ID3v1::Tag::setStringHandler(&idFirst);
  auto idOut = rendered.render();
  ID3v1::Tag::setStringHandler(nullptr);
  std::cout << "callback/id3/replace-render\t" << idFirst.calls << ':' << idNext.calls << ':' << text(rendered.title())
      << ':' << hex(idFirst.shared) << '\t' << hex(idOut) << '\n';
  struct InfoNext : RIFF::Info::StringHandler {
    mutable int calls = 0;

    String parse(const ByteVector &) const override {
      calls++;
      return "replacement";
    }

    ByteVector render(const String &) const override {
      calls++;
      return ByteVector("B");
    }
  } infoNext;
  struct InfoFirst : RIFF::Info::StringHandler {
    InfoNext *next;
    mutable int calls = 0;
    ByteVector shared = ByteVector("A");

    explicit InfoFirst(InfoNext *n) : next(n) {
    }

    String parse(const ByteVector &) const override {
      calls++;
      RIFF::Info::Tag::setStringHandler(next);
      return "first";
    }

    ByteVector render(const String &) const override {
      calls++;
      RIFF::Info::Tag::setStringHandler(next);
      return shared;
    }
  } infoFirst(&infoNext);
  auto infoRaw = ByteVector("INFO") + field("IART", "artist") + field("INAM", "title");
  RIFF::Info::Tag::setStringHandler(&infoFirst);
  Info infoReplaced(infoRaw);
  RIFF::Info::Tag::setStringHandler(nullptr);
  std::cout << "callback/info/replace-parse\t" << infoFirst.calls << ':' << infoNext.calls << ':' <<
      common(infoReplaced) << '\t' << hex(infoRaw) << '\n';
  infoFirst.calls = infoNext.calls = 0;
  Info infoRendered;
  infoRendered.setTitle("title");
  infoRendered.setArtist("artist");
  RIFF::Info::Tag::setStringHandler(&infoFirst);
  auto infoOut = infoRendered.render();
  RIFF::Info::Tag::setStringHandler(nullptr);
  std::cout << "callback/info/replace-render\t" << infoFirst.calls << ':' << infoNext.calls << ':' <<
      text(infoRendered.title()) << ':' << hex(infoFirst.shared) << '\t' << hex(infoOut) << '\n';
}

static void contractHelpers(const fs::path &root) {
  auto raw = read(root / "inputs" / "id3-short-128");
  for (int fail = 1; fail <= 5; fail++) {
    Id t;
    t.setTitle("old-title");
    t.setArtist("old-artist");
    t.setAlbum("old-album");
    t.setYear(2020);
    t.setComment("old-comment");
    t.setTrack(55);
    t.setGenreNumber(222);
    struct Throw : IdHandler {
      int fail;

      Throw(int n) : IdHandler(0), fail(n) {
      }

      String parse(const ByteVector &b) const override {
        if (parsed + 1 == fail) {
          parsed++;
          throw std::invalid_argument("callback");
        }
        return IdHandler::parse(b);
      }
    } h(fail);
    ID3v1::Tag::setStringHandler(&h);
    bool caught = false;
    try { t.apply(raw); } catch (const std::invalid_argument &) { caught = true; }
    ID3v1::Tag::setStringHandler(nullptr);
    std::cout << "callback/id3/fail/" << fail << '\t' << caught << ':' << h.parsed << ':' << common(t) << ':' << t.
        genreNumber() << '\t' << hex(t.render()) << '\n';
  }
  Info t;
  t.setTitle("old-title");
  t.setArtist("old-artist");
  t.setAlbum("old-album");
  struct Throw : InfoHandler {
    Throw() : InfoHandler(0) {
    }

    String parse(const ByteVector &b) const override {
      if (parsed + 1 == 2) {
        parsed++;
        throw std::invalid_argument("callback");
      }
      return InfoHandler::parse(b);
    }
  } h;
  RIFF::Info::Tag::setStringHandler(&h);
  bool caught = false;
  try {
    t.apply(ByteVector("INFO") + field("IART", "new-artist") + field("IPRD", "new-album") + field("INAM", "new-title"));
  } catch (const std::invalid_argument &) { caught = true; }
  RIFF::Info::Tag::setStringHandler(nullptr);
  std::cout << "callback/info/fail/2\t" << caught << ':' << h.parsed << ':' << common(t) << '\t' << hex(t.render()) <<
      '\n';
  replacementHelpers(root);
}

int main(int argc, char **argv) {
  fs::path root = argv[1];
  std::ifstream cases(root / "cases.tsv");
  std::string line;
  while (std::getline(cases, line)) {
    char kind = line[0];
    std::string name = line.substr(2);
    auto raw = read(root / "inputs" / name);
    for (int mode = 0; mode < 3; mode++) {
      std::string key = name + "/" + std::to_string(mode);
      if (kind == 'I') {
        IdHandler h(mode);
        ID3v1::Tag::setStringHandler(mode ? &h : nullptr);
        ByteVectorStream stream(ByteVector(7, 0) + raw);
        Bare file(&stream);
        Id t(&file, 7);
        idstate(key + "/read", t, h);
        std::cout << key << "/cursor\t" << stream.tell() << '\n';
        if (raw.size() >= 128) {
          t.setTrack(55);
          t.apply(raw);
          idstate(key + "/parse", t, h);
          ByteVector v10 = raw;
          v10[126] = 0;
          t.apply(v10);
          idstate(key + "/v10", t, h);
        }
        ID3v1::Tag::setStringHandler(nullptr);
      } else {
        InfoHandler h(mode);
        RIFF::Info::Tag::setStringHandler(mode ? &h : nullptr);
        Info t(raw);
        infostate(key + "/read", t, h);
        Info merged;
        merged.setTitle("old");
        merged.setArtist("kept");
        merged.apply(raw);
        infostate(key + "/merge", merged, h);
        merged.apply(raw);
        infostate(key + "/repeat", merged, h);
        RIFF::Info::Tag::setStringHandler(nullptr);
      }
    }
  }
  for (unsigned n = 0; n <= 300; n++) {
    IdHandler ih(0);
    InfoHandler fh(0);
    Id t;
    Info info;
    t.setYear(n);
    t.setTrack(n);
    t.setGenreNumber(n);
    info.setYear(n);
    info.setTrack(n);
    idstate("number/id3/" + std::to_string(n), t, ih);
    infostate("number/info/" + std::to_string(n), info, fh);
  }
  contractHelpers(root);
  for (const auto &name: {"collision-empty.mpc", "collision-real.ape"}) {
    auto target = root / "outputs" / "native" / name;
    fs::create_directories(target.parent_path());
    fs::copy_file(root / name, target, fs::copy_options::overwrite_existing);
    bool saved;
    {
      APE::File f(target.c_str(), false);
      f.APETag(true)->setArtist("Filltointersect    ");
      f.APETag()->setTitle("Filltointersect    ");
      saved = f.save();
    }
    APE::File f(target.c_str(), false);
    std::cout << "collision/" << name << '\t' << saved << ':' << f.hasID3v1Tag() << '\t' << hex(read(target)) << '\n';
  }
  parents(root);
}
