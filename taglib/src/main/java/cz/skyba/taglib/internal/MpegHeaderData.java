// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

/// Shared native header decoding; an invalid result retains fields parsed before the failure.
public record MpegHeaderData(
  boolean valid, int version, int layer, boolean protectionEnabled, int bitrate,
  int sampleRate, boolean padded, int channelMode, int channelConfiguration,
  boolean copyrighted, boolean original, int frameLength, int samplesPerFrame) {
  private static final int[][][] BITRATES = {
    {
      {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0},
      {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0},
      {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0}
    },
    {
      {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0},
      {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0},
      {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0}
    }
  };
  private static final int[][] SAMPLE_RATES = {
    {44100, 48000, 32000, 0}, {22050, 24000, 16000, 0}, {11025, 12000, 8000, 0}
  };
  private static final int[] ADTS_RATES = {
    96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
    16000, 12000, 11025, 8000, 7350, 0, 0, 0
  };

  public boolean isADTS() {
    return layer == 0 && (version == 1 || version == 3);
  }

  public MpegHeaderData withValidity(boolean value) {
    return new MpegHeaderData(value, version, layer, protectionEnabled, bitrate, sampleRate,
      padded, channelMode, channelConfiguration, copyrighted, original, frameLength,
      samplesPerFrame);
  }

  public static boolean isFrameSync(byte[] data, int at) {
    return at >= 0 && at <= data.length - 2 && (data[at] & 255) == 255
      && (data[at + 1] & 255) != 255 && (data[at + 1] & 224) == 224;
  }

  /// Native Header reads two more bytes only after recognizing the ADTS version/layer bits.
  public static boolean needsADTSLength(byte[] data) {
    if (data.length < 4 || !isFrameSync(data, 0)) {
      return false;
    }
    int bits = data[1] & 255;
    return ((bits >>> 1) & 3) == 0 && ((bits >>> 3) & 3) >= 2;
  }

  public static MpegHeaderData parse(byte[] data, int at) {
    var s = new State();
    if (at < 0 || at > data.length - 4 || !isFrameSync(data, at)) {
      return s.freeze();
    }
    int b1 = data[at + 1] & 255, b2 = data[at + 2] & 255, b3 = data[at + 3] & 255;
    int versionBits = (b1 >>> 3) & 3;
    if (versionBits == 0) {
      s.version = 2;
    } else if (versionBits == 2) {
      s.version = 1;
    } else if (versionBits != 3) {
      return s.freeze();
    }
    int layerBits = (b1 >>> 1) & 3;
    if (layerBits != 0) {
      s.layer = 4 - layerBits;
    } else if (versionBits == 2) {
      s.version = 3;
    } else if (versionBits == 3) {
      s.version = 1;
    } else {
      return s.freeze();
    }
    s.protectionEnabled = (b1 & 1) == 0;
    if (s.layer == 0) {
      s.sampleRate = ADTS_RATES[(b2 >>> 2) & 15];
      s.samplesPerFrame = 1024;
      s.channelConfiguration = ((b3 >>> 6) & 3) | ((b2 << 2) & 4);
      s.channelMode = s.channelConfiguration == 1 ? 3 : 0;
      s.original = (b3 & 0x20) != 0;
      s.copyrighted = (b3 & 4) != 0;
      if (at <= data.length - 6) {
        s.frameLength = ((b3 & 3) << 11) | ((data[at + 4] & 255) << 3)
          | ((data[at + 5] & 255) >>> 5);
        s.bitrate = (int) (s.frameLength * s.sampleRate / 1024.0 + 0.5) * 8 / 1000;
      }
    } else {
      int versionIndex = s.version == 0 ? 0 : 1, layerIndex = s.layer - 1;
      s.bitrate = BITRATES[versionIndex][layerIndex][b2 >>> 4];
      if (s.bitrate == 0) {
        return s.freeze();
      }
      s.sampleRate = SAMPLE_RATES[s.version][(b2 >>> 2) & 3];
      if (s.sampleRate == 0) {
        return s.freeze();
      }
      s.channelMode = b3 >>> 6;
      s.original = (b3 & 4) != 0;
      s.copyrighted = (b3 & 8) != 0;
      s.padded = (b2 & 2) != 0;
      s.samplesPerFrame = s.layer == 1 ? 384 : s.layer == 2 || versionIndex == 0 ? 1152 : 576;
      s.frameLength = s.samplesPerFrame * s.bitrate * 125 / s.sampleRate;
      if (s.padded) {
        s.frameLength += s.layer == 1 ? 4 : 1;
      }
    }
    s.valid = true;
    return s.freeze();
  }

  private static final class State {
    boolean valid, protectionEnabled, padded, copyrighted, original;
    int version, layer, bitrate, sampleRate, channelMode, channelConfiguration,
      frameLength, samplesPerFrame;

    MpegHeaderData freeze() {
      return new MpegHeaderData(valid, version, layer, protectionEnabled, bitrate, sampleRate,
        padded, channelMode, channelConfiguration, copyrighted, original, frameLength,
        samplesPerFrame);
    }
  }
}
