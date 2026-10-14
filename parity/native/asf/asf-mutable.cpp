#include <iostream>
#include <fstream>
#include <filesystem>
#include <functional>
#include <algorithm>
#include <asffile.h>
#include <asftag.h>
#include <asfattribute.h>
#include <asfpicture.h>
#include <asfproperties.h>
#include <tbytevectorstream.h>
#include <tpropertymap.h>
#include <tvariant.h>
#include <fileref.h>
using namespace TagLib;
namespace A = TagLib::ASF;
String str(const std::string &s) { return String(s, String::UTF8); }

std::string hex(const ByteVector &b) {
  auto h = b.toHex();
  return std::string(h.data(), h.size());
}

std::string hex(const String &s) { return hex(s.data(String::UTF16BE)); }

std::string list(const StringList &l) {
  std::string s = std::to_string(l.size()) + ":";
  for (auto &v: l)s += hex(v) + ",";
  return s;
}

std::string props(const PropertyMap &p) {
  std::string s;
  for (auto &[k,v]: p)s += hex(k) + "=" + list(v) + ";";
  return s + "/" + list(p.unsupportedData());
}

ByteVector bytes(const std::filesystem::path &p) {
  std::ifstream f(p, std::ios::binary);
  std::string b((std::istreambuf_iterator<char>(f)), {});
  return ByteVector(b.data(), b.size());
}

std::string picture(const A::Picture &p) {
  return std::to_string(p.isValid()) + ":" + std::to_string(p.type()) + ":" + hex(p.mimeType()) + ":" +
         hex(p.description()) + ":" + hex(p.picture()) + ":" + std::to_string(p.dataSize()) + ":" + hex(p.render());
}

std::string attr(const A::Attribute &a) {
  return std::to_string(a.type()) + ":" + std::to_string(a.stream()) + ":" + std::to_string(a.language()) + ":" +
         std::to_string(a.toBool()) + ":" + std::to_string(a.toUShort()) + ":" + std::to_string(a.toUInt()) + ":" +
         std::to_string(a.toULongLong()) + ":" + hex(a.toString()) + ":" + hex(a.toByteVector()) + ":" +
         std::to_string(a.dataSize()) + ":" + picture(a.toPicture());
}

void tag(const std::string &id, const A::Tag &t) {
  std::cout << id << "/common\t" << hex(t.title()) << ":" << hex(t.artist()) << ":" << hex(t.album()) << ":" <<
      hex(t.comment()) << ":" << hex(t.genre()) << ":" << t.year() << ":" << t.track() << ":" << hex(t.rating()) << ":"
      << hex(t.copyright()) << ":" << t.isEmpty() << '\t' << props(t.properties()) << '\n';
  std::string map;
  for (auto &[k,v]: t.attributeListMap()) {
    map += hex(k) + "=";
    for (auto &a: v)map += attr(a) + ";";
    map += "/";
  }
  std::cout << id << "/map\t" << map << '\n';
  std::cout << id << "/complex\t" << list(t.complexPropertyKeys()) << '\t';
  for (auto &p: t.complexProperties("picture")) {
    for (auto &[k,v]: p)
      std::cout << hex(k) << "=" << (
        k == "data" ? hex(v.value<ByteVector>()) : hex(v.value<String>())) << ";";
    std::cout << "/";
  }
  std::cout << '\n';
}

void state(const std::string &id, A::File &f) {
  if (!f.isValid()) {
    std::cout << id << "/rejected\t1" << '\n';
    return;
  }
  std::cout << id << "/file\t" << f.isOpen() << ":" << f.isValid() << ":" << f.tell() << ":" << (f.tag() != nullptr) <<
      ":" << (f.audioProperties() != nullptr) << '\n';
  if (f.tag())tag(id, *f.tag());
  if (auto *p = f.audioProperties())
    std::cout << id << "/audio\t" << p->lengthInMilliseconds() << ":" << p->bitrate() <<
        ":" << p->sampleRate() << ":" << p->channels() << ":" << p->bitsPerSample() << ":"
        << p->codec() << ":" << hex(p->codecName()) << ":" << hex(p->codecDescription()) <<
        ":" << p->isEncrypted() << '\n';
}

struct Named : ByteVectorStream {
  std::string path;
  bool ro = false;

  explicit Named(ByteVector b, std::string n = "input.wma") : ByteVectorStream(b), path(n) {
  }

  FileName name() const override { return path.c_str(); }
  bool readOnly() const override { return ro; }
};

