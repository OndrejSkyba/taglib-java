// Independent TagLib common FileRef contract probe.
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <memory>
#include <iterator>
#include "taglib.h"
#include "fileref.h"
#include "tag.h"
#include "tpropertymap.h"
#include "tvariant.h"
#include "flacpicture.h"
#include "tbytevectorstream.h"

static std::string quote(const std::string &s) {
  std::ostringstream out;
  out << '"';
  for (unsigned char c: s)
    switch (c) {
      case '"': out << "\\\"";
        break;
      case '\\': out << "\\\\";
        break;
      case '\n': out << "\\n";
        break;
      case '\r': out << "\\r";
        break;
      case '\t': out << "\\t";
        break;
      default: if (c < 32)
          out << "\\u00" << std::hex << std::setw(2)
              << std::setfill('0') << unsigned(c) << std::dec;
        else out << c;
    }
  return out.str() + '"';
}

static std::string text(const TagLib::String &s) { return s.to8Bit(true); }
static TagLib::String string(const std::string &s) { return TagLib::String(s, TagLib::String::UTF8); }

static std::string hash(const TagLib::ByteVector &data) {
  uint64_t h = 14695981039346656037ULL;
  for (unsigned int i = 0; i < data.size(); ++i) h = (h ^ static_cast<unsigned char>(data[i])) * 1099511628211ULL;
  std::ostringstream out;
  out << std::hex << std::setw(16) << std::setfill('0') << h;
  return out.str();
}

static void strings(const TagLib::StringList &list) {
  std::cout << '[';
  bool first = true;
  for (const auto &value: list) {
    if (!first) std::cout << ',';
    first = false;
    std::cout << quote(text(value));
  }
  std::cout << ']';
}

static void properties(const TagLib::PropertyMap &map) {
  std::cout << '{';
  bool first = true;
  for (const auto &[key, values]: map) {
    if (!first) std::cout << ',';
    first = false;
    std::cout << quote(text(key)) << ':';
    strings(values);
  }
  std::cout << '}';
}

static void propertyResult(const TagLib::PropertyMap &map) {
  std::cout << "{\"properties\":";
  properties(map);
  std::cout << ",\"unsupportedData\":";
  strings(map.unsupportedData());
  std::cout << '}';
}

static void snapshot(TagLib::FileRef &file) {
  bool valid = !file.isNull() && file.file() && file.file()->isValid();
  std::cout << "{\"valid\":" << (valid ? "true" : "false");
  if (!valid) {
    std::cout << '}';
    return;
  }
  auto map = file.properties();
  std::cout << ",\"properties\":";
  properties(map);
  std::cout << ",\"unsupportedData\":";
  strings(map.unsupportedData());
  auto *tag = file.tag();
  std::cout << ",\"common\":{\"title\":" << quote(tag ? text(tag->title()) : "")
      << ",\"artist\":" << quote(tag ? text(tag->artist()) : "")
      << ",\"album\":" << quote(tag ? text(tag->album()) : "")
      << ",\"comment\":" << quote(tag ? text(tag->comment()) : "")
      << ",\"genre\":" << quote(tag ? text(tag->genre()) : "")
      << ",\"year\":" << (tag ? tag->year() : 0)
      << ",\"track\":" << (tag ? tag->track() : 0) << '}';
  auto *audio = file.audioProperties();
  std::cout << ",\"audio\":{\"lengthInMilliseconds\":" << (audio ? audio->lengthInMilliseconds() : 0)
      << ",\"bitrate\":" << (audio ? audio->bitrate() : 0)
      << ",\"sampleRate\":" << (audio ? audio->sampleRate() : 0)
      << ",\"channels\":" << (audio ? audio->channels() : 0) << '}';
  std::cout << ",\"pictures\":[";
  bool first = true;
  for (const auto &picture: file.complexProperties("PICTURE")) {
    if (!first) std::cout << ',';
    first = false;
    auto bytes = picture.value("data").toByteVector();
    std::cout << "{\"type\":" << static_cast<int>(TagLib::FLAC::Picture::typeFromString(
          picture.value("pictureType").toString()))
        << ",\"mimeType\":" << quote(text(picture.value("mimeType").toString()))
        << ",\"description\":" << quote(text(picture.value("description").toString()))
        << ",\"size\":" << bytes.size() << ",\"fnv1a64\":" << quote(hash(bytes))
        << ",\"width\":" << picture.value("width").toInt()
        << ",\"height\":" << picture.value("height").toInt()
        << ",\"colorDepth\":" << picture.value("colorDepth").toInt()
        << ",\"indexedColors\":" << picture.value("numColors").toInt() << '}';
  }
  std::cout << "]}";
}

