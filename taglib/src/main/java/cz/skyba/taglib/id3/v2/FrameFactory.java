// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.id3.v1.Genres;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.internal.TextNumbers;
import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/// Decodes and constructs ID3v2 frames, with protected hooks for custom frame types.
/// Configuration applies to future operations through this factory. The shared default instance
/// is also used when decoding embedded frames, independently of an outer tag's custom factory.
public class FrameFactory {
  private static final System.Logger LOG = System.getLogger(FrameFactory.class.getName());

  /// The result of frame-header preparation.
  ///
  /// @param header    the prepared live frame header, or null if the input is invalid
  /// @param supported whether the frame can be decoded by a supported frame implementation
  public record Preparation(Frame.Header header, boolean supported) {
  }

  private static final FrameFactory INSTANCE = new FrameFactory();
  private static final ThreadLocal<Integer> EMBEDDED_DEPTH = ThreadLocal.withInitial(() -> 0);
  private static final Set<String> TEXT_FRAME_IDS = Set.of("WFED", "MVNM", "MVIN", "GRP1");
  private static final Set<String> V2_DISCARDED_IDS =
    Set.of("CRM", "EQU", "LNK", "RVA", "TIM", "TSI", "TDA");
  private static final Set<String> V3_DISCARDED_IDS =
    Set.of("EQUA", "RVAD", "TIME", "TRDA", "TSIZ", "TDAT");
  private static final Map<String, String> V3_CONVERSIONS =
    Map.of("TORY", "TDOR", "TYER", "TDRC", "IPLS", "TIPL");
  private Charset defaultEncoding = ISO_8859_1;
  private boolean useDefaultEncoding;

  protected FrameFactory() {
  }

  public static FrameFactory instance() {
    return INSTANCE;
  }

  public final Charset defaultTextEncoding() {
    return defaultEncoding;
  }

  /// Sets the default text encoding, using the charsets supported by [Frame].
  ///
  /// @param encoding the text encoding supported by [Frame]
  /// @throws IllegalArgumentException if the encoding is not supported
  public final void setDefaultTextEncoding(Charset encoding) {
    defaultEncoding = Id3Text.requireEncoding(encoding);
    useDefaultEncoding = true;
  }

  public final boolean isUsingDefaultTextEncoding() {
    return useDefaultEncoding;
  }

  /// Prepares a frame header using the enclosing tag version and normalizes eligible data.
  /// Unsynchronization handling can modify the supplied byte vector. Unsupported recognizable
  /// frames retain a header; malformed identifiers or extents return a null header.
  ///
  /// @param data      mutable serialized frame bytes
  /// @param tagHeader the enclosing tag header
  /// @return the prepared header and support status
  protected final Preparation prepareFrameHeader(ByteVector data, Header tagHeader) {
    long version = Objects.requireNonNull(tagHeader).majorVersion();
    return prepareFrameHeader(data, tagHeader, new Frame.Header(data, version));
  }

  private Preparation prepareFrameHeader(ByteVector data, Header tagHeader, Frame.Header header) {
    long version = tagHeader.majorVersion();
    ByteVector id = header.frameID();
    if (id.size() != (version < 3 ? 3 : 4)
      || header.frameSize() < (header.dataLengthIndicator() ? 4 : 0) || header.frameSize() > data.size()) {
      return new Preparation(null, false);
    }
    if (version == 3 && (id.at(3) == 0 || id.at(3) == ' ')) {
      id = id.mid(0, 3);
      header.setFrameID(id);
      header.setVersion(2);
      updateFrame(header);
      header.setVersion(3);
    }
    for (byte character : id) {
      int c = character & 255;
      if ((c < 'A' || c > 'Z') && (c < '0' || c > '9')) {
        return new Preparation(null, false);
      }
    }
    if (version > 3 && (tagHeader.unsynchronisation() || header.unsynchronisation())) {
      ByteVector body = data.mid(header.size(), (int) header.frameSize());
      data.setData(data.mid(0, header.size()).append(SynchData.decode(body)).data());
    }
    // The pure Java runtime has the same available-zlib branch through java.util.zip.
    if (header.encryption()) {
      LOG.log(DEBUG, "Encrypted frames are currently not supported.");
      return new Preparation(header, false);
    }
    if (!updateFrame(header)) {
      header.setTagAlterPreservation(true);
      return new Preparation(header, false);
    }
    return new Preparation(header, true);
  }

