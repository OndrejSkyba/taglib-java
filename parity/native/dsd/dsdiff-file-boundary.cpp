#include "dsdifffile.h"
#include "tbytevectorstream.h"
#include <string>
using namespace TagLib;

ByteVector chunk(const char *id, const ByteVector &d = {}) {
  return ByteVector(id, 4) + ByteVector::fromULongLong(d.size()) + d;
}

ByteVector form(const ByteVector &d) {
  return ByteVector("FRM8", 4) + ByteVector::fromULongLong(d.size() + 4) + ByteVector("DSD ", 4) + d;
}

int main(int argc, char **argv) {
  std::string op(argv[1]);
  ByteVector prop = chunk("PROP", ByteVector("SND ", 4)), audio = chunk("DSD ", ByteVector(2u));
  if (op == "first-root") {
    ByteVectorStream s(form(chunk("ID3 ") + prop + audio));
    DSDIFF::File f(&s);
    f.strip(DSDIFF::File::ID3v2);
  }
  if (op == "stale-parent") {
    ByteVectorStream s(form(chunk("FVER") + prop + audio + chunk("DIIN")));
    DSDIFF::File f(&s);
    f.strip(DSDIFF::File::DIIN);
    f.DIINTag()->setTitle("x");
    f.save(DSDIFF::File::DIIN, File::StripNone);
  }
  if (op == "memory-grow") {
    ID3v2::Tag tag;
    tag.setTitle("original");
    ByteVectorStream s(form(chunk("FVER") + prop + audio + chunk("ID3 ", tag.render())));
    DSDIFF::File f(&s);
    s.truncate(0);
    f.ID3v2Tag()->setTitle(String(std::string(5000, 'x')));
    f.save(DSDIFF::File::ID3v2, File::StripNone);
  }
}
