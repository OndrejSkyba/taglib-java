#include <algorithm>
#include <chrono>
#include <cstdint>
#include <fstream>
#include <filesystem>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include "taglib.h"
#include "fileref.h"
#include "tag.h"
#include "tpropertymap.h"
#include "tvariant.h"
#include "flacpicture.h"
#include "mpegfile.h"
#include "id3v2tag.h"
#include "id3v2frame.h"

static std::string version() {
  return std::to_string(TAGLIB_MAJOR_VERSION) + "." + std::to_string(TAGLIB_MINOR_VERSION) + "." + std::to_string(
           TAGLIB_PATCH_VERSION);
}

static std::string utf8(const TagLib::String &s) { return s.to8Bit(true); }

static std::string json(const std::string &s) {
  std::ostringstream out;
  out << '"';
  for (unsigned char c: s)
    switch (c) {
      case '"': out << "\\\"";
        break;
      case '\\': out << "\\\\";
        break;
      case '\b': out << "\\b";
        break;
      case '\f': out << "\\f";
        break;
      case '\n': out << "\\n";
        break;
      case '\r': out << "\\r";
        break;
      case '\t': out << "\\t";
        break;
      default: if (c < 32) out << "\\u00" << std::hex << std::setw(2) << std::setfill('0') << unsigned(c) << std::dec;
        else out << c;
    }
  return out.str() + '"';
}

static uint64_t fnv(const char *p, size_t size) {
  uint64_t h = 14695981039346656037ULL;
  for (size_t i = 0; i < size; ++i) h = (h ^ static_cast<unsigned char>(p[i])) * 1099511628211ULL;
  return h;
}

static std::string hex(uint64_t n) {
  std::ostringstream o;
  o << std::hex << std::setw(16) << std::setfill('0') << n;
  return o.str();
}

struct Picture {
  int type, width, height, colorDepth, indexedColors;
  std::string mime, description, hash;
  size_t size;
};

struct Snapshot {
  bool valid = false;
  std::vector<std::pair<std::string, std::vector<std::string> > > properties;
  std::string title, artist, album, comment, genre;
  unsigned year = 0, track = 0;
  int64_t milliseconds = 0;
  int bitrate = 0, sampleRate = 0, channels = 0;
  std::vector<Picture> pictures;
};

static Snapshot snapshot(const std::string &path) {
  TagLib::FileRef file(path.c_str(), true, TagLib::AudioProperties::Average);
  Snapshot s;
  if (file.isNull() || !file.file() || !file.file()->isValid()) return s;
  s.valid = true;
  if (auto *tag = file.tag()) {
    s.title = utf8(tag->title());
    s.artist = utf8(tag->artist());
    s.album = utf8(tag->album());
    s.comment = utf8(tag->comment());
    s.genre = utf8(tag->genre());
    s.year = tag->year();
    s.track = tag->track();
  }
  auto properties = file.properties();
  for (const auto &[key,values]: properties) {
    std::vector<std::string> v;
    for (const auto &value: values) v.push_back(utf8(value));
    s.properties.emplace_back(utf8(key), v);
  }
  if (auto *a = file.audioProperties()) {
    s.milliseconds = a->lengthInMilliseconds();
    s.bitrate = a->bitrate();
    s.sampleRate = a->sampleRate();
    s.channels = a->channels();
  }
  for (const auto &picture: file.complexProperties("PICTURE")) {
    auto bytes = picture.value("data").toByteVector();
    s.pictures.push_back({
      static_cast<int>(TagLib::FLAC::Picture::typeFromString(picture.value("pictureType").toString())),
      picture.value("width").toInt(), picture.value("height").toInt(), picture.value("colorDepth").toInt(),
      picture.value("numColors").toInt(), utf8(picture.value("mimeType").toString()),
      utf8(picture.value("description").toString()), hex(fnv(bytes.data(), bytes.size())), bytes.size()
    });
  }
  return s;
}

