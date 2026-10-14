// Isolated empty-list dereferences in unchanged pinned ASF getters.
#include <iostream>
#include <asftag.h>
#include <asfattribute.h>
using namespace TagLib;

int main(int argc, char **argv) {
  ASF::Tag t;
  std::string mode(argv[1]);
  String key = mode == "year" ? "WM/Year" : mode == "track" ? "WM/TrackNumber" : "WM/Track";
  t.setAttribute(key, ASF::AttributeList());
  std::cout << "before\t" << mode << "\t" << t.attributeListMap().size() << std::endl;
  std::cout << (mode == "year" ? t.year() : t.track()) << std::endl;
}
