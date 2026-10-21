// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import java.util.List;
import java.util.Map;

/// An immutable description of a physical metadata entry and its editing capabilities.
/// The value list and constraints map are copied. IDs are local to the document session.
/// Inspect capability flags before constructing edits; opaque or malformed entries can
/// require explicit removal before other changes can be saved.
///
/// @param id              the session-local entry identifier
/// @param block           the containing tag block
/// @param key             the physical field key
/// @param standardKey     the mapped common-property key, or an empty string if unmapped
/// @param kind            the entry category, such as text or artwork
/// @param values          displayed text values
/// @param editable        whether text values can be edited
/// @param removable       whether this entry can be removed
/// @param renameable      whether its physical field key can be changed
/// @param multipleValues  whether a variable number of values is accepted
/// @param requiresRemoval whether this entry must be removed before other edits are saved
/// @param details         explanatory text about the entry
/// @param picture         artwork, or `null` for a non-artwork entry
/// @param constraints     format-specific editing constraints
/// @param projected       whether the entry participates in the common metadata projection
/// @param precedence      priority within that projection
public record MetadataEntry(
  String id,
  String block,
  String key,
  String standardKey,
  String kind,
  List<String> values,
  boolean editable,
  boolean removable,
  boolean renameable,
  boolean multipleValues,
  boolean requiresRemoval,
  String details,
  Picture picture,
  Map<String, Object> constraints,
  boolean projected,
  int precedence) {
  /// Creates an entry description, copying its value list and constraints map.
  public MetadataEntry {
    values = List.copyOf(values);
    constraints = Map.copyOf(constraints);
  }
}