  /// Decodes a serialized frame without changing the supplied input bytes.
  ///
  /// @param original  serialized frame bytes
  /// @param tagHeader the enclosing tag header
  /// @return the decoded frame, an unknown frame for recognizable unsupported data, or null for invalid input
  public Frame createFrame(ByteVector original, Header tagHeader) {
    ByteVector data = new ByteVector(original);
    Preparation result = prepareFrameHeader(data, tagHeader);
    if (!result.supported()) {
      return result.header() == null ? null : new UnknownFrame(data, result.header());
    }
    return createFrame(data, result.header(), tagHeader);
  }

  final Frame createFrameAt(ByteVector source, int offset, Header tagHeader) {
    // Custom factories receive the original complete tail, including mutable preparation hooks.
    if (getClass() != FrameFactory.class || source.size() - offset < 4) {
      return createFrame(source.mid(offset), tagHeader);
    }
    var header = new Frame.Header(source, offset, tagHeader.majorVersion());
    // Native zlib decoding can read beyond the declared frame extent on malformed input.
    if (header.compression()) return createFrame(source.mid(offset), tagHeader);
    long span = header.size() + header.frameSize();
    if (header.dataLengthIndicator()) {
      long fields = SynchData.toUnsignedInt(source.mid(offset + header.size(), 4));
      span = Math.max(span, header.size() + 4L + fields);
    }
    int length = (int) Math.min(source.size() - (long) offset, span);
    ByteVector data = source.mid(offset, length);
    Preparation result = prepareFrameHeader(data, tagHeader, header);
    if (!result.supported()) {
      return result.header() == null ? null : new UnknownFrame(data, result.header());
    }
    return createFrame(data, result.header(), tagHeader);
  }

  static Frame createEmbeddedFrame(ByteVector data, int offset, Header tagHeader) {
    int depth = EMBEDDED_DEPTH.get();
    if (depth >= 64) {
      LOG.log(DEBUG, "Maximum embedded frame nesting depth exceeded.");
      return null;
    }
    EMBEDDED_DEPTH.set(depth + 1);
    try {
      return instance().createFrameAt(data, offset, tagHeader);
    } finally {
      if (depth == 0) {
        EMBEDDED_DEPTH.remove();
      } else {
        EMBEDDED_DEPTH.set(depth);
      }
    }
  }

  /// Decodes a serialized frame using a prepared frame header and this factory's custom dispatch hook.
  ///
  /// @param data      prepared serialized frame bytes
  /// @param header    the prepared live frame header
  /// @param tagHeader the enclosing tag header
  /// @return the decoded frame, an unknown frame for recognizable unsupported data, or null for invalid input
  protected Frame createFrame(ByteVector data, Frame.Header header, Header tagHeader) {
    String id = new String(header.frameID().data(), StandardCharsets.ISO_8859_1);
    Frame frame;
    if (id.startsWith("T") || TEXT_FRAME_IDS.contains(id)) {
      var text = id.equals("TXXX") ? new UserTextIdentificationFrame(data, header) : new TextIdentificationFrame(data, header);
      if (useDefaultEncoding) {
        applyDefaultEncoding(text);
      }
      if (id.equals("TCON")) {
        updateGenre(text);
      }
      return text;
    }
    frame = switch (id) {
      case "COMM" -> new CommentsFrame(data, header);
      case "APIC" -> new AttachedPictureFrame(data, header);
      case "PIC" -> new AttachedPictureFrameV22(data, header);
      case "RVA2" -> new RelativeVolumeFrame(data, header);
      case "UFID" -> new UniqueFileIdentifierFrame(data, header);
      case "GEOB" -> new GeneralEncapsulatedObjectFrame(data, header);
      case "USLT" -> new UnsynchronizedLyricsFrame(data, header);
      case "SYLT" -> new SynchronizedLyricsFrame(data, header);
      case "ETCO" -> new EventTimingCodesFrame(data, header);
      case "POPM" -> new PopularimeterFrame(data, header);
      case "PRIV" -> new PrivateFrame(data, header);
      case "OWNE" -> new OwnershipFrame(data, header);
      case "CHAP" -> new ChapterFrame(tagHeader, data, header);
      case "CTOC" -> new TableOfContentsFrame(tagHeader, data, header);
      case "PCST" -> new PodcastFrame(data, header);
      default -> id.startsWith("W") ? (id.equals("WXXX") ? new UserUrlLinkFrame(data, header)
        : new UrlLinkFrame(data, header)) : new UnknownFrame(data, header);
    };
    if (useDefaultEncoding) {
      applyDefaultEncoding(frame);
    }
    return frame;
  }