void edit(A::Tag &t, int mode, const std::string &id) {
  if (mode == 0)return;
  if (mode == 1) {
    t.setTitle(str("Title é音"));
    t.setArtist("Artist");
    t.setAlbum("Album");
    t.setComment("Comment");
    t.setGenre("Jazz");
    t.setYear(0xffffffff);
    t.setTrack(0x80000000);
    t.setRating("rating");
    t.setCopyright("copyright");
  }
  if (mode == 2) {
    t.setAttribute("Unknown", A::Attribute(ByteVector("opaque")));
    t.addAttribute("Unknown", A::Attribute(true));
    t.setAttribute("WM/TrackNumber", static_cast<unsigned int>(123));
    t.setAttribute("WM/AlbumTitle", A::AttributeList({A::Attribute(String("one")), A::Attribute(String("two"))}));
  }
  if (mode == 3) {
    PropertyMap p;
    p.insert("TITLE", {"one", "two"});
    p.insert("DATE", {"-1", "7"});
    p.insert("ISVBR", {"TRUE", "true", "False", "0", "1", ""});
    p.insert("ENCODINGTIME", {"18446744073709551615", "-1", "abc"});
    p.insert("MEDIACLASSPRIMARYID", {"abc", "01234567-89ab-cdef-0123-456789ab-cdef"});
    p.insert("UNKNOWN", {"rejected"});
    std::cout << id << "/rejected/3\t" << props(t.setProperties(p)) << '\n';
  }
  if (mode == 4) {
    A::Attribute a(String("scoped"));
    a.setStream(-1);
    a.setLanguage(-2);
    t.setAttribute("WM/AlbumTitle", a);
    a.setStream(43);
    t.addAttribute("WM/AlbumTitle", a);
    a.setLanguage(0);
    t.addAttribute("WM/AlbumTitle", a);
  }
  if (mode == 5) {
    A::Picture p;
    p.setType(A::Picture::FrontCover);
    p.setMimeType("image/test");
    p.setDescription("desc");
    p.setPicture("picture");
    t.setAttribute("WM/Picture", A::Attribute(p));
    A::Picture q;
    q.setType(A::Picture::BackCover);
    q.setMimeType(p.mimeType());
    q.setPicture(p.picture());
    q.setDescription("shared");
    t.addAttribute("WM/Picture", A::Attribute(q));
  }
  if (mode == 6) {
    t.attributeListMap().clear();
    t.setTitle("");
    t.setArtist("");
    t.setComment("");
    t.setCopyright("");
    t.setRating("");
  }
  if (mode == 7) {
    t.attributeListMap().value("missing");
    t.setAttribute("empty", A::AttributeList());
    t.removeUnsupportedProperties({"Unknown", "WM/Picture"});
  }
  if (mode == 8) {
    t.setAttribute("REPLAYGAIN_TRACK_GAIN", String("upper"));
    t.setAttribute("replaygain_track_gain", String("lower"));
    PropertyMap p = t.properties();
    p["REPLAYGAIN_TRACK_GAIN"] = {"edited"};
    t.setProperties(p);
  }
  if (mode == 9) { t.setAttribute("WM/Blob", A::Attribute(ByteVector(70000, 'x'))); }
  if (mode == 10) { t.setTitle(String(std::string(128 * 1024, 'x'))); }
  if (mode == 11) { t.setAttribute("WM/Picture", A::Attribute(ByteVector("invalid"))); }
}

