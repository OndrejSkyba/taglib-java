// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

/// An operation failed for a reason specific to audio metadata. Storage failures use
/// [java.io.UncheckedIOException]. Invalid arguments and unsupported operations use
/// the corresponding standard Java exceptions.
public class TagLibException extends RuntimeException {
  public TagLibException(String message) {
    super(message);
  }

  public TagLibException(String message, Throwable cause) {
    super(message, cause);
  }
}
