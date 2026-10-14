// Deliberate pinned-native boundary: incrementing an empty std::list's end iterator.
// Compile BOTH this probe and the complete native library with _GLIBCXX_DEBUG.
#include "textidentificationframe.h"
#include "tpropertymap.h"

int main() {
  TagLib::ID3v2::UserTextIdentificationFrame frame;
  frame.TextIdentificationFrame::setText(TagLib::StringList());
  return frame.asProperties().size();
}
