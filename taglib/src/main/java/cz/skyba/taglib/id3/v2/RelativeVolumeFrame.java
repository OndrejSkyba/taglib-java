// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.toolkit.ByteVector;

import java.util.*;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/**
 * A mutable ID3v2 frame containing RVA2 channel volume adjustments and peaks.
 * Editing a frame does not write a file; use the owning file's save operation to persist changes.
 * Reparsing updates channels present in the bytes and retains other channel values.
 */
public class RelativeVolumeFrame extends Frame {
  private static final ChannelType[] CHANNEL_TYPES = ChannelType.values();

  /**
   * The channel used for relative volume adjustment.
   */
  public enum ChannelType {
    Other, MasterVolume, FrontRight, FrontLeft, BackRight, BackLeft, FrontCentre, BackCentre, Subwoofer;

    public int code() {
      return ordinal();
    }
  }

  /**
   * An immutable peak-volume value with copied amplitude bytes.
   * Construction retains the low eight bits of the represented bit count.
   *
   * @param bitsRepresentingPeak the represented amplitude bit count
   * @param peakVolume           encoded amplitude bytes, copied on construction and access
   */
  public record PeakVolume(int bitsRepresentingPeak, ByteVector peakVolume) {
    /**
     * Creates a zero-bit peak with empty amplitude bytes.
     */
    public PeakVolume() {
      this(0, new ByteVector());
    }

    /**
     * Creates a peak value, copying amplitude bytes and retaining the bit count's low eight bits.
     *
     * @throws NullPointerException if the amplitude bytes are null
     */
    public PeakVolume {
      bitsRepresentingPeak &= 255;
      peakVolume = new ByteVector(peakVolume);
    }

    /**
     * Returns an independent mutable copy of the amplitude bytes.
     *
     * @return copied amplitude bytes
     */
    @Override
    public ByteVector peakVolume() {
      return new ByteVector(peakVolume);
    }
  }

  private static final class Channel {
    short index;
    PeakVolume peak = new PeakVolume();
  }

  private String identification = "";
  private final NavigableMap<ChannelType, Channel> channels = new TreeMap<>();

  /**
   * Creates a frame containing RVA2 channel volume adjustments and peaks.
   */
  public RelativeVolumeFrame() {
    super(new ByteVector("RVA2"));
  }

  /**
   * Decodes RVA2 channel volume adjustments and peaks from serialized frame bytes, including their frame header.
   *
   * @param data serialized bytes including the frame header
   */
  public RelativeVolumeFrame(ByteVector data) {
    super(data);
    header().setData(data);
    parseRelativeVolume(fieldData(data));
  }

  RelativeVolumeFrame(ByteVector data, Header header) {
    super(header);
    parseRelativeVolume(fieldData(data));
  }

  public final String identification() {
    return identification;
  }

  public final void setIdentification(String value) {
    identification = Objects.requireNonNull(value);
  }

  @Override
  public String toString() {
    return identification;
  }

  /**
   * Returns a detached list of channels with stored volume data, ordered by channel code.
   *
   * @return the populated channel types
   */
  public final List<ChannelType> channels() {
    return new ArrayList<>(channels.keySet());
  }

  /**
   * Returns the signed 16-bit channel adjustment index, or zero when absent. The channel is master volume.
   *
   * @return the stored adjustment or copied peak value
   */
  public final short volumeAdjustmentIndex() {
    return volumeAdjustmentIndex(ChannelType.MasterVolume);
  }

  /**
   * Returns the signed 16-bit channel adjustment index, or zero when absent.
   *
   * @param type the channel to query or update
   * @return the stored adjustment or copied peak value
   */
  public final short volumeAdjustmentIndex(ChannelType type) {
    var channel = channels.get(Objects.requireNonNull(type));
    return channel == null ? 0 : channel.index;
  }

  /**
   * Sets the channel adjustment from the low 16 bits of an integer. The channel is master volume.
   *
   * @param index adjustment in units of 1/512 dB
   */
  public final void setVolumeAdjustmentIndex(int index) {
    setVolumeAdjustmentIndex(index, ChannelType.MasterVolume);
  }

  /**
   * Sets the channel adjustment from the low 16 bits of an integer.
   *
   * @param type  the channel to query or update
   * @param index adjustment in units of 1/512 dB
   */
  public final void setVolumeAdjustmentIndex(int index, ChannelType type) {
    channel(type).index = (short) index;
  }

