// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import java.io.Serial;

/// Audio metadata could not be read or decoded.
/// Storage failures use [java.io.UncheckedIOException] with the original I/O cause.
public class MetadataReadException extends TagLibException {
  @Serial
  private static final long serialVersionUID = 1L;

  public MetadataReadException(String message) {
    super(message);
  }

  public MetadataReadException(String message, Throwable cause) {
    super(message, cause);
  }
}
