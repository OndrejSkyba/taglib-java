package cz.skyba.taglib.parity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Independent encoded-media extraction for the trusted test corpus, never used by the library.
 * Container metadata, padding and tag framing are excluded; compressed media bytes are not decoded.
 * Returning null means the verifier cannot prove preservation for this container or malformed file.
 */
public final class PayloadHash {
  public record Hash(String sha256, String scope, long bytes) {
  }

  public record GeneratedUidField(
    int javaOffset,
    int nativeOffset,
    int bytes,
    int javaBytes,
    int nativeBytes,
    String attachmentName,
    String mimeType,
    String payloadSha256,
    String lifetimeProof) {
  }

  public record DerivedUidField(
    String kind,
    int javaOffset,
    int nativeOffset,
    long javaValue,
    long nativeValue,
    String derivation) {
  }

  public record NativeBytes(
    boolean verified,
    boolean rawEqual,
    String policy,
    String details,
    long javaLength,
    long nativeLength,
    String javaSha256,
    String nativeSha256,
    String normalizedJavaSha256,
    String normalizedNativeSha256,
    int normalizedUidFieldCount,
    List<GeneratedUidField> generatedUidFields,
    List<DerivedUidField> derivedUidFields) {
  }

  /**
   * Complete equality with a source-bound exception for native generated picture UIDs.
   */
  public static NativeBytes nativeBytesEquivalent(
    Path source, Path javaAfter, Path nativeAfter, String operation) throws IOException {
    Hash javaHash = wholeFile(javaAfter), nativeHash = wholeFile(nativeAfter);
    boolean raw = javaHash.bytes == nativeHash.bytes && javaHash.sha256.equals(nativeHash.sha256);
    String policy =
      "Complete native default-save file SHA-256 and length; no metadata, framing or padding"
        + " omitted";
    if (raw)
      return new NativeBytes(
        true,
        true,
        policy,
        "All saved bytes match",
        javaHash.bytes,
        nativeHash.bytes,
        javaHash.sha256,
        nativeHash.sha256,
        javaHash.sha256,
        nativeHash.sha256,
        0,
        List.of(),
        List.of());
    byte[] original = bounded(source),
      javaBytes = bounded(javaAfter),
      nativeBytes = bounded(nativeAfter);
    var fields = new ArrayList<GeneratedUidField>();
    var derived = new ArrayList<DerivedUidField>();
    String detail = "Raw complete files differ";
    String javaNormalized = javaHash.sha256, nativeNormalized = nativeHash.sha256;
    boolean verified = false;
    if (operation.equals("pictures-replace")
      && original != null
      && javaBytes != null
      && nativeBytes != null
      && eq(original, 0, HexFormat.of().parseHex("1a45dfa3"))) {
      try {
        UidDocument before = new UidDocument(original, true),
          after = new UidDocument(javaBytes, false),
          reference = new UidDocument(nativeBytes, false);
        Set<Long> sourceUids = new HashSet<>();
        for (UidAttachment old : before.attachments())
          if (!sourceUids.add(old.uid)) throw uidFailure("Duplicate source FileUID");
        List<UidAttachment> a = after.attachments(), b = reference.attachments();
        if (a.size() != b.size()) throw uidFailure("Attachment collections differ");
        Set<Long> seenA = new HashSet<>(), seenB = new HashSet<>();
        for (int i = 0; i < a.size(); i++) {
          UidAttachment left = a.get(i), right = b.get(i);
          if (!seenA.add(left.uid) || !seenB.add(right.uid))
            throw uidFailure("Duplicate output FileUID");
          if (left.uid == right.uid) continue;
          if (!left.identity.equals(right.identity)
            || !left.mime.startsWith("image/")
            || left.uid == 0
            || right.uid == 0
            || sourceUids.contains(left.uid)
            || sourceUids.contains(right.uid))
            throw uidFailure("UID mismatch is not a fresh generated picture FileUID");
          after.admit(left.node);
          reference.admit(right.node);
          fields.add(
            new GeneratedUidField(
              left.node.body,
              right.node.body,
              8,
              left.node.end - left.node.body,
              right.node.end - right.node.body,
              left.name,
              left.mime,
              left.payloadSha,
              "Explicit PICTURE replacement; nonzero minimally encoded uint64 FileUIDs absent"
                + " from every source attachment and unique in each output; paired"
                + " name/MIME/description/data match. Canonical eight-byte UID values;"
                + " containing sizes and resolved SeekPositions are derived independently."));
        }
        if (fields.isEmpty()) throw uidFailure("No generated UID field accounts for the mismatch");
        after.prepare(before.segment.sizeWidth);
        reference.prepare(before.segment.sizeWidth);
        pairUidTrees(after.roots, reference.roots, after, reference, derived);
        byte[] normalizedA = after.canonical(), normalizedB = reference.canonical();
        javaNormalized = sha(normalizedA);
        nativeNormalized = sha(normalizedB);
        verified = Arrays.equals(normalizedA, normalizedB);
        policy +=
          "; only fresh Matroska PICTURE uint64 FileUIDs, source-proven containing sizes and"
            + " target-resolved SeekPositions normalized";
        detail =
          verified
            ? "Complete canonical bytes match; "
            + fields.size()
            + " fresh UID fields and "
            + derived.size()
            + " independently derived size/position effects"
            : "Bytes outside the generated UID values and their independently derived effects"
            + " differ";
      } catch (IllegalArgumentException | IndexOutOfBoundsException | ArithmeticException failure) {
        fields.clear();
        derived.clear();
        detail += "; " + failure.getMessage();
      }
    }
    return new NativeBytes(
      verified,
      false,
      policy,
      detail,
      javaHash.bytes,
      nativeHash.bytes,
      javaHash.sha256,
      nativeHash.sha256,
      javaNormalized,
      nativeNormalized,
      fields.size(),
      List.copyOf(fields),
      List.copyOf(derived));
  }

  private static String sha(byte[] bytes) {
    var hash = digest();
    hash.update(bytes);
    return HexFormat.of().formatHex(hash.digest());
  }

  private static IllegalArgumentException uidFailure(String reason) {
    return new IllegalArgumentException(reason);
  }

  private record UidAttachment(
    long uid, UidNode node, String name, String mime, String payloadSha, String identity) {
  }

  private static final class UidNode {
    final long id;
    final int start, body, end, idWidth, sizeWidth;
    final boolean unknown;
    final List<UidNode> children;
    boolean admitted, affected;
    UidNode target;
    int canonicalPosition;

    UidNode(byte[] bytes, int start, int limit, int depth, boolean source) {
      if (depth > 5) throw uidFailure("UID proof nesting exceeds known container grammar");
      Element element = element(bytes, start, limit);
      if (element == null) throw uidFailure("Malformed EBML framing");
      this.start = start;
      id = element.id;
      body = element.body;
      unknown = element.unknown;
      idWidth = Integer.numberOfLeadingZeros(bytes[start] & 255) - 23;
      sizeWidth = body - start - idWidth;
      if (unknown && id != 0x18538067L) {
        // Pinned renderVINT includes the reserved all-ones boundary (e.g. 127 -> ff).
        // Only output serialization can justify its exact numeric span; an unknown
        // source master has no bound from which source UID absence can be proven.
        long renderedSize = (1L << (7 * sizeWidth)) - 1;
        if (source || renderedSize > limit - body)
          throw uidFailure("Unbounded unknown-sized source/output element");
        end = Math.toIntExact(body + renderedSize);
      } else end = element.end;
      if (id == 0x18538067L
        || id == 0x1941a469L
        || id == 0x61a7L
        || id == 0x114d9b74L
        || id == 0x4dbbL) {
        var parsed = new ArrayList<UidNode>();
        for (int at = body; at < end; ) {
          if (parsed.size() >= 100_000) throw uidFailure("Too many UID proof elements");
          UidNode child = new UidNode(bytes, at, end, depth + 1, source);
          parsed.add(child);
          at = child.end;
        }
        children = List.copyOf(parsed);
      } else children = List.of();
    }

    byte[] raw(byte[] bytes) {
      return Arrays.copyOfRange(bytes, start, end);
    }
  }

  private static final class UidDocument {
    final byte[] bytes;
    final List<UidNode> roots;
    final UidNode segment;
    final List<UidNode> seeks = new ArrayList<>();
    int sourceSegmentWidth;

