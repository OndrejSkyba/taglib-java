// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.ConstructionReadStream;
import cz.skyba.taglib.internal.FailureSupport;
import cz.skyba.taglib.it.ItFile;
import cz.skyba.taglib.mod.FileBase;
import cz.skyba.taglib.mod.ModFile;
import cz.skyba.taglib.mod.Tag;
import cz.skyba.taglib.s3m.S3mFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.xm.XmFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/// Shared tracker parsing and saving for live file implementations. Read failures can leave parsed prefix fields available.
public abstract class TrackerFile extends FileBase {
  private static final Set<String> PROTRACKER_IDS = Set.of("M.K.", "M!K!", "M&K!", "N.T.");

  private sealed interface TrackerDetails permits ModState, S3mState, ItState, XmState {
    Object value();

    AudioProperties common();
  }

  private record ModState(cz.skyba.taglib.mod.Properties value) implements TrackerDetails {
    @Override
    public AudioProperties common() {
      return value.common();
    }
  }

  private record S3mState(cz.skyba.taglib.s3m.Properties value) implements TrackerDetails {
    @Override
    public AudioProperties common() {
      return value.common();
    }
  }

  private record ItState(cz.skyba.taglib.it.Properties value) implements TrackerDetails {
    @Override
    public AudioProperties common() {
      return value.common();
    }
  }

  private record XmState(cz.skyba.taglib.xm.Properties value) implements TrackerDetails {
    @Override
    public AudioProperties common() {
      return value.common();
    }
  }

  private final Format format;
  private final ConstructionReadStream source;
  private final boolean owned;
  private final Tag tag = new Tag();
  private final TrackerDetails details;
  private final long maximumWriteExtent;
  private TrackerWritePlan writePlan;

  protected TrackerFile(Path path, Format format, ReadStyle style) {
    Objects.requireNonNull(style);
    this(ConstructionReadStream.wrap(new FileStream(path), true), true, format, style);
  }

  protected TrackerFile(IOStream stream, Format format, ReadStyle style) {
    this(ConstructionReadStream.wrap(stream, false), false, format, style);
  }

  private TrackerFile(ConstructionReadStream stream, boolean owned, Format format, ReadStyle style) {
    super(stream);
    source = stream;
    this.owned = owned;
    this.format = format;
    maximumWriteExtent = stream.source() instanceof ByteVectorStream ? Integer.MAX_VALUE : Long.MAX_VALUE;
    try {
      details = initialDetails(format, style);
      // Custom owners retain their original virtual read/seek callback sequence.
      boolean defaultIo = stream.usesDefaultIo() || switch (format) {
        case MOD -> getClass() == ModFile.class;
        case S3M -> getClass() == S3mFile.class;
        case IT -> getClass() == ItFile.class;
        case XM -> getClass() == XmFile.class;
        default -> false;
      };
      stream.begin(defaultIo, Integer.MAX_VALUE);
      read();
    } catch (RuntimeException | Error failure) {
      if (owned) {
        FailureSupport.closeOnFailure(stream, failure);
      }
      throw failure;
    } finally {
      stream.finish();
    }
  }

  @Override
  public void close() {
    if (owned) {
      source.close();
    }
  }

  private static TrackerDetails initialDetails(Format format, ReadStyle style) {
    Objects.requireNonNull(style);
    return switch (format) {
      case MOD -> new ModState(new cz.skyba.taglib.mod.Properties(style));
      case S3M -> new S3mState(new cz.skyba.taglib.s3m.Properties(style));
      case IT -> new ItState(new cz.skyba.taglib.it.Properties(style));
      case XM -> new XmState(new cz.skyba.taglib.xm.Properties(style));
      default -> throw new IllegalArgumentException("Not a tracker format");
    };
  }

  @Override
  public Tag tag() {
    return tag;
  }

  protected final Object detailedState() {
    return details.value();
  }

  @Override
  public AudioProperties audioProperties() {
    return details.common();
  }

  private void read() {
    try {
      switch (format) {
        case MOD -> readMod();
        case S3M -> readS3m();
        case IT -> readIt();
        case XM -> readXm();
        default -> throw new AssertionError();
      }
    } catch (ReadFailure failure) {
      throw new InvalidAudioFileException("Invalid " + format + " tracker header: " + name(), failure);
    }
  }

  private static final class ReadFailure extends Exception {
  }

  private static void require(boolean condition) throws ReadFailure {
    if (!condition) {
      throw new ReadFailure();
    }
  }

  private ByteVector bytes(int count) throws ReadFailure {
    var value = readBlock(count);
    require(value.size() == count);
    return value;
  }

