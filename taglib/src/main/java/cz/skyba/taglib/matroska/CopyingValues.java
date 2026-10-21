// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.matroska;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/// Wrapper-copy boundaries shared by owners; each owner controls its own notification timing.
final class CopyingValues<T> {
  private final ArrayList<T> values = new ArrayList<>();
  private final Function<T, T> copy;

  CopyingValues(Function<T, T> copy) {
    this.copy = copy;
  }

  ArrayList<T> stored() {
    return values;
  }

  List<T> snapshot() {
    return values.stream().map(copy).toList();
  }

  void add(T value) {
    values.add(copy.apply(value));
  }

  void addAll(List<T> source) {
    source.forEach(this::add);
  }

  void insert(int index, T value) {
    values.add(index, copy.apply(value));
  }

  void clear() {
    values.clear();
  }

  boolean removeFirst(Predicate<T> match) {
    for (int i = 0; i < values.size(); i++) {
      if (match.test(values.get(i))) {
        values.remove(i);
        return true;
      }
    }
    return false;
  }
}