  /**
   * Returns the channel adjustment in decibels, or zero when absent. The channel is master volume.
   *
   * @return the stored adjustment or copied peak value
   */
  public final float volumeAdjustment() {
    return volumeAdjustment(ChannelType.MasterVolume);
  }

  /**
   * Returns the channel adjustment in decibels, or zero when absent.
   *
   * @param type the channel to query or update
   * @return the stored adjustment or copied peak value
   */
  public final float volumeAdjustment(ChannelType type) {
    return volumeAdjustmentIndex(type) / 512.0f;
  }

  /**
   * Sets the channel adjustment in decibels, quantized to steps of 1/512 dB. The channel is master volume.
   *
   * @param adjustment the finite adjustment in decibels
   * @throws IllegalArgumentException if the scaled adjustment cannot be encoded as a signed 16-bit value
   */
  public final void setVolumeAdjustment(float adjustment) {
    setVolumeAdjustment(adjustment, ChannelType.MasterVolume);
  }

  /**
   * Sets the channel adjustment in decibels, quantized to steps of 1/512 dB.
   *
   * @param type       the channel to query or update
   * @param adjustment the finite adjustment in decibels
   * @throws IllegalArgumentException if the scaled adjustment cannot be encoded as a signed 16-bit value
   */
  public final void setVolumeAdjustment(float adjustment, ChannelType type) {
    Objects.requireNonNull(type);
    float scaled = adjustment * 512.0f;
    if (!Float.isFinite(scaled) || scaled <= -32769.0f || scaled >= 32768.0f) {
      throw new IllegalArgumentException("Native float-to-short conversion is undefined for this adjustment");
    }
    channel(type).index = (short) scaled;
  }

  /**
   * Returns the immutable peak value, or a zero-bit empty value when the channel is absent. The channel is master volume.
   *
   * @return the immutable peak value
   */
  public final PeakVolume peakVolume() {
    return peakVolume(ChannelType.MasterVolume);
  }

  /**
   * Returns the immutable peak value, or a zero-bit empty value when the channel is absent.
   *
   * @param type the channel to query or update
   * @return the immutable peak value
   */
  public final PeakVolume peakVolume(ChannelType type) {
    var channel = channels.get(Objects.requireNonNull(type));
    return channel == null ? new PeakVolume() : channel.peak;
  }

  /**
   * Replaces the selected channel's immutable peak value. The channel is master volume.
   *
   * @param peak the replacement peak value
   */
  public final void setPeakVolume(PeakVolume peak) {
    setPeakVolume(peak, ChannelType.MasterVolume);
  }

  /**
   * Replaces the selected channel's immutable peak value.
   *
   * @param type the channel to query or update
   * @param peak the replacement peak value
   */
  public final void setPeakVolume(PeakVolume peak, ChannelType type) {
    Objects.requireNonNull(peak);
    channel(type).peak = peak;
  }

  private Channel channel(ChannelType type) {
    return channels.computeIfAbsent(Objects.requireNonNull(type), _ -> new Channel());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void parseFields(ByteVector data) {
    parseRelativeVolume(data);
  }

  private void parseRelativeVolume(ByteVector data) {
    StringField field = stringField(data, ISO_8859_1, 0);
    identification = field.value();
    int pos = field.nextPosition();
    while (pos <= data.size() - 4) {
      int code = data.at(pos++) & 255;
      Channel channel = channel(code < CHANNEL_TYPES.length ? CHANNEL_TYPES[code] : ChannelType.Other);
      channel.index = data.toShort(pos);
      pos += 2;
      int bits = data.at(pos++) & 255;
      int count = (bits + 7) / 8;
      channel.peak = new PeakVolume(bits, data.mid(pos, count));
      // A truncated peak ends parsing; avoid overflowing native's int cursor at array limits.
      if (count > data.size() - pos) {
        break;
      }
      pos += count;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected ByteVector renderFields() {
    var data = new ByteVectorOutput().append(ByteVector.fromText(identification, ISO_8859_1)).append((byte) 0);
    for (var entry : channels.entrySet()) {
      Channel channel = entry.getValue();
      data.append((byte) entry.getKey().code()).append(ByteVector.fromShort(channel.index))
        .append((byte) channel.peak.bitsRepresentingPeak).append(channel.peak.peakVolume);
    }
    return data.toByteVector();
  }
}
