#define main matrixMain
#include "flac-wave-tag-owners.cpp"
#undef main
struct OverrideFlac : FLAC::File {
  ID3v1::Tag alternate;
  OverrideFlac(IOStream *s) : FLAC::File(s, false) { alternate.setTitle("virtual"); }
  Tag *tag() const override { return const_cast<ID3v1::Tag *>(&alternate); }
};

struct OverrideWav : RIFF::WAV::File {
  ID3v1::Tag alternate;
  OverrideWav(IOStream *s) : RIFF::WAV::File(s, false) { alternate.setTitle("virtual"); }
  Tag *tag() const override { return const_cast<ID3v1::Tag *>(&alternate); }
};

struct RenderPicture : FLAC::Picture {
  mutable int calls = 0;

  ByteVector render() const override {
    calls++;
    return FLAC::Picture::render();
  }
};

struct LargePicture : FLAC::Picture {
  ByteVector render() const override { return ByteVector(0x1000000U, 0); }
};

struct Exposed : Ogg::XiphComment {
  void read(const ByteVector &b) { parse(b); }
};

void comment(const std::string &key, Ogg::XiphComment &c) {
  tag(key + "/tag", &c);
  std::string fields;
  for (const auto &[k,vs]: c.fieldListMap()) {
    fields += hex(k) + "=";
    for (const auto &v: vs)fields += hex(v) + ",";
    fields += ";";
  }
  row(key + "/state", c.fieldCount(), hex(c.vendorID()), fields, hex(c.render()), hex(c.render(false)),
      c.pictureList().size());
}

