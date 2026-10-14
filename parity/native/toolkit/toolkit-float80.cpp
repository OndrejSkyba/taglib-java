#include <cmath>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <limits>
#include <string>
#include <tbytevector.h>

static_assert(std::numeric_limits<long double>::digits == 64 &&
              std::numeric_limits<long double>::max_exponent == 16384,
              "This probe targets the pinned Linux x87 long-double behavior");

namespace {
  std::string canonical(long double value) {
    const bool negative = std::signbit(value);
    std::uint64_t significand = 0;
    int power = 0;
    if (value != 0) {
      int exponent;
      const long double fraction = std::frexp(std::fabs(value), &exponent);
      significand = static_cast<std::uint64_t>(std::ldexp(fraction, 64));
      power = exponent - 64;
      while ((significand & 1) == 0) {
        significand >>= 1;
        ++power;
      }
    }
    double projected = static_cast<double>(value);
    std::uint64_t bits;
    std::memcpy(&bits, &projected, 8);
    return std::string(negative ? "-" : "+") + std::to_string(significand) + "*2^" +
           std::to_string(power) + ";double=" + std::to_string(bits);
  }

  TagLib::ByteVector vector(unsigned exponent, std::uint64_t fraction, bool negative, bool big) {
    unsigned header = exponent | (negative ? 0x8000 : 0);
    TagLib::ByteVector bytes(10, 0);
    bytes[0] = static_cast<char>(header >> 8);
    bytes[1] = static_cast<char>(header);
    for (unsigned i = 0; i < 8; ++i)
      bytes[2 + i] = static_cast<char>(fraction >> (56 - 8 * i));
    if (!big) {
      for (unsigned i = 0; i < 5; ++i)
        std::swap(bytes[i], bytes[9 - i]);
    }
    return bytes;
  }
}

int main() {
  auto shortValue = TagLib::ByteVector(9, 0).toFloat80BE(0);
  std::cout << "short=" << canonical(shortValue) << '\n';
  auto beyond = TagLib::ByteVector(10, 0).toFloat80LE(1);
  std::cout << "beyond=" << canonical(beyond) << '\n';
  auto exceptional = vector(0x7fff, 0xc000000000000001ULL, true, true).toFloat80BE(0);
  std::cout << "exceptional=" << canonical(exceptional) << '\n';
  constexpr unsigned exponents[] = {
    0, 1, 2, 4096, 12288, 15306, 15307, 15308, 15309, 15310,
    16382, 16383, 16384, 16385, 17406, 17407, 17408, 32765, 32766, 32767
  };
  constexpr std::uint64_t fractions[] = {
    0, 1, 2, 3, 4, 5, 7, 15, 0x7fffffffffffffffULL,
    0x8000000000000000ULL, 0x8000000000000001ULL, 0x8000000000000003ULL,
    0xfffffffffffffffdULL, 0xfffffffffffffffeULL, 0xffffffffffffffffULL
  };
  unsigned row = 0;
  for (bool big: {true, false})
    for (bool negative: {false, true})
      for (unsigned exponent: exponents)
        for (std::uint64_t fraction: fractions) {
          auto bytes = vector(exponent, fraction, negative, big);
          auto value = big ? bytes.toFloat80BE(0) : bytes.toFloat80LE(0);
          std::cout << "float80." << row++ << '=' << canonical(value) << '\n';
        }
  auto framed = TagLib::ByteVector("xx");
  framed.append(vector(0x400e, 0xac44000000000000ULL, false, true));
  framed.append('!');
  std::cout << "offset=" << canonical(framed.toFloat80BE(2)) << '\n';
}
