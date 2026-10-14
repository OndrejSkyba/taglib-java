// Linked with the exact upstream RelativeVolumeFrame translation unit instrumented.
#include <cstring>
#include <iostream>
#include "relativevolumeframe.h"

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  unsigned int n = static_cast<unsigned int>(std::stoul(argv[1], nullptr, 16));
  float f;
  std::memcpy(&f, &n, 4);
  TagLib::ID3v2::RelativeVolumeFrame frame;
  frame.setVolumeAdjustment(f);
  std::cout << frame.volumeAdjustmentIndex() << '\n';
}
