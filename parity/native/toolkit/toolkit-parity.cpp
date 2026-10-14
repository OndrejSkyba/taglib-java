#include <iostream>
#include <tpropertymap.h>
#include <tstring.h>
using namespace TagLib;

int main() {
  PropertyMap unicode;
  unicode.insert(String("éx", String::UTF8), StringList("v"));
  std::cout << "non_ascii_key=" << unicode.begin()->first.to8Bit(true) << '\n';
  PropertyMap equals;
  std::cout << "equals_key=" << (equals.insert("a=b", StringList("v")) ? "accepted" : "rejected") << '\n';
  SimplePropertyMap emptySource;
  emptySource.insert(String(), StringList("v"));
  PropertyMap empty(emptySource);
  std::cout << "empty_constructor_size=" << empty.size() << '\n';
  SimplePropertyMap collisionSource;
  collisionSource.insert("TITLE", StringList("upper"));
  collisionSource.insert("title", StringList("lower"));
  PropertyMap collision(collisionSource);
  std::cout << "case_collision_values=" << collision["TITLE"].toString("|").to8Bit(true) << '\n';
}
