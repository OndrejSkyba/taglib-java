// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.matroska;

/// Metadata layout policy used when rendering and saving elements.
/// Compact permits size changes, DoNotShrink preserves occupied spans when shrinking, and
/// AvoidInsert moves growing nonfinal elements to an append location when configured.
public enum WriteStyle {Compact, DoNotShrink, AvoidInsert}
