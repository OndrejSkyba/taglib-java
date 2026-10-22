// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.id3;

import cz.skyba.taglib.Picture;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.Tag;
import cz.skyba.taglib.internal.MetadataPadding;
import cz.skyba.taglib.internal.NativeSaveState;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.internal.UnsupportedDataRemoval;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Variant;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static java.nio.charset.StandardCharsets.*;

/// Bounded ID3v2 parser. Writes preserve the source version and unedited opaque frames.
public final class Id3v2 {
  private Id3v2() {
  }

  public record TagData(PropertyMap properties, List<Picture> pictures, Tag basic, boolean empty, Parsed parsed) {
    public TagData(PropertyMap properties, List<Picture> pictures, Tag basic, boolean empty) {
      this(properties, pictures, basic, empty, null);
    }

    public TagData(PropertyMap properties, List<Picture> pictures) {
      this(properties, pictures, new Tag(properties), properties.isEmpty() && pictures.isEmpty());
    }

    public TagData {
      pictures = List.copyOf(pictures);
    }
  }

  public record Frame(String id, int flags, byte[] raw, byte[] data, List<String> logicalFields, int payloadVersion) {
    public Frame(String id, int flags, byte[] raw, byte[] data) {
      this(id, flags, raw, data, null, 0);
    }

    public Frame(String id, int flags, byte[] raw, byte[] data, List<String> logicalFields) {
      this(id, flags, raw, data, logicalFields, 0);
    }

    public Frame {
      if (logicalFields != null) {
        logicalFields = List.copyOf(logicalFields);
      }
    }
  }

  public record Parsed(int version, List<Frame> frames, int headerFlags) {
    public Parsed(int version, List<Frame> frames) {
      this(version, frames, 0);
    }
  }

  private record CommonField(String id, String current, String replacement) {
  }

  private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;
  private static final Charset[] TEXT_ENCODINGS = {ISO_8859_1, UTF_16, UTF_16BE, UTF_8, UTF_16LE};
  private static final Set<String> V2_UNKNOWN_IDS =
    Set.of("CRM", "EQUA", "LINK", "RVAD", "TIME", "TSIZ", "TDAT");
  private static final Set<String> V2_NON_TEXT_IDS = Set.of("TIM", "TSI", "TDA");
  private static final Set<String> V3_UNKNOWN_IDS =
    Set.of("EQUA", "RVAD", "TIME", "TRDA", "TSIZ", "TDAT");
  private static final Set<String> PROPERTY_FRAME_IDS = Set.of(
    "APIC", "RVA2", "GEOB", "COMM", "USLT", "SYLT", "ETCO", "POPM",
    "PRIV", "OWNE", "CHAP", "CTOC", "PCST", "MVNM", "MVIN", "GRP1");
  private static final Set<String> DESCRIBED_FRAME_IDS = Set.of("COMM", "USLT", "WXXX");
  private static final Set<String> TEXT_FRAME_IDS = Set.of("WFED", "MVNM", "MVIN", "GRP1");
  private static final Map<String, String> KEYS =
    mappings(
      """
        TALB ALBUM
        TBPM BPM
        TCOM COMPOSER
        TCON GENRE
        TCOP COPYRIGHT
        TDEN ENCODINGTIME
        TDLY PLAYLISTDELAY
        TDOR ORIGINALDATE
        TORY ORIGINALDATE
        TDRC DATE
        TYER DATE
        TDRL RELEASEDATE
        TDTG TAGGINGDATE
        TENC ENCODEDBY
        TEXT LYRICIST
        TFLT FILETYPE
        TIT1 WORK
        TIT2 TITLE
        TIT3 SUBTITLE
        TKEY INITIALKEY
        TLAN LANGUAGE
        TLEN LENGTH
        TMED MEDIA
        TMOO MOOD
        TOAL ORIGINALALBUM
        TOFN ORIGINALFILENAME
        TOLY ORIGINALLYRICIST
        TOPE ORIGINALARTIST
        TOWN OWNER
        TPE1 ARTIST
        TPE2 ALBUMARTIST
        TPE3 CONDUCTOR
        TPE4 REMIXER
        TPOS DISCNUMBER
        TPRO PRODUCEDNOTICE
        TPUB LABEL
        TRCK TRACKNUMBER
        TRSN RADIOSTATION
        TRSO RADIOSTATIONOWNER
        TSOA ALBUMSORT
        TSOC COMPOSERSORT
        TSOP ARTISTSORT
        TSOT TITLESORT
        TSO2 ALBUMARTISTSORT
        TSRC ISRC
        TSSE ENCODING
        TSST DISCSUBTITLE
        WCOP COPYRIGHTURL
        WOAF FILEWEBPAGE
        WOAR ARTISTWEBPAGE
        WOAS AUDIOSOURCEWEBPAGE
        WORS RADIOSTATIONWEBPAGE
        WPAY PAYMENTWEBPAGE
        WPUB PUBLISHERWEBPAGE
        TCAT PODCASTCATEGORY
        TDES PODCASTDESC
        TGID PODCASTID
        WFED PODCASTURL
        MVNM MOVEMENTNAME
        MVIN MOVEMENTNUMBER
        GRP1 GROUPING
        TCMP COMPILATION
        """);
  private static final Map<String, String> V22 =
    mappings(
      """
        BUF RBUF
        CNT PCNT
        COM COMM
        CRA AENC
        ETC ETCO
        EQU EQUA
        GEO GEOB
        IPL TIPL
        LNK LINK
        MCI MCDI
        MLL MLLT
        PIC APIC
        POP POPM
        REV RVRB
        RVA RVAD
        SLT SYLT
        STC SYTC
        TAL TALB
        TBP TBPM
        TCM TCOM
        TCO TCON
        TCP TCMP
        TCR TCOP
        TDA TDAT
        TDY TDLY
        TEN TENC
        TFT TFLT
        TIM TIME
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
        TSI TSIZ
        TSS TSSE
        TT1 TIT1
        TT2 TIT2
        TT3 TIT3
        TXT TOLY
        TXX TXXX
        TS2 TSO2
        TSA TSOA
        TSC TSOC
        TSP TSOP
        TST TSOT
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
        """);

  /// Native Frame::frameIDToKey, also used by explicitly constructed URL frames.
  public static String frameIdToKey(String id) {
    return switch (id) {
      case "TRDA", "TDAT", "TYER", "TIME" -> "DATE";
      case "COMM" -> "COMMENT";
      case "PCST" -> "PODCAST";
      default -> KEYS.getOrDefault(id, "");
    };
  }

  private static final Map<String, String> USER_KEYS =
    mappings(
      """
        MUSICBRAINZ_ALBUMID MUSICBRAINZ ALBUM ID
        MUSICBRAINZ_ARTISTID MUSICBRAINZ ARTIST ID
        MUSICBRAINZ_ALBUMARTISTID MUSICBRAINZ ALBUM ARTIST ID
        RELEASECOUNTRY MUSICBRAINZ ALBUM RELEASE COUNTRY
        RELEASESTATUS MUSICBRAINZ ALBUM STATUS
        RELEASETYPE MUSICBRAINZ ALBUM TYPE
        MUSICBRAINZ_RELEASEGROUPID MUSICBRAINZ RELEASE GROUP ID
        MUSICBRAINZ_RELEASETRACKID MUSICBRAINZ RELEASE TRACK ID
        MUSICBRAINZ_WORKID MUSICBRAINZ WORK ID
        ACOUSTID_ID ACOUSTID ID
        ACOUSTID_FINGERPRINT ACOUSTID FINGERPRINT
        MUSICIP_PUID MUSICIP PUID
        """);
  private static final Map<String, String> ROLES =
    mappings(
      """
        ARRANGER ARRANGER
        ENGINEER ENGINEER
        PRODUCER PRODUCER
        DJ-MIX DJMIXER
        MIX MIXER
        """);
  private static final List<String> ROLE_PROPERTY_KEYS = List.copyOf(ROLES.values());

  /// Native Header::parse admits non-FF versions and defaults invalid/short headers to ten bytes.
  public static int tagSize(byte[] header) throws IOException {
    if (header.length < 3 || header[0] != 'I' || header[1] != 'D' || header[2] != '3') {
      return 0;
    }
    return nativeTagSize(header);
  }

  /// A codec-selected ID3 tag parses Header bytes without checking the file identifier.
  public static int nativeTagSize(byte[] header) throws IOException {
    if (!validNativeHeader(header)) {
      return 10;
    }
    return 10 + ((header[5] & 16) != 0 ? 10 : 0) + synchsafe(header);
  }

  private static boolean validNativeHeader(byte[] header) {
    if (header.length < 10 || (header[3] & 255) == 255 || (header[4] & 255) == 255) {
      return false;
    }
    for (int i = 6; i < 10; i++) {
      if (header[i] < 0) {
        return false;
      }
    }
    return true;
  }

  public static TagData read(byte[] tag, ReadOptions options) throws IOException {
    return read(parseNative(tag, options));
  }