    UidDocument(byte[] bytes, boolean source) {
      this.bytes = bytes;
      var parsed = new ArrayList<UidNode>();
      UidNode found = null;
      for (int at = 0; at < bytes.length; ) {
        UidNode node = new UidNode(bytes, at, bytes.length, 0, source);
        parsed.add(node);
        at = node.end;
        if (node.id == 0x18538067L) {
          if (found != null) throw uidFailure("Ambiguous Segment");
          found = node;
        }
      }
      if (parsed.isEmpty() || parsed.getFirst().id != 0x1a45dfa3L || found == null)
        throw uidFailure("No complete EBML header and Segment");
      roots = List.copyOf(parsed);
      segment = found;
    }

    List<UidAttachment> attachments() {
      var result = new ArrayList<UidAttachment>();
      for (UidNode container : segment.children) {
        if (container.id != 0x1941a469L) continue;
        for (UidNode attached : container.children) {
          if (attached.id != 0x61a7L) continue;
          UidNode uid = null;
          String name = "", mime = "", description = "", payload = "";
          Set<Long> ids = new HashSet<>();
          for (UidNode field : attached.children) {
            if (!ids.add(field.id)) throw uidFailure("Duplicate AttachedFile field");
            int length = field.end - field.body;
            if (field.id == 0x46aeL) uid = field;
            else if (field.id == 0x466eL)
              name = new String(bytes, field.body, length, StandardCharsets.UTF_8);
            else if (field.id == 0x4660L)
              mime = new String(bytes, field.body, length, StandardCharsets.ISO_8859_1);
            else if (field.id == 0x467eL)
              description = new String(bytes, field.body, length, StandardCharsets.UTF_8);
            else if (field.id == 0x465cL)
              payload = sha(Arrays.copyOfRange(bytes, field.body, field.end));
          }
          if (uid == null || payload.isEmpty()) throw uidFailure("Incomplete AttachedFile");
          int width = uid.end - uid.body;
          if (width < 1 || width > 8) throw uidFailure("Unbounded FileUID");
          result.add(
            new UidAttachment(
              be(bytes, uid.body, width),
              uid,
              name,
              mime,
              payload,
              name + '\0' + mime + '\0' + description + '\0' + payload));
        }
      }
      return result;
    }

    void admit(UidNode uid) {
      int width = uid.end - uid.body;
      if (width != uidUIntWidth(be(bytes, uid.body, width)) || uid.sizeWidth != 1)
        throw uidFailure("Generated FileUID is not minimally encoded");
      uid.admitted = true;
    }

    void prepare(int sourceWidth) {
      sourceSegmentWidth = sourceWidth;
      if ((segment.unknown && segment.end - segment.body != (1L << (7 * segment.sizeWidth)) - 1)
        || segment.sizeWidth != uidSegmentWidth(segment.end - segment.body, sourceWidth))
        throw uidFailure("Segment size width violates source-preserved native rendering rule");
      for (UidNode head : segment.children)
        if (head.id == 0x114d9b74L) {
          for (UidNode seek : head.children)
            if (seek.id == 0x4dbbL) {
              UidNode id = null, position = null;
              for (UidNode field : seek.children) {
                if (field.id == 0x53abL) {
                  if (id != null) throw uidFailure("Duplicate SeekID");
                  id = field;
                }
                if (field.id == 0x53acL) {
                  if (position != null) throw uidFailure("Duplicate SeekPosition");
                  position = field;
                }
              }
              if (id == null || position == null) throw uidFailure("Incomplete Seek entry");
              int width = position.end - position.body;
              if (width < 1 || width > 8 || position.sizeWidth != 1)
                throw uidFailure("Unbounded SeekPosition");
              long value = be(bytes, position.body, width);
              if (value < 0 || width != uidUIntWidth(value))
                throw uidFailure("Nonminimal SeekPosition");
              for (UidNode candidate : segment.children)
                if ((long) candidate.start - segment.body == value
                  && Arrays.equals(
                  Arrays.copyOfRange(bytes, id.body, id.end),
                  Arrays.copyOfRange(
                    bytes, candidate.start, candidate.start + candidate.idWidth)))
                  position.target = candidate;
              if (position.target == null)
                throw uidFailure("SeekPosition does not resolve its exact SeekID target");
              position.canonicalPosition = (int) value;
              seeks.add(position);
            }
        }
      mark(segment);
    }

    boolean mark(UidNode node) {
      node.affected = node.admitted || node.target != null;
      for (UidNode child : node.children) node.affected |= mark(child);
      if (node.affected
        && !node.admitted
        && node.target == null
        && node != segment
        && node.sizeWidth != uidVintWidth(node.end - node.body))
        throw uidFailure("Affected master size is not native minimum-width rendering");
      return node.affected;
    }

    byte[] canonical() {
      // A SeekPosition's minimal UInt width can itself shift following targets. Resolve the
      // entire independently parsed layout to a fixed point; no original offsets are trusted.
      for (int round = 0; round < 16; round++) {
        IdentityHashMap<UidNode, Integer> positions = new IdentityHashMap<>();
        int at = 0;
        for (UidNode child : segment.children) {
          positions.put(child, at);
          at = Math.addExact(at, render(child).length);
        }
        boolean changed = false;
        for (UidNode seek : seeks) {
          int next = positions.get(seek.target);
          if (seek.canonicalPosition != next) {
            seek.canonicalPosition = next;
            changed = true;
          }
        }
        if (!changed) {
          var out = new ByteArrayOutputStream();
          for (UidNode root : roots) out.writeBytes(render(root));
          return out.toByteArray();
        }
      }
      throw uidFailure("Derived SeekPosition layout did not converge");
    }

    byte[] render(UidNode node) {
      if (!node.affected) return node.raw(bytes);
      byte[] body;
      if (node.admitted) body = new byte[]{1, 0, 0, 0, 0, 0, 0, 0};
      else if (node.target != null) body = uidUInt(node.canonicalPosition);
      else {
        var out = new ByteArrayOutputStream();
        for (UidNode child : node.children) out.writeBytes(render(child));
        body = out.toByteArray();
      }
      int width =
        node == segment
          ? uidSegmentWidth(body.length, sourceSegmentWidth)
          : uidVintWidth(body.length);
      var out = new ByteArrayOutputStream();
      out.writeBytes(Arrays.copyOfRange(bytes, node.start, node.start + node.idWidth));
      out.writeBytes(uidVint(body.length, width));
      out.writeBytes(body);
      return out.toByteArray();
    }
  }

  private static void pairUidTrees(
    List<UidNode> a,
    List<UidNode> b,
    UidDocument left,
    UidDocument right,
    List<DerivedUidField> effects) {
    if (a.size() != b.size())
      throw uidFailure("UID normalization cannot change element collections");
    for (int i = 0; i < a.size(); i++) {
      UidNode x = a.get(i), y = b.get(i);
      if (x.id != y.id
        || x.idWidth != y.idWidth
        || x.admitted != y.admitted
        || (x.target != null) != (y.target != null))
        throw uidFailure("Unrelated EBML structure differs");
      if (!x.affected && !y.affected) {
        if (!Arrays.equals(x.raw(left.bytes), y.raw(right.bytes)))
          throw uidFailure("Unrelated EBML bytes differ");
      } else {
        if (x.target != null) {
          int indexA = left.segment.children.indexOf(x.target),
            indexB = right.segment.children.indexOf(y.target);
          if (indexA != indexB) throw uidFailure("SeekPositions resolve different paired targets");
          long px = be(left.bytes, x.body, x.end - x.body),
            py = be(right.bytes, y.body, y.end - y.body);
          if (px != py)
            effects.add(
              new DerivedUidField(
                "SeekPosition",
                x.body,
                y.body,
                px,
                py,
                "Both exact SeekIDs resolve the same paired Segment child; offset delta="
                  + (px - py)
                  + " is the sum of preceding admitted UID and derived parent/position"
                  + " encoding width deltas"));
        } else if (!x.admitted
          && (x.end - x.body != y.end - y.body || x.sizeWidth != y.sizeWidth)) {
          effects.add(
            new DerivedUidField(
              "Master size 0x" + Long.toHexString(x.id),
              x.start + x.idWidth,
              y.start + y.idWidth,
              x.end - x.body,
              y.end - y.body,
              "Exact bounded child sequence; size delta is the sum of admitted descendant"
                + " UID/SeekPosition widths and their native minimum-width parent headers;"
                + " Segment uses source size width or widens to eight"));
        }
        pairUidTrees(x.children, y.children, left, right, effects);
      }
    }
  }

