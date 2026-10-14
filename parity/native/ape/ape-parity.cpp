// Development-only comparison with pinned TagLib APE APIs.
#include <iostream>
#include <sstream>
#include <vector>
#include "apeitem.h"
#include "apefooter.h"
#include "apetag.h"
#include "tpropertymap.h"
#include "tvariant.h"
#include "tfile.h"
#include "tbytevectorstream.h"
using namespace TagLib;

std::string hex(const ByteVector &v) {
  auto h = v.toHex();
  return {h.data(), h.size()};
}

std::string hex(const String &s) { return hex(s.data(String::UTF8)); }

std::string list(const StringList &v) {
  std::string out = std::to_string(v.size()) + ":";
  for (auto &s: v)out += hex(s) + ",";
  return out;
}

void item(const std::string &name, APE::Item &v) {
  auto before = v.binaryData();
  auto bytes = v.render();
  std::cout << name << "|" << hex(v.key()) << "|" << v.type() << "|" << v.isReadOnly() << "|" << v.size() << "|" << v.
      isEmpty() << "|" << hex(v.toString()) << "|" << list(v.values()) << "|" << hex(before) << "|" << hex(bytes) << "|"
      << hex(v.binaryData()) << "\n";
}

std::string properties(const PropertyMap &p) {
  std::string out;
  for (auto &[k,v]: p)out += hex(k) + "=" + list(v) + ";";
  return out + "/" + list(p.unsupportedData());
}

void tag(const std::string &name, APE::Tag &v) {
  std::cout << name << "|" << v.isEmpty() << "|" << hex(v.title()) << "|" << hex(v.artist()) << "|" << v.year() << "|"
      << v.track() << "|" << properties(v.properties()) << "|";
  for (auto &[k,i]: v.itemListMap())
    std::cout << hex(k) << ":" << hex(i.key()) << ":" << i.type() << ":" << i.
        isReadOnly() << ";";
  std::cout << "|" << list(v.complexPropertyKeys()) << "|";
  for (auto &p: v.complexProperties("picture")) {
    std::cout << hex(p.value("pictureType").toString()) << ":" << hex(p.value("description").toString()) << ":" << hex(
      p.value("data").toByteVector()) << ";";
  }
  std::cout << "|" << hex(v.render()) << "\n";
}

class ParsedTag : public APE::Tag {
public:
  void load(const ByteVector &b, unsigned int n) {
    footer()->setItemCount(n);
    parse(b);
  }
};

ByteVector payload(const char *key, const ByteVector &value, unsigned int flags) {
  return ByteVector::fromUInt(value.size(), false) + ByteVector::fromUInt(flags, false) + ByteVector(key) +
         ByteVector('\0') + value;
}

class InputFile : public File {
public:
  explicit InputFile(IOStream *s) : File(s) {
  }

  Tag *tag() const override { return nullptr; }
  AudioProperties *audioProperties() const override { return nullptr; }
  bool save() override { return false; }
};

void core(const char *name, const ByteVector &body, unsigned int count) {
  APE::Footer footer;
  footer.setItemCount(count);
  footer.setTagSize(body.size() + 32);
  footer.setHeaderPresent(true);
  ByteVectorStream stream(footer.renderHeader() + body + footer.renderFooter());
  InputFile file(&stream);
  APE::Tag t(&file, file.length() - 32);
  std::cout << name << "|" << properties(t.properties()) << "|" << hex(t.title()) << "|" << t.year() << "|" << t.track()
      << "|";
  for (auto &p: t.complexProperties("PICTURE"))std::cout << hex(p.value("data").toByteVector()) << ";";
  auto p = t.properties();
  t.setProperties(p);
  std::cout << "|" << hex(t.isEmpty() ? ByteVector() : t.render()) << "\n";
}