  /// Projects the live native frame list without flattening physical duplicates.
  public static TagData read(Parsed parsed) {
    var properties = new LinkedHashMap<String, List<String>>();
    var pictures = new ArrayList<Picture>();
    var basic = new BasicFields();
    for (Frame frame : parsed.frames()) {
      Map<String, List<String>> values = properties(frame);
      merge(properties, values);
      basic.accept(frame, values);
      if (frame.id().equals("APIC") && frame.data() != null) {
        Picture picture = picture(frame.data(), frame.payloadVersion() == 0 ? parsed.version() : frame.payloadVersion());
        if (picture != null) {
          pictures.add(picture);
        }
      }
    }
    // v2.3 records year, day/month and time separately; v2.4 combines these.
    if (parsed.version() == 3
      && properties.containsKey("DATE")
      && !properties.get("DATE").isEmpty()) {
      String year = properties.get("DATE").getFirst(), date = "", time = "";
      for (Frame frame : parsed.frames()) {
        if (frame.data() == null) {
          continue;
        }
        if (frame.id().equals("TDAT")) {
          date = first(textFields(frame.data()));
        }
        if (frame.id().equals("TIME")) {
          time = first(textFields(frame.data()));
        }
      }
      if (year.length() == 4 && asciiDigits(date)) {
        year += "-" + date.substring(2) + "-" + date.substring(0, 2);
        if (asciiDigits(time)) {
          year += " " + time.substring(0, 2) + ":" + time.substring(2);
        }
        properties.put("DATE", List.of(year));
      }
    }
    // Native frame properties use insert(), which accepts an empty TXXX description.
    // The SimplePropertyMap constructor deliberately classifies empty keys as unsupported.
    var projected = PropertyMap.builder();
    properties.forEach(projected::add);
    for (Frame frame : parsed.frames()) {
      String unsupported = unsupported(frame, parsed.version());
      if (unsupported != null) {
        projected.addUnsupportedData(unsupported);
      }
    }
    return new TagData(projected.build(), pictures, basic.tag(), parsed.frames().isEmpty(), parsed);
  }

  /// Mirrors native FrameFactory class selection and each class's asProperties identifier.
  private static String unsupported(Frame frame, int version) {
    String id = frame.id();
    if (version == 2 && V2_UNKNOWN_IDS.contains(id)) {
      return "UNKNOWN/" + frameId(id, 2);
    }
    if (frame.data() != null && id.length() == 3 && (id.startsWith("T") || id.startsWith("W"))
      && !(version == 2 && V2_NON_TEXT_IDS.contains(id))) {
      return id;
    }
    if (frame.data() == null
      || id.length() != 4
      || version == 3 && V3_UNKNOWN_IDS.contains(id)) {
      return "UNKNOWN/" + id;
    }
    if (id.equals("UFID")) {
      byte[] data = frame.data();
      String owner = new String(data, 0, terminator(data, 0, 0), LATIN1);
      return owner.equals("http://musicbrainz.org") ? null : "UFID/" + owner;
    }
    if (id.equals("CHAP") || id.equals("CTOC")) {
      byte[] data = frame.data();
      return id + "/" + new String(data, 0, terminator(data, 0, 0), LATIN1);
    }
    if (id.equals("TXXX")) {
      return null;
    }
    if (!properties(frame).isEmpty()) {
      return null;
    }
    if (id.startsWith("T") || id.startsWith("W")
      || PROPERTY_FRAME_IDS.contains(id)) {
      return id;
    }
    return "UNKNOWN/" + id;
  }

  /// Native exact-ID removal and first described-frame removal, independent of property export.
  public static Parsed removeUnsupported(Parsed parsed, List<String> specifications) {
    var frames = new ArrayList<>(parsed.frames());
    for (String specification : specifications) {
      if (specification.startsWith("UNKNOWN/")) {
        String id = specification.substring(8);
        if (id.length() == 4) {
          frames.removeIf(frame -> specification.equals(unsupported(frame, parsed.version())));
        }
      } else if (specification.length() == 4) {
        frames.removeIf(frame -> frame.id().equals(specification));
      } else if (specification.length() > 5) {
        String id = specification.substring(0, 4), description = specification.substring(5);
        for (int i = 0; i < frames.size(); i++) {
          Frame frame = frames.get(i);
          if (!frame.id().equals(id) || frame.data() == null) {
            continue;
          }
          byte[] data = frame.data();
          String candidate = null;
          if (id.equals("TXXX")) {
            var fields = textFields(data, true);
            if (!fields.isEmpty()) {
              candidate = fields.getFirst();
            }
          } else if (id.equals("UFID")) {
            candidate = new String(data, 0, terminator(data, 0, 0), LATIN1);
          } else if (DESCRIBED_FRAME_IDS.contains(id) && data.length > 0) {
            int offset = id.equals("WXXX") ? 1 : 4, encoding = data[0] & 255;
            if (data.length >= offset) {
              candidate = decode(data, offset, terminator(data, offset, encoding), encoding, null);
            }
          }
          if (description.equals(candidate)) {
            frames.remove(i);
            break;
          }
        }
      }
    }
    return new Parsed(parsed.version(), List.copyOf(frames), parsed.headerFlags());
  }

  /// Property keys affected by removing a physical frame ID from a staged generic tag.
  public static List<String> propertyKeysForFrame(String id, PropertyMap values) {
    if (KEYS.containsKey(id)) {
      return List.of(KEYS.get(id));
    }
    return switch (id) {
      case "COMM" -> values.asMap().keySet().stream()
        .filter(k -> k.equals("COMMENT") || k.startsWith("COMMENT:")).toList();
      case "USLT" -> values.asMap().keySet().stream()
        .filter(k -> k.equals("LYRICS") || k.startsWith("LYRICS:")).toList();
      case "WXXX" -> values.asMap().keySet().stream()
        .filter(k -> k.equals("URL") || k.startsWith("URL:")).toList();
      case "TMCL" -> values.asMap().keySet().stream().filter(k -> k.startsWith("PERFORMER:")).toList();
      case "TIPL" -> ROLE_PROPERTY_KEYS;
      default -> List.of();
    };
  }

  /// Native text-frame factory preserves an explicitly empty field list.
  public static boolean isTextProperty(String key) {
    return KEYS.entrySet().stream().anyMatch(entry -> entry.getKey().startsWith("T") && entry.getValue().equals(key));
  }

  private static Tag basic(Parsed parsed) {
    var basic = new BasicFields();
    for (Frame frame : parsed.frames()) {
      String key = KEYS.get(frame.id());
      if (frame.id().equals("COMM") || key != null && BasicFields.KEYS.contains(key)) {
        basic.accept(frame, properties(frame));
      }
    }
    return basic.tag();
  }

  private static final class BasicFields {
    private static final Set<String> KEYS =
      Set.of("TITLE", "ARTIST", "ALBUM", "DATE", "GENRE", "TRACKNUMBER");
    private final Map<String, List<String>> common = new LinkedHashMap<>();
    private String comment;
    private boolean emptyDescriptionFound;

    private void accept(Frame frame, Map<String, List<String>> values) {
      String key = Id3v2.KEYS.get(frame.id());
      if (key != null && KEYS.contains(key)) {
        List<String> value = values.get(key);
        if (frame.data() == null) {
          common.putIfAbsent(key, List.of());
        } else if (value != null) {
          common.putIfAbsent(
            key,
            key.equals("GENRE")
              ? value.stream().filter(v -> !v.isEmpty()).distinct().toList()
              : value);
        }
      }
      if (frame.id().equals("COMM") && frame.data() == null && comment == null) {
        comment = "";
      }
      if (frame.id().equals("COMM") && frame.data() != null && !values.isEmpty()) {
        byte[] data = frame.data();
        boolean emptyDescription =
          data.length >= 4
            && decode(data, 4, terminator(data, 4, data[0] & 255), data[0] & 255, null)
            .isEmpty();
        if (comment == null || emptyDescription && !emptyDescriptionFound) {
          comment = values.values().iterator().next().getFirst();
        }
        if (emptyDescription) {
          emptyDescriptionFound = true;
        }
      }
    }

    private Tag tag() {
      if (comment != null) {
        common.put("COMMENT", List.of(comment));
      }
      return new Tag(new PropertyMap(common));
    }
  }

  /// Replaces properties, retaining artwork and opaque frames permitted to survive tag edits.
  public static byte[] render(PropertyMap properties, byte[] originalTag) throws IOException {
    return render(properties, originalTag, null);
  }

  /// A null artwork list preserves existing pictures; a nonnull list replaces them all.
  public static byte[] render(PropertyMap properties, byte[] originalTag, List<Picture> pictures)
    throws IOException {
    return render(properties, originalTag, pictures, 0);
  }

  public static byte[] render(
    PropertyMap properties, byte[] originalTag, List<Picture> pictures, long fileSize)
    throws IOException {
    return render(properties, originalTag, pictures, fileSize, false);
  }

  /// Native generic saves convert the tag and its typed frame payloads to ID3v2.4.
  public static byte[] renderNative(PropertyMap properties, byte[] originalTag, List<Picture> pictures)
    throws IOException {
    return render(properties, originalTag, pictures, 0, true);
  }