  private void applyDefaultEncoding(Frame frame) {
    switch (frame) {
      case TextIdentificationFrame f -> f.setTextEncoding(defaultEncoding);
      case CommentsFrame f -> f.setTextEncoding(defaultEncoding);
      case AttachedPictureFrame f -> f.setTextEncoding(defaultEncoding);
      case GeneralEncapsulatedObjectFrame f -> f.setTextEncoding(defaultEncoding);
      case UserUrlLinkFrame f -> f.setTextEncoding(defaultEncoding);
      case UnsynchronizedLyricsFrame f -> f.setTextEncoding(defaultEncoding);
      case SynchronizedLyricsFrame f -> f.setTextEncoding(defaultEncoding);
      case OwnershipFrame f -> f.setTextEncoding(defaultEncoding);
      case null, default -> {
      }
    }
  }

  /// Normalizes recognized frame identifiers for their version. Unsupported obsolete frame types
  /// are reported as unsupported without creating a concrete frame.
  ///
  /// @param header the live header to update
  /// @return whether the frame type is supported
  protected boolean updateFrame(Frame.Header header) {
    String id = Id3Text.fixedLatin1(header.frameID());
    if (header.version() == 2) {
      return applyConversion(header, id, V2_DISCARDED_IDS, CONVERSION_2);
    } else if (header.version() == 3) {
      return applyConversion(header, id, V3_DISCARDED_IDS, V3_CONVERSIONS);
    } else if (id.equals("TRDC")) {
      header.setFrameID(new ByteVector("TDRC"));
    }
    return true;
  }

  private static boolean applyConversion(
    Frame.Header header, String id, Set<String> discarded, Map<String, String> conversions
  ) {
    if (discarded.contains(id)) {
      LOG.log(DEBUG, () -> "Discarding unsupported frame type " + id + ".");
      return false;
    }
    String converted = conversions.get(id);
    if (converted != null) {
      header.setFrameID(new ByteVector(converted));
    }
    return true;
  }

  /// Creates a representable ID3v2 frame for the supplied text property.
  ///
  /// @param key the common property key
  /// @param values the ordered text values
  /// @return the new frame
  public Frame createFrameForProperty(String key, List<String> values) {
    Objects.requireNonNull(key);
    values = List.copyOf(values);
    ByteVector frameID = Frame.keyToFrameID(key);
    String id = Id3Text.fixedLatin1(frameID);
    if (!id.isEmpty()) {
      if (id.startsWith("T") || TEXT_FRAME_IDS.contains(id)) {
        var frame = new TextIdentificationFrame(frameID, UTF_8);
        frame.setText(values);
        return frame;
      }
      if (id.startsWith("W") && values.size() == 1) {
        var frame = new UrlLinkFrame(frameID);
        frame.setUrl(values.getFirst());
        return frame;
      }
      if (id.equals("PCST")) {
        return new PodcastFrame();
      }
    }
    if (values.size() == 1) {
      if (key.equals("MUSICBRAINZ_TRACKID")) {
        return new UniqueFileIdentifierFrame("http://musicbrainz.org", ByteVector.fromText(values.getFirst(), UTF_8));
      }
      if (key.equals("LYRICS") || key.startsWith(Frame.lyricsPrefix)) {
        var frame = new UnsynchronizedLyricsFrame(UTF_8);
        frame.setDescription(key.equals("LYRICS") ? key : key.substring(Frame.lyricsPrefix.length()));
        frame.setText(values.getFirst());
        return frame;
      }
      if (key.equals("URL") || key.startsWith(Frame.urlPrefix)) {
        var frame = new UserUrlLinkFrame(UTF_8);
        frame.setDescription(key.equals("URL") ? key : key.substring(Frame.urlPrefix.length()));
        frame.setUrl(values.getFirst());
        return frame;
      }
      if (key.equals("COMMENT") || key.startsWith(Frame.commentPrefix)) {
        var frame = new CommentsFrame(UTF_8);
        if (!key.equals("COMMENT")) {
          frame.setDescription(key.substring(Frame.commentPrefix.length()));
        }
        frame.setText(values.getFirst());
        return frame;
      }
    }
    return new UserTextIdentificationFrame(UserTextIdentificationFrame.keyToTXXX(key), values, UTF_8);
  }

