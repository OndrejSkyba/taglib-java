package cz.skyba.taglib.parity.support;

import cz.skyba.taglib.parity.Snapshots;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TagAndAudioSnapshotFieldComparisonTest {
  @Test
  void checksEveryValueAndItsOrder() throws Exception {
    var expected = Snapshots.JSON.readTree("{\"ARTIST\":[\"one\",\"two\"]}");
    assertFalse(
      Snapshots.differences(expected, Snapshots.JSON.readTree("{\"ARTIST\":[\"two\",\"one\"]}"))
        .isEmpty());
    assertFalse(
      Snapshots.differences(expected, Snapshots.JSON.readTree("{\"ARTIST\":[\"one\"]}"))
        .isEmpty());
  }

  @Test
  void numericWidthDoesNotCauseFalseMismatch() {
    assertTrue(
      Snapshots.differences(
          Snapshots.JSON.getNodeFactory().numberNode(1),
          Snapshots.JSON.getNodeFactory().numberNode(1L))
        .isEmpty());
  }

  @Test
  void commonUnsignedNumbersRemainPositiveInJsonAndChecksum() throws Exception {
    var tag = new cz.skyba.taglib.Tag("", "", "", "", "", -1, 2147483648L);
    var file =
      new cz.skyba.taglib.AudioMetadata(
        cz.skyba.taglib.Format.APE,
        cz.skyba.taglib.PropertyMap.EMPTY,
        cz.skyba.taglib.AudioProperties.EMPTY,
        java.util.List.of(),
        tag);
    var snapshot = Snapshots.of(file);
    var common = snapshot.get("common");
    assertEquals(4294967295L, common.get("year").longValue());
    assertEquals(2147483648L, common.get("track").longValue());
    assertEquals(common, Snapshots.JSON.readTree(snapshot.toString()).get("common"));
    long strings = 5 * Snapshots.hash("");
    assertEquals(1 + strings + 4294967295L + 2147483648L, Snapshots.checksum(file));
  }

  @Test
  void fnvMatchesPublishedVector() {
    assertEquals(
      "a430d84680aabd0b",
      Snapshots.fnv("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @Test
  void opaqueRepresentationPayloadAndFlagsAreAllSignificant() throws Exception {
    var v3 =
      Snapshots.JSON.readTree(
        "[{\"id\":\"PRIV\",\"payloadFnv1a64\":\"abc\",\"flags\":{\"readOnly\":false},\"representation\":{\"version\":3}}]");
    var v4 =
      Snapshots.JSON.readTree(
        "[{\"id\":\"PRIV\",\"payloadFnv1a64\":\"abc\",\"flags\":{\"readOnly\":false},\"representation\":{\"version\":4}}]");
    assertFalse(Snapshots.differences(v3, v4).isEmpty());
    ((com.fasterxml.jackson.databind.node.ObjectNode) v4.get(0).get("representation"))
      .put("version", 3);
    assertTrue(Snapshots.differences(v3, v4).isEmpty());
    ((com.fasterxml.jackson.databind.node.ObjectNode) v4.get(0).get("flags")).put("readOnly", true);
    assertFalse(Snapshots.differences(v3, v4).isEmpty());
    ((com.fasterxml.jackson.databind.node.ObjectNode) v4.get(0).get("flags"))
      .put("readOnly", false);
    ((com.fasterxml.jackson.databind.node.ObjectNode) v4.get(0)).put("payloadFnv1a64", "different");
    assertFalse(Snapshots.differences(v3, v4).isEmpty());
  }
}