  public static byte[] renderNative(
    PropertyMap properties, byte[] originalTag,
    List<Picture> pictures, long fileSize
  ) throws IOException {
    return render(properties, originalTag, pictures, fileSize, true);
  }

  /// Native Tag::read folds consecutive ID3 headers into the original padding capacity.
  public static byte[] renderNative(
    PropertyMap properties, byte[] originalTag,
    List<Picture> pictures, long fileSize, long nativeOriginalSpan
  ) throws IOException {
    int footer = originalTag != null && validNativeHeader(originalTag) && (originalTag[5] & 16) != 0 ? 10 : 0;
    long capacity = Math.max(0, nativeOriginalSpan - 10 - footer);
    if (capacity > ReadOptions.DEFAULT.maxMetadataBytes()) {
      throw new IOException("ID3 metadata exceeds configured limit");
    }
    return render(properties, originalTag, pictures, fileSize, true, (int) capacity);
  }

  private static byte[] render(
    PropertyMap properties, byte[] originalTag, List<Picture> pictures, long fileSize,
    boolean nativePolicy
  ) throws IOException {
    return render(properties, originalTag, pictures, fileSize, nativePolicy, originalBodySize(originalTag));
  }

  private static byte[] render(
    PropertyMap properties, byte[] originalTag, List<Picture> pictures, long fileSize,
    boolean nativePolicy, int originalCapacity
  ) throws IOException {
    Parsed staged = nativePolicy ? NativeSaveState.id3v2Frames() : null;
    Parsed original = staged != null ? staged :
      parse(
        originalTag == null ? new byte[0] : originalTag,
        ReadOptions.DEFAULT.maxMetadataBytes());
    if (staged == null) {
      original = removeUnsupported(original, UnsupportedDataRemoval.requested());
    }
    return renderPlan(properties, original, pictures, new RenderContext(fileSize, nativePolicy, originalCapacity, staged != null));
  }

  private record RenderContext(long fileSize, boolean nativePolicy, int originalCapacity, boolean physicalState) {
  }

  private static byte[] renderPlan(
    PropertyMap properties, Parsed original, List<Picture> pictures, RenderContext context
  ) throws IOException {
    long fileSize = context.fileSize();
    boolean nativePolicy = context.nativePolicy(), physicalState = context.physicalState();
    int originalCapacity = context.originalCapacity();
    int sourceVersion = original.version(), version = nativePolicy ? 4 : sourceVersion;
    var frames = new ByteArrayOutputStream();
    var remaining = new LinkedHashMap<>(physicalState ? Map.of() : properties.asMap());
    var retained = new HashSet<Frame>();
    // Native setProperties preserves a frame when its entire exposed map already matches.
    // The frame-map key ordering also decides between different frame types exposing one key.
    var byId = new ArrayList<>(original.frames());
    byId.sort(Comparator.comparing(Frame::id));
    for (Frame frame : byId) {
      Map<String, List<String>> existing = properties(frame);
      if (physicalState || existing.isEmpty()) {
        retained.add(frame);
      } else if (existing.entrySet().stream()
        .allMatch(entry -> entry.getValue().equals(remaining.get(entry.getKey())))) {
        retained.add(frame);
        existing.keySet().forEach(remaining::remove);
      }
    }
    for (Frame frame : original.frames()) {
      if (retained.contains(frame)
        && !discardOnTagAlteration(frame, sourceVersion)
        && (pictures == null || !frame.id().equals("APIC"))) {
        String id = frameId(frame.id(), version);
        if (nativePolicy) {
          byte[] fields = nativeFrameFields(frame, sourceVersion);
          if (fields.length > 0) {
            writeFrame(frames, version, id, 0, fields);
          }
        } else {
          writeFrame(frames, version, id, frame.flags(), frame.raw());
        }
      }
    }
    var people = new ArrayList<String>();
    var musicians = new ArrayList<String>();
    for (var entry : remaining.entrySet()) {
      String key = asciiUpper(entry.getKey());
      List<String> values = entry.getValue();
      if (key.equals("PODCAST")) {
        writeFrame(frames, version, frameId("PCST", version), 0, new byte[4]);
        continue;
      }
      if (values.isEmpty() && !nativePolicy) {
        continue;
      }
      String id = reverse(KEYS, key, null);
      if (ROLES.containsValue(key)) {
        people.add(reverse(ROLES, key, key));
        people.add(String.join(",", values));
        continue;
      }
      if (key.startsWith("PERFORMER:")) {
        musicians.add(key.substring(10));
        musicians.add(String.join(",", values));
        continue;
      }
      if (id != null) {
        if (key.equals("DATE")) {
          if (version < 4) {
            String date = values.getFirst();
            writeText(
              frames, version, "TYER", List.of(date.substring(0, Math.min(4, date.length()))));
            if (date.length() >= 10) {
              writeText(
                frames, version, "TDAT", List.of(date.substring(8, 10) + date.substring(5, 7)));
            }
            if (date.length() >= 16) {
              writeText(
                frames,
                version,
                "TIME",
                List.of(date.substring(11, 13) + date.substring(14, 16)));
            }
            continue;
          }
          if (!nativePolicy) {
            values = values.stream().map(v -> v.replace(' ', 'T')).toList();
          }
        }
        if (version < 4 && id.equals("TDOR")) {
          id = "TORY";
        }
        if (version == 2 && frameId(id, version).length() != 3) {
          id = null;
        }
        if (id != null) {
          if (id.startsWith("W") && !id.equals("WFED")) {
            if (values.size() == 1) {
              writeFrame(
                frames, version, frameId(id, version), 0, values.getFirst().getBytes(LATIN1));
              continue;
            }
          } else {
            writeText(frames, version, id, values);
            continue;
          }
        }
      }
      if (values.size() == 1
        && (key.equals("COMMENT")
        || key.startsWith("COMMENT:")
        || key.equals("LYRICS")
        || key.startsWith("LYRICS:"))) {
        boolean comment = key.startsWith("COMMENT");
        String description = key.contains(":") ? key.substring(key.indexOf(':') + 1)
          : nativePolicy && !comment ? key : "";
        for (String value : values) {
          byte[] text = textData(List.of(description, value), version);
          byte[] payload = new byte[text.length + 3];
          payload[0] = text[0];
          // Native CommentsFrame/UnsynchronizedLyricsFrame start with the unspecified XXX code.
          payload[1] = 'X';
          payload[2] = 'X';
          payload[3] = 'X';
          System.arraycopy(text, 1, payload, 4, text.length - 1);
          writeFrame(frames, version, frameId(comment ? "COMM" : "USLT", version), 0, payload);
        }
      } else if (values.size() == 1 && (key.equals("URL") || key.startsWith("URL:"))) {
        String description = key.contains(":") ? key.substring(4) : nativePolicy ? key : "";
        for (String value : values) {
          var data = new ByteArrayOutputStream();
          byte[] descriptionData = textData(List.of(description, ""), version);
          data.write(descriptionData, 0, descriptionData.length - (version == 4 ? 0 : 2));
          data.writeBytes(value.getBytes(LATIN1));
          writeFrame(frames, version, frameId("WXXX", version), 0, data.toByteArray());
        }
      } else if (values.size() == 1 && key.equals("MUSICBRAINZ_TRACKID")) {
        for (String value : values) {
          var data = new ByteArrayOutputStream();
          data.writeBytes("http://musicbrainz.org\0".getBytes(LATIN1));
          data.writeBytes(value.getBytes(nativePolicy ? StandardCharsets.UTF_8 : LATIN1));
          writeFrame(frames, version, frameId("UFID", version), 0, data.toByteArray());
        }
      } else {
        var fields = new ArrayList<String>();
        fields.add(USER_KEYS.getOrDefault(key, key));
        fields.addAll(values);
        writeText(frames, version, "TXXX", fields);
      }
    }
    if (!people.isEmpty()) {
      writeText(frames, version, version == 3 ? "IPLS" : "TIPL", people);
    }
    if (!musicians.isEmpty()) {
      if (version == 2) {
        throw new IOException("Musician credits require ID3v2.3 or later");
      }
      writeText(frames, version, "TMCL", musicians);
    }
    if (pictures != null) {
      for (Picture picture : pictures) {
        var payload = new ByteArrayOutputStream();
        byte[] description = nativePolicy ? nativeTextData(List.of(picture.description(), ""), 0)
          : textData(List.of(picture.description(), ""), version);
        payload.write(description[0]);
        if (version == 2) {
          String format =
            switch (picture.mimeType().toLowerCase(Locale.ROOT)) {
              case "image/jpeg", "image/jpg" -> "JPG";
              case "image/png" -> "PNG";
              case "-->" -> "-->";
              default -> throw new IOException(
                "Picture MIME type cannot be represented in ID3v2.2: "
                  + picture.mimeType());
            };
          payload.writeBytes(format.getBytes(LATIN1));
        } else {
          payload.writeBytes(picture.mimeType().getBytes(LATIN1));
          payload.write(0);
        }
        payload.write(picture.type());
        // textData puts a BOM on the empty second field; image data follows the delimiter itself.
        payload.write(description, 1, description.length - 1 - ((description[0] & 255) == 1 ? 2 : 0));
        payload.writeBytes(picture.data());
        writeFrame(frames, version, frameId("APIC", version), 0, payload.toByteArray());
      }
    }
    boolean liveFrames = retained.stream().anyMatch(frame -> pictures == null || !frame.id().equals("APIC"))
      || !remaining.isEmpty() || pictures != null && !pictures.isEmpty();
    // Native File::save checks Tag::isEmpty before render removes obsolete frames.
    // A nonempty live tag can therefore render an empty body with native padding.
    if (nativePolicy && frames.size() == 0 && !liveFrames) {
      return new byte[0];
    }
    return renderTag(version, frames, originalCapacity, fileSize, original.headerFlags());
  }

