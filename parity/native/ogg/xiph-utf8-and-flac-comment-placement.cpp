// Independent TagLib UTF-8 parsing and UTF-16 conversion proof.
#include <fstream>
#include <iterator>
#include <string>
#include "xiphcomment.h"
#include "flacpicture.h"

int main(int argc, char **argv) {
  if (argc != 4) return 2;
  std::ifstream input(argv[2], std::ios::binary);
  std::string bytes((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
  TagLib::ByteVector raw(bytes.data(), bytes.size()), result;
  std::string mode = argv[1];
  if (mode == "comment") {
    TagLib::Ogg::XiphComment tag(raw);
    result = tag.render(false);
  } else if (mode == "picture") {
    TagLib::FLAC::Picture picture;
    if (!picture.parse(raw)) return 3;
    result = picture.render();
  } else if (mode == "render-invalid") {
    TagLib::Ogg::XiphComment tag(raw);
    TagLib::String invalid(std::wstring(1, static_cast<wchar_t>(0xd800)));
    tag.addField("TITLE", invalid);
    auto picture = new TagLib::FLAC::Picture();
    picture->setMimeType(invalid);
    picture->setDescription(invalid);
    picture->setData(TagLib::ByteVector("\x01\x02\x03", 3));
    tag.addPicture(picture);
    result = tag.render(false);
  } else return 2;
  std::ofstream output(argv[3], std::ios::binary);
  output.write(result.data(), result.size());
  return output ? 0 : 4;
}
