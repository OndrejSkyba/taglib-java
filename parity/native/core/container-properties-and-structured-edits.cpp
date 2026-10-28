// Focused TagLib container API oracle. Development tool, never linked by the Java library.
#include <iostream>
#include <iomanip>
#include <sstream>
#include <stdexcept>
#include "fileref.h"
#include "tpropertymap.h"
#include "asffile.h"
#include "asftag.h"
#include "asfattribute.h"
#include "asfpicture.h"
#include "mp4file.h"
#include "mp4tag.h"
#include "mp4item.h"
#include "mp4stem.h"
#include "mp4chapter.h"
#include "matroskafile.h"
#include "matroskatag.h"
#include "matroskasimpletag.h"
#include "matroskachapters.h"
#include "matroskachapteredition.h"
#include "matroskachapter.h"

static std::string hex(const TagLib::ByteVector &value) {
  std::ostringstream out;
  for (unsigned char c: value) out << std::hex << std::setw(2) << std::setfill('0') << unsigned(c);
  return out.str();
}

static std::string hex(const TagLib::String &value) { return hex(value.data(TagLib::String::UTF8)); }

int main(int argc, char **argv) {
  try {
    if (argc < 3) throw std::runtime_error("container-probe COMMAND PATH [VALUE]");
    std::string command = argv[1];
    if (command == "asf-set") {
      if (argc != 4) throw std::runtime_error("Missing Boolean input");
      TagLib::ASF::File file(argv[2]);
      if (!file.isValid()) throw std::runtime_error("Invalid ASF fixture");
      TagLib::PropertyMap properties;
      properties["ISVBR"] = {TagLib::String(argv[3], TagLib::String::UTF8)};
      properties["TITLE"] = TagLib::StringList{"One", "Two"};
      properties["MEDIACLASSPRIMARYID"] = {"{01234567-89ab-cdef-0123-456789abcdef}"};
      if (!file.setProperties(properties).isEmpty() || !file.save()) throw std::runtime_error("ASF write failed");
    } else if (command == "asf-scoped") {
      TagLib::ASF::File file(argv[2]);
      if (!file.isValid()) throw std::runtime_error("Invalid ASF fixture");
      file.tag()->attributeListMap().clear();
      file.tag()->addAttribute("Scoped", TagLib::ASF::Attribute(TagLib::String("global")));
      TagLib::ASF::Attribute stream(TagLib::String("stream"));
      stream.setStream(4);
      file.tag()->addAttribute("Scoped", stream);
      TagLib::ASF::Attribute language(TagLib::String("language"));
      language.setStream(4);
      language.setLanguage(9);
      file.tag()->addAttribute("Scoped", language);
      file.tag()->addAttribute("WM/Track", TagLib::ASF::Attribute(42U));
      if (!file.save()) throw std::runtime_error("ASF scoped write failed");
    } else if (command == "asf-scoped-picture") {
      if (argc != 5) throw std::runtime_error("Missing picture scope");
      TagLib::ASF::File file(argv[2], false);
      if (!file.isValid()) throw std::runtime_error("Invalid ASF fixture");
      TagLib::ASF::Picture picture;
      picture.setType(TagLib::ASF::Picture::FrontCover);
      picture.setMimeType("image/jpeg");
      picture.setDescription(TagLib::String("Front é音", TagLib::String::UTF8));
      picture.setPicture("data");
      TagLib::ASF::Attribute original(picture);
      TagLib::ASF::Attribute scoped(original);
      scoped.setStream(std::stoi(argv[3]));
      scoped.setLanguage(std::stoi(argv[4]));
      auto edited = scoped.toPicture();
      edited.setDescription(TagLib::String("Scoped é音", TagLib::String::UTF8));
      edited.setPicture("new image");
      file.tag()->setAttribute("WM/Picture", scoped);
      if (!file.save()) throw std::runtime_error("ASF scoped picture write failed");
    } else if (command == "asf-attributes") {
      TagLib::ASF::File file(argv[2]);
      if (!file.isValid()) throw std::runtime_error("Invalid ASF fixture");
      for (const auto &[name, attributes]: file.tag()->attributeListMap())
        for (const auto &a: attributes) {
          std::cout << hex(name) << '\t' << a.type() << '\t' << a.stream() << '\t' << a.language() << '\t';
          if (a.type() == TagLib::ASF::Attribute::UnicodeType) std::cout << hex(a.toString());
          else if (a.type() >= TagLib::ASF::Attribute::BoolType && a.type() <= TagLib::ASF::Attribute::WordType)
            std::cout << a.toULongLong();
          else std::cout << hex(a.toByteVector());
          std::cout << '\n';
        }
    } else if (command == "mp4-set") {
      TagLib::MP4::File file(argv[2], false);
      if (!file.isValid()) throw std::runtime_error("Invalid MP4 fixture");
      file.setNeroChapters({TagLib::MP4::Chapter("Opening", 0), TagLib::MP4::Chapter("Second", 12001)});
      file.tag()->setItem("stem", TagLib::MP4::Item(TagLib::MP4::Stem(TagLib::ByteVector("{\"stems\":[]}"))));
      if (!file.save()) throw std::runtime_error("MP4 structured write failed");
    } else if (command == "mp4-read") {
      TagLib::MP4::File file(argv[2], false);
      if (!file.isValid()) throw std::runtime_error("Invalid MP4 fixture");
      for (const auto &chapter: file.neroChapters())
        std::cout << chapter.startTime() << '\t' << hex(chapter.title()) << '\n';
      if (file.tag()->contains("stem")) std::cout << "STEM\t" << hex(file.tag()->item("stem").toStem().data()) << '\n';
    } else if (command == "qt-set" || command == "qt-clear") {
      TagLib::MP4::File file(argv[2], false);
      if (!file.isValid()) throw std::runtime_error("Invalid MP4 fixture");
      if (command == "qt-clear") file.setQtChapters({});
      else
        file.setQtChapters({
          TagLib::MP4::Chapter(TagLib::String("Opening — 日本語", TagLib::String::UTF8), 1001),
          TagLib::MP4::Chapter("Second", 12001)
        });
      if (!file.save()) throw std::runtime_error("QuickTime chapter save failed");
    } else if (command == "qt-read") {
      TagLib::MP4::File file(argv[2], false);
      if (!file.isValid()) throw std::runtime_error("Invalid MP4 fixture");
      for (const auto &chapter: file.qtChapters())
        std::cout << chapter.startTime() << '\t' << hex(chapter.title()) << '\n';
    } else if (command == "mp4-colon") {
      TagLib::MP4::File file(argv[2], false);
      TagLib::PropertyMap properties;
      properties["TITLE"] = {"kept"};
      properties["UFIDHTTP://EXAMPLE.COM/ID3"] = {"native drops"};
      if (!file.setProperties(properties).isEmpty() || !file.save())
        throw std::runtime_error(
          "MP4 property save failed");
    } else if (command == "mkv-set") {
      TagLib::Matroska::File file(argv[2], false);
      if (!file.isValid()) throw std::runtime_error("Invalid Matroska fixture");
      file.tag(true)->clearSimpleTags();
      using Simple = TagLib::Matroska::SimpleTag;
      file.tag(true)->addSimpleTag(Simple("TITLE", TagLib::String("Local"), Simple::Track, "eng", true, 0));
      file.tag(true)->addSimpleTag(Simple("DATA", TagLib::ByteVector("\0\xff\x07", 3),
                                          Simple::Album, "deu", false, ~0ULL, 2, 3, 4));
      if (!file.save()) throw std::runtime_error("Matroska typed save failed");
    } else if (command == "mkv-read") {
      TagLib::Matroska::File file(argv[2], false);
      if (!file.isValid()) throw std::runtime_error("Invalid Matroska fixture");
      for (const auto &tag: file.tag(true)->simpleTagsList()) {
        std::cout << hex(tag.name()) << '\t' << tag.targetTypeValue() << '\t' << hex(tag.language())
            << '\t' << tag.defaultLanguageFlag() << '\t' << tag.trackUid() << '\t' << tag.editionUid()
            << '\t' << tag.chapterUid() << '\t' << tag.attachmentUid() << '\t' << tag.type() << '\t';
        if (tag.type() == TagLib::Matroska::SimpleTag::StringType) std::cout << hex(tag.toString());
        else std::cout << hex(tag.toByteVector());
        std::cout << '\n';
      }
    } else if (command == "mkv-chapters-set") {
      TagLib::Matroska::File file(argv[2], false);
      if (!file.isValid()) throw std::runtime_error("Invalid Matroska fixture");
      using Chapter = TagLib::Matroska::Chapter;
      file.chapters(true)->clear();
      file.chapters(true)->addChapterEdition(TagLib::Matroska::ChapterEdition({
                                                                                Chapter(1000, 4000, {
                                                                                    Chapter::Display(
                                                                                      TagLib::String(
                                                                                        "Začátek",
                                                                                        TagLib::String::UTF8), "ces"),
                                                                                    Chapter::Display("Opening", "eng")
                                                                                  }, 101, true),
                                                                                Chapter(4000, 9000, {
                                                                                    Chapter::Display("Second", "eng")
                                                                                  }, 102, false)
                                                                              }, true, true, 9));
      if (!file.save()) throw std::runtime_error("Matroska chapter save failed");
    } else if (command == "mkv-chapters-read") {
      TagLib::Matroska::File file(argv[2], false);
      if (!file.isValid()) throw std::runtime_error("Invalid Matroska fixture");
      if (auto *chapters = file.chapters(false))
        for (const auto &edition: chapters->chapterEditionList()) {
          std::cout << "EDITION\t" << edition.uid() << '\t' << edition.isDefault() << '\t' << edition.isOrdered() <<
              '\n';
          for (const auto &chapter: edition.chapterList()) {
            std::cout << "CHAPTER\t" << chapter.uid() << '\t' << chapter.timeStart() << '\t'
                << chapter.timeEnd() << '\t' << chapter.isHidden() << '\n';
            for (const auto &display: chapter.displayList())
              std::cout << "DISPLAY\t" << hex(display.string()) << '\t' << hex(display.language()) << '\n';
          }
        }
    } else if (command == "read") {
      TagLib::FileRef file(argv[2], false);
      if (file.isNull() || !file.file()->isValid()) throw std::runtime_error("Invalid fixture");
      for (const auto &[key, values]: file.properties())
        for (const auto &value: values) std::cout << key.to8Bit(true) << '\t' << hex(value) << '\n';
      for (const auto &picture: file.complexProperties("PICTURE"))
        std::cout << "PICTURE\t" << hex(picture.value("mimeType").toString()) << '\t'
            << hex(picture.value("data").toByteVector()) << '\n';
    } else throw std::runtime_error("Unknown command");
    return 0;
  } catch (const std::exception &error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
