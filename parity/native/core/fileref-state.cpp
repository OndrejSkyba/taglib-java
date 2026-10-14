// Stateful native common-operation oracle linked to the pinned TagLib build.
#include <fileref.h>
#include <tpropertymap.h>
#include <tvariant.h>
#include <tbytevectorstream.h>
#include <tag.h>
#include <fstream>
#include <iterator>
#include <iostream>
using namespace TagLib;

class Memory : public ByteVectorStream {
  std::string filename;

public:
  bool locked = false;

  Memory(const ByteVector &bytes, const std::string &name) : ByteVectorStream(bytes), filename(name) {
  }

  FileName name() const override { return filename.c_str(); }
  bool readOnly() const override { return locked; }
};

static std::string hex(const String &value) { return String(value.data(String::UTF8).toHex()).to8Bit(true); }

static void dump(const char *label, FileRef &file) {
  std::cout << label << "|" << file.isNull() << "|";
  if (file.isNull()) {
    std::cout << "\n";
    return;
  }
  const auto properties = file.properties();
  for (const auto &[key,values]: properties) {
    std::cout << hex(key) << "=";
    for (const auto &value: values) std::cout << hex(value) << ",";
    std::cout << ";";
  }
  std::cout << "/";
  for (const auto &value: properties.unsupportedData()) std::cout << hex(value) << ",";
  auto tag = file.tag();
  std::cout << "/" << hex(tag->title()) << "," << hex(tag->artist()) << "," << hex(tag->album()) << "," <<
      hex(tag->comment()) << "," << hex(tag->genre()) << "," << tag->year() << "," << tag->track() << "," << tag->
      isEmpty();
  std::cout << "/";
  for (const auto &key: file.complexPropertyKeys()) std::cout << hex(key) << ",";
  std::cout << "/";
  for (const auto &picture: file.complexProperties("PICTURE")) {
    for (const auto &[key,value]: picture) {
      std::cout << hex(key) << "=";
      if (key == "data") std::cout << value.toByteVector().toHex();
      else if (key == "uid") std::cout << value.toULongLong();
      else if (key == "width" || key == "height" || key == "colorDepth" || key == "numColors")
        std::cout << value.toInt();
      else std::cout << hex(value.toString());
      std::cout << ",";
    }
    std::cout << ";";
  }
  std::cout << "\n";
}

int main(int argc, char **argv) {
  if (argc < 3 || argc > 4)return 1;
  std::ifstream input(argv[1], std::ios::binary);
  std::string bytes((std::istreambuf_iterator<char>(input)), {});
  Memory stream(ByteVector(bytes.data(), bytes.size()), argv[1]);
  FileRef file(&stream, false);
  dump("initial", file);
  std::string scenario = argv[2];
  if (scenario == "empty-common") file.setProperties(PropertyMap());
  else if (scenario == "date") file.setProperties(file.properties());
  else if (scenario == "empty-frame") {
    PropertyMap properties;
    properties.replace("ARTIST", StringList());
    properties.replace("CUSTOM", StringList());
    StringList comments;
    comments.append("first");
    comments.append("second");
    properties.replace("COMMENT", comments);
    file.setProperties(properties);
  } else if (scenario == "picture") {
    VariantMap picture;
    picture.insert("data", ByteVector("\x89PNG\x0d\x0a\x1a\x0a", 8));
    picture.insert("description", String("cover"));
    picture.insert("fileName", String("given.bin"));
    picture.insert("uid", 77ULL);
    List<VariantMap> pictures;
    pictures.append(picture);
    std::cout << "picture-supported|" << file.setComplexProperties("PICTURE", pictures) << "\n";
  }
  if (scenario == "common") {
    file.tag()->setTitle("title");
    file.tag()->setAlbum("album");
    file.tag()->setGenre("Jazz");
    file.tag()->setYear(4294967295U);
    file.tag()->setTrack(2147483648U);
  } else if (scenario == "asf-nul-common") {
    file.tag()->setAlbum(String(std::string("A\0B", 3)));
    file.tag()->setGenre(String(std::string("G\0H", 3)));
  } else if (scenario == "asf-nul-properties") {
    PropertyMap props;
    props["ALBUM"] = {String(std::string("A\0B", 3))};
    props["GENRE"] = {String(std::string("G\0H", 3))};
    file.setProperties(props);
  } else if (scenario == "properties") {
    PropertyMap props;
    props["TITLE"] = {"one", "two"};
    props["ALBUM"] = {"mapped", "duplicate"};
    props["DATE"] = {"2026-10"};
    props["PEAKVALUE"] = {"4294967295"};
    props["ISVBR"] = {"1"};
    file.setProperties(props);
  } else if (scenario == "remove")file.removeUnsupportedProperties(StringList("Opaque"));
  dump("staged", file);
  stream.locked = true;
  std::cout << "blocked-save|" << file.save() << "\n";
  dump("failed", file);
  stream.locked = false;
  std::cout << "first-save|" << file.save() << "\n";
  dump("first", file);
  std::cout << "second-save|" << file.save() << "\n";
  dump("second", file);
  if (scenario == "empty-common") {
    file.tag()->setTitle("changed");
    file.tag()->setYear(4294967295U);
    file.tag()->setTrack(4294967295U);
    dump("common", file);
    std::cout << "common-save|" << file.save() << "|" << file.save() << "\n";
    dump("common-after", file);
  }
  if (argc == 4) {
    std::ofstream output(argv[3], std::ios::binary);
    output.write(stream.data()->data(), stream.data()->size());
  }
  Memory reopened(*stream.data(), argv[1]);
  FileRef readback(&reopened, false);
  dump("reopened", readback);
}
