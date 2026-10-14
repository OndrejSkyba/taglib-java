// Public generic complex-property contracts; pinned TagLib native reference.
#include <fstream>
#include <iostream>
#include <sstream>
#include <iomanip>
#include "fileref.h"
#include "tpropertymap.h"
#include "tvariant.h"
using namespace TagLib;

static std::string hex(const ByteVector &bytes) {
  std::ostringstream out;
  for (unsigned char b: bytes)out << std::hex << std::setw(2) << std::setfill('0') << unsigned(b);
  return out.str();
}

static std::string hex(const String &s) { return hex(s.data(String::UTF8)); }

static std::string variant(const Variant &v) {
  std::ostringstream out;
  out << int(v.type()) << ':';
  switch (v.type()) {
    case Variant::Bool: out << v.toBool();
      break;
    case Variant::Int: out << v.toInt();
      break;
    case Variant::UInt: out << v.toUInt();
      break;
    case Variant::LongLong: out << v.toLongLong();
      break;
    case Variant::ULongLong: out << v.toULongLong();
      break;
    case Variant::String: out << hex(v.toString());
      break;
    case Variant::ByteVector: out << hex(v.toByteVector());
      break;
    case Variant::VariantList: out << '[';
      for (const auto &x: v.toList())out << variant(x) << ',';
      out << ']';
      break;
    case Variant::VariantMap: out << '{';
      for (const auto &[k,x]: v.toMap())out << hex(k) << '=' << variant(x) << ',';
      out << '}';
      break;
    default: out << "unsupported";
  }
  return out.str();
}

static void dump(FileRef &f) {
  std::cout << "null=" << f.isNull() << '\n';
  if (f.isNull())return;
  for (const auto &key: f.complexPropertyKeys()) {
    std::cout << "KEY\t" << hex(key) << '\n';
    for (const auto &map: f.complexProperties(key))std::cout << "VALUE\t" << variant(Variant(map)) << '\n';
  }
  for (const auto &[key,values]: f.properties()) {
    std::cout << "P\t" << hex(key);
    for (const auto &v: values)std::cout << '\t' << hex(v);
    std::cout << '\n';
  }
  const auto properties = f.properties();
  for (const auto &v: properties.unsupportedData())std::cout << "U\t" << hex(v) << '\n';
}

int main(int argc, char **argv) {
  if (argc != 3)return 2;
  std::string scenario = argv[2];
  String key;
  List<VariantMap> values;
  VariantMap v;
  ByteVector data("\0\1\2\3", 4);
  if (scenario == "general" || scenario == "general-clear") {
    key = "GENERALOBJECT";
    if (scenario == "general") {
      v.insert("data", data);
      v.insert("uid", 77ULL);
      v.insert("mimeType", "application/octet-stream");
      v.insert("description", String("Object Ž", String::UTF8));
      v.insert("fileName", "obj.bin");
      values.append(v);
    }
  } else if (scenario == "scoped" || scenario == "scoped-clear") {
    key = "BINARY";
    if (scenario == "scoped") {
      v.insert("name", "BINARY");
      v.insert("data", data);
      v.insert("targetTypeValue", 30U);
      v.insert("trackUid", 42ULL);
      v.insert("language", "jpn");
      v.insert("defaultLanguage", false);
      values.append(v);
    }
  } else if (scenario == "chapters") {
    key = "CHAPTERS";
    VariantMap disp;
    disp.insert("string", String("Chapter Ž", String::UTF8));
    disp.insert("language", "ces");
    List<Variant> displays;
    displays.append(Variant(disp));
    VariantMap ch;
    ch.insert("uid", 88ULL);
    ch.insert("timeStart", 123000ULL);
    ch.insert("timeEnd", 999000ULL);
    ch.insert("displays", displays);
    List<Variant> chapters;
    chapters.append(Variant(ch));
    v.insert("uid", 99ULL);
    v.insert("isDefault", true);
    v.insert("isOrdered", false);
    v.insert("chapters", chapters);
    values.append(v);
  } else if (scenario == "mime" || scenario == "filename" || scenario == "uid") {
    key = scenario == "mime" ? "application/pdf" : scenario == "filename" ? "manual.bin" : "77";
    v.insert("data", data);
    v.insert("description", "document");
    if (scenario != "mime")v.insert("mimeType", "application/octet-stream");
    if (scenario != "filename")v.insert("fileName", "doc.bin");
    if (scenario != "uid")v.insert("uid", 77ULL);
    values.append(v);
  } else if (scenario == "stem" || scenario == "stem-clear") {
    key = "STEM";
    if (scenario == "stem") {
      v.insert("manifest", ByteVector("{\"version\":1}", 13));
      values.append(v);
    }
  } else if (scenario == "unknown") {
    key = "UNKNOWN";
    values.append(v);
  } else return 2;
  {
    FileRef f(argv[1], false);
    dump(f);
    if (f.isNull())return 0;
    std::cout << "accepted=" << f.setComplexProperties(key, values) << '\n';
    dump(f);
    for (int i = 0; i < 2; i++) {
      std::cout << "saved=" << f.save() << '\n';
      dump(f);
    }
  }
  std::cout << "reopened\n";
  FileRef f(argv[1], false);
  dump(f);
}
