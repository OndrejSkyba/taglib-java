#define main candidateProtocolMain
#include "dsdiff-file.cpp"
#undef main
#include "tagunion.h"

struct ThrowFrame : I::Frame {
  ThrowFrame() : Frame("X001") {
  }

  String toString() const override { return ""; }

protected:
  void parseFields(const ByteVector &) override {
  }

  ByteVector renderFields() const override { throw std::runtime_error("render hook"); }
};

struct Trace : ByteVectorStream {
  std::string ops;
  D::File *file = nullptr;
  bool callback = false, after = false;
  int fail = -1, count = 0;

  Trace(ByteVector b) : ByteVectorStream(b) {
  }

  void insert(const ByteVector &d, offset_t at, size_t replace) override {
    ops += "insert:" + std::to_string(at) + ":" + std::to_string(replace) + ":" + std::to_string(d.size()) + ";";
    int stage = count++;
    if (stage == fail && !after)throw std::runtime_error("write hook");
    ByteVectorStream::insert(d, at, replace);
    if (callback)file->DIINTag(true)->setTitle("IO callback title");
    if (stage == fail && after)throw std::runtime_error("write hook");
  }

  void removeBlock(offset_t at, size_t count) override {
    ops += "remove:" + std::to_string(at) + ":" + std::to_string(count) + ";";
    ByteVectorStream::removeBlock(at, count);
  }

  void writeBlock(const ByteVector &d) override {
    ops += "write:" + std::to_string(tell()) + ":" + std::to_string(d.size()) + ";";
    ByteVectorStream::writeBlock(d);
  }

  void truncate(offset_t size) override {
    ops += "truncate:" + std::to_string(size) + ";";
    ByteVectorStream::truncate(size);
  }
};

struct Getters : D::DIIN::Tag {
  mutable int titles = 0, artists = 0;

  String title() const override {
    switch (titles++) {
      case 0: return "condition";
      case 1: return "";
      default: return String(std::wstring(L"A\0B", 3));
    }
  }

  String artist() const override {
    artists++;
    return "";
  }
};

struct Utf8 : I::Latin1StringHandler {
  mutable int calls = 0;

  String parse(const ByteVector &b) const override {
    calls++;
    return String(b, String::UTF8);
  }
};

int main(int argc, char **argv) {
  std::filesystem::path root(argv[1]);
  auto original = read(root / "inputs/both.dff");
  {
    ByteVectorStream s(original);
    D::File f(&s);
    f.ID3v2Tag()->addFrame(new ThrowFrame);
    bool threw = false;
    try { f.save(D::File::ID3v2); } catch (const std::runtime_error &) { threw = true; }
    std::cout << "helper/render-after-strip\t" << threw << '\t' << f.hasID3v2Tag() << ':' << f.hasDIINTag() << '\t' <<
        hex(*s.data()) << '\n';
    state("helper/render-after-strip", f);
  }
  for (int mode = 0; mode < 2; ++mode) {
    Trace s(read(root / "inputs/plain.dff"));
    D::File f(&s, false);
    s.file = &f;
    s.callback = mode;
    f.ID3v2Tag()->setTitle("modern");
    f.DIINTag()->setTitle("diin");
    for (int n = 0; n < 3; ++n) {
      s.ops.clear();
      auto ok = f.save();
      std::cout << "helper/io/" << mode << '/' << n << '\t' << ok << ':' << f.tell() << '\t' << s.ops << '\t' <<
          hex(*s.data()) << '\n';
      state("helper/io/" + std::to_string(mode) + '/' + std::to_string(n), f);
      if (n == 0)f.ID3v2Tag()->setTitle(String(std::string(5000, 'x')));
      if (n == 1)f.tag()->setTitle("");
    }
  }
  for (int after = 0; after < 2; ++after)
    for (int stage = 0; stage < 15; ++stage) {
      Trace s(read(root / "inputs/plain.dff"));
      D::File f(&s);
      s.file = &f;
      s.after = after;
      s.fail = stage;
      f.tag()->setTitle("modified");
      f.tag()->setArtist("artist");
      bool threw = false;
      try { f.save(); } catch (const std::runtime_error &) { threw = true; }
      auto base = "helper/failure/" + std::to_string(after) + '/' + std::to_string(stage);
      std::cout << base << '\t' << threw << ':' << f.tell() << '\t' << s.ops << '\t' << hex(*s.data()) << '\n';
      state(base, f);
    }
  {
    ByteVectorStream s(read(root / "inputs/plain.dff"));
    D::File f(&s);
    auto *custom = new Getters;
    dynamic_cast<TagUnion *>(f.tag())->set(1, custom);
    bool ok = f.save();
    std::cout << "helper/diin-getters\t" << ok << ':' << custom->titles << ':' << custom->artists << '\t' <<
        hex(*s.data()) << '\n';
  }
  for (int mode = 0; mode < 2; ++mode) {
    Utf8 handler;
    I::Tag::setLatin1StringHandler(mode ? &handler : nullptr);
    ByteVectorStream s(read(root / "inputs/lowercase-root.dff"));
    D::File f(&s);
    state("helper/latin1/" + std::to_string(mode), f);
    std::cout << "helper/latin1/" << mode << "/calls\t" << handler.calls << '\n';
    I::Tag::setLatin1StringHandler(nullptr);
  }
  std::cout << "helper/enums\t" << D::File::NoTags << ':' << D::File::DIIN << ':' << D::File::ID3v2 << ':' <<
      D::File::AllTags << ':' << TagLib::File::StripNone << ':' << TagLib::File::StripOthers << '\n';
  std::cout << "helper/support/null\t" << D::File::isSupported(nullptr) << '\n';
  FileStream closed((root / "missing.dff").c_str());
  std::cout << "helper/open-stream/rejected\t" << !closed.isOpen() << '\n';
  D::File missing((root / "missing.dff").c_str());
  std::cout << "helper/open-file/rejected\t" << !missing.isValid() << '\n';
}
