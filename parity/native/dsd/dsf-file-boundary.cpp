// Isolated native undefined calls; ASan observes faults in the pinned native library.
#include <string>
#include "dsffile.h"
#include "tbytevectorstream.h"
#include "tfilestream.h"

int main(int argc, char **argv) {
  if (argc != 3)return 2;
  if (std::string(argv[1]) == "closed-tell") {
    TagLib::FileStream stream(argv[2]);
    TagLib::DSF::File f(&stream);
    return static_cast<int>(f.tell());
  }
  if (std::string(argv[1]) == "invalid-save") {
    TagLib::ByteVectorStream stream(TagLib::ByteVector("bad!"));
    TagLib::DSF::File f(&stream);
    return f.save() ? 0 : 1;
  }
  return 3;
}
