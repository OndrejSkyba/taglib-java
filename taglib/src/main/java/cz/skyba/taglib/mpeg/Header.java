// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mpeg;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.internal.MpegHeaderData;
import cz.skyba.taglib.toolkit.ByteVector;

import java.util.Objects;

/**
 * A parsed MPEG audio frame header. Validation can also inspect the next frame header.
 */
public final class Header {
  private static final Version[] VERSION_VALUES = Version.values();
  private static final ChannelMode[] CHANNELMODE_VALUES = ChannelMode.values();
  private static final ChannelConfiguration[] CHANNELCONFIGURATION_VALUES = ChannelConfiguration.values();

  public enum Version {Version1, Version2, Version2_5, Version4}

  public enum ChannelMode {Stereo, JointStereo, DualChannel, SingleChannel}

  public enum ChannelConfiguration {
    Custom, FrontCenter, FrontLeftRight, FrontCenterLeftRight,
    FrontCenterLeftRightBackCenter, FrontCenterLeftRightBackLeftRight,
    FrontCenterLeftRightBackLeftRightLFE,
    FrontCenterLeftRightSideLeftRightBackLeftRightLFE
  }

  private final MpegHeaderData data;

  /**
   * Decodes header fields from borrowed file storage.
   *
   * @param file   the borrowed source
   * @param offset the absolute header byte offset
   */
  public Header(FileBase file, long offset) {
    this(file, offset, true);
  }

  /**
   * Decodes header fields from borrowed file storage. Next-frame validation compares version, layer and sampling-rate fields when requested.
   *
   * @param file        the borrowed source
   * @param offset      the absolute header byte offset
   * @param checkLength whether to validate the next frame header
   */
  public Header(FileBase file, long offset, boolean checkLength) {
    Objects.requireNonNull(file);
    file.seek(offset);
    ByteVector first = file.readBlock(4);
    byte[] bytes = first.toByteArray();
    if (MpegHeaderData.needsADTSLength(bytes)) {
      bytes = new ByteVector(first).append(file.readBlock(2)).toByteArray();
    }
    var parsed = MpegHeaderData.parse(bytes, 0);
    if (parsed.valid() && checkLength) {
      boolean valid = false;
      if (parsed.frameLength() != 0) {
        file.seek(Math.addExact(offset, parsed.frameLength()));
        var next = file.readBlock(4);
        valid = next.size() == 4
          && (first.toUnsignedInt() & 0xfffe0c00L) == (next.toUnsignedInt() & 0xfffe0c00L);
      }
      parsed = parsed.withValidity(valid);
    }
    data = parsed;
  }

  /**
   * Creates a copied parsed header value.
   *
   * @param other the parsed value to copy
   */
  public Header(Header other) {
    data = Objects.requireNonNull(other).data;
  }

  public boolean isValid() {
    return data.valid();
  }

  public Version version() {
    return VERSION_VALUES[data.version()];
  }

  public int layer() {
    return data.layer();
  }

  public boolean protectionEnabled() {
    return data.protectionEnabled();
  }

  public int bitrate() {
    return data.bitrate();
  }

  public int sampleRate() {
    return data.sampleRate();
  }

  public boolean isPadded() {
    return data.padded();
  }

  public ChannelMode channelMode() {
    return CHANNELMODE_VALUES[data.channelMode()];
  }

  public ChannelConfiguration channelConfiguration() {
    return CHANNELCONFIGURATION_VALUES[data.channelConfiguration()];
  }

  public boolean isADTS() {
    return data.isADTS();
  }

  public boolean isCopyrighted() {
    return data.copyrighted();
  }

  public boolean isOriginal() {
    return data.original();
  }

  public int frameLength() {
    return data.frameLength();
  }

  public int samplesPerFrame() {
    return data.samplesPerFrame();
  }
}
