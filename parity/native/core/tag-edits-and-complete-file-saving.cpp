// Isolated exact-fixture writer for reviewing changed regression expectations.
#include <fileref.h>
#include <tpropertymap.h>
#include <tvariant.h>
#include <matroskafile.h>
#include <matroskaattachments.h>
#include <matroskaattachedfile.h>
#include <iostream>
#include <stdexcept>
using namespace TagLib;

int main(int argc, char **argv) {
  if (argc < 3)throw std::runtime_error("regression-contract-probe title|tta-artwork|attachments PATH [TITLE RETAIN]");
  std::string command = argv[1];
  if (command == "attachments") {
    Matroska::File file(argv[2], false);
    if (!file.isValid())return 2;
    auto *list = file.attachments();
    if (!list)return 3;
    list->clear();
    list->addAttachedFile(Matroska::AttachedFile(ByteVector("\211PNG", 4), "second.bin", "application/bin",
                                                 0xffffffffffffffffULL));
    list->addAttachedFile(Matroska::AttachedFile(ByteVector("\0\377\007", 3), String("font-č.ttf", String::UTF8),
                                                 "font/ttf", argc > 3 ? std::stoull(argv[3]) : 1,
                                                 String("Písmo", String::UTF8)));
    std::cout << "saved=" << file.save() << '\n';
    return 0;
  }
  FileRef file(argv[2]);
  if (file.isNull())return 2;
  PropertyMap props;
  if (command == "title") {
    if (argc != 5)return 3;
    if (std::string(argv[4]) == "retain")props = file.properties();
    props["TITLE"] = StringList(String(argv[3], String::UTF8));
  } else if (command == "tta-artwork") {
    props["TITLE"] = StringList("Lossless");
    file.setProperties(props);
    VariantMap picture;
    picture["data"] = ByteVector("\010\011\012", 3);
    picture["mimeType"] = "image/png";
    picture["description"] = "cover";
    picture["pictureType"] = "Front Cover";
    file.setComplexProperties("PICTURE", List<VariantMap>{picture});
  } else return 3;
  if (command == "title")file.setProperties(props);
  std::cout << "saved=" << file.save() << '\n';
  return 0;
}
