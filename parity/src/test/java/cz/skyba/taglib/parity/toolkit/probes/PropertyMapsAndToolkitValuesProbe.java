package cz.skyba.taglib.parity.toolkit.probes;

import cz.skyba.taglib.PropertyMap;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Four focused counterexamples; the paired native program defines the expected behavior.
 */
public class PropertyMapsAndToolkitValuesProbe {
  public static void main(String[] args) {
    var unicode = new PropertyMap(Map.of("éx", List.of("v")));
    System.out.println("non_ascii_key=" + unicode.asMap().keySet().iterator().next());
    try {
      PropertyMap.builder().put("a=b", "v").build();
      System.out.println("equals_key=accepted");
    } catch (IllegalArgumentException rejected) {
      System.out.println("equals_key=rejected");
    }
    var empty = new PropertyMap(Map.of("", List.of("v")));
    System.out.println("empty_constructor_size=" + empty.size());
    var source = new TreeMap<String, List<String>>();
    source.put("TITLE", List.of("upper"));
    source.put("title", List.of("lower"));
    var collision = new PropertyMap(source);
    System.out.println("case_collision_values=" + String.join("|", collision.get("TITLE")));
  }
}
