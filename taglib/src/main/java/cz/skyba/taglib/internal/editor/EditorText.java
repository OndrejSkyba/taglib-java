// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.editor;

import cz.skyba.taglib.MetadataEditException;

import java.util.List;

final class EditorText {
  private EditorText() {
  }

  static void single(List<String> values) {
    if (values.size() != 1) {
      throw new MetadataEditException("This field requires exactly one value");
    }
  }

  static String mime(byte[] data) {
    if (data.length >= 4
      && data[0] == (byte) 0x89
      && data[1] == 'P'
      && data[2] == 'N'
      && data[3] == 'G') {
      return "image/png";
    }
    if (data.length >= 3
      && data[0] == (byte) 0xff
      && data[1] == (byte) 0xd8
      && data[2] == (byte) 0xff) {
      return "image/jpeg";
    }
    return "application/octet-stream";
  }
}