void parseAttributes(const std::filesystem::path &root) {
  for (int kind: {-1, 0, 1, 2, 3})
    for (int type = 0; type <= 7; ++type)
      for (int declared: {0, 1, 2, 4, 8, 65536}) {
        auto name = String("WM/Picture").data(String::UTF16LE) + ByteVector(4, 0);
        ByteVector payload = ByteVector::fromUInt(0x80000001, false) + ByteVector("abcdefghi");
        ByteVector wire;
        if (kind == 0)
          wire = ByteVector::fromShort(name.size(), false) + name + ByteVector::fromShort(type, false) +
                 ByteVector::fromShort(declared, false) + payload;
        else
          wire = ByteVector::fromShort(23, false) + ByteVector::fromShort(43, false) +
                 ByteVector::fromShort(name.size(), false) + ByteVector::fromShort(type, false) +
                 ByteVector::fromUInt(declared, false) + name + payload;
        for (unsigned int cut = 0; cut <= wire.size(); ++cut) {
          Named stream(bytes(root / "inputs/plain.wma"));
          A::File file(&stream, false);
          *stream.data() = wire.mid(0, cut);
          file.seek(0);
          A::Picture picture;
          picture.setPicture("old payload");
          A::Attribute value(picture);
          value.setStream(-7);
          value.setLanguage(-9);
          auto parsed = value.parse(file, kind);
          std::cout << "attribute-parse/" << kind << "/" << type << "/" << declared << "/" << cut << '\t' << hex(parsed)
              << '\t' << file.tell() << '\t' << attr(value) << '\n';
        }
      }
  struct ReadFailure : Named {
    bool armed = false;
    int call = 0, fail = 0;
    std::string trace;

    ReadFailure(ByteVector data) : Named(data) {
    }

    ByteVector readBlock(size_t n) override {
      if (armed) {
        trace += std::to_string(n) + ":" + std::to_string(tell()) + ";";
        if (call++ == fail)throw std::runtime_error("read failure");
      }
      return Named::readBlock(n);
    }
  };
  for (int fail = 0; fail < 8; ++fail) {
    ReadFailure stream(bytes(root / "inputs/plain.wma"));
    A::File file(&stream, false);
    ByteVector name = String("WM/Picture").data(String::UTF16LE) + ByteVector(4, 0), payload =
        String("text").data(String::UTF16LE) + ByteVector(4, 0);
    *stream.data() = ByteVector::fromShort(23, false) + ByteVector::fromShort(43, false) +
                     ByteVector::fromShort(name.size(), false) + ByteVector::fromShort(0, false) + ByteVector::fromUInt(
                       payload.size(), false) + name + payload;
    file.seek(0);
    A::Picture picture;
    picture.setPicture("old");
    A::Attribute value(picture);
    value.setStream(-7);
    value.setLanguage(-9);
    stream.armed = true;
    stream.fail = fail;
    bool failed = false;
    String result;
    try { result = value.parse(file, 2); } catch (const std::runtime_error &) { failed = true; }
    std::cout << "attribute-read-failure/" << fail << '\t' << failed << '\t' << hex(result) << '\t' << file.tell() <<
        '\t' << stream.trace << '\t' << attr(value) << '\n';
  }
  {
    Named stream(bytes(root / "inputs/plain.wma"));
    A::File file(&stream, false);
    A::Attribute value;
    for (int type: {0, 3, 1, 7, 6, 4, 5, 2}) {
      ByteVector name = String("name").data(String::UTF16LE) + ByteVector(4, 0), payload =
          String("old").data(String::UTF16LE) + ByteVector(4, 0);
      *stream.data() = ByteVector::fromShort(name.size(), false) + name + ByteVector::fromShort(type, false) +
                       ByteVector::fromShort(payload.size(), false) + payload;
      file.seek(0);
      std::cout << "attribute-reparse/" << type << '\t' << hex(value.parse(file)) << '\t' << file.tell() << '\t' <<
          attr(value) << '\n';
    }
  }
}