  private int u8() throws ReadFailure {
    return bytes(1).data()[0] & 0xff;
  }

  private int u16() throws ReadFailure {
    return bytes(2).toUnsignedShort(false);
  }

  private long u32() throws ReadFailure {
    return bytes(4).toUnsignedInt(false);
  }

  private String text(int width) throws ReadFailure {
    return trackerText(bytes(width));
  }

  private void skip(long amount) throws ReadFailure {
    try {
      seek(Math.addExact(tell(), amount));
    } catch (ArithmeticException badOffset) {
      throw new ReadFailure();
    }
  }

  private static String trackerText(ByteVector bytes) {
    int end = bytes.find((byte) 0);
    if (end >= 0) {
      bytes.resize(end);
    }
    for (int i = 0; i < bytes.size(); i++) {
      if (bytes.data()[i] == (byte) 0xff) {
        bytes.data()[i] = ' ';
      }
    }
    return bytes.decodeTerminatedText(ISO_8859_1);
  }

  private int orders(int count) throws ReadFailure {
    int length = 0;
    for (int i = 0; i < count; i++) {
      int order = u8();
      if (order == 255) {
        break;
      }
      if (order != 254) {
        length++;
      }
    }
    return length;
  }

  private void readMod() throws ReadFailure {
    var p = ((ModState) details).value();
    seek(1080);
    String id = new String(bytes(4).data(), StandardCharsets.ISO_8859_1);
    int channels = 4, instruments = 31;
    if (PROTRACKER_IDS.contains(id)) {
      tag.setTrackerName("ProTracker");
    } else if (id.startsWith("FLT") || id.startsWith("TDZ")) {
      tag.setTrackerName("StarTrekker");
      require(id.charAt(3) >= '0' && id.charAt(3) <= '9');
      channels = id.charAt(3) - '0';
    } else if (id.endsWith("CHN")) {
      tag.setTrackerName("StarTrekker");
      require(id.charAt(0) >= '0' && id.charAt(0) <= '9');
      channels = id.charAt(0) - '0';
    } else if (id.equals("CD81") || id.equals("OKTA")) {
      tag.setTrackerName("Atari Oktalyzer");
      channels = 8;
    } else if (id.endsWith("CH") || id.endsWith("CN")) {
      tag.setTrackerName("TakeTracker");
      require(id.charAt(0) >= '0' && id.charAt(0) <= '9');
      channels = (id.charAt(0) - '0') * 10;
      require(id.charAt(1) >= '0' && id.charAt(1) <= '9');
      channels += id.charAt(1) - '0';
    } else {
      tag.setTrackerName("NoiseTracker");
      instruments = 15;
    }
    p.setChannels(channels);
    p.setInstrumentCount(instruments);
    seek(0);
    tag.setTitle(text(20));
    var comment = new ArrayList<String>();
    for (int i = 0; i < instruments; i++) {
      comment.add(text(22));
      seek(20 + (i + 1) * 30L);
    }
    p.setLengthInPatterns(u8());
    tag.setComment(String.join("\n", comment));
  }

  private void readS3m() throws ReadFailure {
    var p = ((S3mState) details).value();
    tag.setTitle(text(28));
    int mark = u8(), type = u8();
    require(mark == 26 && type == 16);
    seek(32);
    int length = u16(), samples = u16();
    p.setSampleCount(samples);
    p.setPatternCount(u16());
    p.setFlags(u16());
    p.setTrackerVersion(u16());
    p.setFileFormatVersion(u16());
    require(bytes(4).equals(new ByteVector("SCRM")));
    p.setGlobalVolume(u8());
    p.setBpmSpeed(u8());
    p.setTempo(u8());
    int masterVolume = u8();
    p.setMasterVolume(masterVolume & 127);
    p.setStereo((masterVolume & 128) != 0);
    skip(12);
    int channels = 0;
    for (int i = 0; i < 32; i++) {
      if (u8() != 255) {
        channels++;
      }
    }
    p.setChannels(channels);
    seek(96);
    p.setLengthInPatterns(orders(length));
    skip(channels);
    var comment = new ArrayList<String>();
    for (int i = 0; i < samples; i++) {
      seek(96L + length + i * 2L);
      long sampleOffset = u16() * 16L;
      seek(sampleOffset);
      u8();
      text(13);
      u16();
      u32();
      u32();
      u32();
      u8();
      skip(1);
      u8();
      u8();
      u32();
      skip(12);
      comment.add(text(28));
    }
    tag.setComment(String.join("\n", comment));
    tag.setTrackerName("ScreamTracker III");
  }

