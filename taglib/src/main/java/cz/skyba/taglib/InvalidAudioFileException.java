// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

/// The input is malformed or its audio container is unrecognized or unsupported.
public final class InvalidAudioFileException extends MetadataReadException {
  public InvalidAudioFileException(String message) {
    super(message);
  }

  public InvalidAudioFileException(String message, Throwable cause) {
    super(message, cause);
  }
}
