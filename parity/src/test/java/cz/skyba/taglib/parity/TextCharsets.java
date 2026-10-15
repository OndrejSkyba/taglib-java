package cz.skyba.taglib.parity;

import java.nio.charset.Charset;
import java.util.List;

import static java.nio.charset.StandardCharsets.*;

/** Maps the native probe protocol's encoding numbers to Java charsets. */
public final class TextCharsets {
  public static final Charset[] ALL = {ISO_8859_1, UTF_16, UTF_16BE, UTF_8, UTF_16LE};

  private TextCharsets() {
  }

  public static int id(Charset charset) {
    int id = List.of(ALL).indexOf(charset);
    if (id < 0) throw new IllegalArgumentException("Unexpected probe charset: " + charset);
    return id;
  }
}
