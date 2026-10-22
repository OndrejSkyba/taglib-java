// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import java.util.List;

@FunctionalInterface
interface PictureEdit {
  void apply(List<Picture> pictures);
}