  /// Render parsed field data rather than copying old compression/encoding/version headers.
  private static byte[] nativeFrameFields(Frame frame, int sourceVersion) {
    String id = frame.id();
    byte[] data = frame.data();
    if (data == null) {
      // Native UnknownFrame uses fieldData even for encrypted frames, and clears its header
      // flags when rendered. It never attempts decryption.
      data = frame.raw();
      if ((sourceVersion < 0 || sourceVersion > 3) && (frame.flags() & 2) != 0) {
        data = deunsync(data);
      }
      if ((frame.flags() & (sourceVersion == 3 ? 128 : 9)) != 0) {
        if (data.length < 4) {
          return new byte[0];
        }
        int length = (int) Math.min(compatibleSynchsafe(data, 0), data.length - 4L);
        data = Arrays.copyOfRange(data, 4, 4 + length);
      }
      return data;
    }
    if (id.startsWith("T") || TEXT_FRAME_IDS.contains(id)) {
      var fields = new ArrayList<>(textFields(frame));
      if (id.equals("TXXX") && frame.logicalFields() == null) {
        while (fields.size() < 2) {
          fields.add("");
        }
      }
      if (id.equals("TCON")) {
        fields = new ArrayList<>(nativeGenres(textFields(frame)));
      }
      return nativeTextData(fields, data.length == 0 ? 0 : data[0] & 255);
    }
    if (id.equals("COMM") || id.equals("USLT")) {
      int encoding = data.length < 5 ? 0 : data[0] & 255;
      String description = "", text = "";
      if (data.length >= 5) {
        int end = terminator(data, 4, encoding), start = end + delimiterSize(encoding);
        if (start <= data.length) {
          description = decode(data, 4, end, encoding, null);
          text = decode(data, start, data.length, encoding, null);
        }
      }
      byte[] content = nativeTextData(List.of(description, text), encoding);
      byte[] fields = new byte[content.length + 3];
      fields[0] = content[0];
      if (data.length >= 5) {
        System.arraycopy(data, 1, fields, 1, 3);
      } else {
        Arrays.fill(fields, 1, 4, (byte) 'X');
      }
      System.arraycopy(content, 1, fields, 4, content.length - 1);
      return fields;
    }
    if (id.equals("APIC")) {
      Picture parsed = picture(data, frame.payloadVersion() == 0 ? sourceVersion : frame.payloadVersion());
      if (parsed == null) {
        parsed = new Picture(0, "", "", new byte[0]);
      }
      int encoding = data.length < 5 ? 0 : data[0] & 255;
      byte[] description = nativeTextData(List.of(parsed.description(), ""), encoding);
      var fields = new ByteArrayOutputStream();
      fields.write(description[0]);
      fields.writeBytes(parsed.mimeType().getBytes(LATIN1));
      fields.write(0);
      fields.write(parsed.type() <= 20 ? parsed.type() : 0);
      int emptyBom = description[0] == 1 ? 2 : 0;
      fields.write(description, 1, description.length - 1 - emptyBom);
      fields.writeBytes(parsed.data());
      return fields.toByteArray();
    }
    if (id.equals("GEOB")) {
      var object = generalObjects(new Parsed(sourceVersion, List.of(frame))).getFirst();
      return generalObjectData(object.get("mimeType").toText(), object.get("fileName").toText(),
        object.get("description").toText(), object.get("data").toByteVector().data(),
        data.length >= 4 ? data[0] & 255 : 0);
    }
    if (id.equals("WXXX")) {
      int encoding = data.length < 2 ? 0 : data[0] & 255;
      int end = terminator(data, 1, encoding), start = end + delimiterSize(encoding);
      String description = start <= data.length ? decode(data, 1, end, encoding, null) : "";
      String url = start <= data.length ? decode(data, start, data.length, 0, null) : "";
      byte[] content = nativeTextData(List.of(description, ""), encoding);
      int emptyBom = content[0] == 1 ? 2 : 0;
      var fields = new ByteArrayOutputStream();
      fields.write(content, 0, content.length - emptyBom);
      fields.writeBytes(url.getBytes(LATIN1));
      return fields.toByteArray();
    }
    if (id.startsWith("W") && !id.equals("WFED")) {
      return decode(data, 0, data.length, 0, null).getBytes(LATIN1);
    }
    // Version-invariant typed/binary payloads and UnknownFrame data already have all outer
    // compression/unsynchronisation indicators removed by parse(). Their complete bytes survive.
    return data;
  }

  public record RenderedTag(byte[] bytes, Tag basic) {
  }

  /// MPEG's common-field duplication shares the parsed frames and renders only when needed.
  public static RenderedTag renderMpeg(
    PropertyMap properties, byte[] originalTag, List<Picture> pictures, Tag source, long fileSize)
    throws IOException {
    return renderMpegPlan(properties, originalTag, pictures, source, fileSize,
      originalBodySize(originalTag));
  }

  /// Native consecutive ID3 headers enlarge the first tag's capacity and replacement span.
  public static RenderedTag renderMpeg(
    PropertyMap properties, byte[] originalTag, List<Picture> pictures, Tag source, long fileSize,
    long nativeOriginalSpan
  ) throws IOException {
    int footer = originalTag != null && validNativeHeader(originalTag)
      && (originalTag[5] & 16) != 0 ? 10 : 0;
    long capacity = Math.max(0, nativeOriginalSpan - 10 - footer);
    if (capacity > 0x0fff_ffff) {
      throw new IOException("ID3 declared capacity exceeds header limit");
    }
    return renderMpegPlan(properties, originalTag, pictures, source, fileSize, (int) capacity);
  }

  private static RenderedTag renderMpegPlan(
    PropertyMap properties, byte[] originalTag,
    List<Picture> pictures, Tag source, long fileSize, int originalCapacity
  ) throws IOException {
    Parsed physical = NativeSaveState.id3v2Frames();
    if (physical == null) {
      physical = parseNative(originalTag == null ? new byte[0] : originalTag, ReadOptions.DEFAULT);
      physical = removeUnsupported(physical, UnsupportedDataRemoval.requested());
      physical = updateProperties(physical, properties);
    }
    if (pictures != null) {
      physical = updatePictures(physical, pictures);
    }
    Tag target = basic(physical);
    var fields = List.of(
      new CommonField("TITLE", target.title(), source.title()),
      new CommonField("ARTIST", target.artist(), source.artist()),
      new CommonField("ALBUM", target.album(), source.album()),
      new CommonField("COMMENT", target.comment(), source.comment()),
      new CommonField("GENRE", target.genre(), source.genre()),
      new CommonField(
        "DATE",
        target.year() == 0 ? "" : Integer.toString((int) target.year()),
        source.year() == 0 ? "" : Integer.toString((int) source.year())),
      new CommonField(
        "TRACKNUMBER",
        target.track() == 0 ? "" : Integer.toString((int) target.track()),
        source.track() == 0 ? "" : Integer.toString((int) source.track())));
    // Tag::duplicate invokes the setters on the live frame list before Tag::render drops
    // obsolete frames. Rendering first would lose that list's native isEmpty distinction.
    for (CommonField field : fields) {
      if (field.current().isEmpty()) {
        physical = updateCommon(physical, field.id(), field.replacement());
      }
    }
    byte[] rendered = renderPlan(PropertyMap.EMPTY, physical, null, new RenderContext(fileSize, true, originalCapacity, true));
    return new RenderedTag(rendered, basic(physical));
  }

  /// Applies MPEG's default ID3v1-to-ID3v2 common-field duplication after setProperties.
  public static byte[] duplicateMissingBasic(byte[] renderedTag, Tag source) throws IOException {
    return duplicateMissingBasic(renderedTag, source, originalBodySize(renderedTag)).bytes();
  }