static void print(const Snapshot &s) {
  std::cout << "{\"valid\":" << (s.valid ? "true" : "false");
  if (!s.valid) {
    std::cout << '}';
    return;
  }
  std::cout << ",\"properties\":{";
  bool first = true;
  for (const auto &[k,v]: s.properties) {
    if (!first) std::cout << ',';
    first = false;
    std::cout << json(k) << ":[";
    bool valueFirst = true;
    for (const auto &x: v) {
      if (!valueFirst) std::cout << ',';
      valueFirst = false;
      std::cout << json(x);
    }
    std::cout << ']';
  }
  std::cout << "},\"common\":{\"title\":" << json(s.title) << ",\"artist\":" << json(s.artist) << ",\"album\":" <<
      json(s.album) << ",\"comment\":" << json(s.comment) << ",\"genre\":" << json(s.genre) << ",\"year\":" << s.year <<
      ",\"track\":" << s.track;
  std::cout << "},\"audio\":{\"lengthInMilliseconds\":" << s.milliseconds << ",\"bitrate\":" << s.bitrate <<
      ",\"sampleRate\":" << s.sampleRate << ",\"channels\":" << s.channels << "},\"pictures\":[";
  first = true;
  for (const auto &p: s.pictures) {
    if (!first)std::cout << ',';
    first = false;
    std::cout << "{\"type\":" << p.type << ",\"mimeType\":" << json(p.mime) << ",\"description\":" <<
        json(p.description) << ",\"size\":" << p.size << ",\"fnv1a64\":" << json(p.hash) << ",\"width\":" << p.width <<
        ",\"height\":" << p.height << ",\"colorDepth\":" << p.colorDepth << ",\"indexedColors\":" << p.indexedColors <<
        '}';
  }
  std::cout << "]}";
}

// Consume public API values directly in both runtimes. Building the JSON-oracle
// Snapshot here would add a C++-only vector/string materialization cost.
static uint64_t checksumFile(const std::string &path) {
  TagLib::FileRef file(path.c_str(), true, TagLib::AudioProperties::Average);
  if (file.isNull() || !file.file() || !file.file()->isValid())return 0;
  uint64_t sum = 1;
  auto hashString = [](const TagLib::String &value) {
    auto bytes = utf8(value);
    return fnv(bytes.data(), bytes.size());
  };
  for (const auto &[key,values]: file.properties()) {
    sum += hashString(key);
    for (const auto &value: values)sum += hashString(value);
  }
  if (auto t = file.tag()) {
    sum += hashString(t->title()) + hashString(t->artist()) + hashString(t->album()) + hashString(t->comment()) +
        hashString(t->genre());
    sum += static_cast<uint64_t>(t->year()) + t->track();
  } else sum += 5 * fnv("", 0);
  if (auto a = file.audioProperties())
    sum += static_cast<uint64_t>(a->lengthInMilliseconds()) + a->bitrate() + a->
        sampleRate() + a->channels();
  for (const auto &p: file.complexProperties("PICTURE")) {
    auto bytes = p.value("data").toByteVector();
    auto hash = hex(fnv(bytes.data(), bytes.size()));
    sum += bytes.size() + static_cast<uint64_t>(
          TagLib::FLAC::Picture::typeFromString(p.value("pictureType").toString())) + p.value("width").toInt() + p.
        value("height").toInt() + p.value("colorDepth").toInt() + p.value("numColors").toInt();
    sum += hashString(p.value("mimeType").toString()) + hashString(p.value("description").toString()) + fnv(
      hash.data(), hash.size());
  }
  return sum;
}

static std::string manifestFingerprint(const std::vector<std::string> &paths) {
  std::string joined;
  for (const auto &path: paths) {
    joined += path;
    joined += '\n';
  }
  return hex(fnv(joined.data(), joined.size()));
}

static std::vector<std::string> manifest(const char *path) {
  std::ifstream in(path);
  if (!in)throw std::runtime_error("Cannot open manifest");
  std::vector<std::string> result;
  std::string line;
  while (std::getline(in, line))if (!line.empty())result.push_back(line);
  if (result.empty())throw std::runtime_error("Empty corpus");
  return result;
}

static int write(const char *path) {
  TagLib::FileRef file(path, true, TagLib::AudioProperties::Average);
  if (file.isNull() || !file.file()->isValid()) return 3;
  auto props = file.properties();
  const std::pair<const char *, const char *> values[] = {
    {"TITLE", "Port conformance \xE2\x80\x94 \xC5\xBDlu\xC5\xA5ou\xC4\x8Dk\xC3\xBD"}, {"ARTIST", "TagLib Java"},
    {"ALBUM", "Cross-language test"}, {"COMMENT", "Preserve the audio payload"}, {"GENRE", "Jazz"}, {"DATE", "2026"},
    {"TRACKNUMBER", "7"}
  };
  for (const auto &[key,value]: values)
    props.replace(
      key, TagLib::StringList(TagLib::String(value, TagLib::String::UTF8)));
  auto rejected = file.setProperties(props);
  bool saved = file.save();
  std::cout << "{\"saved\":" << (saved ? "true" : "false") << ",\"rejectedKeys\":[";
  bool first = true;
  for (const auto &[k,v]: rejected) {
    if (!first)std::cout << ',';
    first = false;
    std::cout << json(utf8(k));
  }
  std::cout << "]}\n";
  return saved ? 0 : 4;
}

