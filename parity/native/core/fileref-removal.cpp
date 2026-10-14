#include <fileref.h>
#include <tpropertymap.h>
#include <tvariant.h>
#include <tag.h>
#include <wavpack/wavpackfile.h>
#include <ape/apetag.h>
#include <asf/asffile.h>
#include <asf/asftag.h>
#include <iostream>
using namespace TagLib;
static std::string hex(const String &s) { return String(s.data(String::UTF8).toHex()).to8Bit(true); }

static void dump(const char *label, FileRef &file) {
  const auto properties = file.properties();
  std::cout << label << "|" << file.isNull() << "|";
  for (const auto &[key, values]: properties) {
    std::cout << hex(key) << "=";
    for (const auto &value: values) std::cout << hex(value) << ",";
    std::cout << ";";
  }
  std::cout << "/";
  for (const auto &value: properties.unsupportedData()) std::cout << hex(value) << ",";
  std::cout << "/";
  for (const auto &value: file.complexPropertyKeys()) std::cout << hex(value) << ",";
  std::cout << "\n";
}

int main(int argc, char **argv) {
  if (argc < 2) return 1;
  if (argc > 2 && std::string(argv[2]) == "seed-ape") {
    WavPack::File file(argv[1]);
    file.APETag(true)->setData("Opaque", ByteVector("opaque", 6));
    file.APETag(true)->setData("Keep", ByteVector("retained", 8));
    std::cout << "seeded|" << file.save() << "\n";
    return 0;
  }
  if (argc > 2 && std::string(argv[2]) == "seed-asf") {
    ASF::File file(argv[1]);
    file.tag()->addAttribute("Opaque", ASF::Attribute(ByteVector("opaque", 6)));
    file.tag()->addAttribute("Keep", ASF::Attribute(ByteVector("retained", 8)));
    std::cout << "seeded|" << file.save() << "\n";
    return 0;
  }
  FileRef file(argv[1]);
  dump("before", file);
  StringList removals = argc > 2
                          ? StringList::split(String(argv[2], String::UTF8), ",")
                          : file.properties().unsupportedData();
  file.removeUnsupportedProperties(removals);
  dump("staged", file);
  std::cout << "saved|" << file.save() << "|" << file.save() << "\n";
  FileRef readback(argv[1]);
  dump("after", readback);
}