  private static RenderedTag duplicateMissingBasic(
    byte[] renderedTag, Tag source, int originalBodySize) throws IOException {
    Parsed parsed = parse(renderedTag, ReadOptions.DEFAULT.maxMetadataBytes());
    Tag target = basic(parsed);
    var frames = new ArrayList<>(parsed.frames());
    var fields = List.of(
      new CommonField("TIT2", target.title(), source.title()),
      new CommonField("TPE1", target.artist(), source.artist()),
      new CommonField("TALB", target.album(), source.album()),
      new CommonField("COMM", target.comment(), source.comment()),
      new CommonField("TCON", target.genre(), source.genre()),
      // Native ID3v2 numeric setters use String::number(int), keeping signed text
      // rendering even when their common accessor values are unsigned.
      new CommonField(
        parsed.version() < 4 ? "TYER" : "TDRC",
        target.year() == 0 ? "" : Integer.toString((int) target.year()),
        source.year() == 0 ? "" : Integer.toString((int) source.year())),
      new CommonField(
        "TRCK",
        target.track() == 0 ? "" : Integer.toString((int) target.track()),
        source.track() == 0 ? "" : Integer.toString((int) source.track())));
    for (CommonField field : fields) {
      if (!field.current().isEmpty()) {
        continue;
      }
      String id = field.id();
      String value = field.replacement();
      if (value.isEmpty()) {
        frames.removeIf(frame -> frame.id().equals(id));
        continue;
      }
      int selected = -1;
      for (int index = 0; index < frames.size(); index++) {
        Frame frame = frames.get(index);
        if (!frame.id().equals(id)) {
          continue;
        }
        if (selected < 0) {
          selected = index;
        }
        if (!id.equals("COMM") || commentDescription(frame).isEmpty()) {
          selected = index;
          break;
        }
      }
      byte[] data;
      int encoding = selected < 0 || frames.get(selected).data() == null
        || frames.get(selected).data().length == 0 ? 0 : frames.get(selected).data()[0] & 255;
      if (id.equals("COMM")) {
        Frame previous = selected < 0 ? null : frames.get(selected);
        String description = previous == null ? "" : commentDescription(previous);
        byte[] text = nativeTextData(List.of(description, value), encoding);
        data = new byte[text.length + 3];
        data[0] = text[0];
        Arrays.fill(data, 1, 4, (byte) 'X');
        if (previous != null && previous.data() != null && previous.data().length >= 4) {
          System.arraycopy(previous.data(), 1, data, 1, 3);
        }
        System.arraycopy(text, 1, data, 4, text.length - 1);
      } else {
        data = nativeTextData(List.of(value), encoding);
      }
      Frame updated = new Frame(id, 0, data, data);
      if (selected < 0) {
        frames.add(updated);
      } else {
        frames.set(selected, updated);
      }
    }
    if (frames.isEmpty()) {
      return new RenderedTag(parsed.frames().isEmpty() ? renderedTag : new byte[0], target);
    }
    if (frames.equals(parsed.frames())) {
      return new RenderedTag(renderedTag, target);
    }
    var body = new ByteArrayOutputStream();
    for (Frame frame : frames) {
      writeFrame(
        body,
        parsed.version(),
        frameId(frame.id(), parsed.version()),
        frame.flags(),
        frame.raw());
    }
    return new RenderedTag(
      renderTag(parsed.version(), body, originalBodySize, 0, parsed.headerFlags()),
      basic(new Parsed(parsed.version(), frames)));
  }

  public static String commentDescription(Frame frame) {
    byte[] data = frame.data();
    if (data == null || data.length < 4) {
      return "";
    }
    int encoding = data[0] & 255;
    return decode(data, 4, terminator(data, 4, encoding), encoding, null);
  }

  public static byte[] renderTag(int version, ByteArrayOutputStream frames) throws IOException {
    return renderTag(version, frames, 0, 0);
  }

  /// Native padding uses header-declared capacity even when the physical source is shorter.
  public static int originalBodySize(byte[] original) throws IOException {
    if (original == null
      || !validNativeHeader(original)) {
      return 0;
    }
    return synchsafe(original);
  }

  public static byte[] renderTag(
    int version, ByteArrayOutputStream frames, int originalBodySize, long fileSize)
    throws IOException {
    return renderTag(version, frames, originalBodySize, fileSize, 0);
  }

  private static byte[] renderTag(
    int version, ByteArrayOutputStream frames, int originalBodySize,
    long fileSize, int headerFlags
  ) throws IOException {
    int padding = MetadataPadding.bytes((long) originalBodySize - frames.size(), fileSize, 1024);
    if (frames.size() > 0x0fff_ffff - padding) {
      throw new IOException("ID3 tag exceeds synchsafe size limit");
    }
    byte[] out = new byte[10 + frames.size() + padding];
    out[0] = 'I';
    out[1] = 'D';
    out[2] = '3';
    out[3] = (byte) version;
    out[5] = (byte) (headerFlags & 32);
    putSynchsafe(out, 6, out.length - 10);
    System.arraycopy(frames.toByteArray(), 0, out, 10, frames.size());
    return out;
  }

  /// Mirrors FrameFactory's obsolete-frame handling and ID3 tag-alter-preservation flags.
  private static boolean discardOnTagAlteration(Frame frame, int version) {
    if (version == 2) {
      return List.of("CRM", "EQUA", "LINK", "RVAD", "TIME", "TSIZ", "TDAT").contains(frame.id());
    }
    if ((frame.flags() & (version == 3 ? 0x8000 : 0x4000)) != 0) {
      return true;
    }
    return version == 3
      && List.of("EQUA", "RVAD", "TIME", "TRDA", "TSIZ", "TDAT").contains(frame.id());
  }

  public static Parsed parseForEditing(byte[] tag) throws IOException {
    return parse(tag, ReadOptions.DEFAULT.maxMetadataBytes(), true);
  }

  public static Parsed parseNative(byte[] tag, ReadOptions options) throws IOException {
    return parse(tag, options.maxMetadataBytes());
  }

  /// Native setProperties preserves exactly matching frames and then appends new UTF-8 frames.
  public static Parsed updateProperties(Parsed original, PropertyMap requested) throws IOException {
    var remaining = new LinkedHashMap<>(requested.asMap());
    var retained = new HashSet<Frame>();
    var byId = new ArrayList<>(original.frames());
    byId.sort(Comparator.comparing(Frame::id));
    for (Frame frame : byId) {
      var exposed = properties(frame);
      if (exposed.isEmpty()) {
        retained.add(frame);
      } else if (exposed.entrySet().stream().allMatch(e -> e.getValue().equals(remaining.get(e.getKey())))) {
        retained.add(frame);
        exposed.keySet().forEach(remaining::remove);
      }
    }
    var result = new ArrayList<Frame>();
    for (Frame frame : original.frames()) {
      if (retained.contains(frame)) {
        result.add(frame);
      }
    }
    var pending = PropertyMap.builder();
    remaining.forEach(pending::add);
    byte[] added = renderPlan(pending.build(), new Parsed(4, List.of()), null,
      new RenderContext(0, true, 0, false));
    if (added.length != 0) {
      for (Frame frame : parseNative(added, ReadOptions.DEFAULT).frames()) {
        List<String> logical = null;
        String mapped = KEYS.get(frame.id());
        if (mapped != null && !frame.id().startsWith("W") && remaining.containsKey(mapped)) {
          logical = remaining.get(mapped);
        } else if (frame.id().equals("TXXX")) {
          String description = first(textFields(frame));
          String key = reverse(USER_KEYS, description, description);
          if (remaining.containsKey(key)) {
            var fields = new ArrayList<String>();
            fields.add(description);
            fields.addAll(remaining.get(key));
            logical = fields;
          }
        }
        result.add(new Frame(frame.id(), frame.flags(), frame.raw(), frame.data(), logical));
      }
    }
    return new Parsed(original.version(), List.copyOf(result), original.headerFlags());
  }

  public static Parsed updatePictures(Parsed original, List<Picture> pictures) throws IOException {
    var result = new ArrayList<Frame>();
    for (Frame frame : original.frames()) {
      if (!frame.id().equals("APIC")) {
        result.add(frame);
      }
    }
    byte[] added = renderPlan(PropertyMap.EMPTY, new Parsed(4, List.of()), pictures,
      new RenderContext(0, true, 0, false));
    if (added.length != 0) {
      for (Frame frame : parseNative(added, ReadOptions.DEFAULT).frames()) {
        result.add(new Frame(frame.id(), frame.flags(), frame.raw(), frame.data(), frame.logicalFields(), 4));
      }
    }
    return new Parsed(original.version(), List.copyOf(result), original.headerFlags());
  }

  /// Native GENERALOBJECT dictionaries for typed, including default-empty malformed, GEOB frames.
  public static List<Map<String, Variant>> generalObjects(Parsed parsed) {
    var result = new ArrayList<Map<String, Variant>>();
    for (Frame frame : parsed.frames()) {
      if (frame.id().equals("GEOB") && frame.data() != null) {
        byte[] data = frame.data();
        String mime = "", name = "", description = "";
        int at = data.length;
        if (data.length >= 4) {
          int encoding = data[0] & 255;
          int end = terminator(data, 1, 0);
          mime = decode(data, 1, end, 0, null);
          at = Math.min(data.length, end + 1);
          end = terminator(data, at, encoding);
          name = decode(data, at, end, encoding, null);
          at = Math.min(data.length, end + delimiterSize(encoding));
          end = terminator(data, at, encoding);
          description = decode(data, at, end, encoding, null);
          at = Math.min(data.length, end + delimiterSize(encoding));
        }
        result.add(Map.of("data",
          Variant.fromByteArray(Arrays.copyOfRange(data, at, data.length)),
          "mimeType", new Variant(mime), "fileName", new Variant(name),
          "description", new Variant(description)));
      }
    }
    return List.copyOf(result);
  }

