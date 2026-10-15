// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Debug;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Bounded native-compatible zlib frame decoding.
 */
final class FrameCompression {
  private FrameCompression() {
  }

  static ByteVector decompress(ByteVector compressed, int limit) {
    try (var inflater = new Inflater()) {
      inflater.setInput(compressed.data());
      var out = new ByteVectorOutput();
      byte[] chunk = new byte[1024];
      int count;
      do {
        count = inflater.inflate(chunk);
        if (inflater.needsDictionary()) {
          Debug.debug("zlib::decompress() - Error reading compressed stream.");
          return new ByteVector();
        }
        int retained = Math.min(count, limit - out.size());
        out.append(chunk, 0, retained);
        if ((long) out.size() + count - retained > limit) {
          Debug.debug("zlib::decompress() - Too long compressed stream.");
          break;
        }
      } while (count == chunk.length);
      return out.toByteVector();
    } catch (DataFormatException invalid) {
      Debug.debug("zlib::decompress() - Error reading compressed stream.");
      return new ByteVector();
    }
  }

}
