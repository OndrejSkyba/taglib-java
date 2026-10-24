// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.toolkit;

/// A version with three unsigned eight-bit components.
/// Construction retains each component's low eight bits. Comparison orders major, minor and
/// patch components numerically.
///
/// @param majorVersion the major version component
/// @param minorVersion the minor version component
/// @param patchVersion the patch version component
public record VersionNumber(int majorVersion, int minorVersion, int patchVersion)
  implements Comparable<VersionNumber> {
  /// Creates a version, retaining the low eight bits of each supplied component.
  ///
  /// @param majorVersion the major version component
  /// @param minorVersion the minor version component
  /// @param patchVersion the patch version component
  public VersionNumber {
    majorVersion &= 255;
    minorVersion &= 255;
    patchVersion &= 255;
  }

  /// Creates a version, retaining the low eight bits of each supplied component.
  /// The patch component is zero.
  ///
  /// @param major the major version component
  /// @param minor the minor version component
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

  @Override
  public String toString() {
    return majorVersion + "." + minorVersion + "." + patchVersion;
  }

  @Override
  public int compareTo(VersionNumber other) {
    return Integer.compare(combinedVersion(), other.combinedVersion());
  }
}
