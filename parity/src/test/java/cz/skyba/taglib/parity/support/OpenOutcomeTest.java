package cz.skyba.taglib.parity.support;

import cz.skyba.taglib.InvalidAudioFileException;
import cz.skyba.taglib.TagLibException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("core")
class OpenOutcomeTest {
  @Test
  void openingAndClosingFailuresAreDistinct() throws Exception {
    assertTrue(OpenOutcome.rejected(() -> { throw new InvalidAudioFileException("invalid input"); }));
    int[] closes = {0};
    assertFalse(OpenOutcome.rejected(() -> (AutoCloseable) () -> closes[0]++));
    assertEquals(1, closes[0]);
    for (var failure : List.of(new UncheckedIOException(new IOException("close failed")),
                              new TagLibException("close failed"))) {
      assertSame(failure, assertThrows(RuntimeException.class,
        () -> OpenOutcome.rejected(() -> (AutoCloseable) () -> { throw failure; })));
    }
  }
}
