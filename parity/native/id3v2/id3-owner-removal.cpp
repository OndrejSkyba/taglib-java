// Deliberate native undefined removals; the WHOLE library must share the checked ABI.
#include <string>
#include "id3v2tag.h"
#include "chapterframe.h"
#include "textidentificationframe.h"

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  auto f = new TagLib::ID3v2::TextIdentificationFrame("TIT2");
  std::string mode = argv[1];
  if (mode == "tag-missing") {
    TagLib::ID3v2::Tag t;
    t.removeFrame(f, false);
  } else if (mode == "tag-stale") {
    TagLib::ID3v2::Tag t;
    t.addFrame(f);
    f->header()->setFrameID("TPE1");
    t.removeFrame(f, false);
  } else if (mode == "chapter-missing") {
    TagLib::ID3v2::ChapterFrame t("c", 0, 0, 0, 0);
    t.removeEmbeddedFrame(f, false);
  } else if (mode == "chapter-stale") {
    TagLib::ID3v2::ChapterFrame t("c", 0, 0, 0, 0);
    t.addEmbeddedFrame(f);
    f->header()->setFrameID("TPE1");
    t.removeEmbeddedFrame(f, false);
  } else return 3;
  delete f;
  return 0;
}
