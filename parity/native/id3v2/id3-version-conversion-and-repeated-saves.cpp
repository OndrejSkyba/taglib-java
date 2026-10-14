// Ordinary T01 real-file/frame/default-constructor completion comparisons.
#define main retained_id3_owner_protocol_main
#include "id3-owner.cpp"
#undef main
#include <filesystem>
#include <mpegfile.h>
#include <id3v1tag.h>
#include <flacfile.h>
#include <wavfile.h>
#include <aifffile.h>
#include <dsffile.h>
#include <trueaudiofile.h>
#include <dsdifffile.h>

static std::string counts(const Factory &factory) {
  return std::to_string(factory.raw) + ":" + std::to_string(factory.typed) + ":" + std::to_string(factory.update) + ":"
         + std::to_string(factory.aggregate);
}

static void snapshot(const std::string &key, MPEG::File &file, Factory &factory) {
  auto tag = file.ID3v2Tag();
  auto h = tag->header();
  std::cout << key << "/file\t" << file.isValid() << ":" << file.hasID3v2Tag() << ":" << file.length() << ":" <<
      counts(factory) << "\n";
  std::cout << key << "/header\t" << h->majorVersion() << ":" << h->revisionNumber() << ":" << h->tagSize() << ":" << h
      ->completeTagSize() << ":" << h->unsynchronisation() << ":" << h->extendedHeader() << ":" << h->
      experimentalIndicator() << ":" << h->footerPresent() << ":" << (tag->extendedHeader()
                                                                        ? std::to_string(tag->extendedHeader()->size())
                                                                        : "null") << "\n";
  std::cout << key << "/tag\t" << fields({tag->title(), tag->artist(), tag->album(), tag->comment(), tag->genre()}) <<
      ":" << tag->year() << ":" << tag->track() << ":" << tag->isEmpty() << ":" << props(tag->properties()) << ":" <<
      map(tag->frameListMap()) << "\n";
  int n = 0;
  for (auto f: tag->frameList())std::cout << key << "/frame/" << n++ << "\t" << frame(f) << "\n";
}

static void common(I::Tag &tag) {
  tag.setTitle(String("Title caf\xc3\xa9", String::UTF8));
  tag.setYear(4294967295U);
  tag.setTrack(2147483648U);
  tag.setComment("comment");
  tag.addFrame(new I::TableOfContentsFrame("toc"));
}

static void properties(I::Tag &tag) {
  PropertyMap p;
  p["TITLE"].append("Second");
  p["ARTIST"].append("First").append("Second");
  p["PERFORMER:VIOLIN"].append("a violinist");
  p["ARRANGER"].append("arranger");
  p["T01:CUSTOM"].append(String("caf\xc3\xa9", String::UTF8));
  auto rejected = tag.setProperties(p);
  if (!rejected.isEmpty())throw std::runtime_error("Unexpected rejected ordinary properties");
}

static std::unique_ptr<TagLib::File> parent(const std::filesystem::path &p, const std::string &kind, Factory &factory) {
  if (kind == "mpeg.mp3")return std::make_unique<MPEG::File>(p.c_str(), false, AudioProperties::Average, &factory);
  if (kind == "flac.flac")return std::make_unique<FLAC::File>(p.c_str(), false, AudioProperties::Average, &factory);
  if (kind == "wav.wav")return std::make_unique<RIFF::WAV::File>(p.c_str(), false, AudioProperties::Average, &factory);
  if (kind == "aiff.aiff")
    return std::make_unique<RIFF::AIFF::File>(p.c_str(), false, AudioProperties::Average,
                                              &factory);
  if (kind == "dsf.dsf")return std::make_unique<DSF::File>(p.c_str(), false, AudioProperties::Average, &factory);
  if (kind == "tta.tta")return std::make_unique<TrueAudio::File>(p.c_str(), false, AudioProperties::Average, &factory);
  return std::make_unique<DSDIFF::File>(p.c_str(), false, AudioProperties::Average, &factory);
}

static I::Tag *parentTag(TagLib::File &f) {
  if (auto *p = dynamic_cast<MPEG::File *>(&f))return p->ID3v2Tag();
  if (auto *p = dynamic_cast<FLAC::File *>(&f))return p->ID3v2Tag();
  if (auto *p = dynamic_cast<RIFF::WAV::File *>(&f))return p->ID3v2Tag();
  if (auto *p = dynamic_cast<TrueAudio::File *>(&f))return p->ID3v2Tag();
  if (auto *p = dynamic_cast<DSDIFF::File *>(&f))return p->ID3v2Tag();
  return dynamic_cast<I::Tag *>(f.tag());
}

