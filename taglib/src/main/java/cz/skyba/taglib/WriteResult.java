// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

/// Result of saving a complete text property replacement. Applied values were accepted for saving;
/// rejected values were not representable. Applied values are not a read-back snapshot.
///
/// @param applied text values accepted for saving
/// @param rejected text values that could not be represented
public record WriteResult(PropertyMap applied, PropertyMap rejected) {
}
