package cz.skyba.taglib.internal;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileOwnerStreamTest {
  private static final class Owner extends FileBase {
    final List<String> calls = new ArrayList<>();

    Owner() {
      super(new ByteVectorStream(new ByteVector("abcd")));
    }

    public TagBase tag() {
      return null;
    }

    public AudioProperties audioProperties() {
      return null;
    }

    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }

    public String name() {
      calls.add("name");
      return "owner";
    }

    public void seek(long at, IOStream.Position origin) {
      calls.add("seek:" + at);
      super.seek(at, origin);
    }

    public ByteVector readBlock(long size) {
      calls.add("read:" + size);
      return super.readBlock(size);
    }

    public void writeBlock(ByteVector data) {
      calls.add("write:" + data);
      super.writeBlock(data);
    }

    public void truncate(long size) {
      calls.add("truncate:" + size);
      super.truncate(size);
    }

    IOStream writableView() {
      return ownerStream();
    }

    public void close() {
      calls.add("close");
    }
  }

  @Test
  void borrowedAdapterDispatchesVirtualMethodsAndDoesNotCloseTheOwner() {
    var owner = new Owner();
    try (var stream = owner.writableView()) {
      assertEquals("owner", stream.name());
      stream.seek(1);
      assertEquals("bc", stream.readBlock(2).toString());
      stream.writeBlock(new ByteVector("Z"));
      stream.truncate(2);
    }
    assertEquals(List.of("name", "seek:1", "read:2", "write:Z", "truncate:2"), owner.calls);
    owner.seek(0);
    assertEquals("ab", owner.readBlock(8).toString());
  }

  @Test
  void readViewRejectsMutationsWithoutInvokingTheOwner() {
    var owner = new Owner();
    try (var stream = FileOwnerStream.properties(owner)) {
      assertTrue(stream.readOnly());
      assertThrows(UnsupportedOperationException.class, () -> stream.writeBlock(new ByteVector("x")));
      assertThrows(UnsupportedOperationException.class, () -> stream.insert(new ByteVector("x")));
      assertThrows(UnsupportedOperationException.class, () -> stream.removeBlock(0, 1));
      assertThrows(UnsupportedOperationException.class, () -> stream.truncate(1));
    }
    assertTrue(owner.calls.isEmpty());
  }
}