  private void readIt() throws ReadFailure {
    var p = ((ItState) details).value();
    seek(0);
    require(bytes(4).equals(new ByteVector("IMPM")));
    tag.setTitle(text(26));
    skip(2);
    int length = u16(), instruments = u16(), samples = u16();
    p.setInstrumentCount(instruments);
    p.setSampleCount(samples);
    p.setPatternCount(u16());
    p.setVersion(u16());
    p.setCompatibleVersion(u16());
    p.setFlags(u16());
    int special = u16();
    p.setSpecial(special);
    p.setGlobalVolume(u8());
    p.setMixVolume(u8());
    p.setBpmSpeed(u8());
    p.setTempo(u8());
    p.setPanningSeparation(u8());
    p.setPitchWheelDepth(u8());
    String message = "";
    if ((special & cz.skyba.taglib.it.Properties.MessageAttached) != 0) {
      int messageLength = u16();
      long messageOffset = u32();
      seek(messageOffset);
      var data = bytes(messageLength);
      int nul = data.find((byte) 0);
      if (nul >= 0) {
        data.resize(nul);
      }
      data.replace((byte) '\r', (byte) '\n');
      message = data.decodeTerminatedText(ISO_8859_1);
    }
    seek(64);
    var pannings = readBlock(64);
    var volumes = readBlock(64);
    require(pannings.size() == 64 && volumes.size() == 64);
    int channels = 0;
    for (int i = 0; i < 64; i++) {
      if ((pannings.data()[i] & 255) < 128 && volumes.data()[i] > 0) {
        channels++;
      }
    }
    p.setChannels(channels);
    p.setLengthInPatterns(orders(length));
    var comment = new ArrayList<String>();
    for (int i = 0; i < instruments; i++) {
      seek(192L + length + i * 4L);
      long offset = u32();
      seek(offset);
      require(bytes(4).equals(new ByteVector("IMPI")));
      text(13);
      skip(15);
      comment.add(text(26));
    }
    for (int i = 0; i < samples; i++) {
      seek(192L + length + instruments * 4L + i * 4L);
      long offset = u32();
      seek(offset);
      require(bytes(4).equals(new ByteVector("IMPS")));
      text(13);
      u8();
      u8();
      u8();
      comment.add(text(26));
    }
    if (!message.isEmpty()) {
      comment.add(message);
    }
    tag.setComment(String.join("\n", comment));
    tag.setTrackerName("Impulse Tracker");
  }

  private record Struct(ByteVector[] values, long count) {
    int u16(int index) {
      return values[index].toUnsignedShort(false);
    }

    long u32() {
      return values[0].toUnsignedInt(false);
    }

    String text(int index) {
      return trackerText(values[index]);
    }
  }

  private Struct struct(long limit, int... widths) throws ReadFailure {
    var values = new ByteVector[widths.length];
    long count = 0, wanted = 0;
    for (int width : widths) {
      wanted += width;
    }
    for (int i = 0; i < widths.length; i++) {
      values[i] = limit == count ? new ByteVector() : readBlock(Math.min(widths[i], limit - count));
      count += values[i].size();
    }
    require(count == Math.min(limit, wanted));
    return new Struct(values, count);
  }

