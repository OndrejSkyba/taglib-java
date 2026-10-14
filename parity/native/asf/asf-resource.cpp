// Ordinary, valid file workloads only; no native boundary or crash operations.
#include <asffile.h>
#include <asftag.h>
#include <asfpicture.h>
#include <asfattribute.h>
#include <cstdlib>
#include <iostream>
#include <string>
using namespace TagLib;

int main(int argc, char **argv) {
  if (argc != 4) return 2;
  unsigned int bytes = std::stoul(argv[2]), count = std::stoul(argv[3]);
  ASF::File file(argv[1]);
  if (!file.isValid()) return 3;
  ASF::Picture picture;
  picture.setMimeType("image/test");
  picture.setPicture(ByteVector(bytes, char(0x5a)));
  file.tag()->setAttribute("WM/Picture", ASF::Attribute(picture));
  for (unsigned int i = 0; i < count; ++i)
    file.tag()->setAttribute(String("Resource/") + String::number(i), ASF::Attribute(i));
  if (!file.save()) return 4;
  file.tag()->setTitle(String(std::string(32000, 'x')));
  if (!file.save()) return 5;
  file.tag()->setTitle("small");
  if (!file.save()) return 6;
  std::cout << "ASF ordinary resource native PASS\n";
}