  private static int uidUIntWidth(long value) {
    int width = 1;
    while (width < 8 && value >>> (width * 8) != 0) width++;
    return width;
  }

  private static byte[] uidUInt(long value) {
    byte[] bytes = new byte[uidUIntWidth(value)];
    for (int i = bytes.length - 1; i >= 0; i--) {
      bytes[i] = (byte) value;
      value >>>= 8;
    }
    return bytes;
  }

  private static int uidVintWidth(long size) {
    for (int width = 1; width <= 8; width++) if (size <= (1L << (7 * width)) - 1) return width;
    throw uidFailure("Unbounded canonical EBML size");
  }

  private static int uidSegmentWidth(int size, int sourceWidth) {
    return size <= (1L << (7 * sourceWidth)) - 1 ? sourceWidth : 8;
  }

  private static byte[] uidVint(long size, int width) {
    byte[] bytes = new byte[width];
    long encoded = size | (1L << (7 * width));
    for (int i = width - 1; i >= 0; i--) {
      bytes[i] = (byte) encoded;
      encoded >>>= 8;
    }
    return bytes;
  }

  /**
   * A separate write proof for malformed envelopes. An absent media range is never counted as
   * preserved audio. The proof requires both original retained bytes and the complete independently
   * interpreted native/Java metadata effects to agree.
   */
  public record Preservation(
    boolean verified,
    boolean mediaAbsent,
    Hash before,
    Hash after,
    Hash nativeAfter,
    Hash javaStructure,
    Hash nativeStructure,
    String policy,
    String details) {
  }

  // Large real-world media uses the streaming FFmpeg fallback instead of allocating its file size.
  public static final int MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024;

  private PayloadHash() {
  }