static void snapshot(const char *path, const std::string &mode) {
  bool readAudio = mode != "TAGS_ONLY";
  auto style = mode == "FAST"
                 ? TagLib::AudioProperties::Fast
                 : mode == "ACCURATE"
                     ? TagLib::AudioProperties::Accurate
                     : TagLib::AudioProperties::Average;
  TagLib::FileRef file(path, readAudio, style);
  snapshot(file);
}

static TagLib::PropertyMap plan(TagLib::FileRef &file, const std::string &scenario) {
  TagLib::PropertyMap map;
  if (scenario == "grow-shrink") {
    map.insert("TITLE", TagLib::StringList(string(std::string(8192, 'x'))));
    return map;
  }
  auto put = [&](const std::string &key, std::initializer_list<std::string> values) {
    TagLib::StringList list;
    for (const auto &v: values) list.append(string(v));
    map.replace(string(key), list);
  };
  if (scenario == "unchanged" || scenario == "pictures-replace" || scenario == "pictures-remove")
    return file.
        properties();
  if (scenario == "empty") return map;
  if (scenario == "empty-values") {
    put("TITLE", {""});
    put("ARTIST", {});
    put("COMMENT", {""});
    put("DATE", {});
    put("TRACKNUMBER", {});
    put("CUSTOM", {});
  } else if (scenario == "multivalue") {
    put("TITLE", {"First", "Second"});
    put("ARTIST", {"Alpha", "Beta"});
    put("ALBUM", {"One", "Two"});
    put("COMMENT", {"Comment A", "Comment B"});
    put("GENRE", {"Jazz", "Rock"});
    put("DATE", {"2026-10-03", "2027"});
    put("TRACKNUMBER", {"7/11", "8"});
    put("CUSTOM", {"one", "two"});
  } else if (scenario == "aliases") {
    put("title", {"Alias title"});
    put("Album Artist", {"Spaced"});
    put("albumartist", {"Joined"});
    put("year", {"1999"});
    put("date", {"2026-10-03"});
    put("track", {"2"});
    put("tracknumber", {"7/11"});
    put("DESCRIPTION", {"Description alias"});
    put("COMMENT", {"Comment alias"});
  } else if (scenario == "unicode") {
    put("TITLE", {"Žluťoučký 🐦"});
    put("ARTIST", {"東京", "é"});
    put("COMMENT", {"Line one\nLine two"});
    put("CUSTOM", {"Ελληνικά", "العربية"});
  } else if (scenario == "rejected-keys") {
    for (const std::string &key: {"", "=", "éx", "~", "}", "TITLE", "EMPTY", "KEY\n", "X=A"})
      put(key, {"value"});
  } else if (scenario.rfind("numeric-", 0) == 0) {
    auto value = scenario.substr(8);
    put("DATE", {value});
    put("TRACKNUMBER", {value});
  } else throw std::runtime_error("Unknown scenario: " + scenario);
  return map;
}

static TagLib::ByteVector image() {
  const std::string hex =
      "89504e470d0a1a0a0000000d4948445200000001000000010804000000b51c0c020000000b4944415478da6364f80f00010501012718e3660000000049454e44ae426082";
  TagLib::ByteVector bytes;
  for (size_t i = 0; i < hex.size(); i += 2) bytes.append(static_cast<char>(std::stoul(hex.substr(i, 2), nullptr, 16)));
  return bytes;
}

class NamedMemory : public TagLib::ByteVectorStream {
public:
  NamedMemory(const TagLib::ByteVector &bytes, const std::string &path) : TagLib::ByteVectorStream(bytes), path(path) {
  }

  TagLib::FileName name() const override { return path.c_str(); }

private:
  std::string path;
};