int main() {
  APE::Tag live;
  const auto &view = live.itemListMap();
  live.addValue("TITLE", "value");
  std::cout << "map-live|" << view.size() << "|" << hex(view.value("TITLE").toString());
  live.removeItem("TITLE");
  std::cout << "|" << view.size() << "\n";
  for (const auto &s: std::vector<std::wstring>{
         std::wstring(2, L'\xd800'), std::wstring(1, L'\xd800'), L"éé", L"ID3", L"A=B"
       })
    std::cout << "check-key|" << APE::Tag::checkKey(String(s)) << "\n";
  std::vector<StringList> values{
    {}, {""}, {"a"}, {"", ""}, {"", "a", ""}, {"a", "b"}, {String("é", String::UTF8), String("音", String::UTF8)}
  };
  int n = 0;
  for (auto &v: values)
    for (int t = 0; t < 4; t++)
      for (int ro = 0; ro < 2; ro++) {
        APE::Item i("MiXeD", v);
        i.setType(static_cast<APE::Item::ItemTypes>(t));
        i.setReadOnly(ro);
        item("item-" + std::to_string(n++), i);
      }
  APE::Item i("Title", ByteVector("abc"), true);
  item("binary", i);
  i.setType(APE::Item::Locator);
  item("locator", i);
  i.setType(APE::Item::Text);
  item("type-text", i);
  i.appendValue("new");
  item("append", i);
  i.parse(payload("Other", ByteVector("raw"), 2));
  item("reuse-binary", i);
  i.parse(payload("Name", ByteVector("x\0\0", 3), 1));
  item("reuse-text", i);
  i.parse(ByteVector("short"));
  item("short-parse", i);
  APE::Item latin("Text", ByteVector("\xc3\xa9", 2), false);
  item("latin-constructor", latin);
  n = 0;
  for (unsigned int flags: {0u, 0x80000000u, 0x40000000u, 0x20000000u, 0xffffffffu})
    for (unsigned int size: {0u, 32u, 0xfffffff0u, 0xffffffffu}) {
      auto b = ByteVector("NOTMAGIC") + ByteVector::fromUInt(1000, false) + ByteVector::fromUInt(size, false) +
               ByteVector::fromUInt(17, false) + ByteVector::fromUInt(flags, false) + ByteVector(8, 0);
      APE::Footer f(b);
      f.setData(ByteVector("short"));
      std::cout << "footer-" << n++ << "|" << f.version() << "|" << f.headerPresent() << "|" << f.footerPresent() << "|"
          << f.isHeader() << "|" << f.itemCount() << "|" << f.tagSize() << "|" << f.completeTagSize() << "|" <<
          hex(f.renderHeader()) << "|" << hex(f.renderFooter()) << "\n";
    }
  APE::Tag a;
  tag("empty-tag", a);
  a.addValue("Title", "one");
  a.addValue("TITLE", "two", false);
  a.setYear(0xffffffffu);
  a.setTrack(0x80000000u);
  a.setItem("A B", APE::Item("different", StringList{"v"}));
  a.setItem("id3", APE::Item("id3", StringList{"invalid"}));
  a.setData("binary", ByteVector("opaque"));
  tag("mixed-tag", a);
  PropertyMap p;
  p.insert("TITLE", StringList{"", "updated", ""});
  p.insert("DATE", StringList{"2026"});
  p.insert("YEAR", StringList{"2000"});
  p.insert("x", StringList{"rejected"});
  p.insert("ID3", StringList{"invalid"});
  p.insert("EMPTY", StringList{});
  std::cout << "rejected|" << properties(a.setProperties(p)) << "\n";
  tag("properties-tag", a);
  a.removeUnsupportedProperties(StringList{"binary"});
  a.addValue("Title", "", true);
  tag("removed-tag", a);
  List<VariantMap> pictures;
  for (const char *type: {"Other", "Front Cover", "Front Cover", "Back Cover", "Back Cover"}) {
    VariantMap picture;
    picture.insert("pictureType", type);
    picture.insert("description", type);
    picture.insert("data", ByteVector::fromUInt(pictures.size() + 1, false));
    pictures.append(picture);
  }
  a.setComplexProperties("picture", pictures);
  tag("picture-tag", a);
  a.setComplexProperties("PICTURE", {});
  tag("clear-pictures", a);
  ParsedTag parsed;
  parsed.load(
    payload("TITLE", ByteVector("one"), 0) + payload("title", ByteVector("two"), 1) +
    payload("bad", ByteVector("\xc3", 1), 0) + payload("reserved", ByteVector("payload"), 6), 4);
  tag("parsed-tag", parsed);
  ParsedTag truncated;
  auto truncatedBody = payload("TITLE", ByteVector("xy"), 0);
  truncatedBody[0] = 10;
  truncated.load(truncatedBody, 1);
  tag("truncated-value", truncated);
  core("core-numeric",
       payload("TITLE", ByteVector("\xc3", 1), 0) + payload("YEAR", ByteVector("20345tail"), 0) + payload(
         "TRACK", ByteVector("-1/9"), 0), 3);
  core("core-truncated", truncatedBody, 1);
  core("core-reserved",
       payload("EMPTY", ByteVector(), 0) + payload("binary", ByteVector("opaque"), 0xfffffffa) +
       payload("locator", ByteVector("url"), 4) + payload("reserved", ByteVector("r"), 6), 4);
  core("core-empty", payload("TITLE", ByteVector(), 0), 1);
  core("core-roundtrip-unsupported",
       payload("ARTIST", ByteVector("α\0β", 5), 0) + payload("TRACK", ByteVector("2/12"), 0) +
       payload("YEAR", ByteVector("2026"), 0) + payload("Opaque", ByteVector("\0\1\2\3", 4), 2), 4);
  core("core-unsigned-maximum", payload("YEAR", ByteVector("-1"), 0) + payload("TRACK", ByteVector("2147483648"), 0),
       2);
  core("core-unsigned-reversed", payload("YEAR", ByteVector("2147483648"), 0) + payload("TRACK", ByteVector("-1"), 0),
       2);
  return 0;
}
