// Common FileRef constructor-owned read-style/save state control.
// Reuses the required complete common snapshot projection.
#define main common_core_probe_main
#include "file-reference-reads-and-mutations.cpp"
#undef main

int main(int argc, char **argv) {
  try {
    if (argc != 5)throw std::runtime_error("Usage: mpeg-tta-session-probe PATH MODE SCENARIO ORIGIN");
    const char *path = argv[1];
    std::string mode = argv[2], scenario = argv[3];
    long origin = std::stol(argv[4]);
    auto style = mode == "FAST"
                   ? TagLib::AudioProperties::Fast
                   : mode == "ACCURATE"
                       ? TagLib::AudioProperties::Accurate
                       : TagLib::AudioProperties::Average;
    std::unique_ptr<NamedMemory> memory;
    if (origin >= 0) {
      std::ifstream input(path, std::ios::binary);
      std::string bytes((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
      memory = std::make_unique<NamedMemory>(TagLib::ByteVector(bytes.data(), bytes.size()), path);
      memory->seek(origin);
    }
    {
      TagLib::FileRef file = memory
                               ? TagLib::FileRef(memory.get(), mode != "TAGS_ONLY", style)
                               : TagLib::FileRef(path, mode != "TAGS_ONLY", style);
      std::cout << "{\"opened\":" << (!file.isNull() ? "true" : "false") << ",\"construction\":";
      snapshot(file);
      std::cout << ",\"cursorAfterConstruction\":" << (memory ? memory->tell() : 0);
      if (!file.isNull() && scenario == "common-title")file.tag()->setTitle(string("Common title"));
      std::cout << ",\"beforeSave\":";
      snapshot(file);
      std::cout << ",\"beforeSaveIsEmpty\":" << (!file.isNull() && file.tag()->isEmpty() ? "true" : "false");
      for (int i = 1; i <= 2; i++) {
        bool saved = file.save();
        std::cout << ",\"saved" << i << "\":" << (saved ? "true" : "false")
            << ",\"cursorAfterSave" << i << "\":" << (memory ? memory->tell() : 0)
            << ",\"afterSave" << i << "\":";
        snapshot(file);
        std::cout << ",\"isEmptyAfterSave" << i << "\":" << (
          !file.isNull() && file.tag()->isEmpty() ? "true" : "false");
        std::string outputPath = std::string(path) + ".save" + std::to_string(i);
        if (memory) {
          std::ofstream output(outputPath, std::ios::binary | std::ios::trunc);
          output.write(memory->data()->data(), memory->data()->size());
        } else {
          // Native FileStream::length seeks to EOF and back, flushing its stdio
          // output without changing the observable cursor or metadata state.
          file.file()->length();
          std::ifstream input(path, std::ios::binary);
          std::ofstream output(outputPath, std::ios::binary | std::ios::trunc);
          output << input.rdbuf();
        }
      }
    }
    if (memory) {
      std::ofstream output(path, std::ios::binary | std::ios::trunc);
      output.write(memory->data()->data(), memory->data()->size());
    }
    std::cout << ",\"post\":";
    snapshot(path, "AVERAGE");
    std::cout << "}\n";
    return 0;
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
