// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;

/**
 * An ID3v2.2 PIC picture frame created by the frame factory.
 * Rendering emits an APIC frame with an ID3v2.4 header.
 */
public class AttachedPictureFrameV22 extends AttachedPictureFrame {
  AttachedPictureFrameV22(ByteVector data, Header header) {
    super();
    setHeader(header);
    parsePictureV22(fieldData(data));
    var replacement = new Header(new ByteVector("APIC"));
    replacement.setFrameSize(header.frameSize());
    setHeader(replacement);
  }

  @Override
  protected void parseFields(ByteVector data) {
    parsePictureV22(data);
  }
}