static TagLib::ByteVector imageBytes() {
  const std::string h =
      "89504e470d0a1a0a0000000d4948445200000001000000010804000000b51c0c020000000b4944415478da6364f80f00010501012718e3660000000049454e44ae426082";
  TagLib::ByteVector b;
  for (size_t i = 0; i < h.size(); i += 2)b.append(static_cast<char>(std::stoul(h.substr(i, 2), nullptr, 16)));
  return b;
}

static int writeArtwork(const char *path, bool clear) {
  TagLib::FileRef f(path, true, TagLib::AudioProperties::Average);
  if (f.isNull() || !f.file()->isValid())return 3;
  // Match Java's combined text-and-artwork API, including native normalization of existing properties.
  auto rejected = f.setProperties(f.properties());
  TagLib::List<TagLib::VariantMap> pictures;
  if (!clear)
    for (int type: {3, 4}) {
      TagLib::VariantMap p;
      p.insert("data", imageBytes());
      p.insert("mimeType", TagLib::String("image/png"));
      p.insert("description", TagLib::String(type == 3 ? "Front cover" : "Back cover", TagLib::String::UTF8));
      p.insert("pictureType", TagLib::FLAC::Picture::typeToString(static_cast<TagLib::FLAC::Picture::Type>(type)));
      p.insert("width", 1);
      p.insert("height", 1);
      p.insert("colorDepth", 8);
      p.insert("numColors", 0);
      pictures.append(p);
    }
  bool supported = f.setComplexProperties("PICTURE", pictures);
  bool saved = supported && f.save();
  std::cout << "{\"supported\":" << (supported ? "true" : "false") << ",\"saved\":" << (saved ? "true" : "false") <<
      ",\"operationContract\":\"setProperties(existing properties), setComplexProperties(PICTURE), save\",\"rejectedKeys\":[";
  bool first = true;
  for (const auto &[key,values]: rejected) {
    if (!first)std::cout << ',';
    first = false;
    std::cout << json(utf8(key));
  }
  std::cout << "]}\n";
  return supported ? (saved ? 0 : 4) : 5;
}

static void opaque(const char *path) {
  TagLib::FileRef f(path);
  std::cout << '[';
  bool first = true;
  if (!f.isNull())
    if (auto mpeg = dynamic_cast<TagLib::MPEG::File *>(f.file()))
      if (auto tag = mpeg->ID3v2Tag()) {
        auto unsupported = tag->properties().unsupportedData();
        for (const auto *frame: tag->frameList()) {
          std::string id(frame->frameID().data(), frame->frameID().size());
          if (id == "APIC")continue;
          bool include = false;
          for (const auto &label: unsupported) {
            auto key = utf8(label);
            if (key == id || key.rfind(id + "/", 0) == 0 || key == "UNKNOWN/" + id) {
              include = true;
              break;
            }
          }
          if (include) {
            auto bytes = frame->render();
            auto header = frame->header();
            auto renderedHeader = header->render();
            // Header::render changes its length encoding between ID3v2.3 and v2.4.
            // Compare parsed flag meanings and the rendered payload, not representation-only header bytes.
            auto payload = bytes.mid(renderedHeader.size());
            if (!first)std::cout << ',';
            first = false;
            std::cout << "{\"id\":" << json(id) << ",\"payloadSize\":" << payload.size() << ",\"payloadFnv1a64\":" <<
                json(hex(fnv(payload.data(), payload.size())));
            std::cout << ",\"flags\":{\"tagAlterPreservation\":" << (header->tagAlterPreservation() ? "true" : "false")
                << ",\"fileAlterPreservation\":" << (header->fileAlterPreservation() ? "true" : "false") <<
                ",\"readOnly\":" << (header->readOnly() ? "true" : "false") << ",\"groupingIdentity\":" << (
                  header->groupingIdentity() ? "true" : "false") << ",\"compression\":" << (
                  header->compression() ? "true" : "false") << ",\"encryption\":" << (
                  header->encryption() ? "true" : "false") << ",\"unsynchronisation\":" << (
                  header->unsynchronisation() ? "true" : "false") << ",\"dataLengthIndicator\":" << (
                  header->dataLengthIndicator() ? "true" : "false") << '}';
            std::cout << ",\"representation\":{\"version\":" << header->version() << ",\"headerBytes\":" <<
                renderedHeader.size() << ",\"headerFnv1a64\":" <<
                json(hex(fnv(renderedHeader.data(), renderedHeader.size()))) << ",\"frameFnv1a64\":" << json(
                  hex(fnv(bytes.data(), bytes.size()))) << "}}";
          }
        }
      }
  std::cout << "]\n";
}

