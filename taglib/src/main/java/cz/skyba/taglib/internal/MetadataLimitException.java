// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.MetadataReadException;

import java.io.Serial;

/**
 * A metadata allocation or aggregate byte budget was exceeded.
 */
public final class MetadataLimitException extends MetadataReadException {
  @Serial
  private static final long serialVersionUID = 1L;

  public MetadataLimitException(String message) {
    super(message);
  }
}
