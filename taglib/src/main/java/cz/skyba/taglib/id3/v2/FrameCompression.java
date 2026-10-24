// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.toolkit.ByteVector;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static java.lang.System.Logger.Level.DEBUG;

/// Size-limited zlib decoding for compressed ID3v2 frames.
final class FrameCompression {
  private static final System.Logger LOG = System.getLogger(FrameCompression.class.getName());

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
          LOG.log(DEBUG, "Cannot decompress the frame payload.");
          return new ByteVector();
        }
        int retained = Math.min(count, limit - out.size());
        out.append(chunk, 0, retained);
        if ((long) out.size() + count - retained > limit) {
          LOG.log(DEBUG, "Decompressed frame payload exceeds the size limit; truncating it.");
          break;
        }
      } while (count == chunk.length);
      return out.toByteVector();
    } catch (DataFormatException invalid) {
      LOG.log(DEBUG, "Cannot decompress the frame payload.");
      return new ByteVector();
    }
  }

}