int main(int argc, char **argv) {
  fs::path root = argv[1];
  for (bool flac: {false, true}) {
    auto path = root / "inputs" / (flac ? "bare.flac" : "bare.wav");
    auto bytes = read(path);
    std::string key = flac ? "extra/flac" : "extra/wav";
    Factory factory;
    std::vector<std::unique_ptr<ByteVectorStream> > streams;
    std::vector<std::unique_ptr<File> > files;
    auto stream = [&]() {
      streams.push_back(std::make_unique<ByteVectorStream>(bytes));
      return streams.back().get();
    };
    if (flac) {
      files.emplace_back(new FLAC::File(path.c_str()));
      files.emplace_back(new FLAC::File(path.c_str(), false));
      files.emplace_back(new FLAC::File(path.c_str(), false, AudioProperties::Fast));
      files.emplace_back(new FLAC::File(path.c_str(), false, AudioProperties::Accurate, &factory));
      files.emplace_back(new FLAC::File(path.c_str(), true, AudioProperties::Average, &factory));
      files.emplace_back(new FLAC::File(path.c_str(), false, AudioProperties::Average, &factory));
      files.emplace_back(new FLAC::File(path.c_str(), false, AudioProperties::Fast, &factory));
      files.emplace_back(new FLAC::File(stream()));
      files.emplace_back(new FLAC::File(stream(), false));
      files.emplace_back(new FLAC::File(stream(), false, AudioProperties::Fast));
      files.emplace_back(new FLAC::File(stream(), true, AudioProperties::Average, &factory));
      files.emplace_back(new FLAC::File(stream(), false, AudioProperties::Average, &factory));
      files.emplace_back(new FLAC::File(stream(), false, AudioProperties::Fast, &factory));
    } else {
      files.emplace_back(new RIFF::WAV::File(path.c_str()));
      files.emplace_back(new RIFF::WAV::File(path.c_str(), false));
      files.emplace_back(new RIFF::WAV::File(path.c_str(), false, AudioProperties::Fast));
      files.emplace_back(new RIFF::WAV::File(path.c_str(), false, AudioProperties::Accurate, &factory));
      files.emplace_back(new RIFF::WAV::File(stream()));
      files.emplace_back(new RIFF::WAV::File(stream(), false));
      files.emplace_back(new RIFF::WAV::File(stream(), false, AudioProperties::Fast));
    }
    for (size_t i = 0; i < files.size(); i++)state(key + "/default/" + std::to_string(i), files[i].get());
    files.clear();
    for (int overload = 0; overload < 5; overload++) {
      Stream s(bytes, flac ? "sample.flac" : "sample.wav");
      auto f = open(&s, flac, false, AudioProperties::Average, nullptr);
      f->tag()->setTitle("default");
      bool saved;
      if (flac) {
        auto *a = dynamic_cast<FLAC::File *>(f.get());
        if (overload == 0)a->strip();
        if (overload == 1)a->strip(0);
        saved = a->save();
      } else {
        auto *a = dynamic_cast<RIFF::WAV::File *>(f.get());
        if (overload == 0)a->strip();
        saved = overload == 0
                  ? a->save()
                  : overload == 1
                      ? a->save(RIFF::WAV::File::AllTags)
                      : overload == 2
                          ? a->save(RIFF::WAV::File::AllTags, File::StripNone)
                          : a->save(RIFF::WAV::File::AllTags, File::StripNone, overload == 3 ? ID3v2::v3 : ID3v2::v4);
      }
      row(key + "/overload/" + std::to_string(overload), saved, hex(*s.data()));
    }
    for (int point = 1; point <= 18; point++) {
      Stream s(bytes, flac ? "sample.flac" : "sample.wav");
      auto f = open(&s, flac, false, AudioProperties::Average, nullptr);
      edit(f.get(), 3, 0);
      edit(f.get(), 5, 0);
      s.trace.clear();
      s.calls = 0;
      s.fault = point;
      bool saved = false, threw = false;
      try { saved = f->save(); } catch (const std::runtime_error &) { threw = true; }
      row(key + "/callback/" + std::to_string(point), threw, saved, f->tell(), hex(*s.data()), s.trace);
      state(key + "/callback/" + std::to_string(point), f.get());
    }
    {
      Stream s(bytes, flac ? "sample.flac" : "sample.wav");
      std::unique_ptr<File> f = flac
                                  ? std::unique_ptr<File>(new OverrideFlac(&s))
                                  : std::unique_ptr<File>(new OverrideWav(&s));
      PropertyMap p;
      p["TITLE"] = StringList("stored");
      f->setProperties(p);
      auto title = hex(f->tag()->title());
      auto properties = props(f->properties());
      bool saved = f->save();
      row(key + "/subclass", title, properties, saved, hex(*s.data()));
    }
    {
      Stream s(bytes, flac ? "sample.flac" : "sample.wav");
      FileRef ref(&s);
      ref.tag()->setTitle("FileRef shared");
      bool saved = ref.save();
      row(key + "/ref-save", saved, hex(*s.data()));
    }
  }
  {
    ByteVectorStream s(read(root / "inputs/bare.flac"));
    FLAC::File f(&s, false);
    auto *p = new RenderPicture();
    p->setData(ByteVector("\1\2", 2));
    f.addPicture(p);
    auto container = f.pictureList();
    row("extra/picture/identity", container.front() == p);
    f.save();
    row("extra/picture/render", p->calls, f.pictureList().front() == p);
    f.removePicture(p, false);
    row("extra/picture/remove", container.front() == p, f.pictureList().isEmpty());
    delete p;
  }
  Exposed c;
  comment("xiph/empty", c);
  int step = 0;
  for (std::string k: {
         "title", "Title", "COMMENT", "DESCRIPTION", "YEAR", "DATE", "TRACKNUM", "TRACKNUMBER", "", "A=B", "A~B",
         "A\x7f" "B", "A\xe3\x91\x96" "B", " ", "}"
       }) {
    c.addField(String(k, String::UTF8), "value", false);
    comment("xiph/add/" + std::to_string(step++), c);
  }
  c.addField("TITLE", "first", true);
  c.addField("title", "first", false);
  c.addField("title", "second", false);
  c.removeFields("title", "first");
  c.removeFields("absent", "x");
  comment("xiph/remove", c);
  c.setComment("sticky");
  c.setYear(0xffffffffU);
  c.setTrack(0x80000000U);
  comment("xiph/numeric", c);
  c.setYear(0);
  c.setTrack(0);
  comment("xiph/zero", c);
  {
    PropertyMap p;
    p["TITLE"] = StringList("a").append("").append("b");
    p["A=B"] = StringList("bad");
    p["EMPTY"] = StringList();
    row("xiph/rejected", props(c.setProperties(p)));
  }
  comment("xiph/properties", c);
  auto n = c.fieldListMap().value("GHOST").size();
  row("xiph/lookup", n, c.contains("ghost"));
  comment("xiph/bucket", c);
  auto *picture = new FLAC::Picture();
  picture->setDescription("embedded");
  picture->setData(ByteVector("\0\1\xff", 3));
  c.addPicture(picture);
  comment("xiph/picture", c);
  auto rendered = c.render(false);
  c.read(rendered);
  comment("xiph/parse-twice", c);
  c.removePicture(picture, false);
  comment("xiph/remove-picture", c);
  c.removeAllFields();
  c.removeAllPictures();
  comment("xiph/clear", c);
  for (const auto &data: std::vector<ByteVector>{
         ByteVector(), ByteVector(7U, 0), ByteVector::fromUInt(100, false) + ByteVector(4U, 0),
         ByteVector::fromUInt(3, false) + ByteVector("new") + ByteVector::fromUInt(50001, false),
         ByteVector::fromUInt(0, false) + ByteVector::fromUInt(1, false) + ByteVector::fromUInt(7, false) +
         ByteVector("title=x")
       }) {
    c.read(data);
    comment("xiph/parse/" + std::to_string(step++), c);
  }
  ByteVector fields = ByteVector::fromUInt(0, false) + ByteVector::fromUInt(3, false);
  for (const auto &v: std::vector<ByteVector>{
         ByteVector("title=lower"), ByteVector("COVERART=AP8="),
         ByteVector("METADATA_BLOCK_PICTURE=") + picture->render().toBase64()
       })
    fields.append(ByteVector::fromUInt(v.size(), false) + v);
  c.read(fields);
  comment("xiph/legacy", c);
  delete picture;
  for (std::string k: {"PICTURE", "picture", "other"}) {
    VariantMap p;
    p["data"] = ByteVector("image");
    p["width"] = String("wrong");
    p["pictureType"] = String("Front Cover");
    bool saved = c.setComplexProperties(k.c_str(), {p});
    row("xiph/complex/" + k, saved, c.complexProperties(k.c_str()).size());
    comment("xiph/complex-state/" + k, c);
  }
  ByteVector raw = ByteVector::fromUInt(3) + ByteVector::fromUInt(1) + ByteVector("\xff", 1) + ByteVector::fromUInt(1) +
                   ByteVector("\xff", 1) + ByteVector(20U, 0);
  FLAC::Picture p;
  bool parsed = p.parse(raw);
  row("picture/invalid-utf8", parsed, hex(p.mimeType()), hex(p.description()), hex(p.render()));
  {
    Stream s(read(root / "inputs/bare.flac"), "large.flac");
    FLAC::File f(&s, false);
    f.addPicture(new LargePicture());
    bool saved = f.save();
    row("extra/flac/large-block", saved, f.pictureList().size(), hex(*s.data()));
  }
}
