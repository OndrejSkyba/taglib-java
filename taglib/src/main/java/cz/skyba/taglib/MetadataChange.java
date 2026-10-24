// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import java.util.List;

/// A requested edit to a physical metadata entry.
/// Entry IDs belong to one document session. Non-null value lists are copied and must contain
/// no null elements. Edit validity is checked by [MetadataDocument#apply(List)].
public sealed interface MetadataChange {
  /// A request to add a text field.
  ///
  /// @param key the text field key
  /// @param values the text values
  /// @param custom whether the key is a format-specific field name
  record Add(String key, List<String> values, boolean custom) implements MetadataChange {
    public Add {
      if (values != null) {
        values = List.copyOf(values);
      }
    }
  }

  /// A request to replace an existing text field's key and values.
  ///
  /// @param id the target entry ID
  /// @param key the replacement text field key
  /// @param values the replacement text values
  record Update(String id, String key, List<String> values) implements MetadataChange {
    public Update {
      if (values != null) {
        values = List.copyOf(values);
      }
    }
  }

  /// A request to remove an existing entry.
  ///
  /// @param id the target entry ID
  record Remove(String id) implements MetadataChange {
  }
}