  /// Rebuilds aggregate date and credit frames for the tag version.
  ///
  /// @param tag the tag whose date and credit frames to update
  public void rebuildAggregateFrames(Tag tag) {
    if (tag.header().majorVersion() < 4) {
      var years = tag.frameList(new ByteVector("TDRC"));
      if (years.size() == 1) {
        var dates = tag.frameList(new ByteVector("TDAT"));
        if (dates.size() == 1) {
          Frame a = years.getFirst(), b = dates.getFirst();
          if (a instanceof TextIdentificationFrame year && year.fieldList().size() == 1
            && year.fieldList().getFirst().length() == 4 && b instanceof UnknownFrame date && date.data().size() >= 5) {
            String d = fixedText(date.data());
            if (d.length() == 4) {
              year.setText(year.toString() + '-' + d.substring(2, 4) + '-' + d.substring(0, 2));
              var times = tag.frameList(new ByteVector("TIME"));
              if (times.size() == 1 && times.getFirst() instanceof UnknownFrame time && time.data().size() >= 5) {
                String t = fixedText(time.data());
                if (t.length() == 4) {
                  year.setText(year.toString() + 'T' + t.substring(0, 2) + ':' + t.substring(2, 4));
                }
              }
            }
          }
        }
      }
    }
    if (tag.header().majorVersion() < 4) {
      var peopleFrames = tag.frameList(new ByteVector("TIPL"));
      if (peopleFrames.size() == 1 && tag.frameList(new ByteVector("TMCL")).isEmpty()
        && peopleFrames.getFirst() instanceof TextIdentificationFrame tipl) {
        List<String> values = tipl.toStringList();
        if ((values.size() & 1) == 0) {
          List<String> keys = aggregateTIPLKeys();
          var people = new ArrayList<String>();
          var musicians = new ArrayList<String>();
          for (int i = 0; i < values.size(); i += 2) {
            var destination = keys.contains(Id3Text.upper(values.get(i))) ? people : musicians;
            destination.add(values.get(i));
            destination.add(values.get(i + 1));
          }
          if (!musicians.isEmpty()) {
            var tmcl = new TextIdentificationFrame(new ByteVector("TMCL"));
            tmcl.setText(musicians);
            tag.addFrame(tmcl);
            if (!people.isEmpty()) {
              tipl.setText(people);
            } else {
              tag.removeFrame(tipl);
            }
          }
        }
      }
    }
  }

  private static final class AggregateKeys {
    static final List<String> TIPL = List.copyOf(TextIdentificationFrame.involvedPeopleMap().values());
  }

  private static List<String> aggregateTIPLKeys() {
    return AggregateKeys.TIPL;
  }

  private static String fixedText(ByteVector data) {
    var charset = Id3Text.encoding(data.at(0));
    return TextBytes.decodeTerminated(data.data(), 1, Math.max(0, data.size() - 1), charset);
  }

  private static void updateGenre(TextIdentificationFrame frame) {
    var values = new ArrayList<String>();
    for (String field : frame.fieldList()) {
      int end;
      while (!field.isEmpty() && field.charAt(0) == '(' && (end = field.indexOf(')', 1)) > 0) {
        String code = field.substring(1, end);
        field = field.substring(end + 1);
        var number = TextNumbers.extractInt(code);
        if ((number.ok() && number.value() >= 0 && number.value() <= 255 && !Genres.genre(number.value()).equals(field))
          || code.equals("RX") || code.equals("CR")) {
          values.add(code);
        }
      }
      if (!field.isEmpty()) {
        values.add(field);
      }
    }
    frame.setText(values);
  }

  private static final Map<String, String> CONVERSION_2 = conversions();

  private static Map<String, String> conversions() {
    var result = new HashMap<String, String>();
    for (String row : """
      BUF RBUF
      CNT PCNT
      COM COMM
      CRA AENC
      ETC ETCO
      GEO GEOB
      IPL TIPL
      MCI MCDI
      MLL MLLT
      POP POPM
      REV RVRB
      SLT SYLT
      STC SYTC
      TAL TALB
      TBP TBPM
      TCM TCOM
      TCO TCON
      TCP TCMP
      TCR TCOP
      TDY TDLY
      TEN TENC
      TFT TFLT
      TKE TKEY
      TLA TLAN
      TLE TLEN
      TMT TMED
      TOA TOAL
      TOF TOFN
      TOL TOLY
      TOR TDOR
      TOT TOAL
      TP1 TPE1
      TP2 TPE2
      TP3 TPE3
      TP4 TPE4
      TPA TPOS
      TPB TPUB
      TRC TSRC
      TRD TDRC
      TRK TRCK
      TS2 TSO2
      TSA TSOA
      TSC TSOC
      TSP TSOP
      TSS TSSE
      TST TSOT
      TT1 TIT1
      TT2 TIT2
      TT3 TIT3
      TXT TOLY
      TXX TXXX
      TYE TDRC
      UFI UFID
      ULT USLT
      WAF WOAF
      WAR WOAR
      WAS WOAS
      WCM WCOM
      WCP WCOP
      WPB WPUB
      WXX WXXX
      PCS PCST
      TCT TCAT
      TDR TDRL
      TDS TDES
      TID TGID
      WFD WFED
      MVN MVNM
      MVI MVIN
      GP1 GRP1
      """.strip().split("\n")) {
      String[] pair = row.strip().split(" ");
      result.put(pair[0], pair[1]);
    }
    return Map.copyOf(result);
  }
}
