#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
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

struct Case { std::string id, path; int iterations, writes; };
struct Write { Case input; std::string workload; TagLib::PropertyMap properties; std::filesystem::path target; };
static volatile uint64_t blackhole = 0;

static std::string version() {
  return std::to_string(TAGLIB_MAJOR_VERSION) + "." + std::to_string(TAGLIB_MINOR_VERSION) + "." + std::to_string(TAGLIB_PATCH_VERSION);
}
static void environment() {
  std::cout << "{\"kind\":\"environment\",\"version\":" << std::quoted(version())
    << ",\"compiler\":" << std::quoted(__VERSION__) << ",\"buildType\":\"Release\"}\n";
}
static void valid(const TagLib::FileRef &file) {
  if(file.isNull() || !file.file()->isValid()) throw std::runtime_error("Cannot open audio file");
}
static uint64_t read(const std::string &path) {
  TagLib::FileRef file(path.c_str(), true, TagLib::AudioProperties::Average);
  valid(file);
  uint64_t total = 0;
  for(const auto &[key, values] : file.properties()) {
    total += key.size();
    for(const auto &value : values) total += value.size();
  }
  if(auto tag = file.tag()) {
    total += tag->title().size() + tag->artist().size() + tag->album().size()
      + tag->comment().size() + tag->genre().size() + tag->year() + tag->track();
  }
  if(auto audio = file.audioProperties())
    total += audio->lengthInMilliseconds() + audio->bitrate() + audio->sampleRate() + audio->channels();
  for(const auto &picture : file.complexProperties("PICTURE")) {
    total += picture.value("data").toByteVector().size() + picture.value("pictureType").toString().size()
      + picture.value("mimeType").toString().size() + picture.value("description").toString().size()
      + picture.value("width").toInt() + picture.value("height").toInt()
      + picture.value("colorDepth").toInt() + picture.value("numColors").toInt();
  }
  return total;
}
static size_t shifted(size_t index, int sample, size_t size) {
  int value = (static_cast<int>(index) + sample) % static_cast<int>(size);
  return value < 0 ? value + size : value;
}
static void timing(const std::string &id, const std::string &workload, int sample, int64_t nanos, int iterations) {
  std::cout << "{\"kind\":\"timing\",\"id\":" << std::quoted(id) << ",\"workload\":" << std::quoted(workload)
    << ",\"sample\":" << sample << ",\"nanos\":" << nanos << ",\"iterations\":" << iterations << "}\n";
}
int main(int argc, char **argv) {
  try {
    if(argc == 2 && std::string(argv[1]) == "version") { environment(); return 0; }
    if(argc != 6) throw std::runtime_error("MODE MANIFEST OUTPUT WARMUP SAMPLES");
    std::vector<Case> cases;
    std::ifstream manifest(argv[2]); std::string line;
    while(std::getline(manifest, line)) {
      std::istringstream row(line); std::string id, path, iterations, writes;
      if(!std::getline(row, id, '\t') || !std::getline(row, path, '\t')
        || !std::getline(row, iterations, '\t') || !std::getline(row, writes))
        throw std::runtime_error("Invalid manifest");
      cases.push_back({id, path, std::stoi(iterations), std::stoi(writes)});
    }
    int warmup = std::stoi(argv[4]), samples = std::stoi(argv[5]);
    if(cases.empty() || warmup < 0 || samples < 1) throw std::runtime_error("Invalid settings");
    environment();
    if(std::string(argv[1]) == "read") {
      for(int sample = -warmup; sample < samples; ++sample) for(size_t index = 0; index < cases.size(); ++index) {
        const auto &input = cases[shifted(index, sample, cases.size())];
        uint64_t total = 0; auto start = std::chrono::steady_clock::now();
        for(int iteration = 0; iteration < input.iterations; ++iteration) total += read(input.path);
        auto nanos = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start).count();
        blackhole = total;
        if(sample >= 0) timing(input.id, "read", sample, nanos, input.iterations);
      }
      return 0;
    }
    if(std::string(argv[1]) != "write") throw std::runtime_error("Unknown mode");
    std::filesystem::path output(argv[3]); std::filesystem::create_directories(output);
    std::vector<Write> writes;
    for(const auto &input : cases) {
      if(input.writes == 0) continue;
      TagLib::FileRef source(input.path.c_str(), false); valid(source);
      for(int kind = 0; kind < 2; ++kind) {
        if(!(input.writes & (1 << kind))) continue;
        std::string workload = kind == 0 ? "title" : "growth";
        auto properties = source.properties(); properties.replace("TITLE", TagLib::StringList("Benchmark title"));
        if(kind == 1) properties.replace("COMMENT", TagLib::StringList(TagLib::String(std::string(8192, 'x'), TagLib::String::UTF8)));
        writes.push_back({input, workload, properties,
          output / (input.id + "-" + workload + std::filesystem::path(input.path).extension().string())});
      }
    }
    for(int sample = -warmup; sample < samples; ++sample) for(size_t index = 0; index < writes.size(); ++index) {
      const auto &write = writes[shifted(index, sample, writes.size())];
      std::filesystem::copy_file(write.input.path, write.target, std::filesystem::copy_options::overwrite_existing);
      auto start = std::chrono::steady_clock::now();
      {
        TagLib::FileRef file(write.target.c_str(), false); valid(file);
        file.setProperties(write.properties);
        if(!file.save()) throw std::runtime_error("Metadata save failed");
      }
      auto nanos = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start).count();
      if(sample >= 0) timing(write.input.id, write.workload, sample, nanos, 1);
    }
    return 0;
  } catch(const std::exception &error) { std::cerr << error.what() << '\n'; return 1; }
}