static void parents(const std::filesystem::path &root) {
  for (const std::string name: {"mpeg.mp3", "flac.flac", "wav.wav", "aiff.aiff", "dsf.dsf", "tta.tta", "dff.dff"})
    for (int decode: {0, 1}) {
      Factory factory;
      factory.setDefaultTextEncoding(String::UTF16);
      Utf8 handler;
      I::Tag::setLatin1StringHandler(decode ? &handler : nullptr);
      auto key = "parent/" + name + "/" + std::to_string(decode);
      auto target = root / "outputs" / ("parent-" + name + "-" + std::to_string(decode) + ".native");
      std::filesystem::copy_file(root / "parents" / name, target, std::filesystem::copy_options::overwrite_existing);
      auto f = parent(target, name, factory);
      auto *t = parentTag(*f);
      if (!f->isValid() || !t || t->frameList().size() != 2)throw std::runtime_error("Unexpected ordinary parent read");
      auto *child = t->frameList().front();
      std::cout << key << "/read\t" << text(t->title()) << ":" << props(t->properties()) << ":" << counts(factory) <<
          ":" << frame(child) << "\n";
      t->setTitle(String("changed caf\xc3\xa9", String::UTF8));
      t->addFrame(new I::TableOfContentsFrame("toc"));
      if (!f->save())throw std::runtime_error("Unexpected ordinary parent save");
      std::cout << key << "/identity\t" << (t == parentTag(*f)) << ":" << (child == t->frameList().front()) << ":" <<
          text(t->title()) << ":" << props(t->properties()) << ":" << frame(child) << "\n";
      f.reset();
      std::ifstream input(target, std::ios::binary);
      std::string output((std::istreambuf_iterator<char>(input)), {});
      std::cout << key << "/save/0/bytes\t" << hex(ByteVector(output.data(), output.size())) << "\n";
      auto reread = parent(target, name, factory);
      auto *r = parentTag(*reread);
      std::cout << key << "/reread\t" << text(r->title()) << ":" << props(r->properties()) << ":" << counts(factory) <<
          ":" << frame(r->frameList().front()) << "\n";
      I::Tag::setLatin1StringHandler(nullptr);
    }
}

int main(int argc, char **argv) {
  std::filesystem::path root = argv[1];
  I::TableOfContentsFrame toc("toc");
  std::cout << "default/toc\t" << frame(&toc) << "\t" << hex(toc.render()) << "\n";
  std::ifstream cases(root / "inputs.tsv");
  std::string line;
  while (std::getline(cases, line)) {
    auto tab = line.find('\t');
    auto name = line.substr(0, tab);
    bool mutate = line.substr(tab + 1) == "1";
    std::ifstream raw(root / "inputs" / name, std::ios::binary);
    std::string data((std::istreambuf_iterator<char>(raw)), {});
    ByteVector original(data.data(), data.size());
    for (int mode: {-1, 0, 1, 3})
      for (int decode: {0, 1})
        for (bool path: {false, true}) {
          Factory factory;
          if (mode >= 0)factory.setDefaultTextEncoding(String::Type(mode));
          Utf8 handler;
          I::Tag::setLatin1StringHandler(decode ? &handler : nullptr);
          auto key = name + "/" + std::to_string(mode) + "/" + std::to_string(decode) + "/" + (
                       path ? "path" : "borrowed");
          auto target = root / "outputs" / (key.substr(0, key.find('/')) + "-" + std::to_string(mode) + "-" +
                                            std::to_string(decode) + "-" + (path ? "path" : "borrowed") + ".native");
          ByteVectorStream stream(original);
          std::unique_ptr<MPEG::File> file;
          if (path) {
            std::filesystem::copy_file(root / "inputs" / name, target,
                                       std::filesystem::copy_options::overwrite_existing);
            file = std::make_unique<MPEG::File>(target.c_str(), false, AudioProperties::Average, &factory);
          } else file = std::make_unique<MPEG::File>(&stream, false, AudioProperties::Average, &factory);
          snapshot(key + "/read", *file, factory);
          for (auto version: {I::v3, I::v4}) {
            auto rendered = file->ID3v2Tag()->render(version);
            std::cout << key << "/render/" << int(version) << "\t" << hex(rendered) << "\n";
            snapshot(key + "/rendered/" + std::to_string(int(version)), *file, factory);
          }
          if (mutate) {
            for (int round = 0; round < 2; round++) {
              if (round == 0)common(*file->ID3v2Tag());
              else properties(*file->ID3v2Tag());
              bool saved = file->save(MPEG::File::ID3v2, File::StripOthers, round == 0 ? I::v3 : I::v4);
              if (!saved)throw std::runtime_error("Unexpected ordinary save rejection");
              snapshot(key + "/save/" + std::to_string(round), *file, factory);
              ByteVector output;
              if (path) {
                std::ifstream in(target, std::ios::binary);
                std::string bytes((std::istreambuf_iterator<char>(in)), {});
                output = ByteVector(bytes.data(), bytes.size());
              } else output = *stream.data();
              std::cout << key << "/save/" << round << "/bytes\t" << hex(output) << "\n";
            }
          }
          if (mutate && !path) {
            std::ofstream out(target, std::ios::binary);
            out.write(stream.data()->data(), stream.data()->size());
          }
          file.reset();
          if (mutate) {
            MPEG::File reread(target.c_str(), false, AudioProperties::Average, &factory);
            snapshot(key + "/reread", reread, factory);
          } else if (path)std::filesystem::remove(target);
          I::Tag::setLatin1StringHandler(nullptr);
        }
  }
  parents(root);
}
