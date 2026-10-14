// TagLib RIFF Info::Tag public operation differential probe.
#include <iostream>
#include <iomanip>
#include <sstream>
#include <infotag.h>
#include <tpropertymap.h>
using namespace TagLib;

static std::string hex(const ByteVector &v) {
  std::ostringstream o;
  for (unsigned char c: v)o << std::hex << std::setfill('0') << std::setw(2) << static_cast<unsigned int>(c);
  return o.str();
}

static std::string text(const String &s) { return hex(s.data(String::UTF8)); }

static void state(const char *name, const RIFF::Info::Tag &tag) {
  std::cout << name << '\t' << tag.isEmpty() << '\t' << tag.year() << '\t' << tag.track() << '\t';
  for (const auto &[id,val]: tag.fieldListMap())std::cout << hex(id) << '=' << text(val) << ';';
  std::cout << '\t';
  auto props = tag.properties();
  for (const auto &[key,values]: props) {
    std::cout << text(key) << '=';
    for (const auto &v: values)std::cout << text(v) << ',';
    std::cout << ';';
  }
  std::cout << '\t';
  for (const auto &id: props.unsupportedData())std::cout << text(id) << ',';
  std::cout << '\t' << hex(tag.render()) << '\n';
}

static ByteVector field(const ByteVector &id, const ByteVector &value) {
  auto r = id + ByteVector::fromUInt(value.size(), false) + value;
  if (r.size() & 1)r.append('\0');
  return r;
}

class Latin1 : public RIFF::Info::StringHandler {
public:
  String parse(const ByteVector &d) const override { return String(d, String::Latin1); }
  ByteVector render(const String &s) const override { return s.data(String::Latin1); }
};

int main() {
  RIFF::Info::Tag t;
  state("empty", t);
  t.setTitle("Title");
  t.setArtist("Artist");
  t.setAlbum("Album");
  t.setComment("Comment");
  t.setGenre("Genre");
  t.setYear(2026);
  t.setTrack(7);
  state("common", t);
  t.setFieldText("ZZZZ", "opaque");
  t.setFieldText("YYYY", "other");
  state("unknown", t);
  t.removeUnsupportedProperties(t.properties().unsupportedData());
  state("remove-empty-identifiers", t);
  t.removeUnsupportedProperties(StringList{"ZZZZ"});
  state("remove-raw-id", t);
  PropertyMap p;
  p["TITLE"] = StringList{"", "second"};
  p["ARTIST"] = StringList();
  p["CUSTOM"] = StringList{"x"};
  auto rejected = t.setProperties(p);
  state("replace-properties", t);
  std::cout << "rejected\t";
  for (const auto &[key,values]: rejected) {
    std::cout << text(key) << '=';
    for (const auto &v: values)std::cout << text(v) << ',';
    std::cout << ';';
  }
  std::cout << '\n';
  t.setYear(0xffffffffU);
  t.setTrack(0xffffffffU);
  state("unsigned", t);
  t.setYear(0);
  t.setTrack(0);
  state("number-clear", t);
  auto copy = t.fieldListMap();
  copy.clear();
  state("map-copy", t);
  for (const auto &id: {
         ByteVector("ABC"), ByteVector("ABCDE"), ByteVector("\037ABC"), ByteVector("\200ABC")
       })
    t.setFieldText(id, "bad");
  state("invalid-ids", t);
  t.removeField("YYYY");
  t.setTitle("");
  state("clear-all", t);
  ByteVector input("INFO");
  input.append(field("INAM", ByteVector("a\0", 2)));
  input.append(field("INAM", ByteVector("b\0", 2)));
  input.append(field("IART", ByteVector("\0", 1)));
  input.append(field("ZZZZ", ByteVector("\303\0", 2)));
  RIFF::Info::Tag parsed(input);
  state("parse-duplicate-empty-invalid-utf8", parsed);
  Latin1 latin;
  RIFF::Info::Tag::setStringHandler(&latin);
  RIFF::Info::Tag local(ByteVector("INFO") + field("INAM", ByteVector("\351\0", 2)));
  state("latin1", local);
  RIFF::Info::Tag::setStringHandler(nullptr);
  state("restored-utf8", local);
  ByteVector broken("INFO");
  broken.append(field("INAM", ByteVector("x\0", 2)));
  broken.append(ByteVector("ZZZZ"));
  broken.append(ByteVector::fromUInt(0xffffffffU, false));
  RIFF::Info::Tag truncated(broken);
  state("oversized-field", truncated);
  for (const auto &id: {
         "IPRD", "IENG", "IART", "IBSU", "IBPM", "ICMT", "IMUS", "ICOP", "ICRD", "PRT1", "ITCH", "ISFT", "IDIT", "IGNR",
         "ISRC", "IPUB", "ILNG", "IWRI", "IMED", "ISTR", "ICNT", "IEDT", "INAM", "IPRT"
       }) {
    RIFF::Info::Tag each;
    each.setFieldText(id, "value");
    state(id, each);
  }
}
