// Isolated undefined calls, never executed inside the defined-operation protocol.
#include <string>
#include "trueaudiofile.h"
#include "id3v2tag.h"
#include "id3v2framefactory.h"
#include "tbytevectorstream.h"
#include "tpropertymap.h"
using namespace TagLib;

int main(int argc, char **argv) {
  std::string op(argv[1]);
  ByteVector input("TTA1opaque");
  if (op == "null-parse")input = ByteVector("ID3\4\0\0\0\0\0\14TIT2\0\0\0\4\0\0\0abc", 24);
  if (op == "memory-grow")input = ByteVector("ID3");
  ByteVectorStream stream(input);
  if (op == "memory-grow") {
    TrueAudio::File file(&stream, false);
    file.ID3v2Tag()->setTitle("new");
    return file.save() ? 0 : 2;
  }
  TrueAudio::File file(&stream, static_cast<ID3v2::FrameFactory *>(nullptr), false);
  if (op == "null-title")file.ID3v2Tag()->setTitle("new");
  if (op == "null-property") {
    PropertyMap values;
    values.insert("TITLE", {"new"});
    file.setProperties(values);
  }
  return 0;
}
