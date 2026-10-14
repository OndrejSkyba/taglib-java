#define main f02_matrix_main
#include "ogg-tag-owners.cpp"
#undef main
#include "oggpage.h"

std::unique_ptr<Ogg::File> overload(const fs::path &path, Stream *s, int codec, int variant) {
  switch (codec) {
    case 0: switch (variant) {
        case 0: return std::make_unique<Vorbis::File>(path.c_str());
        case 1: return std::make_unique<Vorbis::File>(path.c_str(), false);
        case 2: return std::make_unique<Vorbis::File>(s);
        default: return std::make_unique<Vorbis::File>(s, false);
      }
    case 1: switch (variant) {
        case 0: return std::make_unique<Ogg::Opus::File>(path.c_str());
        case 1: return std::make_unique<Ogg::Opus::File>(path.c_str(), false);
        case 2: return std::make_unique<Ogg::Opus::File>(s);
        default: return std::make_unique<Ogg::Opus::File>(s, false);
      }
    case 2: switch (variant) {
        case 0: return std::make_unique<Ogg::Speex::File>(path.c_str());
        case 1: return std::make_unique<Ogg::Speex::File>(path.c_str(), false);
        case 2: return std::make_unique<Ogg::Speex::File>(s);
        default: return std::make_unique<Ogg::Speex::File>(s, false);
      }
    default: switch (variant) {
        case 0: return std::make_unique<Ogg::FLAC::File>(path.c_str());
        case 1: return std::make_unique<Ogg::FLAC::File>(path.c_str(), false);
        case 2: return std::make_unique<Ogg::FLAC::File>(s);
        default: return std::make_unique<Ogg::FLAC::File>(s, false);
      }
  }
}

template<typename T>
struct Derived : T {
  Ogg::XiphComment *alternate;

  Derived(Stream *s, Ogg::XiphComment *tag) : T(s, false), alternate(tag) {
  }

  Ogg::XiphComment *tag() const override { return alternate; }
};

std::unique_ptr<Ogg::File> derived(Stream *s, int codec, Ogg::XiphComment *alternate) {
  switch (codec) {
    case 0: return std::make_unique<Derived<Vorbis::File> >(s, alternate);
    case 1: return std::make_unique<Derived<Ogg::Opus::File> >(s, alternate);
    case 2: return std::make_unique<Derived<Ogg::Speex::File> >(s, alternate);
    default: return std::make_unique<Derived<Ogg::FLAC::File> >(s, alternate);
  }
}

struct BarePage : Ogg::Page {
  BarePage(const ByteVectorList &p, unsigned int s, int n) : Page(p, s, n) {
  }

  BarePage(const ByteVectorList &p, unsigned int s, int n, bool c) : Page(p, s, n, c) {
  }

  BarePage(const ByteVectorList &p, unsigned int s, int n, bool c, bool d) : Page(p, s, n, c, d) {
  }

  BarePage(const ByteVectorList &p, unsigned int s, int n, bool c, bool d, bool e) : Page(p, s, n, c, d, e) {
  }
};

