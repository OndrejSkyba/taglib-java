// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.toolkit;

import java.util.Locale;
import java.util.Objects;

/**
 * Shared diagnostic mode and listener configuration.
 * Diagnostics are disabled by default. Enabling a mode routes messages to the configured
 * listener, which defaults to UTF-8 output on standard error. Listeners can be called from
 * multiple threads and must handle their own synchronization.
 */
public final class Debug {
  /**
   * Diagnostic output policy. {@code RELEASE} disables messages; the other modes enable listener dispatch.
   */
  public enum Mode {
    RELEASE,
    DEBUG,
    TRACE_IN_RELEASE
  }

  private static final DebugListener DEFAULT_LISTENER =
    new DebugListener() {
      @Override
      public void printMessage(TagString message) {
        // Native POSIX std::cerr << String writes UTF-8, including embedded NULs.
        byte[] bytes = message.to8Bit(true);
        System.err.write(bytes, 0, bytes.length);
        System.err.flush();
      }
    };

  private static volatile DebugListener listener = DEFAULT_LISTENER;
  private static volatile Mode mode = Mode.RELEASE;

  private Debug() {
  }

  /**
   * Installs a listener in the shared registry without changing the diagnostic mode.
   * The listener is borrowed and is not closed by the library.
   *
   * @param value the listener, or {@code null} to restore standard-error output
   */
  public static void setDebugListener(DebugListener value) {
    listener = value == null ? DEFAULT_LISTENER : value;
  }

  public static Mode mode() {
    return mode;
  }

  /**
   * Changes the shared diagnostic mode without replacing the listener.
   *
   * @param value the output policy
   */
  public static void setMode(Mode value) {
    mode = Objects.requireNonNull(value);
  }

  /**
   * Dispatches a diagnostic message when output is enabled. Text ends at its first NUL
   * and is sent with a {@code TagLib: } prefix and a trailing newline. Listener failures propagate.
   *
   * @param message the text to dispatch
   */
  public static void debug(TagString message) {
    if (mode == Mode.RELEASE) {
      return;
    }
    String text = message.toString();
    // The native second operator+ detaches a shared String through a wide C-string,
    // truncating at its first NUL before the newline is appended.
    int nul = text.indexOf('\0');
    listener.printMessage(new TagString("TagLib: " + (nul < 0 ? text : text.substring(0, nul)) + "\n"));
  }

  /**
   * Dispatches a byte-by-byte diagnostic dump when output is enabled. Each entry includes
   * its index, character, signed integer, hexadecimal value and bit pattern. Listener failures propagate.
   *
   * @param value the bytes to inspect
   */
  public static void debugData(ByteVector value) {
    if (mode == Mode.RELEASE) {
      return;
    }
    for (int i = 0; i < value.size(); i++) {
      byte b = value.get(i);
      String bits = String.format(Locale.ROOT, "%8s", Integer.toBinaryString(b & 255)).replace(' ', '0');
      // Follow the pinned Linux native signed-char promotion, including %02x's
      // eight hex digits for negative bytes. The native String C-string constructor
      // truncates the formatted diagnostic when %c inserts a NUL byte.
      String text = String.format(Locale.ROOT, "*** [%d] - char '%c' - int %d, 0x%02x, 0b%s\n",
        i, (char) (b & 255), (int) b, (int) b, bits);
      int nul = text.indexOf('\0');
      listener.printMessage(new TagString(nul < 0 ? text : text.substring(0, nul)));
    }
  }
}
