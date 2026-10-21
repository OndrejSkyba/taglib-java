// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.toolkit;


import cz.skyba.taglib.TagLib;

/// A version with three unsigned eight-bit components.
/// Construction retains each component's low eight bits. Comparison orders major, minor and
/// patch components numerically.
public record VersionNumber(int majorVersion, int minorVersion, int patchVersion)
  implements Comparable<VersionNumber> {
  /// Creates a version, retaining the low eight bits of each supplied component.
  public VersionNumber {
    majorVersion &= 255;
    minorVersion &= 255;
    patchVersion &= 255;
  }

  /// Creates a version, retaining the low eight bits of each supplied component.
  /// The patch component is zero.
  public VersionNumber(int major, int minor) {
    this(major, minor, 0);
  }

  /// Packs the version into 24 bits, with major in bits 16 to 23, minor in bits 8 to 15
  /// and patch in bits 0 to 7.
  ///
  /// @return the packed version
  public int combinedVersion() {
    return majorVersion << 16 | minorVersion << 8 | patchVersion;
  }

  /// Returns the format compatibility version reported by this library.
  /// This value is independent of the Maven artifact version.
  ///
  /// @return the compatibility version
  public static VersionNumber runtimeVersion() {
    String[] version = TagLib.UPSTREAM_VERSION.split("\\.");
    return new VersionNumber(
      Integer.parseInt(version[0]), Integer.parseInt(version[1]), Integer.parseInt(version[2]));
  }

  @Override
  public String toString() {
    return majorVersion + "." + minorVersion + "." + patchVersion;
  }

  @Override
  public int compareTo(VersionNumber other) {
    return Integer.compare(combinedVersion(), other.combinedVersion());
  }
}
