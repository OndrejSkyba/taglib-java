// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.xiph;

import cz.skyba.taglib.ogg.Page;
import cz.skyba.taglib.toolkit.ByteVector;

import java.util.ArrayList;
import java.util.List;

/// Shared native packet-group replacement/pagination for the typed Ogg File and codec writer.
public final class OggPacketWrite {
  private OggPacketWrite() {
  }

  public static List<Page> paginate(Page first, Page last, int localIndex, ByteVector replacement) {
    var packets = new ArrayList<>(first.packets());
    packets.set(localIndex, replacement);
    if (first != last && last.packetCount() > 1) {
      List<ByteVector> trailing = last.packets();
      packets.addAll(trailing.subList(1, trailing.size()));
    }
    long groupSize = 0;
    for (ByteVector value : packets) {
      groupSize += value.size();
      if (groupSize > Integer.MAX_VALUE - 8L) {
        throw new IllegalStateException("Ogg packet/page group exceeds Java array size");
      }
    }
    // Native's TODO/limitations are deliberate: replacement resets granules/EOS, groups packets
    // on one page (or ~8 KiB repagination), and keeps adjacent continued packets as fragments.
    List<Page> pages = Page.paginate(packets, Page.PaginationStrategy.SinglePagePerGroup,
      first.header().streamSerialNumber(), first.pageSequenceNumber(),
      first.header().firstPacketContinued(), last.header().lastPacketCompleted(), false);
    if (pages.isEmpty()) {
      // Native dereferences pages.back(); reject before editing its undefined empty-group case.
      throw new IllegalStateException("Native Ogg repagination produced no replacement pages");
    }
    return pages;
  }
}
