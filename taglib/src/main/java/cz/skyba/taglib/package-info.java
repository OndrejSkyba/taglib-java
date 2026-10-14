// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
/**
 * A pure Java audio metadata library targeting TagLib semantics.
 *
 * <p>{@link cz.skyba.taglib.TagLib} reads immutable snapshots and replaces complete text property maps.
 * Saves update files in place, following upstream. Paths are opened only for the duration of the
 * operation. Independent files can be
 * processed concurrently; writes through this API are serialized for the same resolved path.
 * Metadata allocation limits are configurable per read. Format-specific support and compatibility
 * results are documented in the repository; upstream version is a target, not a parity
 * certification.
 */
package cz.skyba.taglib;