int main(int argc, char **argv) {
  fs::path root = argv[1];
  std::vector<std::string> names = {
    "upstream-empty.ogg", "upstream-correctness_gain_silent_output.opus", "upstream-empty.spx",
    "upstream-empty_flac.oga"
  };
  for (int codec = 0; codec < 4; codec++) {
    auto name = names[codec];
    auto path = root / "inputs" / name;
    auto bytes = read(path);
    std::string key = "extra/" + std::to_string(codec);
    for (int variant = 0; variant < 4; variant++) {
      Stream stream(bytes, name);
      stream.seek(7);
      {
        auto f = overload(path, &stream, codec, variant);
        state(key + "/overload/" + std::to_string(variant), f.get(), codec);
      }
      row(key + "/overload-lifetime/" + std::to_string(variant), stream.isOpen());
    }
    Ogg::XiphComment alt;
    alt.setTitle("virtual");
    auto *picture = new FLAC::Picture();
    picture->setData(ByteVector("\1", 1));
    alt.addPicture(picture);
    Stream s(bytes, name);
    {
      auto f = derived(&s, codec, &alt);
      PropertyMap p;
      p["TITLE"] = StringList("stored");
      f->setProperties(p);
      row(key + "/subclass-before", hex(f->tag()->title()), props(f->properties()),
          f->complexProperties("PICTURE").size());
      bool saved = f->save();
      row(key + "/subclass", saved, hex(f->tag()->title()), props(f->properties()), hex(*s.data()));
    }
    for (int phase = 1; phase <= 12; phase++) {
      Stream stream(bytes, name);
      auto f = open(&stream, codec, false, AudioProperties::Average);
      auto *original = f->tag();
      original->setTitle(std::string(8192, 'x'));
      stream.fault = phase;
      bool threw = false, saved = false;
      try { saved = f->save(); } catch (const std::runtime_error &failure) { threw = true; }
      row(key + "/callback/" + std::to_string(phase), threw, saved, f->tell(), hex(*stream.data()), stream.trace,
          f->tag() == original);
      stream.fault = 0;
      bool retry = f->save();
      row(key + "/callback-retry/" + std::to_string(phase), retry, f->tell(), hex(*stream.data()));
    }
    Stream stream(bytes, name);
    {
      FileRef ref(&stream, false);
      {
        FileRef copied(ref);
        row(key + "/ref-shared", copied.file() == ref.file(), copied.tag() == ref.tag());
        ref.tag()->setTitle("shared");
        bool saved = copied.save();
        row(key + "/ref-save", saved, hex(*stream.data()), hex(ref.tag()->title()));
      }
      row(key + "/ref-lifetime", stream.isOpen(), !ref.isNull());
    }
  }
  ByteVectorList values;
  values.append(ByteVector("a")).append(ByteVector(256, 0));
  for (int n = 0; n < 4; n++) {
    auto page = n == 0
                  ? std::make_unique<BarePage>(values, 0xffffffffU, 7)
                  : n == 1
                      ? std::make_unique<BarePage>(values, 0xffffffffU, 7, true)
                      : n == 2
                          ? std::make_unique<BarePage>(values, 0xffffffffU, 7, true, false)
                          : std::make_unique<BarePage>(values, 0xffffffffU, 7, true, false, true);
    row("extra/page-default/" + std::to_string(n), hex(page->render()), page->header()->firstPacketContinued(),
        page->header()->lastPacketCompleted(), page->header()->lastPageOfStream());
  }
  for (int n = 0; n < 3; n++) {
    auto pages = n == 0
                   ? Ogg::Page::paginate(values, Ogg::Page::SinglePagePerGroup, 0xffffffffU, 7)
                   : n == 1
                       ? Ogg::Page::paginate(values, Ogg::Page::SinglePagePerGroup, 0xffffffffU, 7, true)
                       : Ogg::Page::paginate(values, Ogg::Page::SinglePagePerGroup, 0xffffffffU, 7, true, false);
    row("extra/paginate-default/" + std::to_string(n), hex(pages.front()->render()));
    for (auto *p: pages)delete p;
  }
  Stream ownerStream(read(root / "inputs/upstream-empty.ogg"), "page.ogg");
  {
    Vorbis::File f(&ownerStream, false);
    Ogg::Page p(&f, 0);
    auto data = p.render();
    row("extra/page-owner", hex(data), f.tell(), p.fileOffset(), p.packetCount(), p.size());
    Ogg::PageHeader h(&f, 0);
    row("extra/header-owner", h.isValid(), hex(h.render()), f.tell());
    row("extra/header-default", Ogg::PageHeader(&f).isValid(), Ogg::PageHeader(nullptr, 0).isValid(), f.tell());
  }
  auto bytes = read(root / "inputs/upstream-empty_flac.oga");
  for (int mode = 0; mode < 2; mode++) {
    Stream stream(bytes, "large.oga");
    Ogg::FLAC::File f(&stream, false);
    auto *picture = new FLAC::Picture();
    picture->setData(ByteVector(mode == 0 ? 3 * (1 << 22) : 1, 0));
    f.tag()->addPicture(picture);
    f.tag()->setTitle(mode == 0 ? String("retained") : String(std::string(1 << 24, 'x')));
    bool saved = f.save();
    row("extra/large/" + std::to_string(mode), saved, f.tag()->pictureList().size(), f.tag()->title().size(),
        hex(*stream.data()));
  }
  return 0;
}
