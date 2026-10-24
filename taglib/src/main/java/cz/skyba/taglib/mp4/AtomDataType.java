// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

/// An MP4 data-atom type code in the unsigned byte range.
/// Named constants cover known encodings; unnamed codes remain representable.
///
/// @param value the code from 0 to 255
public record AtomDataType(int value) {
  /// Creates a data type code.
  ///
  /// @param value the data type code, from 0 to 255
  /// @throws IllegalArgumentException if the value is outside 0 to 255
  public AtomDataType {
    if (value < 0 || value > 255) {
      throw new IllegalArgumentException("Atom data type out of range");
    }
  }

  public static final AtomDataType TypeImplicit = new AtomDataType(0), TypeUTF8 = new AtomDataType(1),
    TypeUTF16 = new AtomDataType(2), TypeSJIS = new AtomDataType(3), TypeHTML = new AtomDataType(6),
    TypeXML = new AtomDataType(7), TypeUUID = new AtomDataType(8), TypeISRC = new AtomDataType(9),
    TypeMI3P = new AtomDataType(10), TypeGIF = new AtomDataType(12), TypeJPEG = new AtomDataType(13),
    TypePNG = new AtomDataType(14), TypeURL = new AtomDataType(15), TypeDuration = new AtomDataType(16),
    TypeDateTime = new AtomDataType(17), TypeGenred = new AtomDataType(18), TypeInteger = new AtomDataType(21),
    TypeRIAAPA = new AtomDataType(24), TypeUPC = new AtomDataType(25), TypeBMP = new AtomDataType(27),
    TypeUndefined = new AtomDataType(255);

  /// Maps an atom flag value to a data type code.
  ///
  /// @param flags the complete type flag value
  /// @return the code, or TypeUndefined when the value is outside 0 to 255
  public static AtomDataType fromFlags(int flags) {
    return flags >= 0 && flags <= 255 ? new AtomDataType(flags) : TypeUndefined;
  }
}