  private void readXm() throws ReadFailure {
    var p = ((XmState) details).value();
    seek(0);
    var magic = bytes(17);
    require(magic.equals(new ByteVector("Extended Module: ")) || magic.equals(new ByteVector(new byte[17])));
    tag.setTitle(text(20));
    int escape = u8();
    require(escape == 26 || escape == 0);
    tag.setTrackerName(text(20));
    p.setVersion(u16());
    long headerSize = u32();
    require(headerSize >= 4);
    var header = struct(headerSize - 4, 2, 2, 2, 2, 2, 2, 2, 2);
    int patterns = header.u16(3), instruments = header.u16(4);
    p.setLengthInPatterns(header.u16(0));
    p.setRestartPosition(header.u16(1));
    p.setChannels(header.u16(2));
    p.setPatternCount(patterns);
    p.setInstrumentCount(instruments);
    p.setFlags(header.u16(5));
    p.setTempo(header.u16(6));
    p.setBpmSpeed(header.u16(7));
    seek(60 + headerSize);
    for (int i = 0; i < patterns; i++) {
      long size = u32();
      require(size >= 4);
      var pattern = struct(size - 4, 1, 2, 2);
      skip(size - 4 - pattern.count() + pattern.u16(2));
    }
    var instrumentNames = new ArrayList<String>();
    var sampleNames = new ArrayList<String>();
    long sumSamples = 0;
    for (int i = 0; i < instruments; i++) {
      long size = u32();
      require(size >= 4);
      var instrument = struct(size - 4, 22, 1, 2);
      long count = 4 + instrument.count(), offset = 0;
      int samples = instrument.u16(2);
      if (samples > 0) {
        sumSamples = (sumSamples + samples) & 0xffff_ffffL;
        require(size >= count + 4);
        long sampleHeaderSize = u32();
        if (sampleHeaderSize == 0) {
          sampleHeaderSize = 40;
        }
        skip(size - count - 4);
        for (int j = 0; j < samples; j++) {
          var sample = struct(sampleHeaderSize, 4, 4, 4, 1, 1, 1, 1, 1, 1, 22);
          skip(sampleHeaderSize - sample.count());
          offset = Math.addExact(offset, sample.u32());
          sampleNames.add(sample.text(9));
        }
      } else {
        offset = size - count;
      }
      instrumentNames.add(instrument.text(0));
      skip(offset);
    }
    p.setSampleCount(sumSamples);
    String comment = String.join("\n", instrumentNames);
    if (!sampleNames.isEmpty()) {
      comment += "\n" + String.join("\n", sampleNames);
    }
    tag.setComment(comment);
  }

  @Override
  public void save() {
    requireWritable();
    // MOD performs no post-write reads. Its complete fixed-stride plan needs no retained overlay.
    if (format == Format.MOD) {
      long count = ((ModState) details).value().instrumentCount();
      long extent = count == 0 ? 20 : 42 + (count - 1) * 30;
      if (extent > maximumWriteExtent) {
        throw new TagLibException("MOD instrument headers exceed the writable stream range: " + name());
      }
      try {
        saveDirect();
      } catch (ReadFailure failure) {
        throw new TagLibException("Cannot save MOD tags: truncated tracker data in " + name(), failure);
      }
      return;
    }
    long cursor = super.tell();
    TrackerWritePlan plan;
    ReadFailure failure = null;
    try {
      plan = writePlan = new TrackerWritePlan((offset, count) -> {
        super.seek(offset);
        return super.readBlock(count);
      }, super.length(), cursor, maximumWriteExtent);
      try {
        saveDirect();
      } catch (ReadFailure truncated) {
        // Native read failures retain preceding writes; report the failure after replaying them.
        failure = truncated;
      }
    } catch (ArithmeticException | IllegalArgumentException invalidPlan) {
      throw new TagLibException("Tracker write plan exceeds the writable stream range: " + name(), invalidPlan);
    } finally {
      writePlan = null;
      super.seek(cursor);
    }
    plan.apply((offset, bytes) -> {
        super.seek(offset);
        super.writeBlock(bytes);
      },
      super::truncate, super::seek);
    if (failure != null) {
      throw new TagLibException("Cannot save " + format + " tags: truncated tracker data in " + name(), failure);
    }
  }

  @Override
  public ByteVector readBlock(long count) {
    return writePlan == null ? super.readBlock(count) : writePlan.read(count);
  }

  @Override
  protected void writeBlock(ByteVector bytes) {
    if (writePlan == null) {
      super.writeBlock(bytes);
    } else {
      writePlan.write(bytes);
    }
  }

  @Override
  public void seek(long offset) {
    if (writePlan == null) {
      super.seek(offset);
    } else {
      writePlan.seek(offset);
    }
  }

  @Override
  public void seek(long offset, IOStream.Position origin) {
    if (writePlan == null) {
      super.seek(offset, origin);
    } else {
      writePlan.seek(Math.addExact(offset, switch (origin) {
        case BEGINNING -> 0;
        case CURRENT -> writePlan.tell();
        case END -> writePlan.length();
      }));
    }
  }

  @Override
  public long tell() {
    return writePlan == null ? super.tell() : writePlan.tell();
  }

  @Override
  public long length() {
    return writePlan == null ? super.length() : writePlan.length();
  }

  @Override
  protected void truncate(long length) {
    if (writePlan == null) {
      super.truncate(length);
    } else {
      writePlan.truncate(length);
    }
  }

  private void saveDirect() throws ReadFailure {
    switch (format) {
      case MOD -> saveMod();
      case S3M -> saveS3m();
      case IT -> saveIt();
      case XM -> saveXm();
      default -> throw new AssertionError();
    }
  }

  private List<String> commentLines() {
    return Arrays.asList(tag.comment().split("\n", -1));
  }

