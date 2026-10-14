// Native FileRef lifecycle and resolver oracle, linked to checksum-verified TagLib.
#include <iostream>
#include <string>
#include <fileref.h>
#include <tpropertymap.h>
#include <tvariant.h>
#include <tbytevectorstream.h>
#include <mod/modtag.h>
#include <tagunion.h>

using namespace TagLib;

namespace {
  std::string events;
  int destroyed = 0;

  class TestFile final : public File {
    Mod::Tag tagValue;

  public:
    TestFile(IOStream *stream, bool valid = true) : File(stream) { setValid(valid); }
    ~TestFile() override { ++destroyed; }
    Tag *tag() const override { return const_cast<Mod::Tag *>(&tagValue); }
    AudioProperties *audioProperties() const override { return nullptr; }
    bool save() override { return isValid(); }
  };

  class ComplexTag final : public Mod::Tag {
    bool accepts;

  public:
    int calls = 0, removals = 0;

    explicit ComplexTag(bool accept) : accepts(accept) {
    }

    StringList complexPropertyKeys() const override { return accepts ? StringList("CUSTOM") : StringList(); }

    List<VariantMap> complexProperties(const String &) const override {
      List<VariantMap> values;
      if (accepts) {
        VariantMap value;
        value.insert("data", String("x"));
        values.append(value);
      }
      return values;
    }

    bool setComplexProperties(const String &, const List<VariantMap> &) override {
      ++calls;
      return accepts;
    }

    void removeUnsupportedProperties(const StringList &) override { ++removals; }
  };

  class Resolver final : public FileRef::StreamTypeResolver {
    char id;
    IOStream *stream;
    bool succeedPath, succeedStream, valid;

  public:
    Resolver(char id, IOStream *stream, bool path, bool direct, bool valid = true)
      : id(id), stream(stream), succeedPath(path), succeedStream(direct), valid(valid) {
    }

    File *createFile(FileName, bool read, AudioProperties::ReadStyle style) const override {
      events += std::string{id} + "P" + (read ? "1" : "0") + std::to_string(static_cast<int>(style)) + ";";
      return succeedPath ? new TestFile(stream, valid) : nullptr;
    }

    File *createFileFromStream(IOStream *s, bool read, AudioProperties::ReadStyle style) const override {
      events += std::string{id} + "S" + (read ? "1" : "0") + std::to_string(static_cast<int>(style)) + ";";
      return succeedStream ? new TestFile(s, valid) : nullptr;
    }
  };

  class NamedStream final : public ByteVectorStream {
    std::string filename;

  public:
    explicit NamedStream(const std::string &name) : ByteVectorStream(ByteVector()), filename(name) {
    }

    FileName name() const override { return filename.c_str(); }
  };

  void flags(const std::string &label, const FileRef &ref) {
    std::cout << label << "|" << ref.isNull() << "|" << (ref.file() != nullptr) << "|"
        << (ref.tag() != nullptr) << "|" << (ref.audioProperties() != nullptr) << "|"
        << ref.properties().size() << "|" << ref.complexPropertyKeys().size() << "\n";
  }
}

int main(int argc, char **argv) {
  std::cout << "extensions|" << FileRef::defaultFileExtensions().toString(",").to8Bit(true) << "\n";
  NamedStream stream("resolver.unknown");
  Resolver first('A', &stream, true, false), second('B', &stream, false, false);
  FileRef::addFileTypeResolver(&first);
  FileRef::addFileTypeResolver(&second);
  {
    FileRef ref(&stream, false, AudioProperties::Accurate);
    flags("resolved-stream", ref);
    std::cout << "stream-order|" << events << "\n";
    events.clear();
    ref.tag()->setTitle("shared");
    FileRef::clearFileTypeResolvers();
    std::cout << "registry-clear|" << ref.save() << "|" << destroyed << "\n";
  }
  std::cout << "last-owner|" << destroyed << "|" << stream.isOpen() << "\n";
  FileRef::addFileTypeResolver(&first);
  FileRef::addFileTypeResolver(&second);
  {
    FileRef ref("resolver.unknown", false, AudioProperties::Fast);
    flags("resolved-path", ref);
  }
  std::cout << "path-order|" << events << "\n";
  events.clear();
  Resolver invalid('I', &stream, true, true, false);
  FileRef::addFileTypeResolver(&invalid);
  {
    FileRef ref(&stream);
    std::cout << "invalid-resolver|" << ref.isNull() << "\n";
    std::cout << "invalid-save|" << ref.save() << "\n";
  }
  std::cout << "invalid-order|" << events << "\n";
  FileRef::clearFileTypeResolvers();
  {
    auto first = new ComplexTag(false), second = new ComplexTag(true), third = new ComplexTag(false);
    TagUnion tags(first, second, third);
    std::cout << "union-complex|" << tags.complexPropertyKeys().toString(",").to8Bit(true) << "|" << tags.
        complexProperties("CUSTOM").size() << "|" << tags.setComplexProperties("CUSTOM", List<VariantMap>()) << "|" <<
        first->calls << "," << second->calls << "," << third->calls << "\n";
    tags.removeUnsupportedProperties(StringList("opaque"));
    std::cout << "union-removal|" << first->removals << "," << second->removals << "," << third->removals << "\n";
    Mod::Tag base;
    std::cout << "base-complex-default|" << base.complexPropertyKeys().size() << "|" << base.
        complexProperties("UNKNOWN").size() << "|" << base.setComplexProperties("UNKNOWN", List<VariantMap>()) << "\n";
    base.removeUnsupportedProperties(StringList("opaque"));
    TagUnion defaults;
    std::cout << "union-default|" << defaults.isEmpty() << "|" << defaults.properties().size() << "|" << defaults.
        complexPropertyKeys().size() << "|" << defaults.complexProperties("UNKNOWN").size() << "|" << defaults.
        setComplexProperties("UNKNOWN", List<VariantMap>()) << "\n";
  }
  if (argc > 1) {
    FileRef ref(argv[1], false);
    flags("path-tags-only", ref);
    FileRef bad("this-path-does-not-exist.flac");
    flags("missing-path", bad);
  }
}
