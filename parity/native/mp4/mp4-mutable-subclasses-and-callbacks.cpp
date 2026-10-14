#define main baseMain
#include "mp4-mutable.cpp"
#undef main
#include <mp4nerochapterlist.h>
#include <mp4qtchapterlist.h>
#include <tvariant.h>

struct Stream : ByteVectorStream {
  std::string trace;
  bool ro = false;
  int calls = 0, point = 0;

  Stream(const ByteVector &v) : ByteVectorStream(v) {
  }

  FileName name() const override { return "sample.m4a"; }
  bool readOnly() const override { return ro; }

  void event(std::string s) {
    trace += s;
    if (point > 0 && ++calls == point)throw std::runtime_error("ordinary callback exception");
  }

  void insert(const ByteVector &d, offset_t s, size_t r) override {
    event("I" + std::to_string(s) + "," + std::to_string(r) + "," + std::to_string(d.size()) + ";");
    ByteVectorStream::insert(d, s, r);
  }

  void removeBlock(offset_t s, size_t n) override {
    event("R" + std::to_string(s) + "," + std::to_string(n) + ";");
    ByteVectorStream::removeBlock(s, n);
  }

  void writeBlock(const ByteVector &d) override {
    event("W" + std::to_string(tell()) + "," + std::to_string(d.size()) + ";");
    ByteVectorStream::writeBlock(d);
  }

  void truncate(offset_t n) override {
    event("T" + std::to_string(n) + ";");
    ByteVectorStream::truncate(n);
  }
};

void audio(const std::string &k, MP4::File &f) {
  auto *p = f.audioProperties();
  if (!p) {
    row(k, "null");
    return;
  }
  row(k, std::to_string(p->lengthInSeconds()) + "," + std::to_string(p->lengthInMilliseconds()) + "," +
         std::to_string(p->bitrate()) + "," + std::to_string(p->sampleRate()) + "," + std::to_string(p->channels()) +
         "," + std::to_string(p->bitsPerSample()) + "," + b(p->isEncrypted()) + "," + std::to_string(p->codec()) + "," +
         hs(p->codecId()));
}

struct Override : MP4::File {
  MP4::Tag alternate;
  Override(IOStream *s) : MP4::File(s, false) { alternate.setTitle("virtual"); }
  MP4::Tag *tag() const override { return const_cast<MP4::Tag *>(&alternate); }
};

struct Helpers : MP4::ItemFactory {
  using ItemFactory::nameHandlerMap;
  using ItemFactory::namePropertyMap;
  using ItemFactory::handlerTypeForName;

  static std::string data(MP4::Atom *a, const ByteVector &d, int flags, bool free) {
    std::string s;
    for (auto &v: parseData2(a, d, flags, free))
      s += std::to_string(v.type) + ":" + std::to_string(v.locale) + ":" +
          hex(v.data) + ",";
    return s;
  }

  static std::pair<String, MP4::Item> parse(int n, MP4::Atom *a, const ByteVector &d) {
    switch (n) {
      case 0: return parseInt(a, d);
      case 1: return parseUInt(a, d);
      case 2: return parseLongLong(a, d);
      case 3: return parseByte(a, d);
      case 4: return parseBool(a, d);
      case 5: return parseIntPair(a, d);
      case 6: return parseTextOrInt(a, d);
      case 7: return parseText(a, d);
      case 8: return parseText(a, d, -1);
      case 9: return parseGnre(a, d);
      case 10: return parseCovr(a, d);
      case 11: return parseStem(a, d);
      default: return parseFreeForm(a, d);
    }
  }

  static ByteVector render(int n, const ByteVector &name, const MP4::Item &i) {
    switch (n) {
      case 0: return renderInt(name, i);
      case 1: return renderUInt(name, i);
      case 2: return renderLongLong(name, i);
      case 3: return renderByte(name, i);
      case 4: return renderBool(name, i);
      case 5: return renderIntPair(name, i);
      case 6: return renderTextOrInt(name, i);
      case 7: return renderText(name, i);
      case 8: return renderText(name, i, 0);
      case 9: return renderIntPairNoTrailing(name, i);
      case 10: return renderCovr(name, i);
      case 11: return renderStem(name, i);
      default: return renderFreeForm("----:org.TagLib:TEST", i);
    }
  }

  static ByteVector ra(const ByteVector &name, const ByteVector &d) { return renderAtom(name, d); }

  static ByteVector rd(const ByteVector &name, int flags, const ByteVectorList &v) {
    return renderData(name, flags, v);
  }
};

struct ExposedAtom : MP4::Atom {
  ExposedAtom(TagLib::File *f, int depth) : Atom(f, depth) {
  }

  ExposedAtom(TagLib::File *f, int depth, unsigned int &count) : Atom(f, depth, count) {
  }
};

