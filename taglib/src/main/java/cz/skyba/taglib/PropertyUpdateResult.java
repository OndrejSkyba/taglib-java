// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import java.util.ArrayList;

/// Result of replacing text properties in memory, without saving. Accepted values reflect the
/// format's mapping policy; they are not a read-back snapshot and may be normalized or truncated.
///
/// @param accepted text values accepted by the format mapping
/// @param rejected text values that could not be represented
public record PropertyUpdateResult(PropertyMap accepted, PropertyMap rejected) {
  /// Derives accepted values from the requested map and the values a tag did not consume.
  ///
  /// @param requested the requested replacement properties
  /// @param rejected the values that were not consumed
  /// @return the accepted and rejected text values
  public static PropertyUpdateResult fromRejections(PropertyMap requested, PropertyMap rejected) {
    if (rejected.isEmpty()) {
      return new PropertyUpdateResult(requested, rejected);
    }
    var accepted = requested.toBuilder();
    rejected.asMap().forEach((key, values) -> {
      var remaining = new ArrayList<>(requested.get(key));
      values.forEach(remaining::remove);
      if (remaining.isEmpty() || values.isEmpty()) {
        accepted.remove(key);
      } else {
        accepted.put(key, remaining);
      }
    });
    return new PropertyUpdateResult(accepted.build(), rejected);
  }
}