  public static Parsed updateGeneralObjects(Parsed original, List<Map<String, Variant>> values) {
    var result = new ArrayList<Frame>();
    for (Frame frame : original.frames()) {
      if (!frame.id().equals("GEOB")) {
        result.add(frame);
      }
    }
    for (var value : values) {
      var empty = new Variant();
      String mime = value.getOrDefault("mimeType", empty).toText();
      String name = value.getOrDefault("fileName", empty).toText();
      String description = value.getOrDefault("description", empty).toText();
      byte[] object = value.getOrDefault("data", empty).toByteVector().data();
      byte[] payload = generalObjectData(mime, name, description, object, 0);
      result.add(new Frame("GEOB", 0, payload, payload));
    }
    return new Parsed(original.version(), List.copyOf(result), original.headerFlags());
  }

  private static byte[] generalObjectData(String mime, String name, String description, byte[] object, int encoding) {
    byte[] text = nativeTextData(List.of(name, description, ""), encoding);
    var payload = new ByteArrayOutputStream();
    payload.write(text[0]);
    payload.writeBytes(TextBytes.encode(mime, ISO_8859_1));
    payload.write(0);
    payload.write(text, 1, text.length - 1 - ((text[0] & 255) == 1 ? 2 : 0));
    payload.writeBytes(object);
    return payload.toByteArray();
  }

  /// Native common setters update one typed physical frame, preserving other duplicates.
  public static Parsed updateCommon(Parsed original, String key, String value) {
    String id = switch (key) {
      case "TITLE" -> "TIT2";
      case "ARTIST" -> "TPE1";
      case "ALBUM" -> "TALB";
      case "COMMENT" -> "COMM";
      case "GENRE" -> "TCON";
      case "DATE" -> "TDRC";
      case "TRACKNUMBER" -> "TRCK";
      default -> throw new IllegalArgumentException("Not a common ID3 field: " + key);
    };
    var frames = new ArrayList<>(original.frames());
    if (value.isEmpty()) {
      frames.removeIf(frame -> frame.id().equals(id));
      return new Parsed(original.version(), List.copyOf(frames), original.headerFlags());
    }
    int selected = -1;
    for (int i = 0; i < frames.size(); i++) {
      if (frames.get(i).id().equals(id)) {
        if (selected < 0) {
          selected = i;
        }
        if (id.equals("COMM")) {
          byte[] data = frames.get(i).data();
          if (data != null && data.length >= 4 && decode(data, 4,
            terminator(data, 4, data[0] & 255), data[0] & 255, null).isEmpty()) {
            selected = i;
            break;
          }
        } else {
          break;
        }
      }
    }
    Frame old = selected < 0 ? null : frames.get(selected);
    // UnknownFrame::setText is the native no-op, including encrypted known identifiers.
    if (old != null && old.data() == null) {
      return original;
    }
    int encoding = old == null || old.data().length == 0 ? 0 : old.data()[0] & 255;
    byte[] data;
    if (id.equals("COMM")) {
      String description = old == null || old.data().length < 4 ? "" : decode(old.data(), 4,
        terminator(old.data(), 4, encoding), encoding, null);
      byte[] text = nativeTextData(List.of(description, value), encoding);
      data = new byte[text.length + 3];
      data[0] = text[0];
      byte[] language = old != null && old.data().length >= 4
        ? Arrays.copyOfRange(old.data(), 1, 4) : new byte[]{'X', 'X', 'X'};
      System.arraycopy(language, 0, data, 1, 3);
      System.arraycopy(text, 1, data, 4, text.length - 1);
    } else {
      data = nativeTextData(List.of(value), encoding);
    }
    Frame updated = new Frame(id, old == null ? 0 : old.flags(), data, data);
    if (selected < 0) {
      frames.add(updated);
    } else {
      frames.set(selected, updated);
    }
    return new Parsed(original.version(), List.copyOf(frames), original.headerFlags());
  }

  private static Parsed parse(byte[] tag, int limit) throws IOException {
    return parse(tag, limit, false);
  }

  private static Parsed parse(byte[] tag, int limit, boolean strict) throws IOException {
    if (tag.length == 0) {
      return new Parsed(4, List.of());
    }
    int size = strict ? tagSize(tag) : nativeTagSize(tag);
    if (size == 0) {
      return new Parsed(4, List.of());
    }
    if (!validNativeHeader(tag)) {
      if (strict) {
        throw new IOException("Invalid or truncated ID3 header cannot be edited safely");
      }
      return new Parsed(4, List.of());
    }
    if (strict && size > tag.length) {
      throw new IOException("Truncated ID3 tag cannot be edited safely");
    }
    if (size > limit || tag.length > limit) {
      throw new IOException("ID3 metadata exceeds configured byte limit");
    }
    // Header assigns the signed char to unsigned int; retain its 32-bit pattern.
    int version = tag[3];
    int frameVersion = version >= 0 && version <= 2 ? 2 : version == 3 ? 3 : 4;
    int end = Math.min(tag.length, 10 + synchsafe(tag));
    byte[] body = Arrays.copyOfRange(tag, 10, end);
    if (version >= 0 && version <= 3 && (tag[5] & 128) != 0) {
      body = deunsync(body);
    }
    int offset = 0;
    if ((tag[5] & 64) != 0) {
      long extended = compatibleSynchsafePrefix(body);
      // Native advances by the declared size for both v3 and v4, and leaves
      // frame parsing at zero when the declaration exceeds the available body.
      if (extended <= body.length) {
        offset = (int) extended;
      }
    }
    if ((tag[5] & 16) != 0 && body.length >= 10) {
      body = Arrays.copyOf(body, body.length - 10);
    }
    var frames = new ArrayList<Frame>();
    int header = frameVersion == 2 ? 6 : 10;
    // Tag::parse uses the ten-byte tag header as its lower bound even for v2.2's
    // six-byte frame headers; a short final v2.2 frame is left unparsed.
    for (int count = 0; offset + 10 < body.length && count < 50_000; count++) {
      String originalId = new String(body, offset, frameVersion == 2 ? 3 : 4, LATIN1);
      boolean itunesV22 =
        version == 3
          && (originalId.charAt(3) == 0 || originalId.charAt(3) == ' ')
          // PIC additionally changes its binary payload layout; leave that malformed layout
          // outside this compatibility path until its conversion can be preserved safely.
          && !originalId.startsWith("PIC")
          && V22.containsKey(originalId.substring(0, 3));
      if (!itunesV22 && !validFrameId(body, offset, frameVersion == 2 ? 3 : 4)) {
        if (strict) {
          for (int i = offset; i < body.length; i++) {
            if (body[i] != 0) {
              throw new IOException("Unparsed ID3 data cannot be edited safely");
            }
          }
        }
        break;
      }
      long length =
        frameVersion == 2
          ? unsigned(body, offset + 3, 3)
          : frameVersion == 4 ? compatibleSynchsafe(body, offset + 4) : unsigned(body, offset + 4, 4);
      // Native chooses an ordinary integer size only when its following frame ID
      // is valid and the synchsafe candidate's following ID is not.
      if (frameVersion == 4 && length > 127 && !validFrameId(body, (long) offset + header + length)) {
        long ordinary = unsigned(body, offset + 4, 4);
        if (validFrameId(body, (long) offset + header + ordinary)) {
          length = ordinary;
        }
      }
      if (length < 0 || length > body.length - offset - header) {
        if (strict) {
          throw new IOException("Truncated ID3 frame cannot be edited safely");
        }
        break;
      }
      if (length == 0) {
        offset += header;
        continue;
      }
      int flags = frameVersion == 2 ? 0 : (int) unsigned(body, offset + 8, 2);
      byte[] raw = Arrays.copyOfRange(body, offset + header, offset + header + (int) length);
      byte[] data = raw;
      boolean encrypted = (flags & (frameVersion == 4 ? 4 : 64)) != 0;
      boolean compressed = (flags & (frameVersion == 4 ? 8 : 128)) != 0;
      if ((version < 0 || version > 3) && ((flags & 2) != 0 || (tag[5] & 128) != 0)) {
        data = deunsync(data);
      }
      if (encrypted) {
        data = null;
      } else {
        long expected = compressed && data.length >= 4 ? compatibleSynchsafe(data, 0) : 0;
        int skip = 0;
        if (version == 3 && compressed) {
          skip += 4;
        }
        if ((flags & (frameVersion == 4 ? 64 : 32)) != 0) {
          skip++;
        }
        if (frameVersion == 4 && (flags & 1) != 0) {
          skip += 4;
        }
        if (skip > data.length) {
          data = null;
        } else if (skip > 0) {
          data = Arrays.copyOfRange(data, skip, data.length);
        }
        if (compressed && data != null) {
          data =
            expected > limit || expected > data.length * 64L
              ? new byte[0]
              : inflate(data, (int) expected);
        }
      }
      String id =
        itunesV22
          ? V22.get(originalId.substring(0, 3))
          : version == 2 ? V22.getOrDefault(originalId, originalId) : originalId;
      if (version == 3) {
        id =
          switch (id) {
            case "IPLS" -> "TIPL";
            case "TYER" -> "TDRC";
            case "TORY" -> "TDOR";
            default -> id;
          };
      } else if (version != 2 && id.equals("TRDC")) {
        id = "TDRC";
      }
      // Store the normalized unsynchronization-free bytes if the flag was on the entire v2.4 tag.
      if ((version < 0 || version > 3) && (tag[5] & 128) != 0 && (flags & 2) == 0) {
        flags |= 2;
      }
      frames.add(new Frame(id, flags, raw, data));
      offset += header + (int) length;
    }
    if (strict && offset + header > body.length) {
      for (int i = offset; i < body.length; i++) {
        if (body[i] != 0) {
          throw new IOException("Trailing ID3 data cannot be edited safely");
        }
      }
    }
    if (strict && frames.size() >= 50_000) {
      throw new IOException("Too many ID3 frames");
    }
    return new Parsed(version, frames, tag[5] & 255);
  }

