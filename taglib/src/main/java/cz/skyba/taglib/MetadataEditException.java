// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import java.io.Serial;

/// An edit cannot be represented safely; no source file has been changed.
public class MetadataEditException extends TagLibException {
  @Serial
  private static final long serialVersionUID = 1L;

  public MetadataEditException(String message) {
    super(message);
  }
}
