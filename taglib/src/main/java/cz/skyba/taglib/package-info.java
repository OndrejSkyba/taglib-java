// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
/// A pure Java audio metadata library targeting TagLib semantics.
///
/// [cz.skyba.taglib.TagLib] provides high-level access to audio metadata.
/// Saves update files in place. Paths are opened only for the duration of the
/// operation. Independent files can be processed concurrently; writes through this API are
/// serialized for the same resolved path.
/// Metadata allocation limits are configurable per read.
package cz.skyba.taglib;