  public static Map<String, List<String>> properties(Frame frame) {
    var out = new LinkedHashMap<String, List<String>>();
    byte[] data = frame.data();
    String id = frame.id();
    if (data == null) {
      return out;
    }
    if (id.equals("PCST")) {
      out.put("PODCAST", List.of());
      return out;
    }
    if (id.equals("UFID")) {
      int split = terminator(data, 0, 0);
      if (new String(data, 0, split, LATIN1).equals("http://musicbrainz.org")
        && split < data.length) {
        out.put(
          "MUSICBRAINZ_TRACKID",
          List.of(new String(data, split + 1, data.length - split - 1, LATIN1)));
      }
    } else if (id.equals("COMM") || id.equals("USLT") || id.equals("WXXX")) {
      int start = id.equals("WXXX") ? 1 : 4;
      String prefix = id.equals("COMM") ? "COMMENT" : id.equals("USLT") ? "LYRICS" : "URL";
      if (data.length < start) {
        out.put(prefix, List.of(""));
        return out;
      }
      int encoding = data[0] & 255,
        end = terminator(data, start, encoding),
        step = delimiterSize(encoding);
      if (end + step > data.length) {
        return out;
      }
      String description = asciiUpper(decode(data, start, end, encoding, null));
      String key =
        description.isEmpty() || description.equals(prefix) ? prefix : prefix + ":" + description;
      String value =
        decode(
          data,
          end + step,
          data.length,
          id.equals("WXXX") ? 0 : encoding,
          charset(data, start, encoding));
      out.put(key, List.of(value));
    } else if (id.equals("TXXX")) {
      List<String> fields = textFields(frame);
      if (frame.logicalFields() != null && fields.size() <= 1) {
        return out;
      }
      if (fields.isEmpty()) {
        out.put("", List.of(""));
      } else if (fields.size() == 1) {
        out.put(
          reverse(USER_KEYS, asciiUpper(fields.getFirst()), asciiUpper(fields.getFirst())),
          List.of(""));
      } else {
        String description = asciiUpper(fields.getFirst());
        out.put(reverse(USER_KEYS, description, description), fields.subList(1, fields.size()));
      }
    } else if (id.equals("TIPL") || id.equals("TMCL")) {
      List<String> fields = textFields(frame);
      if (fields.size() % 2 != 0) {
        return out;
      }
      for (int i = 0; i < fields.size(); i += 2) {
        String role = asciiUpper(fields.get(i));
        String key = id.equals("TMCL") ? "PERFORMER:" + role : ROLES.get(role);
        if (key == null || role.isEmpty()) {
          return Map.of();
        }
        out.computeIfAbsent(key, _ -> new ArrayList<>())
          .addAll(Arrays.asList(fields.get(i + 1).split(",", -1)));
      }
    } else if (KEYS.containsKey(id)) {
      String key = KEYS.get(id);
      List<String> values =
        id.startsWith("W") && !id.equals("WFED")
          ? List.of(decode(data, 0, data.length, 0, null))
          : textFields(frame);
      if (key.equals("GENRE")) {
        values = genreValues(values);
      }
      if (key.equals("DATE")) {
        values = values.stream().map(v -> {
          int at = v.indexOf('T');
          return at < 0 ? v : v.substring(0, at) + ' ' + v.substring(at + 1);
        }).toList();
      }
      out.put(key, values);
    }
    return out;
  }

  private static List<String> genreValues(List<String> raw) {
    var values = new ArrayList<String>();
    for (String value : raw) {
      if (value.startsWith("((")) {
        values.add(value.substring(1));
        continue;
      }
      while (value.startsWith("(")) {
        int end = value.indexOf(')');
        if (end < 0) {
          break;
        }
        String number = value.substring(1, end), name = genreName(number);
        if (name.isEmpty()) {
          break;
        }
        values.add(name);
        value = value.substring(end + 1);
      }
      if (!value.isEmpty()) {
        String name = genreName(value);
        values.add(name.isEmpty() ? value : name);
      }
    }
    return values;
  }

