// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.toolkit.ByteVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// A mutable ID3v2 frame for timed events (ETCO).
/// Editing a frame does not write a file; use the owning file's save operation to persist changes.
public class EventTimingCodesFrame extends Frame {
  private static final TimestampFormat[] TIMESTAMP_FORMATS = TimestampFormat.values();

  /// The unit used by frame timestamps.
  public enum TimestampFormat {
    Unknown, AbsoluteMpegFrames, AbsoluteMilliseconds;

    public int code() {
      return ordinal();
    }
  }

  /// An event code with a named value when recognized. Every unsigned byte value can be represented.
  public static final class EventType {
    private final int code;

    private EventType(int value) {
      code = value;
    }

    private static final EventType[] VALUES = values();

    private static EventType[] values() {
      var values = new EventType[256];
      for (int i = 0; i < values.length; i++) {
        values[i] = new EventType(i);
      }
      return values;
    }

    public static final EventType Padding = VALUES[0];
    public static final EventType EndOfInitialSilence = VALUES[1];
    public static final EventType IntroStart = VALUES[2];
    public static final EventType MainPartStart = VALUES[3];
    public static final EventType OutroStart = VALUES[4];
    public static final EventType OutroEnd = VALUES[5];
    public static final EventType VerseStart = VALUES[6];
    public static final EventType RefrainStart = VALUES[7];
    public static final EventType InterludeStart = VALUES[8];
    public static final EventType ThemeStart = VALUES[9];
    public static final EventType VariationStart = VALUES[10];
    public static final EventType KeyChange = VALUES[11];
    public static final EventType TimeChange = VALUES[12];
    public static final EventType MomentaryUnwantedNoise = VALUES[13];
    public static final EventType SustainedNoise = VALUES[14];
    public static final EventType SustainedNoiseEnd = VALUES[15];
    public static final EventType IntroEnd = VALUES[16];
    public static final EventType MainPartEnd = VALUES[17];
    public static final EventType VerseEnd = VALUES[18];
    public static final EventType RefrainEnd = VALUES[19];
    public static final EventType ThemeEnd = VALUES[20];
    public static final EventType Profanity = VALUES[21];
    public static final EventType ProfanityEnd = VALUES[22];
    public static final EventType NotPredefinedSynch0 = VALUES[224];
    public static final EventType NotPredefinedSynch1 = VALUES[225];
    public static final EventType NotPredefinedSynch2 = VALUES[226];
    public static final EventType NotPredefinedSynch3 = VALUES[227];
    public static final EventType NotPredefinedSynch4 = VALUES[228];
    public static final EventType NotPredefinedSynch5 = VALUES[229];
    public static final EventType NotPredefinedSynch6 = VALUES[230];
    public static final EventType NotPredefinedSynch7 = VALUES[231];
    public static final EventType NotPredefinedSynch8 = VALUES[232];
    public static final EventType NotPredefinedSynch9 = VALUES[233];
    public static final EventType NotPredefinedSynchA = VALUES[234];
    public static final EventType NotPredefinedSynchB = VALUES[235];
    public static final EventType NotPredefinedSynchC = VALUES[236];
    public static final EventType NotPredefinedSynchD = VALUES[237];
    public static final EventType NotPredefinedSynchE = VALUES[238];
    public static final EventType NotPredefinedSynchF = VALUES[239];
    public static final EventType AudioEnd = VALUES[253];
    public static final EventType AudioFileEnds = VALUES[254];

    public int code() {
      return code;
    }

    /// Maps an unsigned byte to an event type without discarding unnamed codes.
    ///
    /// @param value the encoded event byte
    /// @return the corresponding event code
    public static EventType fromByte(byte value) {
      return VALUES[value & 255];
    }

    @Override
    public boolean equals(Object value) {
      return value instanceof EventType other && code == other.code;
    }

    @Override
    public int hashCode() {
      return code;
    }
  }

  /// An immutable timed entry. Timestamps retain their low 32 bits and use the containing frame's units.
  ///
  /// @param time the timestamp in the containing frame's selected units
  /// @param type the event type
  public record SynchedEvent(long time, EventType type) {
    /// Creates a timed entry, retaining the timestamp's low 32 bits.
    ///
    /// @throws NullPointerException if the event type is null
    public SynchedEvent {
      time &= 0xffff_ffffL;
      Objects.requireNonNull(type);
    }
  }

  private TimestampFormat format = TimestampFormat.AbsoluteMilliseconds;
  private final List<SynchedEvent> events = new ArrayList<>();

  public EventTimingCodesFrame() {
    super(new ByteVector("ETCO"));
  }

  /// Decodes ETCO timestamps and event codes from serialized frame bytes, including their frame header.
  ///
  /// @param data serialized bytes including the frame header
  public EventTimingCodesFrame(ByteVector data) {
    super(data);
    header().setData(data);
    parseEvents(fieldData(data));
  }

  EventTimingCodesFrame(ByteVector data, Header header) {
    super(header);
    parseEvents(fieldData(data));
  }

  public final TimestampFormat timestampFormat() {
    return format;
  }

  public final void setTimestampFormat(TimestampFormat value) {
    format = Objects.requireNonNull(value);
  }

  /// Returns an independent mutable list of immutable timed event entries.
  ///
  /// @return an independent list of entries
  public final List<SynchedEvent> synchedEvents() {
    return new ArrayList<>(events);
  }

  /// Replaces the timed event entries, copying the list and rejecting null entries.
  ///
  /// @param value replacement entries
  public final void setSynchedEvents(List<SynchedEvent> value) {
    var copy = List.copyOf(value);
    events.clear();
    events.addAll(copy);
  }

  @Override
  public String toString() {
    return "";
  }

  @Override
  protected void parseFields(ByteVector data) {
    parseEvents(data);
  }

  private void parseEvents(ByteVector data) {
    if (data.isEmpty()) {
      return;
    }
    int code = data.at(0) & 255;
    format = code < TIMESTAMP_FORMATS.length ? TIMESTAMP_FORMATS[code] : TimestampFormat.Unknown;
    events.clear();
    for (int pos = 1; pos <= data.size() - 5; pos += 5) {
      events.add(new SynchedEvent(data.toUnsignedInt(pos + 1, true), EventType.fromByte(data.at(pos))));
    }
  }

  @Override
  protected ByteVector renderFields() {
    var data = new ByteVectorOutput().append((byte) format.code());
    for (var entry : events) {
      data.append((byte) entry.type().code()).append(ByteVector.fromUnsignedInt(entry.time()));
    }
    return data.toByteVector();
  }
}