// Length-prefixed fields retain key/value boundaries and ordered values across runtimes.
static std::string propertyChecksum(const TagLib::PropertyMap &properties) {
  std::string bytes;
  auto number = [&](size_t n) {
    for (int shift = 24; shift >= 0; shift -= 8)bytes.push_back(static_cast<char>(n >> shift));
  };
  auto string = [&](const TagLib::String &text) {
    auto s = utf8(text);
    number(s.size());
    bytes += s;
  };
  for (const auto &[key,values]: properties) {
    string(key);
    number(values.size());
    for (const auto &value: values)string(value);
  }
  return hex(fnv(bytes.data(), bytes.size()));
}

static std::string fileChecksum(const std::filesystem::path &path) {
  std::ifstream input(path, std::ios::binary);
  if (!input)throw std::runtime_error("Cannot hash benchmark output");
  uint64_t hash = 14695981039346656037ULL;
  char buffer[65536];
  while (input) {
    input.read(buffer, sizeof(buffer));
    for (std::streamsize i = 0; i < input.gcount(); ++i)
      hash = (hash ^ static_cast<unsigned char>(buffer[i])) * 1099511628211ULL;
  }
  if (!input.eof())throw std::runtime_error("Cannot read benchmark output");
  return hex(hash);
}

static int benchmarkWrites(const char *list, const char *destination, int warmup, int samples) {
  if (warmup < 0 || samples < 1)throw std::runtime_error("Invalid write benchmark sample settings");
  auto paths = manifest(list);
  std::filesystem::path output(destination);
  std::filesystem::create_directories(output);
  std::vector<TagLib::PropertyMap> originals;
  for (const auto &path: paths) {
    TagLib::FileRef file(path.c_str(), false);
    if (file.isNull() || !file.file()->isValid())throw std::runtime_error("Invalid benchmark source");
    originals.push_back(file.properties());
  }
  std::ostringstream rows;
  bool first = true;
  for (int sample = -warmup; sample < samples; ++sample)
    for (size_t i = 0; i < paths.size(); ++i)
      for (const std::string workload: {"title", "growth"}) {
        auto properties = originals[i];
        properties.replace("TITLE", TagLib::StringList(TagLib::String(
                             "Write benchmark title \xE2\x80\x94 \xC5\xBDlu\xC5\xA5ou\xC4\x8Dk\xC3\xBD",
                             TagLib::String::UTF8)));
        if (workload == "growth")
          properties.replace("COMMENT", TagLib::StringList(
                               TagLib::String(
                                 std::string(8192, 'x') +
                                 " \xE2\x80\x94 \xC5\xBDlu\xC5\xA5ou\xC4\x8Dk\xC3\xBD",
                                 TagLib::String::UTF8)));
        auto target = output / (workload + "-" + std::to_string(i) + std::filesystem::path(paths[i]).extension().
                                string());
        if (std::filesystem::equivalent(std::filesystem::path(paths[i]).parent_path(), output))
          throw std::runtime_error(
            "Benchmark output must not overwrite corpus");
        std::filesystem::copy_file(paths[i], target, std::filesystem::copy_options::overwrite_existing);
        auto expected = propertyChecksum(properties);
        bool saved = false, accepted = false;
        auto start = std::chrono::steady_clock::now();
        {
          TagLib::FileRef file(target.c_str(), false);
          if (!file.isNull() && file.file()->isValid()) {
            accepted = file.setProperties(properties).isEmpty();
            saved = file.save();
          }
        }
        auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start).
            count();
        TagLib::FileRef readback(target.c_str(), false);
        auto actualProperties = readback.properties();
        auto actual = readback.isNull() ? "invalid" : propertyChecksum(actualProperties);
        bool requested = actualProperties.value("TITLE") == properties.value("TITLE") && (
                           workload != "growth" || actualProperties.value("COMMENT") == properties.value("COMMENT"));
        auto bytesChecksum = sample == samples - 1 ? fileChecksum(target) : "";
        if (!first)rows << ',';
        first = false;
        rows << "{\"caseIndex\":" << i << ",\"workload\":" << json(workload) << ",\"sample\":" << sample <<
            ",\"nanos\":" << elapsed << ",\"metadataChecksum\":" << json(actual) << ",\"fileFnv1a64\":" <<
            json(bytesChecksum) << ",\"matchesPlan\":" << (saved && accepted && requested ? "true" : "false") <<
            ",\"matchesExactPropertyMap\":" << (actual == expected ? "true" : "false") << ",\"outputBytes\":" <<
            std::filesystem::file_size(target) << '}';
      }
  std::cout << "{\"engine\":\"native\",\"upstreamVersion\":" << json(version()) << ",\"compiler\":" << json(__VERSION__)
      <<
      ",\"buildType\":\"Release\",\"operation\":\"open without audio properties; setProperties(precomputed map); save; close\",\"durability\":\"native in-place save; no added fsync or atomic replacement\",\"warmup\":"
      << warmup << ",\"samples\":" << samples << ",\"manifestFnv1a64\":" << json(manifestFingerprint(paths)) <<
      ",\"records\":[" << rows.str() << "]}\n";
  return 0;
}