  private static String asciiUpper(String text) {
    char[] chars = text.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      if (chars[i] >= 'a' && chars[i] <= 'z') {
        chars[i] -= 'a' - 'A';
      }
    }
    return new String(chars);
  }

  private static String genreName(String text) {
    if (text.equals("RX")) {
      return "Remix";
    }
    if (text.equals("CR")) {
      return "Cover";
    }
    try {
      return Id3v1.genre(Integer.parseInt(text));
    } catch (NumberFormatException ignored) {
      return "";
    }
  }

  public static Picture picture(byte[] data, int version) {
    if (data.length < 5) {
      return null;
    }
    int encoding = data[0] & 255, offset;
    String mime;
    if (version == 2) {
      String format = new String(data, 1, 3, LATIN1).toUpperCase(Locale.ROOT);
      mime =
        switch (format) {
          case "JPG" -> "image/jpeg";
          case "PNG" -> "image/png";
          case "-->" -> "-->";
          default -> "image/" + format.toLowerCase(Locale.ROOT);
        };
      offset = 4;
    } else {
      int end = terminator(data, 1, 0);
      mime = new String(data, 1, end - 1, LATIN1);
      offset = end + 1;
    }
    if (offset >= data.length) {
      return null;
    }
    int type = data[offset++] & 255,
      end = terminator(data, offset, encoding),
      next = end + delimiterSize(encoding);
    if (next > data.length) {
      return null;
    }
    return new Picture(
      type,
      mime,
      decode(data, offset, end, encoding, null),
      Arrays.copyOfRange(data, next, data.length));
  }

  public static List<String> textFields(byte[] data) {
    return textFields(data, false);
  }

  /// Native text-frame splitting, with TXXX's distinguished empty description.
  public static List<String> textFields(byte[] data, boolean userText) {
    if (data.length < 2) {
      return List.of();
    }
    int encoding = data[0] & 255;
    if (encoding > 3) {
      encoding = 0;
    }
    int step = delimiterSize(encoding), length = data.length - 1;
    while (length > 0 && data[length] == 0) {
      length--;
    }
    while (length % step != 0) {
      length++;
    }
    var fields = new ByteVector(Arrays.copyOfRange(data, 1, Math.min(data.length, length + 1))).split(new ByteVector(new byte[step]), step);
    var values = new ArrayList<String>();
    int firstBom = fields.isEmpty() ? 0 : fields.getFirst().toUnsignedShort();
    for (int i = 0; i < fields.size(); i++) {
      var field = fields.get(i);
      if (field.isEmpty() && !(i == 0 && userText)) {
        continue;
      }
      var charset = TEXT_ENCODINGS[encoding];
      if (encoding == 1 && i > 0) {
        int bom = field.toUnsignedShort();
        if (bom != 0xfeff && bom != 0xfffe) {
          if (firstBom == 0xfeff) {
            charset = UTF_16BE;
          } else if (firstBom == 0xfffe) {
            charset = UTF_16LE;
          }
        }
      }
      values.add(field.decodeTerminatedText(charset));
    }
    return values;
  }

  private static List<String> textFields(Frame frame) {
    return frame.logicalFields() != null ? frame.logicalFields() : textFields(frame.data(), frame.id().equals("TXXX"));
  }

  public static Charset charset(byte[] data, int start, int encoding) {
    return switch (encoding) {
      case 1 -> start + 1 < data.length && data[start] == (byte) 0xff && data[start + 1] == (byte) 0xfe
        ? StandardCharsets.UTF_16LE
        : StandardCharsets.UTF_16BE;
      case 2 -> StandardCharsets.UTF_16BE;
      case 3 -> StandardCharsets.UTF_8;
      default -> LATIN1;
    };
  }

  public static String decode(byte[] data, int start, int end, int encoding, Charset inherited) {
    if (start >= end) {
      return "";
    }
    Charset charset =
      encoding == 1 && inherited != null ? inherited : charset(data, start, encoding);
    if (encoding == 1 && start + 1 < end) {
      if ((data[start] == (byte) 0xff && data[start + 1] == (byte) 0xfe)
        || (data[start] == (byte) 0xfe && data[start + 1] == (byte) 0xff)) {
        charset = charset(data, start, encoding);
        start += 2;
      }
    }
    String text;
    try {
      text =
        charset
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(data, start, end - start))
          .toString();
    } catch (CharacterCodingException ignored) {
      return "";
    }
    int zero = text.indexOf('\0');
    return zero < 0 ? text : text.substring(0, zero);
  }

  public static int delimiterSize(int encoding) {
    return encoding == 1 || encoding == 2 ? 2 : 1;
  }

  public static int terminator(byte[] data, int start, int encoding) {
    int step = delimiterSize(encoding);
    for (int i = start; i + step <= data.length; i += step) {
      if (data[i] == 0 && (step == 1 || data[i + 1] == 0)) {
        return i;
      }
    }
    return data.length;
  }

  public static byte[] textData(List<String> fields, int version) {
    var data = new ByteArrayOutputStream();
    data.write(version == 4 ? 3 : 1);
    for (int i = 0; i < fields.size(); i++) {
      if (i > 0) {
        data.write(0);
        if (version != 4) {
          data.write(0);
        }
      }
      if (version != 4) {
        data.write(0xff);
        data.write(0xfe);
      }
      data.writeBytes(
        fields
          .get(i)
          .getBytes(version == 4 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE));
    }
    return data.toByteArray();
  }

  private static List<String> nativeGenres(List<String> fields) {
    var output = new ArrayList<String>();
    for (String source : fields) {
      String remaining = source;
      while (remaining.startsWith("(") && remaining.indexOf(')') > 0) {
        int end = remaining.indexOf(')');
        String code = remaining.substring(1, end);
        remaining = remaining.substring(end + 1);
        int number = -1;
        try {
          number = Integer.parseInt(code);
        } catch (NumberFormatException ignored) {
        }
        if (code.equals("RX") || code.equals("CR")
          || number >= 0 && number <= 255 && !Id3v1.genre(number).equals(remaining)) {
          output.add(code);
        }
      }
      if (!remaining.isEmpty()) {
        output.add(remaining);
      }
    }
    return output;
  }

  /// Retained native frames keep their declared encoding; newly created frames use UTF-8.
  private static byte[] nativeTextData(List<String> fields, int requestedEncoding) {
    int encoding = requestedEncoding >= 0 && requestedEncoding <= 3 ? requestedEncoding : 0;
    if (encoding == 0 && fields.stream().flatMapToInt(String::chars).anyMatch(c -> c > 255)) {
      encoding = 3;
    }
    var output = new ByteArrayOutputStream();
    output.write(encoding);
    for (int index = 0; index < fields.size(); index++) {
      if (index != 0) {
        output.write(0);
        if (encoding == 1 || encoding == 2) {
          output.write(0);
        }
      }
      if (encoding == 1) {
        output.write(0xff);
        output.write(0xfe);
      }
      output.writeBytes(fields.get(index).getBytes(switch (encoding) {
        case 1 -> StandardCharsets.UTF_16LE;
        case 2 -> StandardCharsets.UTF_16BE;
        case 3 -> StandardCharsets.UTF_8;
        default -> LATIN1;
      }));
    }
    return output.toByteArray();
  }

  private static void writeText(
    ByteArrayOutputStream out, int version, String id, List<String> fields) throws IOException {
    writeFrame(out, version, frameId(id, version), 0, textData(fields, version));
  }

  public static String frameId(String id, int version) {
    if (version == 2) {
      return switch (id) {
        // Preserve the legacy tag version while using the factory's normalized IDs internally.
        case "TYER", "TDRC" -> "TYE";
        case "TORY", "TDOR" -> "TOR";
        default -> reverse(V22, id, id);
      };
    }
    if (version == 3) {
      return switch (id) {
        case "TIPL" -> "IPLS";
        case "TDRC" -> "TYER";
        case "TDOR" -> "TORY";
        default -> id;
      };
    }
    return id;
  }

  public static void writeFrame(
    ByteArrayOutputStream out, int version, String id, int flags, byte[] data)
    throws IOException {
    if ((version == 2 && data.length > 0x00ff_ffff) || (version == 4 && data.length > 0x0fff_ffff)) {
      throw new IOException("ID3 frame exceeds the size supported by its tag version");
    }
    int idLength = version == 2 ? 3 : 4;
    if (id.length() != idLength) {
      throw new IOException("Frame " + id + " cannot be represented in ID3v2." + version);
    }
    out.writeBytes(id.getBytes(LATIN1));
    byte[] size = new byte[version == 2 ? 3 : 4];
    if (version == 4) {
      putSynchsafe(size, 0, data.length);
    } else {
      for (int i = 0; i < size.length; i++) {
        size[size.length - i - 1] = (byte) (data.length >>> (8 * i));
      }
    }
    out.writeBytes(size);
    if (version != 2) {
      out.write(flags >>> 8);
      out.write(flags);
    }
    out.writeBytes(data);
  }

  private static byte[] inflate(byte[] compressed, int limit) {
    try (var inflater = new Inflater()) {
      inflater.setInput(compressed);
      var out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      while (!inflater.finished()) {
        int count = inflater.inflate(buffer);
        if (count == 0) {
          return out.toByteArray();
        }
        int retained = Math.min(count, limit - out.size());
        out.write(buffer, 0, retained);
        if (out.size() == limit) {
          return out.toByteArray();
        }
      }
      return out.toByteArray();
    } catch (DataFormatException ignored) {
      return new byte[0];
    }
  }

  private static byte[] deunsync(byte[] data) {
    byte[] out = new byte[data.length];
    int size = 0;
    for (int i = 0; i < data.length; i++) {
      out[size++] = data[i];
      if ((data[i] & 255) == 255 && i + 1 < data.length && data[i + 1] == 0) {
        i++;
      }
    }
    return Arrays.copyOf(out, size);
  }

  private static long unsigned(byte[] data, int offset, int count) {
    long value = 0;
    for (int i = 0; i < count; i++) {
      value = (value << 8) | (data[offset + i] & 255);
    }
    return value;
  }

  private static boolean asciiDigits(String value) {
    if (value.length() != 4) return false;
    for (int i = 0; i < 4; i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') return false;
    }
    return true;
  }

  private static boolean validFrameId(byte[] data, long offset) {
    return validFrameId(data, offset, 4);
  }

  private static boolean validFrameId(byte[] data, long offset, int width) {
    if (offset < 0 || offset > data.length - width) {
      return false;
    }
    for (int i = 0; i < width; i++) {
      int c = data[(int) offset + i] & 255;
      if (c < 'A' || c > 'Z') {
        if (c < '0' || c > '9') {
          return false;
        }
      }
    }
    return true;
  }

  private static long compatibleSynchsafePrefix(byte[] data) {
    int length = Math.min(4, data.length);
    long value = 0;
    for (int i = 0; i < length; i++) {
      if ((data[i] & 128) != 0) {
        return unsigned(Arrays.copyOf(data, 4), 0, 4);
      }
      value = (value << 7) | (data[i] & 127);
    }
    return value;
  }

  private static long compatibleSynchsafe(byte[] data, int offset) {
    int value = 0;
    for (int i = 0; i < 4; i++) {
      if ((data[offset + i] & 128) != 0) {
        return unsigned(data, offset, 4);
      }
      value = (value << 7) | (data[offset + i] & 127);
    }
    return value;
  }

  private static int synchsafe(byte[] data) throws IOException {
    int offset = 6;
    int value = 0;
    for (int i = 0; i < 4; i++) {
      if ((data[offset + i] & 128) != 0) {
        throw new IOException("Invalid ID3 synchsafe integer");
      }
      value = (value << 7) | (data[offset + i] & 127);
    }
    return value;
  }

  private static void putSynchsafe(byte[] data, int offset, int value) {
    for (int i = 3; i >= 0; i--) {
      data[offset + i] = (byte) (value & 127);
      value >>>= 7;
    }
  }

  private static Map<String, String> mappings(String text) {
    var result = new LinkedHashMap<String, String>();
    text.lines()
      .filter(l -> !l.isBlank())
      .forEach(
        line -> {
          String[] pair = line.strip().split(" ", 2);
          result.put(pair[0], pair[1]);
        });
    return result;
  }

  private static String reverse(Map<String, String> map, String value, String fallback) {
    for (var entry : map.entrySet()) {
      if (entry.getValue().equals(value)) {
        return entry.getKey();
      }
    }
    return fallback;
  }

  private static String first(List<String> values) {
    return values.isEmpty() ? "" : values.getFirst();
  }

  private static void merge(Map<String, List<String>> target, Map<String, List<String>> source) {
    source.forEach(
      (key, values) -> target.computeIfAbsent(key, _ -> new ArrayList<>()).addAll(values));
  }
}
