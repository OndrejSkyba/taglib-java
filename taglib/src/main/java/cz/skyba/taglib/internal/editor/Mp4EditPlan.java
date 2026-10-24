// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.editor;

import cz.skyba.taglib.MetadataEditException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Mp4EditPlan {
  final Map<Integer, byte[]> replacements;
  final Map<Integer, byte[]> appendices;
  private final Map<Integer, List<byte[]>> fragments = new HashMap<>();
  private final Map<Integer, Integer> lengths = new HashMap<>();

  Mp4EditPlan() {
    this(new HashMap<>(), new HashMap<>());
  }

  private Mp4EditPlan(Map<Integer, byte[]> replacements, Map<Integer, byte[]> appendices) {
    this.replacements = replacements;
    this.appendices = appendices;
  }

  Mp4EditPlan withCopiedReplacements() {
    finishAppendices();
    return new Mp4EditPlan(new HashMap<>(replacements), appendices);
  }

  void append(int at, byte[] data) {
    int before = lengths.getOrDefault(at, appendices.containsKey(at) ? appendices.get(at).length : 0);
    if (before > Mp4Tree.LIMIT - data.length) throw new MetadataEditException("MP4 metadata exceeds 64 MB.");
    var parts = fragments.computeIfAbsent(at, key -> {
      var values = new ArrayList<byte[]>();
      byte[] existing = appendices.get(key);
      if (existing != null) values.add(existing);
      return values;
    });
    parts.add(data.clone());
    lengths.put(at, before + data.length);
  }

  void finishAppendices() {
    fragments.forEach((at, parts) -> {
      byte[] result = new byte[lengths.get(at)];
      int offset = 0;
      for (byte[] part : parts) {
        System.arraycopy(part, 0, result, offset, part.length);
        offset += part.length;
      }
      appendices.put(at, result);
    });
    fragments.clear();
    lengths.clear();
  }
}
