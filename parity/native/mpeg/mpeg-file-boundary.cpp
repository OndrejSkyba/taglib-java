// Isolated native ByteVectorStream growth after an actual MPEG owner's cached tag is truncated.
#include <iostream>
#include "mpegfile.h"
#include "id3v2tag.h"
#include "id3v2header.h"
#include "tbytevectorstream.h"
using namespace TagLib;

int main() {
  ID3v2::Tag tag;
  tag.setTitle("small");
  ByteVector data = tag.render();
  data.append(ByteVector("\xff\xfb\x90\0", 4)).append(ByteVector(413, 0));
  data.append(ByteVector("\xff\xfb\x90\0", 4)).append(ByteVector(413, 0));
  ByteVectorStream stream(data);
  MPEG::File file(&stream, false);
  stream.truncate(3);
  file.ID3v2Tag()->setTitle(String(std::string(5000, 'x')));
  std::cout << "before\t" << file.ID3v2Tag()->header()->completeTagSize() << '\t'
      << stream.length() << '\t' << stream.data()->toHex() << std::endl;
  return file.save() ? 0 : 1;
}
