// Bounded malformed-header oracle: observable getters must preserve constructor position.
#include <fileref.h>
#include <tbytevectorstream.h>
#include <tpropertymap.h>
#include <tag.h>
#include <fstream>
#include <iterator>
#include <iostream>
using namespace TagLib;

class Memory : public ByteVectorStream {
  std::string filename;

public:
  Memory(const ByteVector &bytes, const std::string &name) : ByteVectorStream(bytes), filename(name) {
  }

  FileName name() const override { return filename.c_str(); }
};

static std::string hex(const String &value) { return String(value.data(String::UTF8).toHex()).to8Bit(true); }

int main(int argc, char **argv) {
  if (argc != 4)return 1;
  std::ifstream input(argv[1], std::ios::binary);
  std::string bytes((std::istreambuf_iterator<char>(input)), {});
  Memory stream(ByteVector(bytes.data(), bytes.size()), argv[1]);
  stream.seek(std::stoll(argv[3]));
  std::string mode = argv[2];
  auto style = mode == "FAST"
                 ? AudioProperties::Fast
                 : mode == "ACCURATE"
                     ? AudioProperties::Accurate
                     : AudioProperties::Average;
  {
    FileRef file(&stream, mode != "TAGS_ONLY", style);
    std::cout << "constructor|" << file.isNull() << "|" << stream.tell() << "\n";
    if (!file.isNull()) {
      auto props = file.properties();
      std::cout << "properties|";
      for (const auto &[key,values]: props) {
        std::cout << hex(key) << "=";
        for (const auto &value: values)std::cout << hex(value) << ",";
        std::cout << ";";
      }
      std::cout << "/";
      for (const auto &key: props.unsupportedData())std::cout << hex(key) << ",";
      std::cout << "\n";
      auto tag = file.tag();
      std::cout << "common|" << hex(tag->title()) << "," << hex(tag->artist()) << "," << hex(tag->album()) << "," <<
          hex(tag->comment()) << "," << hex(tag->genre()) << "," << tag->year() << "," << tag->track() << "," << tag->
          isEmpty() << "\n";
      auto audio = file.audioProperties();
      std::cout << "audio|" << (audio != nullptr);
      if (audio)
        std::cout << "|" << audio->lengthInMilliseconds() << "," << audio->bitrate() << "," << audio->
            sampleRate() << "," << audio->channels();
      std::cout << "\n";
      std::cout << "complex|";
      for (const auto &key: file.complexPropertyKeys())std::cout << hex(key) << ",";
      std::cout << "\n";
    }
    std::cout << "getters-cursor|" << stream.tell() << "\n";
  }
  std::cout << "closed|" << stream.isOpen() << "|" << stream.tell() << "\n";
}