int main(int argc, char **argv) {
  std::filesystem::path root(argv[1]);
  parseAttributes(root);
  std::cout << "value/default\t" << attr(A::Attribute()) << '\n';
  for (unsigned long long n: {
         0ull, 1ull, 65535ull, 65536ull, 2147483648ull, 4294967295ull, 4294967296ull, 0x8000000000000000ull,
         0xffffffffffffffffull
       }) {
    std::cout << "value/" << n << "\t" << attr(A::Attribute(static_cast<unsigned short>(n))) << '\t' <<
        attr(A::Attribute(static_cast<unsigned int>(n))) << '\t' << attr(A::Attribute(n)) << '\t' << attr(
          A::Attribute(n != 0)) << '\n';
  }
  for (int n = 0; n <= 34; ++n) {
    ByteVector b(n, 'x');
    std::cout << "bytes/" << n << "\t" << attr(A::Attribute(b)) << '\t' << attr(A::Attribute::fromGuid(b)) << '\n';
  }
  for (auto &s: std::vector<std::string>{"", "123", "é音", std::string("a\0b", 3)})
    std::cout << "text/" << hex(str(s)) << "\t" << attr(A::Attribute(str(s))) << '\n';
  {
    A::Tag t;
    tag("tag/default", t);
    t.attributeListMap().value("missing");
    tag("tag/missing", t);
    for (int m = 1; m < 12; ++m) {
      edit(t, m, "tag");
      tag("tag/edit/" + std::to_string(m), t);
    }
  }

  for (unsigned int n = 0; n < 65536; ++n)
    std::cout << "word/" << n << '\t' << attr(
      A::Attribute(static_cast<unsigned short>(n))) << '\n';
  for (unsigned int n = 0; n < 65536; ++n)
    std::cout << "type/" << n << '\t' << A::Picture::typeFromUInt(n) << '\t' <<
        hex(A::Picture::typeToString(static_cast<A::Picture::Type>(n))) << '\n';
  for (int n: {32766, 32767, 32768, 65535}) {
    Named s(bytes(root / "inputs/plain.wma"));
    A::File f(&s, false);
    f.tag()->setAttribute(String(std::string(n, 'x')), A::Attribute(String("value")));
    auto saved = f.save();
    std::cout << "long-name/" << n << "/save/0\t" << saved << '\t' << s.tell() << '\t' << hex(*s.data()) << '\n';
  }
  for (int n: {65535, 65536}) {
    Named s(bytes(root / "inputs/plain.wma"));
    A::File f(&s, false);
    A::AttributeList list;
    for (int i = 0; i < n; ++i)list.append(A::Attribute(String("")));
    f.tag()->setAttribute("many", list);
    auto saved = f.save();
    std::cout << "count/" << n << "/save/0\t" << saved << '\t' << s.tell() << '\t' << hex(*s.data()) << '\n';
  }
  for (int n: {-1, 0, 1, 20, 21, 255, 256, INT32_MIN, INT32_MAX}) {
    A::Picture p;
    p.setType(static_cast<A::Picture::Type>(n));
    std::cout << "picture/enum/" << n << "\t" << picture(p) << '\t' << hex(A::Picture::typeToString(p.type())) << '\t'
        << A::Picture::typeFromUInt(static_cast<unsigned int>(n)) << '\t' << A::Picture::typeFromByte(
          static_cast<char>(n)) << '\n';
  }
  std::vector<std::filesystem::path> inputs;
  for (auto &p: std::filesystem::directory_iterator(root / "inputs"))inputs.push_back(p.path());
  std::sort(inputs.begin(), inputs.end());
  std::filesystem::create_directories(root / "outputs");
  for (auto &input: inputs) {
    std::string id = input.filename().string();
    auto data = bytes(input);
    for (int origin: {0, 7})
      for (int style = 0; style < 3; ++style)
        for (int enabled: {0, 1}) {
          Named s(data);
          s.seek(origin);
          A::File f(&s, enabled, static_cast<AudioProperties::ReadStyle>(style));
          state(id + "/read/" + std::to_string(origin) + "/" + std::to_string(style) + "/" + std::to_string(enabled),
                f);
        }
    for (int origin: {0, 7}) {
      Named s(data);
      s.seek(origin);
      std::cout << id << "/support/" << origin << '\t' << A::File::isSupported(&s) << '\t' << s.tell() << '\n';
      Named named(data);
      named.seek(origin);
      FileRef ref(&named, false);
      std::cout << id << "/ref/" << origin << '\t' << ref.isNull() << ":" << (
        dynamic_cast<A::File *>(ref.file()) != nullptr) << ":" << named.tell() << '\n';
    }
    if (id == "plain.wma" || id == "duplicates.wma" || id == "carrier-0.wma" || id == "upstream-silence-1-wma.wma" || id
        == "case-names.wma")
      for (int m = 0; m < 12; ++m) {
        Named s(data);
        A::File f(&s, false);
        edit(*f.tag(), m, id);
        auto base = id + "/edit/" + std::to_string(m);
        auto *t = f.tag();
        auto *p = f.audioProperties();
        state(base + "/staged", f);
        for (int n = 0; n < 3; ++n) {
          bool saved = f.save();
          std::cout << base << "/save/" << n << '\t' << saved << '\t' << s.tell() << '\t' << (t == f.tag()) << ":" << (
            p == f.audioProperties()) << '\t' << hex(*s.data()) << '\n';
          state(base + "/after/" + std::to_string(n), f);
          if (n == 0 && m == 10)t->setTitle("x");
        }
        auto output = root / "outputs" / (id + "." + std::to_string(m) + ".native");
        std::ofstream out(output, std::ios::binary);
        out.write(s.data()->data(), s.data()->size());
      }
    if (id == "plain.wma" || id == "duplicates.wma" || id == "carrier-0.wma" || id == "upstream-silence-1-wma.wma" || id
        == "case-names.wma")
      for (int m = 0; m < 12; ++m) {
        auto output = root / "outputs" / (id + ".path." + std::to_string(m) + ".native");
        std::filesystem::copy_file(input, output, std::filesystem::copy_options::overwrite_existing);
        A::File f(output.c_str(), false);
        auto base = id + "/path/" + std::to_string(m);
        edit(*f.tag(), m, base);
        auto *t = f.tag();
        auto *p = f.audioProperties();
        state(base + "/staged", f);
        for (int n = 0; n < 3; ++n) {
          auto saved = f.save();
          f.seek(f.tell());
          std::cout << base << "/save/" << n << '\t' << saved << '\t' << f.tell() << '\t' << (t == f.tag()) << ":" << (
            p == f.audioProperties()) << '\t' << hex(bytes(output)) << '\n';
          state(base + "/after/" + std::to_string(n), f);
          if (n == 0 && m == 10)t->setTitle("x");
        }
      }
  }

  {
    A::Tag t;
    for (int n = 0; n < 5; ++n) {
      VariantMap m;
      if (n == 1) {
        m.insert("data", ByteVector("data"));
        m.insert("mimeType", String("image/test"));
        m.insert("description", String("desc"));
        m.insert("pictureType", String("Front Cover"));
      }
      if (n == 2) {
        m.insert("data", 1);
        m.insert("mimeType", true);
        m.insert("description", ByteVector("wrong"));
        m.insert("pictureType", 21);
      }
      List<VariantMap> values;
      if (n != 4)values.append(m);
      std::cout << "complex/set/" << n << '\t' << t.setComplexProperties(n == 3 ? "unknown" : "pIcTuRe", values) <<
          '\n';
      tag("complex/state/" + std::to_string(n), t);
    }
  }
  struct Virtual : A::Tag {
    String title() const override { return "virtual"; }
    String rating() const override { return "virtual rating"; }
  };
  {
    Virtual t;
    t.setTitle("stored");
    t.setRating("stored rating");
    tag("virtual/stored", t);
    PropertyMap p;
    p.insert("TITLE", {"one", "two"});
    p.insert("RATING", {"ignored"});
    std::cout << "virtual/rejected\t" << props(t.setProperties(p)) << '\n';
    tag("virtual/after", t);
  }
  struct Trace : Named {
    std::string trace;
    A::File *file = nullptr;
    bool callback = false;

    Trace(ByteVector b) : Named(b) {
    }

    void writeBlock(const ByteVector &b) override {
      trace += "write:" + std::to_string(tell()) + ":" + std::to_string(b.size()) + ";";
      Named::writeBlock(b);
      if (callback)file->tag()->setTitle("callback");
    }

    void insert(const ByteVector &b, offset_t start, size_t replace) override {
      trace += "insert:" + std::to_string(start) + ":" + std::to_string(replace) + ":" + std::to_string(b.size()) + ";";
      Named::insert(b, start, replace);
    }

    void truncate(offset_t n) override {
      trace += "truncate:" + std::to_string(n) + ";";
      Named::truncate(n);
    }

    void removeBlock(offset_t start, size_t n) override {
      trace += "remove:" + std::to_string(start) + ":" + std::to_string(n) + ";";
      Named::removeBlock(start, n);
    }
  };
  for (int m = 0; m < 2; ++m) {
    Trace s(bytes(root / "inputs/plain.wma"));
    A::File f(&s, false);
    s.file = &f;
    s.callback = m == 1;
    f.tag()->setTitle("title");
    for (int n = 0; n < 3; ++n) {
      s.trace.clear();
      bool saved = f.save();
      std::cout << "io/" << m << "/save/" << n << '\t' << saved << '\t' << s.trace << '\t' << hex(*s.data()) << '\n';
      state("io/" + std::to_string(m) + "/after/" + std::to_string(n), f);
      if (n == 0)f.tag()->setTitle(String(std::string(5000, 'x')));
      if (n == 1)f.tag()->setTitle("short");
    }
  }
  {
    Named s(bytes(root / "inputs/plain.wma"));
    A::File f(&s);
    s.ro = true;
    std::cout << "readonly\t" << f.save() << '\t' << f.tell() << '\t' << hex(*s.data()) << '\n';
  }
  struct Fail : Named {
    Fail(ByteVector b) : Named(b) {
    }

    void writeBlock(const ByteVector &) override { throw std::runtime_error("write failure"); }
  };
  {
    Fail s(bytes(root / "inputs/plain.wma"));
    A::File f(&s);
    bool failed = false;
    try { f.save(); } catch (const std::runtime_error &) { failed = true; }
    std::cout << "io/failure\t" << failed << '\t' << f.tell() << '\t' << hex(*s.data()) << '\n';
  }
}