  private void saveMod() throws ReadFailure {
    seek(0);
    writeString(tag.title(), 20);
    var lines = commentLines();
    long instruments = ((ModState) details).value().instrumentCount();
    for (long i = 0; i < instruments; i++) {
      writeString(i < lines.size() ? lines.get((int) i) : "", 22);
      skip(8);
    }
  }

  private void saveS3m() throws ReadFailure {
    seek(0);
    writeString(tag.title(), 27);
    writeByte(0);
    seek(32);
    int length = u16(), samples = u16();
    skip(28);
    int channels = 0;
    for (int i = 0; i < 32; i++) {
      if (u8() != 255) {
        channels++;
      }
    }
    skip(channels);
    var lines = commentLines();
    for (int i = 0; i < samples; i++) {
      seek(96L + length + i * 2L);
      long offset = u16() * 16L + 48;
      seek(offset);
      writeString(i < lines.size() ? lines.get(i) : "", 27);
      writeByte(0);
    }
  }

  private void saveIt() throws ReadFailure {
    seek(4);
    writeString(tag.title(), 25);
    writeByte(0);
    skip(2);
    int length = u16(), instruments = u16(), samples = u16();
    skip(15);
    var lines = commentLines();
    for (int i = 0; i < instruments; i++) {
      seek(192L + length + i * 4L);
      long offset = u32();
      seek(offset + 32);
      writeString(i < lines.size() ? lines.get(i) : "", 25);
      writeByte(0);
    }
    for (int i = 0; i < samples; i++) {
      seek(192L + length + instruments * 4L + i * 4L);
      long offset = u32();
      seek(offset + 20);
      int nameIndex = instruments + i;
      writeString(nameIndex < lines.size() ? lines.get(nameIndex) : "", 25);
      writeByte(0);
    }
    int start = Math.min(lines.size(), instruments + samples);
    var message = ByteVector.fromText(String.join("\r", lines.subList(start, lines.size())), ISO_8859_1);
    if (message.size() > 7999) {
      message.resize(7999);
    }
    message.append((byte) 0);
    seek(46);
    int special = u16();
    long fileSize = length(), messageOffset;
    int messageLength = 0;
    if ((special & cz.skyba.taglib.it.Properties.MessageAttached) != 0) {
      seek(54);
      messageLength = u16();
      messageOffset = u32();
      if (messageLength == 0) {
        messageOffset = fileSize;
      }
    } else {
      messageOffset = fileSize;
      seek(46);
      writeU16L(special | 1);
    }
    if (messageOffset + messageLength >= fileSize) {
      seek(54);
      writeU16L(message.size());
      writeU32L(messageOffset);
      seek(messageOffset);
      writeBlock(message);
      truncate(messageOffset + message.size());
    } else {
      message.resize(messageLength, (byte) 0);
      seek(messageOffset);
      writeBlock(message);
    }
  }

  private void saveXm() throws ReadFailure {
    seek(17);
    writeString(tag.title(), 20);
    seek(38);
    writeString(tag.trackerName(), 20);
    seek(60);
    long headerSize = u32();
    seek(70);
    int patterns = u16(), instruments = u16();
    long pos = 60 + headerSize;
    for (int i = 0; i < patterns; i++) {
      seek(pos);
      long size = u32();
      require(size >= 4);
      seek(pos + 7);
      int dataSize = u16();
      pos = Math.addExact(pos, size + dataSize);
    }
    var lines = commentLines();
    int sampleNameIndex = instruments;
    for (int i = 0; i < instruments; i++) {
      seek(pos);
      long size = u32();
      require(size >= 4);
      seek(pos + 4);
      writeString(i < lines.size() ? lines.get(i) : "", Math.min(22, size - 4));
      int samples = 0;
      if (size >= 29) {
        seek(pos + 27);
        samples = u16();
      }
      long sampleSize = 0, dataSize = 0;
      if (samples > 0) {
        seek(pos + 29);
        require(size >= 33);
        sampleSize = u32();
      }
      pos = Math.addExact(pos, size);
      for (int j = 0; j < samples; j++) {
        if (sampleSize > 4) {
          seek(pos);
          long sampleLength = u32();
          dataSize = Math.addExact(dataSize, sampleLength);
          if (sampleSize > 18) {
            seek(pos + 18);
            String name = sampleNameIndex < lines.size() ? lines.get(sampleNameIndex++) : "";
            writeString(name, Math.min(sampleSize - 18, 22));
          }
        }
        pos = Math.addExact(pos, sampleSize);
      }
      pos = Math.addExact(pos, dataSize);
    }
  }
}