int main(int argc, char **argv) {
  fs::path root = argv[1];
  auto input = bytes(root / "inputs/upstream-no-tags.m4a");
  for (int depth: {0, 64, 65})
    for (unsigned int initial: {0U, 49999U, 50000U, 0xffffffffU}) {
      Stream s(input);
      MP4::File f(&s, false);
      f.seek(0);
      unsigned int count = initial;
      ExposedAtom a(&f, depth, count);
      row("extra/atom/counter/" + std::to_string(depth) + "/" + std::to_string(initial),
          atom(&a) + "\t" + std::to_string(count) + "\t" + std::to_string(f.tell()));
    }
  {
    Stream s(input);
    MP4::File f(&s, false);
    MP4::Atoms a(&f);
    auto *m = a.find("moov");
    MP4::AtomList p;
    bool found = m->path(p, "trak", "absent");
    std::string names;
    for (auto *x: p)names += hex(x->name()) + ",";
    row("extra/atom/partial", b(found) + "\t" + names + "\t" + std::to_string(a.path("moov", "absent").size()));
    f.seek(0);
    auto *child = new MP4::Atom(&f);
    m->prependChild(child);
    row("extra/atom/prepend", b(m->children().front() == child) + "\t" + std::to_string(m->children().size()));
    bool removed = m->removeChild(child);
    row("extra/atom/remove",
        b(removed) + "\t" + b(m->removeChild(child)) + "\t" + std::to_string(m->children().size()));
    delete child;
  }
  for (auto name: {"upstream-no-tags.m4a", "upstream-has-tags.m4a", "upstream-blank_video.m4v"}) {
    auto raw = bytes(root / "inputs" / name);
    for (int style = 0; style < 3; style++)
      for (int enabled = 0; enabled < 2; enabled++)
        for (int origin: {0, 7}) {
          Stream s(raw);
          s.seek(origin);
          MP4::File f(&s, enabled, static_cast<AudioProperties::ReadStyle>(style));
          std::string k = std::string("extra/") + name + "/control/" + std::to_string(style) + "/" +
                          std::to_string(enabled)
                          + "/" + std::to_string(origin);
          row(k, b(f.isValid()) + "\t" + std::to_string(f.tell()) + "\t" + tag(f.tag()));
          audio(k + "/audio", f);
        }
  }
  for (int mode = 0; mode < 6; mode++) {
    Stream s(input);
    MP4::File f(&s, false);
    std::string k = "extra/repeated/" + std::to_string(mode);
    for (int step = 0; step < 3; step++) {
      if (mode == 0)f.tag()->setTitle(step == 0 ? String(std::string(8192, 'X')) : step == 1 ? "short" : "");
      if (mode == 1)
        f.setNeroChapters(step == 2
                            ? MP4::ChapterList{}
                            : MP4::ChapterList{
                              MP4::Chapter(step == 0 ? "A" : "B", 0), MP4::Chapter("Second", 1500)
                            });
      if (mode == 2)
        f.setQtChapters(step == 2
                          ? MP4::ChapterList{}
                          : MP4::ChapterList{
                            MP4::Chapter(step == 0 ? "A" : "B", 0), MP4::Chapter("Second", 1500)
                          });
      if (mode == 3) {
        f.tag()->setTitle("combined");
        f.setNeroChapters({MP4::Chapter("Nero", 0)});
        f.setQtChapters({MP4::Chapter("Qt", 0)});
      }
      if (mode == 4) {
        if (step == 0)f.tag()->setItem("stem", MP4::Stem("manifest"));
        else f.tag()->removeItem("stem");
      }
      if (mode == 5) {
        if (step == 0)f.tag()->setTitle("strip");
        else f.strip(step == 1 ? 0 : 65535);
      }
      bool result = f.save();
      row(k + "/save/" + std::to_string(step),
          b(result) + "\t" + std::to_string(f.tell()) + "\t" + hex(*s.data()) + "\t" + tag(f.tag()));
    }
    row(k + "/trace", s.trace);
  }
  for (int mode = 0; mode < 3; mode++)
    for (int point = 0; point <= 24; point++) {
      Stream s(input);
      MP4::File f(&s, false);
      f.tag()->setTitle("pending");
      if (mode == 1)f.setNeroChapters({MP4::Chapter("chapter", 0)});
      if (mode == 2)f.setQtChapters({MP4::Chapter("chapter", 0)});
      s.point = point;
      bool saved = false, threw = false;
      try { saved = f.save(); } catch (const std::runtime_error &) { threw = true; }
      row("extra/callback/" + std::to_string(mode) + "/" + std::to_string(point),
          b(threw) + "\t" + b(saved) + "\t" + std::to_string(f.tell()) + "\t" + hex(*s.data()) + "\t" + tag(f.tag()) +
          "\t" + s.trace);
    }
  {
    Stream s(input);
    s.ro = true;
    MP4::File f(&s, false);
    f.tag()->setTitle("pending");
    bool save = f.save(), strip = f.strip();
    row("extra/readonly", b(save) + "\t" + b(strip) + "\t" + hex(*s.data()) + "\t" + tag(f.tag()));
  }
  {
    Stream s(input);
    s.seek(7);
    bool support = MP4::File::isSupported(&s);
    row("extra/support", b(support) + "\t" + std::to_string(s.tell()));
    FileRef r(&s);
    row("extra/ref", b(dynamic_cast<MP4::File *>(r.file()) != nullptr) + "\t" + std::to_string(s.tell()));
    r.tag()->setTitle("shared");
    bool saved = r.save();
    row("extra/ref-save", b(saved) + "\t" + hex(*s.data()));
  }
  {
    Stream s(input);
    Override f(&s);
    PropertyMap p;
    p.insert("TITLE", StringList("stored"));
    f.setProperties(p);
    VariantMap picture;
    picture["data"] = ByteVector("cover");
    picture["mimeType"] = String("image/png");
    f.setComplexProperties("PICTURE", {picture});
    bool saved = f.save();
    row("extra/subclass",
        hs(f.tag()->title()) + "\t" + props(f.properties()) + "\t" + std::to_string(
          f.tag()->complexProperties("PICTURE").size()) + "\t" + b(saved) + "\t" + hex(*s.data()));
  }
  for (auto key: {"PICTURE", "picture", "STEM", "stem", "OTHER"})
    for (auto mime: {
           "image/png", "image/jpeg", "image/gif",
           "image/bmp", "other"
         }) {
      MP4::Tag t;
      VariantMap p;
      p["data"] = ByteVector("image");
      p["mimeType"] = String(mime);
      p["manifest"] = ByteVector("manifest");
      bool result = t.setComplexProperties(key, {p});
      row(std::string("extra/complex/") + key + "/" + mime,
          b(result) + "\t" + sl(t.complexPropertyKeys()) + "\t" + tag(&t) + "\t" + std::to_string(
            t.complexProperties(key).size()));
    }
  Helpers h;
  {
    MP4::Tag t;
    for (auto &x: h.namePropertyMap()) {
      auto name = h.nameForPropertyKey(x.second);
      row("extra/mapping/" + hex(x.first),
          hs(x.second) + "\t" + hs(h.propertyKeyForName(x.first)) + "\t" + hex(name) + "\t" + std::to_string(
            int(h.handlerTypeForName(x.first))));
    }
  }
  Stream s(input);
  MP4::File f(&s, false);
  MP4::Atoms tree(&f);
  auto *a = tree.find("moov");
  for (int flags: {0, 1, 21, 255})
    for (int size = 0; size < 20; size++) {
      auto d = Helpers::rd("test", flags, {ByteVector(size, 'A')});
      d = d.mid(8);
      std::string k = "extra/helper/" + std::to_string(flags) + "/" + std::to_string(size);
      row(k + "/data", Helpers::data(a, d, -1, false));
      for (int n = 0; n < 12; n++) {
        auto r = Helpers::parse(n, a, d);
        row(k + "/parse/" + std::to_string(n), hs(r.first) + "\t" + item(r.second));
      }
    }
  std::vector<MP4::Item> values = {
    MP4::Item(-2), MP4::Item(0xffffffffU), MP4::Item(-7LL), MP4::Item((unsigned char) 255), MP4::Item(true),
    MP4::Item(2, 10), MP4::Item(StringList("rate")), MP4::Item(StringList{"one", "two"}),
    MP4::Item(StringList("implicit")), MP4::Item(3, 4),
    MP4::Item(MP4::CoverArtList{MP4::CoverArt(MP4::CoverArt::GIF, "GIF89a")}), MP4::Item(MP4::Stem("stem")),
    MP4::Item(ByteVectorList{"a", "bc"})
  };
  for (int n = 0; n < 13; n++)
    row("extra/helper/render/" + std::to_string(n),
        hex(Helpers::render(n, "test", values[n])));
  for (int n = 0; n < int(values.size()); n++)
    for (int m = 0; m < int(values.size()); m++)
      row(
        "extra/item/equality/" + std::to_string(n) + "/" + std::to_string(m), b(values[n] == values[m]));
  for (bool qt: {false, true}) {
    Stream chapterInput(input);
    MP4::File cf(&chapterInput, false);
    MP4::NeroChapterList n;
    MP4::QtChapterList q;
    bool read = qt ? q.read(&cf) : n.read(&cf);
    row(std::string("extra/direct/") + b(qt) + "/read", b(read) + "\t" + std::to_string(cf.tell()));
    MP4::ChapterList list;
    for (int i = 0; i < 260; i++)list.append(MP4::Chapter(String(std::string(300, 'x')), i * 10));
    if (qt)q.setChapters(list);
    else n.setChapters(list);
    bool wrote = qt ? q.write(&cf) : n.write(&cf);
    row(std::string("extra/direct/") + b(qt) + "/write",
        b(wrote) + "\t" + std::to_string(cf.tell()) + "\t" + hex(*chapterInput.data()));
    bool reread = qt ? q.read(&cf) : n.read(&cf);
    row(std::string("extra/direct/") + b(qt) + "/reread",
        b(reread) + "\t" + chapters(qt ? q.chapters() : n.chapters()));
    bool removed = qt ? q.remove(&cf) : n.remove(&cf);
    row(std::string("extra/direct/") + b(qt) + "/remove",
        b(removed) + "\t" + std::to_string(cf.tell()) + "\t" + hex(*chapterInput.data()));
  }
}
