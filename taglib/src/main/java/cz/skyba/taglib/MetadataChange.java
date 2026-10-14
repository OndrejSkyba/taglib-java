// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import java.util.List;

/**
 * A requested edit to a physical metadata entry.
 * IDs refer to one document session. Values are copied when non-null. Validation is performed
 * by {@link MetadataDocument#apply(List)}; removal does not require a key or values.
 *
 * @param action {@code add}, {@code update} or {@code remove}
 * @param id     the target entry ID for update or removal; unused for addition
 * @param key    the text field key for addition or update
 * @param values replacement text values for addition or update
 * @param custom whether an addition uses a format-specific field name
 */
public record MetadataChange(
  String action, String id, String key, List<String> values, boolean custom) {
  /**
   * Creates an edit request, copying the value list when it is non-null.
   */
  public MetadataChange {
    if (values != null) {
      values = List.copyOf(values);
    }
  }
}
