package cz.skyba.taglib.internal;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.asf.Attribute;
import cz.skyba.taglib.matroska.AttachedFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FileSessionSaveContextTest {
  @Test
  void unboundDefaultsRetainTheDifferenceBetweenConvenienceAndNativeSessions() {
    assertFalse(NativeSaveState.nativeSession());
    assertFalse(NativeSaveState.retainedEmptyMatroskaTag());
    assertTrue(NativeSaveState.mp4SetPropertiesCalled());
    assertTrue(NativeSaveState.mp4PictureSetCalled());
    assertTrue(NativeSaveState.id3CommonFields().isEmpty());
    assertNull(NativeSaveState.mpegApeProperties());
    assertNull(NativeSaveState.asfAttributes());
    assertNull(NativeSaveState.matroskaChapters());
    assertNull(NativeSaveState.matroskaAttachments());
    assertNull(NativeSaveState.flacId3v2Properties());
    assertNull(NativeSaveState.flacId3v1Properties());
    assertNull(NativeSaveState.id3v2Frames());
    assertNull(NativeSaveState.mp4Stem());
  }

  @Test
  void missingOverridesInheritOuterHintsAndExplicitFlagsAreRestored() throws IOException {
    var properties = PropertyMap.builder().put("TITLE", "outer").build();
    NativeSaveState.run(true, properties, () -> {
      NativeSaveState.run(false, (PropertyMap) null, () -> {
        assertTrue(NativeSaveState.nativeSession());
        assertFalse(NativeSaveState.retainedEmptyMatroskaTag());
        assertSame(properties, NativeSaveState.mpegApeProperties());
      });
      assertTrue(NativeSaveState.retainedEmptyMatroskaTag());
      assertSame(properties, NativeSaveState.mpegApeProperties());
    });
    assertFalse(NativeSaveState.nativeSession());
    assertNull(NativeSaveState.mpegApeProperties());
    NativeSaveState.runMp4(false, false, () -> {
      NativeSaveState.runMp4(true, () -> {
        assertTrue(NativeSaveState.mp4SetPropertiesCalled());
        assertFalse(NativeSaveState.mp4PictureSetCalled());
      });
      assertFalse(NativeSaveState.mp4SetPropertiesCalled());
      assertFalse(NativeSaveState.mp4PictureSetCalled());
    });
    assertTrue(NativeSaveState.mp4SetPropertiesCalled());
    assertTrue(NativeSaveState.mp4PictureSetCalled());
  }

  @Test
  void checkedFailuresRestoreTheOuterBindingWithoutWrappingTheException() throws IOException {
    var outer = PropertyMap.builder().put("TITLE", "outer").build();
    var inner = PropertyMap.builder().put("TITLE", "inner").build();
    var failure = new IOException("write interrupted");
    NativeSaveState.runDsdiff(outer, () -> {
      var caught = assertThrows(IOException.class, () ->
        NativeSaveState.runDsdiff(inner, () -> {
          assertSame(inner, NativeSaveState.dsdiffDiinProperties());
          throw failure;
        }));
      assertSame(failure, caught);
      assertSame(outer, NativeSaveState.dsdiffDiinProperties());
      NativeSaveState.runDsdiff(null, () -> assertSame(outer, NativeSaveState.dsdiffDiinProperties()));
    });
    assertNull(NativeSaveState.dsdiffDiinProperties());
  }

  @Test
  void collectionMembershipIsSnapshottedWhileStoredValuesRemainLive() throws IOException {
    var attribute = new Attribute("retained");
    var attributes = new ArrayList<>(List.of(attribute));
    var commonFields = new HashSet<>(Set.of("TITLE"));
    NativeSaveState.runTagState(attributes, commonFields, null, () -> {
      attributes.clear();
      attribute.setStream(9);
      commonFields.clear();
      assertSame(attribute, NativeSaveState.asfAttributes().getFirst());
      assertEquals(9, NativeSaveState.asfAttributes().getFirst().stream());
      assertEquals(Set.of("TITLE"), NativeSaveState.id3CommonFields());
      assertThrows(UnsupportedOperationException.class, () -> NativeSaveState.asfAttributes().add(null));
    });
    assertNull(NativeSaveState.asfAttributes());
    assertTrue(NativeSaveState.id3CommonFields().isEmpty());
  }

  @Test
  void attachmentSnapshotCallbacksSeeTheChapterBindingAndFailuresUnwindIt() {
    var attachments = new AbstractList<AttachedFile>() {
      @Override
      public int size() {
        return 1;
      }

      @Override
      public AttachedFile get(int index) {
        assertEquals(List.of(), NativeSaveState.matroskaChapters());
        return null;
      }
    };
    assertThrows(NullPointerException.class, () ->
      NativeSaveState.runMatroska(List.of(), attachments, () -> fail("Invalid snapshot must not reach save")));
    assertNull(NativeSaveState.matroskaChapters());
    assertNull(NativeSaveState.matroskaAttachments());
  }

  @Test
  void commonFieldSnapshotCallbacksSeeThePreviouslyBoundTagHints() throws IOException {
    var properties = PropertyMap.builder().put("TITLE", "retained").build();
    var commonFields = new AbstractSet<String>() {
      @Override
      public int size() {
        return 1;
      }

      @Override
      public Iterator<String> iterator() {
        assertEquals(List.of(), NativeSaveState.asfAttributes());
        assertSame(properties, NativeSaveState.id3v1Properties());
        return Set.of("TITLE").iterator();
      }
    };
    NativeSaveState.runTagState(List.of(), commonFields, properties, () ->
      assertEquals(Set.of("TITLE"), NativeSaveState.id3CommonFields()));
    assertNull(NativeSaveState.id3v1Properties());
  }

  @Test
  void ordinaryChildThreadsDoNotInheritOrLeakSaveHints() throws IOException {
    var childResult = new AtomicReference<Boolean>();
    NativeSaveState.run(true, () -> {
      var child = Thread.ofVirtual().start(() -> childResult.set(NativeSaveState.nativeSession()));
      try {
        child.join();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException(interrupted);
      }
      assertFalse(childResult.get());
      assertTrue(NativeSaveState.nativeSession());
    });
    assertFalse(NativeSaveState.nativeSession());
  }
}