static void mutate(const char *path, const std::string &scenario, bool borrowed = false, long origin = 7,
                   bool twice = false, bool stageOnly = false) {
  TagLib::PropertyMap requested, rejected;
  bool opened = false, pictureSupported = true, saved = false;
  bool propertiesCalled = false, complexCalled = false, saveCalled = false, removeCalled = false;
  int commonSettersCalled = 0, savesCalled = 0;
  std::string beforeSave, beforeSaveStemData = "null";
  TagLib::StringList complexKeys;
  bool tagEmpty = true;
  TagLib::offset_t cursorAfterConstruction = 0, cursorBeforeSave = 0, cursorAfterSave = 0;
  std::unique_ptr<NamedMemory> memory;
  if (borrowed) {
    std::ifstream input(path, std::ios::binary);
    std::string bytes((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    memory = std::make_unique<NamedMemory>(TagLib::ByteVector(bytes.data(), bytes.size()), path);
    memory->seek(origin);
  }
  {
    TagLib::FileRef file = borrowed ? TagLib::FileRef(memory.get(), false) : TagLib::FileRef(path, false);
    if (memory) cursorAfterConstruction = cursorBeforeSave = cursorAfterSave = memory->tell();
    opened = !file.isNull() && file.file() && file.file()->isValid();
    if (opened) {
      if (scenario == "save-only" || scenario.rfind("stem-", 0) == 0) requested = file.properties();
      else if (scenario == "remove-unsupported") {
        removeCalled = true;
        requested = file.properties();
        file.removeUnsupportedProperties(requested.unsupportedData());
      } else if (scenario == "common-title") {
        commonSettersCalled = 1;
        file.tag()->setTitle(string("Common title"));
        requested = file.properties();
      } else if (scenario.rfind("common-", 0) == 0) {
        commonSettersCalled = 7;
        auto *tag = file.tag();
        tag->setTitle(string("Common Žluť"));
        tag->setArtist(string("Common artist"));
        tag->setAlbum(string("Common album"));
        tag->setComment(string("Common comment"));
        tag->setGenre(string("Jazz"));
        unsigned int value = static_cast<unsigned int>(std::stoull(scenario.substr(7)));
        tag->setYear(value);
        tag->setTrack(value);
        requested = file.properties();
      } else {
        propertiesCalled = true;
        requested = plan(file, scenario);
        rejected = file.setProperties(requested);
      }
      if (scenario == "pictures-replace" || scenario == "pictures-remove") {
        complexCalled = true;
        TagLib::List<TagLib::VariantMap> pictures;
        if (scenario == "pictures-replace")
          for (int type: {3, 4}) {
            TagLib::VariantMap p;
            p.insert("data", image());
            p.insert("mimeType", string("image/png"));
            p.insert("description", string(type == 3 ? "Front Žluť" : "Back 東京"));
            p.insert("pictureType",
                     TagLib::FLAC::Picture::typeToString(static_cast<TagLib::FLAC::Picture::Type>(type)));
            p.insert("width", 1);
            p.insert("height", 1);
            p.insert("colorDepth", 8);
            p.insert("numColors", 0);
            pictures.append(p);
          }
        pictureSupported = file.setComplexProperties("PICTURE", pictures);
      }
      if (scenario.rfind("stem-", 0) == 0) {
        complexCalled = true;
        TagLib::List<TagLib::VariantMap> values;
        if (scenario != "stem-remove") {
          TagLib::VariantMap value;
          value.insert("manifest", scenario == "stem-empty"
                                     ? TagLib::ByteVector()
                                     : TagLib::ByteVector("{\"stem\":1}"));
          values.append(value);
        }
        pictureSupported = file.setComplexProperties("STEM", values);
      }
      std::ostringstream capture;
      auto previous = std::cout.rdbuf(capture.rdbuf());
      snapshot(file);
      std::cout.rdbuf(previous);
      beforeSave = capture.str();
      complexKeys = file.complexPropertyKeys();
      tagEmpty = file.tag()->isEmpty();
      auto stems = file.complexProperties("STEM");
      if (!stems.isEmpty()) {
        auto data = stems.front().value("manifest").value<TagLib::ByteVector>().toHex();
        beforeSaveStemData = quote(std::string(data.data(), data.size()));
      }
      if (memory) cursorBeforeSave = memory->tell();
      saveCalled = !stageOnly && pictureSupported;
      saved = saveCalled && file.save();
      if (saveCalled) ++savesCalled;
      if (twice && saved) {
        if (scenario == "grow-shrink") {
          file.tag()->setTitle("x");
          ++commonSettersCalled;
        }
        saved = file.save();
        ++savesCalled;
      }
      if (memory) cursorAfterSave = memory->tell();
    }
  }
  if (memory) {
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    output.write(memory->data()->data(), memory->data()->size());
  }
  std::cout << "{\"opened\":" << (opened ? "true" : "false")
      << ",\"pictureSupported\":" << (pictureSupported ? "true" : "false")
      << ",\"saved\":" << (saved ? "true" : "false") << ",\"requested\":";
  propertyResult(requested);
  std::cout << ",\"methodCalls\":{\"setProperties\":" << (propertiesCalled ? 1 : 0)
      << ",\"setComplexProperties\":" << (complexCalled ? 1 : 0)
      << ",\"save\":" << savesCalled
      << ",\"removeUnsupportedProperties\":" << (removeCalled ? 1 : 0)
      << ",\"commonSetters\":" << commonSettersCalled << '}';
  std::cout << ",\"rejected\":";
  propertyResult(rejected);
  std::cout << ",\"beforeSave\":" << (beforeSave.empty() ? "{\"valid\":false}" : beforeSave);
  std::cout << ",\"beforeSaveComplexPropertyKeys\":";
  strings(complexKeys);
  std::cout << ",\"beforeSaveIsEmpty\":" << (tagEmpty ? "true" : "false");
  std::cout << ",\"beforeSaveStemData\":" << beforeSaveStemData;
  if (memory)
    std::cout << ",\"borrowedStreamOpenAfterClose\":" << (memory->isOpen() ? "true" : "false")
        << ",\"borrowedStreamCursorAfterConstruction\":" << cursorAfterConstruction
        << ",\"borrowedStreamCursorBeforeSave\":" << cursorBeforeSave
        << ",\"borrowedStreamCursorAfterSave\":" << cursorAfterSave;
  std::cout << ",\"post\":";
  snapshot(path, "AVERAGE");
  std::cout << "}\n";
}

static void snapshotStream(const char *path, const std::string &mode, long origin = 7) {
  std::ifstream input(path, std::ios::binary);
  std::string bytes((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
  NamedMemory memory(TagLib::ByteVector(bytes.data(), bytes.size()), path);
  memory.seek(origin);
  auto style = mode == "FAST"
                 ? TagLib::AudioProperties::Fast
                 : mode == "ACCURATE"
                     ? TagLib::AudioProperties::Accurate
                     : TagLib::AudioProperties::Average;
  TagLib::FileRef file(&memory, mode != "TAGS_ONLY", style);
  std::cout << "{\"cursorAfterConstruction\":" << memory.tell() << ",\"snapshot\":";
  snapshot(file);
  std::cout << ",\"cursorAfterSnapshot\":" << memory.tell() << "}\n";
}

int main(int argc, char **argv) {
  try {
    if (argc == 2 && std::string(argv[1]) == "version") {
      std::cout << "{\"version\":\"" << TAGLIB_MAJOR_VERSION << '.' << TAGLIB_MINOR_VERSION << '.' <<
          TAGLIB_PATCH_VERSION << "\"}\n";
    } else if (argc == 4 && std::string(argv[1]) == "snapshot") {
      snapshot(argv[2], argv[3]);
      std::cout << '\n';
    } else if ((argc == 4 || argc == 5) && std::string(argv[1]) == "snapshot-stream")
      snapshotStream(
        argv[2], argv[3], argc == 5 ? std::stol(argv[4]) : 7);
    else if (argc == 4 && std::string(argv[1]) == "mutate") mutate(argv[2], argv[3]);
    else if ((argc == 4 || argc == 5) && std::string(argv[1]) == "mutate-stream")
      mutate(
        argv[2], argv[3], true, argc == 5 ? std::stol(argv[4]) : 7);
    else if (argc == 5 && std::string(argv[1]) == "stage-stream")
      mutate(
        argv[2], argv[3], true, std::stol(argv[4]), false, true);
    else if ((argc == 4 || argc == 5) && std::string(argv[1]) == "mutate-stream-twice")
      mutate(
        argv[2], argv[3], true, argc == 5 ? std::stol(argv[4]) : 7, true);
    else throw std::runtime_error("Usage: core-parity-probe version | snapshot PATH MODE | mutate PATH SCENARIO");
    return 0;
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