int main(int argc, char **argv) {
  try {
    if (argc == 2 && std::string(argv[1]) == "version") {
      std::cout << "{\"version\":" << json(version()) << "}\n";
      return 0;
    }
    if (argc == 3 && std::string(argv[1]) == "snapshot") {
      print(snapshot(argv[2]));
      std::cout << '\n';
      return 0;
    }
    if (argc == 3 && std::string(argv[1]) == "manifest") {
      for (const auto &p: manifest(argv[2])) {
        std::cout << "{\"path\":" << json(p) << ",\"snapshot\":";
        print(snapshot(p));
        std::cout << "}\n";
      }
      return 0;
    }
    if (argc == 3 && std::string(argv[1]) == "write") return write(argv[2]);
    if (argc == 3 && std::string(argv[1]) == "opaque") {
      opaque(argv[2]);
      return 0;
    }
    if (argc == 4 && std::string(argv[1]) == "write-artwork")
      return writeArtwork(
        argv[2], std::string(argv[3]) == "clear");
    if (argc == 6 && std::string(argv[1]) == "write-benchmark")
      return benchmarkWrites(
        argv[2], argv[3], std::stoi(argv[4]), std::stoi(argv[5]));
    if (argc == 6 && std::string(argv[1]) == "benchmark") {
      auto paths = manifest(argv[2]);
      int warmup = std::stoi(argv[3]), samples = std::stoi(argv[4]), iterations = std::stoi(argv[5]);
      if (warmup < 0 || samples < 1 || iterations < 1)throw std::runtime_error("Invalid sample settings");
      volatile uint64_t sink = 0;
      std::vector<int64_t> timings;
      std::vector<uint64_t> checksums;
      for (int sample = -warmup; sample < samples; ++sample) {
        uint64_t sum = 0;
        auto start = std::chrono::steady_clock::now();
        for (int i = 0; i < iterations; ++i)for (const auto &p: paths)sum += checksumFile(p);
        auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start).
            count();
        sink = sum;
        if (sample >= 0) {
          timings.push_back(elapsed);
          checksums.push_back(sum);
        }
      }
      std::cout << "{\"engine\":" << json("taglib-" + version() + "-cpp") << ",\"compiler\":" << json(__VERSION__) <<
          ",\"buildType\":\"Release\",\"files\":" << paths.size() << ",\"iterations\":" << iterations <<
          ",\"warmupSamples\":" << warmup << ",\"manifestFnv1a64\":" << json(manifestFingerprint(paths)) <<
          ",\"samplesNanos\":[";
      for (size_t i = 0; i < timings.size(); ++i) {
        if (i)std::cout << ',';
        std::cout << timings[i];
      }
      std::cout << "],\"checksums\":[";
      for (size_t i = 0; i < checksums.size(); ++i) {
        if (i)std::cout << ',';
        std::cout << json(hex(checksums[i]));
      }
      std::cout << "]}\n";
      (void) sink;
      return 0;
    }
    std::cerr << "Usage: taglib-oracle snapshot|write PATH; manifest LIST; benchmark LIST WARMUP SAMPLES ITERATIONS\n";
    return 2;
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