  public static Hash wholeFile(Path path) throws IOException {
    var digest = digest();
    try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
      long bytes = input.transferTo(OutputStream.nullOutputStream());
      return result(digest, "Whole file", bytes);
    }
  }

  public static Hash of(Path path) throws IOException {
    if (Files.size(path) > MAX_IN_MEMORY_BYTES) return null;
    byte[] b;
    try (var input = Files.newInputStream(path)) {
      // A bounded read also protects against a file growing after the size check.
      b = input.readNBytes(MAX_IN_MEMORY_BYTES + 1);
      if (b.length > MAX_IN_MEMORY_BYTES) return null;
    }
    try {
      return extract(path.getFileName().toString().toLowerCase(Locale.ROOT), b);
    } catch (IllegalArgumentException | IndexOutOfBoundsException | ArithmeticException _) {
      return null;
    }
  }

  /**
   * Bounded, independent fallback for the pinned native MPEG and RIFF save policies. This does not
   * use the Java library, the native snapshot, a decoder's success/empty output, or fixture names.
   * A proof accounts for every physical byte as a retained range, metadata frame/chunk, explicit
   * padding, or a precisely reported rejected ID3 tail inside the source's declared tag envelope.
   * Unsupported structures remain unverified.
   */
  public static Preservation preservation(Path source, Path javaAfter, Path nativeAfter)
    throws IOException {
    byte[] original = bounded(source),
      rewritten = bounded(javaAfter),
      reference = bounded(nativeAfter);
    if (original == null || rewritten == null || reference == null) return null;
    String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
    try {
      if (name.matches(".*\\.(mp3|mp2|aac)$") && eq(original, 0, "ID3")) {
        var before = mpegStructure(original, true);
        var after = mpegStructure(rewritten, false);
        var nativeResult = mpegStructure(reference, false);
        if (before == null || after == null || nativeResult == null) return null;
        boolean retained =
          same(before.retained, after.retained)
            && same(before.retained, nativeResult.retained)
            && retainedFrames(before.preservedFrames, after.preservedFrames)
            && retainedFrames(before.preservedFrames, nativeResult.preservedFrames);
        boolean effects = same(after.metadata, nativeResult.metadata);
        return new Preservation(
          retained && effects,
          before.retained.bytes == 0,
          before.retained,
          after.retained,
          nativeResult.retained,
          after.metadata,
          nativeResult.metadata,
          "TagLib MPEG ID3 envelope replacement; all outside-tag bytes retained; complete"
            + " frame semantics compared independently, including compressed/binary frames",
          "Source declared ID3 end="
            + before.declaredEnd
            + "; physical tag end="
            + before.tagEnd
            + "; structurally accounted source frames="
            + before.frames
            + "; explicit zero padding="
            + before.padding
            + "; rejected source metadata tail="
            + before.rejected.bytes
            + " bytes SHA-256="
            + before.rejected.sha256
            + "; source-bound unmapped binary frame fields="
            + before.preservedFrames.values().stream().mapToInt(List::size).sum()
            + "; native and Java output have no unexplained tag bytes."
            + " A rejected tail is only accepted within an oversize source ID3 envelope,"
            + " following id3v2tag.cpp parse termination and MPEG::File::save replacement."
            + " No absence or preservation claim is inferred from decoding.");
      }
      if (eq(original, 0, "MPCK") || eq(original, 0, "MP+")) {
        var before = apeStructure(original, true);
        if (before != null && before.missingHeader.bytes == 32) {
          var after = apeStructure(rewritten, false);
          var nativeResult = apeStructure(reference, false);
          if (after == null || nativeResult == null) return null;
          boolean retained =
            same(before.retained, after.retained)
              && same(before.retained, nativeResult.retained)
              && retainedFrames(before.preserved, after.preserved)
              && retainedFrames(before.preserved, nativeResult.preserved);
          return new Preservation(
            retained && same(after.metadata, nativeResult.metadata),
            before.retained.bytes == 0,
            before.retained,
            after.retained,
            nativeResult.retained,
            after.metadata,
            nativeResult.metadata,
            "TagLib APE footer-declared replacement extent; complete original prefix"
              + " retained; missing physical header and rejected metadata bytes reported as"
              + " native effects; bounded complete output header/items/footer and retained"
              + " source binary fields verified independently",
            "Source footer declares a 32-byte header but its range lacks APETAGEX magic: offset="
              + before.start
              + "; discarded header range bytes="
              + before.missingHeader.bytes
              + "; discarded header range SHA-256="
              + before.missingHeader.sha256
              + "; rejected source item tail bytes="
              + before.rejected.bytes
              + "; rejected source item tail SHA-256="
              + before.rejected.sha256
              + "; retained nonmetadata prefix bytes="
              + before.retained.bytes
              + "; source-bound binary items="
              + before.preserved.values().stream().mapToInt(List::size).sum()
              + ". These discarded source ranges are not classified as preserved audio; every"
              + " byte outside the exact footer-declared tag extent must match both outputs.");
        }
      }
      if (eq(original, 0, "FORM") || eq(original, 0, "RIFF")) {
        var before = riffStructure(original, true);
        var after =
          riffStructure(rewritten, false, before == null ? null : before.terminalOversize);
        var nativeResult =
          riffStructure(reference, false, before == null ? null : before.terminalOversize);
        if (before == null || after == null || nativeResult == null) return null;
        boolean retained =
          same(before.retained, after.retained) && same(before.retained, nativeResult.retained);
        boolean effects =
          same(after.metadata, nativeResult.metadata)
            && Arrays.equals(
            Arrays.copyOfRange(rewritten, 4, 8), Arrays.copyOfRange(reference, 4, 8));
        return new Preservation(
          retained && effects,
          before.mediaBytes == 0 && before.tailBytes == 0,
          before.retained,
          after.retained,
          nativeResult.retained,
          after.metadata,
          nativeResult.metadata,
          "TagLib RIFF physical-EOF chunk scan/clamp/padding and ID3 chunk"
            + " removal/insertion; original nonmetadata chunk headers, bodies, padding and"
            + " entire opaque tail retained; complete metadata semantics and native global-size"
            + " effect compared independently",
          "Original parsed chunks="
            + before.chunks
            + "; retained bytes="
            + before.retained.bytes
            + "; opaque tail="
            + before.tailBytes
            + " bytes; recognized media chunk bytes="
            + before.mediaBytes
            + ". Source terminal oversized chunk="
            + (before.terminalOversize == null
            ? "none"
            : "declared body "
            + before.terminalOversize.declared
            + ", physical body "
            + before.terminalOversize.physical)
            + ". Native appends after the source-clamped physical body; any such suffix must"
            + " consist entirely of independently bounded ID3 or LIST/INFO metadata chunks. The"
            + " arbitrary tail is preserved as bytes, even when no valid media chunk can be"
            + " identified; it is never treated as empty decoded audio.");
      }
    } catch (IllegalArgumentException | IndexOutOfBoundsException | ArithmeticException _) {
      return null;
    }
    return null;
  }

  private static byte[] bounded(Path path) throws IOException {
    if (Files.size(path) > MAX_IN_MEMORY_BYTES) return null;
    try (var input = Files.newInputStream(path)) {
      byte[] bytes = input.readNBytes(MAX_IN_MEMORY_BYTES + 1);
      return bytes.length <= MAX_IN_MEMORY_BYTES ? bytes : null;
    }
  }

  private static boolean same(Hash a, Hash b) {
    return a.bytes == b.bytes && a.sha256.equals(b.sha256) && a.scope.equals(b.scope);
  }

  private record ApeStructure(
    int start,
    Hash retained,
    Hash metadata,
    Hash missingHeader,
    Hash rejected,
    Map<String, List<String>> preserved) {
  }

  private static ApeStructure apeStructure(byte[] bytes, boolean source) {
    int end = bytes.length;
    if (end < 32 || !eq(bytes, end - 32, "APETAGEX")) return null;
    int footer = end - 32;
    long declared = le(bytes, footer + 12, 4),
      flags = le(bytes, footer + 20, 4),
      count = le(bytes, footer + 16, 4);
    boolean header = (flags & 0x8000_0000L) != 0;
    long complete = declared + (header ? 32 : 0);
    if (declared < 32 || complete > end || count > 50_000) return null;
    int start = Math.toIntExact(end - complete), body = start + (header ? 32 : 0);
    if (!source
      && (!header
      || le(bytes, footer + 8, 4) != 2000
      || flags != 0x8000_0000L
      || le(bytes, footer + 24, 8) != 0
      || le(bytes, start + 20, 4) != 0xa000_0000L
      || le(bytes, start + 24, 8) != 0)) return null;
    var missing = digest();
    if (header && !eq(bytes, start, "APETAGEX")) {
      if (!source) return null;
      missing.update(bytes, start, 32);
    } else if (header
      && !source
      && !Arrays.equals(
      Arrays.copyOfRange(bytes, start, start + 20),
      Arrays.copyOfRange(bytes, footer, footer + 20))) return null;
    var retained = digest();
    retained.update(bytes, 0, start);
    var metadata = digest();
    metadata.update(bytes, start, end - start);
    var preserved = new LinkedHashMap<String, List<String>>();
    int at = body;
    for (long i = 0; i < count; i++) {
      if (footer - at < 8) {
        if (!source) return null;
        break;
      }
      long valueLength = le(bytes, at, 4), itemFlags = le(bytes, at + 4, 4);
      int keyEnd = at + 8;
      while (keyEnd < footer && bytes[keyEnd] != 0) keyEnd++;
      if (keyEnd == footer || valueLength > footer - keyEnd - 1) {
        if (!source) return null;
        break;
      }
      int next = Math.toIntExact(keyEnd + 1 + valueLength);
      String key = HexFormat.of().formatHex(Arrays.copyOfRange(bytes, at + 8, keyEnd));
      if ((itemFlags & 6) != 0)
        preserved
          .computeIfAbsent(key, _ -> new ArrayList<>())
          .add(sha(Arrays.copyOfRange(bytes, at, next)));
      at = next;
    }
    var rejected = digest();
    if (at < footer) {
      if (!source) return null;
      rejected.update(bytes, at, footer - at);
    }
    return new ApeStructure(
      start,
      result(retained, "APE complete original nonmetadata prefix", start),
      result(metadata, "APE complete independently bounded header/items/footer", end - start),
      result(
        missing,
        "APE footer-declared missing-header native discard",
        header && !eq(bytes, start, "APETAGEX") ? 32 : 0),
      result(rejected, "APE rejected source item tail", footer - at),
      preserved);
  }

  private record Id3Structure(
    int tagEnd,
    long declaredEnd,
    int frames,
    int padding,
    Hash rejected,
    Hash metadata,
    Map<String, List<String>> preservedFrames) {
  }

  private record MpegStructure(
    int tagEnd,
    long declaredEnd,
    int frames,
    int padding,
    Hash rejected,
    Hash metadata,
    Hash retained,
    Map<String, List<String>> preservedFrames) {
  }

  private static boolean retainedFrames(
    Map<String, List<String>> source, Map<String, List<String>> output) {
    return source.entrySet().stream()
      .allMatch(entry -> entry.getValue().equals(output.get(entry.getKey())));
  }

  private static MpegStructure mpegStructure(byte[] bytes, boolean source) {
    var id3 = id3Structure(bytes, 0, bytes.length, source);
    if (id3 == null) return null;
    int end = bytes.length;
    var metadata = digest();
    put(metadata, id3.metadata.sha256);
    if (end - id3.tagEnd >= 128 && eq(bytes, end - 128, "TAG")) {
      end -= 128;
      // Every legacy tag byte is compared too; different default language/genre or truncation
      // must not disappear merely because the high-level property snapshots agree.
      metadata.update(bytes, end, 128);
    }
    if (end < id3.tagEnd) return null;
    var retained = digest();
    retained.update(bytes, id3.tagEnd, end - id3.tagEnd);
    return new MpegStructure(
      id3.tagEnd,
      id3.declaredEnd,
      id3.frames,
      id3.padding,
      id3.rejected,
      result(metadata, "Complete ID3 frame semantics and ID3v1 bytes", 0),
      result(
        retained, "All MPEG bytes outside the physical ID3/ID3v1 envelope", end - id3.tagEnd),
      id3.preservedFrames);
  }

  private static Id3Structure id3Structure(byte[] b, int start, int limit, boolean source) {
    if (limit - start < 10 || !eq(b, start, "ID3")) return null;
    int version = b[start + 3] & 255, flags = b[start + 5] & 255;
    if ((version != 3 && version != 4) || b[start + 4] == (byte) 255 || (flags & ~32) != 0)
      return null;
    long declared = start + 10L + synch(b, start + 6, true);
    if (declared < start + 10L || (!source && declared > limit)) return null;
    int end = (int) Math.min(declared, limit), at = start + 10, frames = 0, padding = 0;
    var values = new TreeMap<String, List<String>>();
    var preserved = new TreeMap<String, List<String>>();
    var rejected = digest();
    int rejectedBytes = 0;
    while (at < end) {
      if (frames >= 50_000) return null;
      if (b[at] == 0) {
        for (int i = at; i < end; i++) if (b[i] != 0) return null;
        padding = end - at;
        at = end;
        break;
      }
      if (end - at < 10 || !frameId(b, at)) {
        // This is the native parser's discarded metadata region, not a media extractor clamp.
        // Require the original oversize envelope and a bounded explicit rejected range. Outputs
        // must be fully framed, so identical corrupt outputs cannot prove an unsupported layout.
        if (!source || declared <= limit) return null;
        rejectedBytes = end - at;
        rejected.update(b, at, rejectedBytes);
        at = end;
        break;
      }
      long length = version == 3 ? be(b, at + 4, 4) : synch(b, at + 4, false);
      if (length > end - at - 10L) return null;
      String id = new String(b, at, 4, StandardCharsets.ISO_8859_1);
      byte[] fields = frameFields(b, at, (int) length, version);
      if (fields == null) return null;
      String canonical = canonicalFrame(id, fields);
      if (canonical == null) return null;
      // TYER's v3 year-only representation is promoted by the native default v4 save.
      String canonicalId = id.equals("TYER") ? "TDRC" : id;
      values.computeIfAbsent(canonicalId, _ -> new ArrayList<>()).add(canonical);
      int status = b[at + 8] & 255, format = b[at + 9] & 255;
      boolean discardedOnAlter = (status & (version == 3 ? 128 : 64)) != 0;
      boolean encrypted = (format & (version == 3 ? 64 : 4)) != 0;
      // Generic replaceProperties cannot modify these unmapped frame types. Require their exact
      // native-interpreted source fields, even if both output files suffer identical damage.
      // The only discard here is the explicit native tag-alter flag or an empty URL render.
      if (!discardedOnAlter
        && !encrypted
        && Set.of("APIC", "POPM", "PRIV", "GEOB", "UFID", "W000").contains(id)
        && !(id.equals("UFID")
        && new String(fields, 0, delimiter(fields, 0, 0), StandardCharsets.ISO_8859_1)
        .equals("http://musicbrainz.org"))
        && !(id.equals("W000") && delimiter(fields, 0, 0) == 0))
        preserved.computeIfAbsent(id, _ -> new ArrayList<>()).add(canonical);
      at += 10 + (int) length;
      frames++;
    }
    if (at != end) return null;
    var metadata = digest();
    put(metadata, Integer.toString(flags & 32));
    values.forEach(
      (id, list) -> {
        put(metadata, id);
        // Preserve duplicate order within an ID; frame IDs can move when mapped text is replaced.
        list.forEach(value -> put(metadata, value));
      });
    return new Id3Structure(
      end,
      declared,
      frames,
      padding,
      result(rejected, "Rejected bytes inside oversize source ID3 envelope", rejectedBytes),
      result(metadata, "All independently interpreted ID3 frame fields", frames),
      preserved);
  }

  private static boolean frameId(byte[] b, int at) {
    for (int i = 0; i < 4; i++) {
      int value = b[at + i] & 255;
      if (!(value >= 'A' && value <= 'Z') && !(value >= '0' && value <= '9')) return false;
    }
    return true;
  }

  private static long synch(byte[] b, int at, boolean strict) {
    require(b, at, 4);
    long value = 0;
    for (int i = at; i < at + 4; i++) {
      if ((b[i] & 128) != 0) {
        if (strict) throw new IllegalArgumentException("Invalid syncsafe tag size");
        return be(b, at, 4); // Native SynchData's legacy ordinary-integer fallback.
      }
      value = value * 128 + (b[i] & 127);
    }
    return value;
  }

  private static byte[] frameFields(byte[] b, int at, int length, int version) {
    int flags = b[at + 9] & 255;
    boolean compressed = (flags & (version == 3 ? 128 : 8)) != 0;
    boolean encrypted = (flags & (version == 3 ? 64 : 4)) != 0;
    boolean indicator = version == 4 && (flags & 1) != 0;
    if ((flags & (version == 3 ? 32 : 64)) != 0) return null; // Grouping needs a distinct parser.
    byte[] fields = Arrays.copyOfRange(b, at + 10, at + 10 + length);
    if (version == 4 && (flags & 2) != 0) fields = unsynchronised(fields);
    if (!compressed && !indicator) return fields;
    if (fields.length < 4) return null;
    long wanted = synch(fields, 0, false);
    byte[] data = Arrays.copyOfRange(fields, 4, fields.length);
    if (!compressed || encrypted)
      return Arrays.copyOfRange(data, 0, (int) Math.min(wanted, data.length));
    // Source: Frame::fieldData maxCompressedFrameOutputSize/maxCompressedFrameRatio and
    // tzlib.cpp's output truncation. Bound actual allocation and progress independently.
    if (wanted > 64 * 1024 * 1024L || wanted > data.length * 64L) return new byte[0];
    if (wanted > MAX_IN_MEMORY_BYTES) return null;
    var inflater = new Inflater();
    try {
      inflater.setInput(data);
      var out = new ByteArrayOutputStream();
      byte[] block = new byte[1024];
      while (true) {
        int count = inflater.inflate(block);
        if (count > 0) {
          int accepted = (int) Math.min(count, wanted - out.size());
          out.write(block, 0, accepted);
          if (count > accepted || inflater.finished() || count < block.length)
            return out.toByteArray();
        } else if (inflater.finished() || inflater.needsInput()) return out.toByteArray();
        else return null;
        if (out.size() == wanted) return out.toByteArray();
      }
    } catch (DataFormatException _) {
      return new byte[0]; // Native zlib::decompress rejects the malformed compressed frame.
    } finally {
      inflater.end();
    }
  }

  private static byte[] unsynchronised(byte[] bytes) {
    var out = new ByteArrayOutputStream();
    for (int i = 0; i < bytes.length; i++) {
      out.write(bytes[i]);
      if (bytes[i] == (byte) 255 && i + 1 < bytes.length && bytes[i + 1] == 0) i++;
    }
    return out.toByteArray();
  }

  private static String canonicalFrame(String id, byte[] fields) {
    var value = digest();
    if (id.startsWith("T")) {
      List<String> texts = textValues(fields, id.equals("TXXX"));
      texts.forEach(text -> put(value, text));
    } else if (id.equals("COMM") || id.equals("USLT")) {
      if (fields.length < 4) return null;
      int encoding = encoding(fields[0]);
      put(value, new String(fields, 1, 3, StandardCharsets.ISO_8859_1));
      int end = delimiter(fields, 4, encoding);
      put(value, text(fields, 4, end, encoding));
      int next = Math.min(fields.length, end + (encoding == 0 || encoding == 3 ? 1 : 2));
      put(value, text(fields, next, fields.length, encoding));
    } else if (id.equals("APIC")) {
      Field mime = fields.length < 5 ? new Field("", 1) : stringField(fields, 1, 0);
      int type = 0, pictureAt = fields.length;
      Field description = new Field("", fields.length);
      if (fields.length >= 5 && mime.next + 1 < fields.length) {
        type = fields[mime.next] & 255;
        if (type > 20) type = 0;
        description = stringField(fields, mime.next + 1, encoding(fields[0]));
        pictureAt = description.next;
      }
      put(value, mime.value);
      put(value, Integer.toString(type));
      put(value, description.value);
      value.update(fields, pictureAt, fields.length - pictureAt);
    } else if (id.equals("GEOB")) {
      Field mime = new Field("", fields.length), name = mime, description = mime;
      if (fields.length >= 4) {
        mime = stringField(fields, 1, 0);
        name = stringField(fields, mime.next, encoding(fields[0]));
        description = stringField(fields, name.next, encoding(fields[0]));
      }
      put(value, mime.value);
      put(value, name.value);
      put(value, description.value);
      value.update(fields, description.next, fields.length - description.next);
    } else if (id.equals("POPM")) {
      Field email = stringField(fields, 0, 0);
      put(value, email.value);
      int rating = email.next < fields.length ? fields[email.next] & 255 : 0;
      int counterAt = Math.min(fields.length, email.next + 1);
      put(value, Integer.toString(rating));
      put(value, Long.toString(be(fields, counterAt, Math.min(4, fields.length - counterAt))));
    } else if (id.equals("UFID")) {
      Field owner = stringField(fields, 0, 0);
      put(value, owner.value);
      value.update(fields, owner.next, fields.length - owner.next);
    } else if (id.equals("PRIV")) {
      int end = delimiter(fields, 0, 0);
      if (fields.length < 2) put(value, "");
      else {
        put(value, text(fields, 0, end, 0));
        int payload = end == fields.length ? 0 : end + 1;
        value.update(fields, payload, fields.length - payload);
      }
    } else if (id.equals("W000")) {
      put(value, text(fields, 0, delimiter(fields, 0, 0), 0));
    } else {
      // Unknown and binary frames retain every interpreted byte, including W000 and POPM.
      value.update(fields);
    }
    return HexFormat.of().formatHex(value.digest());
  }

  private record Field(String value, int next) {
  }

  private static Field stringField(byte[] fields, int at, int encoding) {
    int end = delimiter(fields, at, encoding), width = encoding == 0 || encoding == 3 ? 1 : 2;
    // Frame::readStringField leaves its position unchanged when the delimiter is absent.
    return end >= fields.length
      ? new Field("", at)
      : new Field(text(fields, at, end, encoding), end + width);
  }

  private static List<String> textValues(byte[] bytes, boolean userText) {
    if (bytes.length < 2) return userText ? List.of("") : List.of();
    int encoding = encoding(bytes[0]), at = 1;
    var values = new ArrayList<String>();
    while (at < bytes.length) {
      int end = delimiter(bytes, at, encoding);
      String value = text(bytes, at, end, encoding);
      if (!value.isEmpty() || (userText && values.isEmpty())) values.add(value);
      at = end + (encoding == 0 || encoding == 3 ? 1 : 2);
    }
    return values.isEmpty() && userText ? List.of("") : values;
  }

  private static int encoding(byte value) {
    int encoding = value & 255;
    return encoding <= 4 ? encoding : 0; // Native Utils::textEncodingFromByte fallback.
  }

  private static int delimiter(byte[] bytes, int at, int encoding) {
    if (encoding == 0 || encoding == 3) {
      while (at < bytes.length && bytes[at] != 0) at++;
      return at;
    }
    for (int i = at; i + 1 < bytes.length; i += 2) if (bytes[i] == 0 && bytes[i + 1] == 0) return i;
    return bytes.length;
  }

  private static String text(byte[] b, int start, int end, int encoding) {
    var charset =
      switch (encoding) {
        case 1 -> StandardCharsets.UTF_16;
        case 2 -> StandardCharsets.UTF_16BE;
        case 3 -> StandardCharsets.UTF_8;
        case 4 -> StandardCharsets.UTF_16LE;
        default -> StandardCharsets.ISO_8859_1;
      };
    String text = new String(b, start, end - start, charset);
    while (text.endsWith("\0")) text = text.substring(0, text.length() - 1);
    return text;
  }

  private static void put(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(
      new byte[]{
        (byte) (bytes.length >>> 24),
        (byte) (bytes.length >>> 16),
        (byte) (bytes.length >>> 8),
        (byte) bytes.length
      });
    digest.update(bytes);
  }

  private record OversizeChunk(byte[] header, long declared, int physical) {
  }

  private record RiffStructure(
    Hash retained,
    Hash metadata,
    int chunks,
    int tailBytes,
    long mediaBytes,
    OversizeChunk terminalOversize) {
  }

  private static RiffStructure riffStructure(byte[] b, boolean source) {
    return riffStructure(b, source, null);
  }

  private static RiffStructure riffStructure(
    byte[] b, boolean source, OversizeChunk sourceOversize) {
    if (b.length < 12 || !(eq(b, 0, "FORM") || eq(b, 0, "RIFF"))) return null;
    boolean big = eq(b, 0, "FORM");
    var retained = digest();
    retained.update(b, 0, 4);
    retained.update(b, 8, 4);
    long kept = 8, media = 0;
    int at = 12, count = 0;
    var metadata = new TreeMap<String, List<String>>();
    OversizeChunk terminalOversize = null;
    boolean appendedMetadata = false;
    while (b.length - at >= 8 && riffId(b, at)) {
      long declared = big ? be(b, at + 4, 4) : le(b, at + 4, 4);
      int length = (int) Math.min(declared, b.length - at - 8L), end = at + 8 + length;
      boolean terminalSourceChunk = false;
      if (!source
        && !appendedMetadata
        && sourceOversize != null
        && Arrays.equals(sourceOversize.header, Arrays.copyOfRange(b, at, at + 8))) {
        if (sourceOversize.physical > b.length - at - 8) return null;
        length = sourceOversize.physical;
        end = at + 8 + length;
        appendedMetadata = true;
        terminalSourceChunk = true;
      } else if (appendedMetadata && declared > b.length - at - 8L) return null;
      int padding = 0;
      if ((end & 1) != 0
        && end < b.length
        && (b[end] == 0 || (b.length - end >= 5 && riffId(b, end + 1)))) padding = 1;
      if (appendedMetadata && !terminalSourceChunk && padding != 0 && b[end] != 0) return null;
      boolean id3 = eq(b, at, "ID3 ") || eq(b, at, "id3 ");
      boolean hiddenInfo =
        appendedMetadata
          && !terminalSourceChunk
          && eq(b, at, "LIST")
          && length >= 4
          && eq(b, at + 8, "INFO");
      if (id3) {
        if (length > 0 && !source) {
          var tag = id3Structure(b, at + 8, end, false);
          if (tag == null || tag.tagEnd != end) return null;
          metadata.computeIfAbsent("ID3", _ -> new ArrayList<>()).add(tag.metadata.sha256);
        }
      } else if (hiddenInfo) {
        if (!completeInfo(b, at + 12, end, big)) return null;
        var info = digest();
        info.update(b, at, 8 + length + padding);
        metadata
          .computeIfAbsent("LIST/INFO", _ -> new ArrayList<>())
          .add(HexFormat.of().formatHex(info.digest()));
      } else {
        // Once the source-sized final chunk has been consumed, only newly appended,
        // complete metadata is permitted. A native-equivalent invented opaque suffix fails.
        if (appendedMetadata && !terminalSourceChunk) return null;
        // Retain the physical chunk header, including an oversize declaration; this is stronger
        // than hashing only recognized audio and detects damage to COMM/fmt/index/unknown chunks.
        retained.update(b, at, 8 + length + padding);
        kept += 8L + length + padding;
        if (eq(b, at, big ? "SSND" : "data")) media += length;
        if (source && declared > length && end == b.length)
          terminalOversize = new OversizeChunk(Arrays.copyOfRange(b, at, at + 8), declared, length);
      }
      at = end + padding;
      if (++count > MAX_IN_MEMORY_BYTES / 8) return null;
    }
    int tail = b.length - at;
    if (appendedMetadata && tail != 0) return null;
    retained.update(b, at, tail);
    kept += tail;
    var structure = digest();
    metadata.forEach(
      (id, list) -> {
        put(structure, id);
        list.forEach(value -> put(structure, value));
      });
    return new RiffStructure(
      result(retained, "RIFF original structure and every nonmetadata byte", kept),
      result(structure, "RIFF complete independently interpreted metadata", 0),
      count,
      tail,
      media,
      terminalOversize);
  }

  private static boolean completeInfo(byte[] bytes, int at, int end, boolean big) {
    while (at < end) {
      if (end - at < 8 || !riffId(bytes, at)) return false;
      long length = big ? be(bytes, at + 4, 4) : le(bytes, at + 4, 4);
      if (length > end - at - 8L) return false;
      int next = at + 8 + (int) length;
      if ((length & 1) != 0) {
        if (next >= end || bytes[next] != 0) return false;
        next++;
      }
      at = next;
    }
    return at == end;
  }

  private static boolean riffId(byte[] bytes, int at) {
    if (at < 0 || bytes.length - at < 4) return false;
    for (int i = at; i < at + 4; i++) {
      int value = bytes[i] & 255;
      if (value < 32 || value > 127) return false;
    }
    return true;
  }

  private static Hash extract(String name, byte[] b) {
    var digest = digest();
    long bytes = 0;
    int start = 0, end = b.length;
    if (eq(b, 0, "ID3")) {
      start = completeId3End(b, 0, end);
    }
    while (end > start) {
      if (end - start >= 128 && eq(b, end - 128, "TAG")) {
        end -= 128;
        continue;
      }
      if (end - start >= 32 && eq(b, end - 32, "APETAGEX")) {
        long size = le(b, end - 20, 4);
        // APE::Footer::completeTagSize follows the footer flag, not header magic.
        // Even malformed files declare that complete replacement extent to native save.
        long complete = size + ((le(b, end - 12, 4) & 0x8000_0000L) != 0 ? 32 : 0);
        if (size < 32 || complete > end - start) return null;
        if (complete > size && !eq(b, Math.toIntExact(end - complete), "APETAGEX"))
          return null; // A missing declared header requires the separate named native-effect proof.
        end -= Math.toIntExact(complete);
        continue;
      }
      break;
    }
    if (eq(b, start, "fLaC")) {
      int p = start + 4;
      boolean last = false;
      while (!last) {
        require(b, p, 4);
        last = (b[p] & 128) != 0;
        int len = Math.toIntExact(be(b, p + 1, 3));
        p = Math.addExact(p, 4 + len);
        require(b, 0, p);
      }
      if (p > end) return null;
      digest.update(b, p, end - p);
      return result(digest, "FLAC encoded frames", end - p);
    }
    if (name.matches(".*\\.(mp3|mp2|aac)$")) {
      int tagAt = start > 0 ? 0 : -1;
      if (tagAt < 0)
        for (int p = 0; p < Math.min(end, 3072); p++)
          if (eq(b, p, "ID3")) {
            tagAt = p;
            break;
          }
      if (tagAt >= 0) {
        int tagEnd = completeId3End(b, tagAt, end);
        digest.update(b, 0, tagAt);
        digest.update(b, tagEnd, end - tagEnd);
        return result(
          digest,
          "MPEG bytes outside parsed ID3/APEv2 tags, including arbitrary prefix/suffix",
          tagAt + (long) end - tagEnd);
      }
      digest.update(b, 0, end);
      return result(
        digest,
        "MPEG bytes outside parsed ID3/APEv2 tags, including arbitrary prefix/suffix",
        end);
    }
    if (name.matches(".*\\.(ape|wv|mpc|tta)$")) {
      if (start > end) return null;
      digest.update(b, start, end - start);
      return result(digest, "Encoded stream excluding outer ID3/APEv2 tags", end - start);
    }
    if (eq(b, 0, "OggS")) return ogg(b);
    if (name.matches(".*\\.(mod|s3m|it|xm)$")) return tracker(name, b);
    if (eq(b, 0, HexFormat.of().parseHex("1a45dfa3"))) return matroska(b);
    if (eq(b, 0, "RIFF") || eq(b, 0, "RF64")) {
      if (!eq(b, 8, "WAVE")) return null;
      long largeData = -1;
      int p = 12;
      while (p + 8 <= b.length) {
        long len = le(b, p + 4, 4);
        if (eq(b, p, "ds64") && len >= 28) largeData = le(b, p + 16, 8);
        if (eq(b, p, "data") && len == 0xffffffffL) len = largeData;
        if (len < 0 || len > Integer.MAX_VALUE) return null;
        require(b, p + 8, (int) len);
        if (eq(b, p, "data")) {
          digest.update(b, p + 8, (int) len);
          bytes += len;
        }
        p = Math.addExact(p, 8 + Math.toIntExact(len + (len & 1)));
      }
      return result(digest, "WAVE data chunks", bytes);
    }
    if (eq(b, 0, "FORM")) {
      int p = 12;
      while (p + 8 <= b.length) {
        int len = Math.toIntExact(be(b, p + 4, 4));
        require(b, p + 8, len);
        if (eq(b, p, "SSND")) {
          digest.update(b, p + 8, len);
          bytes += len;
        }
        p = Math.addExact(p, 8 + len + (len & 1));
      }
      return result(digest, "AIFF SSND chunks including offset/block headers", bytes);
    }
    if (eq(b, 0, "DSD ")) {
      int p = 0;
      while (p + 12 <= b.length) {
        long len = le(b, p + 4, 8);
        if (len < 12 || len > Integer.MAX_VALUE) return null;
        require(b, p, (int) len);
        if (eq(b, p, "data")) {
          digest.update(b, p + 12, (int) len - 12);
          bytes += len - 12;
          break;
        }
        p += Math.toIntExact(len);
      }
      return bytes > 0 ? result(digest, "DSF data chunk", bytes) : null;
    }
    if (eq(b, 0, "FRM8")) {
      int p = 16;
      while (p + 12 <= b.length) {
        long len = be(b, p + 4, 8);
        if (len < 0 || len > Integer.MAX_VALUE) return null;
        require(b, p + 12, (int) len);
        if (eq(b, p, "DSD ") || eq(b, p, "DST ")) {
          digest.update(b, p + 12, (int) len);
          bytes += len;
        }
        p = Math.addExact(p, 12 + Math.toIntExact(len + (len & 1)));
      }
      return bytes > 0 ? result(digest, "DSDIFF DSD/DST chunks", bytes) : null;
    }
    if (name.matches(".*\\.(mp4|m4a|m4v|m4b|m4p|m4r|3g2)$")) {
      int legacyMetadata = legacyMetadataStart(b);
      if (legacyMetadata >= 0)
        return legacyMetadataOnlyMp4(b, legacyMetadata)
          ? result(digest, "All MP4 mdat payloads (audio and video)", 0)
          : null;
      int p = 0;
      while (p + 8 <= b.length) {
        long len = be(b, p, 4);
        int header = 8;
        if (len == 1) {
          len = be(b, p + 8, 8);
          header = 16;
        }
        if (len == 0) len = b.length - p;
        // A terminal free atom can declare padding beyond EOF; its physical bytes are
        // still padding. Keep truncated media and all other atom types unverified.
        if (len > b.length - p && eq(b, p + 4, "free")) len = b.length - p;
        if (len < header || len > Integer.MAX_VALUE) return null;
        require(b, p, (int) len);
        if (eq(b, p + 4, "mdat")) {
          digest.update(b, p + header, (int) len - header);
          bytes += len - header;
        }
        p += Math.toIntExact(len);
      }
      if (p != b.length) return null;
      return result(digest, "All MP4 mdat payloads (audio and video)", bytes);
    }
    // ASF data object GUID, followed by a uint64 little-endian object size.
    byte[] asfData = HexFormat.of().parseHex("3626b2758e66cf11a6d900aa0062ce6c");
    if (b.length >= 30 && eq(b, 0, HexFormat.of().parseHex("3026b2758e66cf11a6d900aa0062ce6c"))) {
      int p = 0;
      while (p + 24 <= b.length) {
        long len = le(b, p + 16, 8);
        if (len < 24 || len > Integer.MAX_VALUE) return null;
        require(b, p, (int) len);
        if (eq(b, p, asfData)) {
          digest.update(b, p + 24, (int) len - 24);
          bytes += len - 24;
        }
        p += Math.toIntExact(len);
      }
      return bytes > 0 ? result(digest, "ASF data objects", bytes) : null;
    }
    return null;
  }

  /**
   * Native ID3v2::Tag::read folds immediately consecutive complete tag headers into capacity.
   */
  private static int completeId3End(byte[] bytes, int offset, int physicalEnd) {
    int at = offset, count = 0;
    do {
      if (++count > 50_000 || physicalEnd - at < 10)
        throw new IllegalArgumentException("Unbounded or truncated consecutive ID3 envelope");
      if (bytes[at + 3] < 2 || bytes[at + 3] > 4)
        throw new IllegalArgumentException("Unsupported ID3 envelope version");
      int length = 0;
      for (int i = 6; i < 10; i++) {
        if ((bytes[at + i] & 128) != 0)
          throw new IllegalArgumentException("Invalid ID3 syncsafe envelope");
        length = (length << 7) | (bytes[at + i] & 127);
      }
      at =
        Math.addExact(
          at,
          Math.addExact(
            length, 10 + (bytes[at + 3] == 4 && (bytes[at + 5] & 16) != 0 ? 10 : 0)));
      if (at > physicalEnd) throw new IllegalArgumentException("Truncated declared ID3 envelope");
    } while (eq(bytes, at, "ID3"));
    return at;
  }

  /**
   * Independently establishes absence of media in the historical extended-size TagLib fixture and
   * its native rewrites. All three enclosing sizes undercount the same eight bytes. No bytes may
   * lie outside the exact moov/udta/meta chain, its ilst items, or explicit free padding. This is
   * an empty payload result, not positive evidence that any media bytes survived a write.
   */
  private static int legacyMetadataStart(byte[] b) {
    int at = 0;
    for (String container : List.of("moov", "udta", "meta")) {
      if (at + 16 > b.length
        || be(b, at, 4) != 1
        || !eq(b, at + 4, container)
        || be(b, at + 8, 8) != b.length - at - 8L) return -1;
      at += 16;
    }
    return at;
  }

  private static boolean legacyMetadataOnlyMp4(byte[] b, int at) {
    if (at + 4 > b.length || be(b, at, 4) != 0) return false;
    at += 4;
    boolean foundItems = false;
    while (at < b.length) {
      int end = boundedMp4End(b, at, b.length, 8);
      if (end < 0) return false;
      if (eq(b, at + 4, "ilst")) {
        if (foundItems || !metadataItemsOnly(b, at + 8, end)) return false;
        foundItems = true;
      } else if (!eq(b, at + 4, "free")) return false;
      at = end;
    }
    return foundItems;
  }

  private static boolean metadataItemsOnly(byte[] b, int at, int end) {
    while (at < end) {
      int itemEnd = boundedMp4End(b, at, end, 8);
      if (itemEnd < 0) return false;
      int child = at + 8;
      boolean foundData = false;
      while (child < itemEnd) {
        int minimum;
        if (eq(b, child + 4, "data")) {
          minimum = 16; // Full-box flags and locale precede the metadata value.
          foundData = true;
        } else if (eq(b, child + 4, "mean") || eq(b, child + 4, "name")) minimum = 12;
        else return false;
        child = boundedMp4End(b, child, itemEnd, minimum);
        if (child < 0) return false;
      }
      if (!foundData) return false;
      at = itemEnd;
    }
    return true;
  }

  private static int boundedMp4End(byte[] b, int at, int end, int minimum) {
    if (at + 8 > end) return -1;
    long length = be(b, at, 4);
    return length >= minimum && length <= end - at ? at + (int) length : -1;
  }

  private record Range(int start, int length) {
  }

  private static Hash tracker(String name, byte[] input) {
    byte[] b = input.clone();
    var skipped = new ArrayList<Range>();
    if (name.endsWith(".mod")) {
      require(b, 0, 1084);
      String magic = new String(b, 1080, 4, StandardCharsets.ISO_8859_1);
      boolean thirtyOne =
        Set.of("M.K.", "M!K!", "M&K!", "N.T.", "CD81", "OKTA").contains(magic)
          || magic.matches("(?:FLT|TDZ)[0-9]|[0-9]CHN|[0-9]{2}(?:CH|CN)");
      skipped.add(new Range(0, 20));
      for (int i = 0; i < (thirtyOne ? 31 : 15); i++) skipped.add(new Range(20 + i * 30, 22));
    } else if (name.endsWith(".s3m")) {
      require(b, 0, 96);
      if (!eq(b, 44, "SCRM")) return null;
      skipped.add(new Range(0, 28));
      int orders = (int) le(b, 32, 2), instruments = (int) le(b, 34, 2);
      for (int i = 0; i < instruments; i++) {
        int p = Math.toIntExact(le(b, 96 + orders + i * 2, 2) * 16);
        require(b, p, 80);
        skipped.add(new Range(p + 48, 28));
      }
    } else if (name.endsWith(".it")) {
      require(b, 0, 192);
      if (!eq(b, 0, "IMPM")) return null;
      skipped.add(new Range(4, 26));
      int orders = (int) le(b, 32, 2),
        instruments = (int) le(b, 34, 2),
        samples = (int) le(b, 36, 2);
      for (int i = 0; i < instruments + samples; i++) {
        int p = Math.toIntExact(le(b, 192 + orders + i * 4, 4));
        if (!eq(b, p, i < instruments ? "IMPI" : "IMPS")) return null;
        skipped.add(new Range(p + (i < instruments ? 32 : 20), 26));
      }
      if ((b[46] & 1) != 0) {
        int size = (int) le(b, 54, 2), offset = Math.toIntExact(le(b, 56, 4));
        if (size > 0) skipped.add(new Range(offset, size));
      }
      b[46] &= (byte) 0xfe;
      skipped.add(new Range(54, 6));
    } else {
      require(b, 0, 80);
      skipped.add(new Range(17, 20));
      skipped.add(new Range(38, 20));
      int header = Math.toIntExact(le(b, 60, 4));
      if (header < 20) return null;
      int patterns = (int) le(b, 70, 2), instruments = (int) le(b, 72, 2), p = 60 + header;
      for (int i = 0; i < patterns; i++) {
        int size = Math.toIntExact(le(b, p, 4));
        if (size < 9) return null;
        int packed = (int) le(b, p + 7, 2);
        require(b, p, size + packed);
        p += size + packed;
      }
      for (int i = 0; i < instruments; i++) {
        int size = Math.toIntExact(le(b, p, 4));
        if (size < 4) return null;
        require(b, p, size);
        skipped.add(new Range(p + 4, Math.min(22, size - 4)));
        int count = size >= 29 ? (int) le(b, p + 27, 2) : 0,
          sampleHeader = count > 0 ? Math.toIntExact(le(b, p + 29, 4)) : 0;
        if (count > 0 && sampleHeader == 0) sampleHeader = 40;
        p += size;
        long sampleBytes = 0;
        for (int j = 0; j < count; j++) {
          require(b, p, sampleHeader);
          if (sampleHeader >= 4) sampleBytes += le(b, p, 4);
          skipped.add(
            new Range(
              p + Math.min(18, sampleHeader), Math.min(22, Math.max(0, sampleHeader - 18))));
          p += sampleHeader;
        }
        require(b, p, Math.toIntExact(sampleBytes));
        p += Math.toIntExact(sampleBytes);
      }
    }
    skipped.sort(Comparator.comparingInt(Range::start));
    var digest = digest();
    int position = 0;
    long bytes = 0;
    for (var range : skipped) {
      require(b, range.start, range.length);
      if (range.start < position) return null;
      digest.update(b, position, range.start - position);
      bytes += range.start - position;
      position = range.start + range.length;
    }
    digest.update(b, position, b.length - position);
    bytes += b.length - position;
    return result(
      digest, "Tracker structure/pattern/sample bytes excluding names and messages", bytes);
  }

  private record Element(long id, int body, int end, boolean unknown) {
  }

  private static Element element(byte[] b, int offset, int limit) {
    require(b, offset, 1);
    int width = Integer.numberOfLeadingZeros(b[offset] & 255) - 24 + 1;
    if (width < 1 || width > 4) return null;
    long id = be(b, offset, width);
    int sizeAt = offset + width;
    require(b, sizeAt, 1);
    int sizeWidth = Integer.numberOfLeadingZeros(b[sizeAt] & 255) - 24 + 1;
    if (sizeWidth < 1 || sizeWidth > 8) return null;
    long size =
      be(b, sizeAt, sizeWidth)
        & (sizeWidth == 8 ? 0x00ffffffffffffffL : (1L << (7 * sizeWidth)) - 1);
    boolean unknown = size == (sizeWidth == 8 ? 0x00ffffffffffffffL : (1L << (7 * sizeWidth)) - 1);
    int body = sizeAt + sizeWidth;
    long end = unknown ? limit : body + size;
    if (end < body || end > limit) return null;
    return new Element(id, body, (int) end, unknown);
  }

  private static Hash matroska(byte[] b) {
    var digest = digest();
    long bytes = 0;
    int p = 0;
    while (p < b.length) {
      var top = element(b, p, b.length);
      if (top == null) return null;
      if (top.id == 0x18538067L) {
        int c = top.body;
        while (c < top.end) {
          var child = element(b, c, top.end);
          if (child == null) return null;
          if (child.id == 0x1f43b675L) {
            if (child.unknown) return null;
            digest.update(b, c, child.end - c);
            bytes += child.end - c;
          }
          c = child.end;
        }
      }
      p = top.end;
    }
    return result(
      digest, "Complete Matroska Cluster elements including encoded media and timing", bytes);
  }

  private static final class LogicalStream {
    final ByteArrayOutputStream packet = new ByteArrayOutputStream();
    int packetNumber = 0, skip = -1;
  }

  private static Hash ogg(byte[] b) {
    var streams = new HashMap<Long, LogicalStream>();
    var digest = digest();
    long bytes = 0;
    int p = 0;
    while (p < b.length) {
      require(b, p, 27);
      if (!eq(b, p, "OggS")) return null;
      int segments = b[p + 26] & 255;
      require(b, p + 27, segments);
      int data = p + 27 + segments;
      var stream = streams.computeIfAbsent(le(b, p + 14, 4), _ -> new LogicalStream());
      for (int i = 0; i < segments; i++) {
        int len = b[p + 27 + i] & 255;
        require(b, data, len);
        stream.packet.write(b, data, len);
        data += len;
        if (len < 255) {
          byte[] packet = stream.packet.toByteArray();
          stream.packet.reset();
          if (stream.packetNumber == 0) {
            if (eq(packet, 0, "OpusHead")) stream.skip = 2;
            else if (eq(packet, 1, "vorbis")) stream.skip = 3;
            else if (eq(packet, 0, "Speex   ")) stream.skip = 2;
            else return null;
          }
          if (stream.packetNumber++ >= stream.skip) {
            digest.update(packet);
            bytes += packet.length;
          }
        }
      }
      p = data;
    }
    for (var stream : streams.values()) if (stream.packet.size() != 0) return null;
    return bytes > 0
      ? result(digest, "Ogg audio packet bytes excluding codec headers/comments", bytes)
      : null;
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static Hash result(MessageDigest digest, String scope, long bytes) {
    return new Hash(HexFormat.of().formatHex(digest.digest()), scope, bytes);
  }

  private static boolean eq(byte[] b, int offset, String value) {
    return eq(b, offset, value.getBytes(StandardCharsets.ISO_8859_1));
  }

  private static boolean eq(byte[] b, int offset, byte[] v) {
    if (offset < 0 || offset + v.length > b.length) return false;
    for (int i = 0; i < v.length; i++) if (b[offset + i] != v[i]) return false;
    return true;
  }

  private static void require(byte[] b, int offset, int length) {
    if (offset < 0 || length < 0 || offset > b.length - length)
      throw new IllegalArgumentException("Truncated payload");
  }

  private static long le(byte[] b, int p, int n) {
    require(b, p, n);
    long v = 0;
    for (int i = n - 1; i >= 0; i--) v = (v << 8) | (b[p + i] & 255L);
    return v;
  }

  private static long be(byte[] b, int p, int n) {
    require(b, p, n);
    long v = 0;
    for (int i = 0; i < n; i++) v = (v << 8) | (b[p + i] & 255L);
    return v;
  }
}
