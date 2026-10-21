// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.id3;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.id3.v1.StringHandler;
import cz.skyba.taglib.internal.NativeSaveState;
import cz.skyba.taglib.internal.TextNumbers;
import cz.skyba.taglib.toolkit.ByteVector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/// ID3v1 metadata and the canonical TagLib/Winamp genre vocabulary.
public final class Id3v1 {
  private Id3v1() {
  }

  private static final StringHandler DEFAULT_HANDLER =
    new StringHandler();
  private static volatile StringHandler stringHandler = DEFAULT_HANDLER;
  private static final ScopedValue<ReadState> READ_STATE = ScopedValue.newInstance();

  /// One native constructor decodes its legacy child once, then shares the decoded views.
  public static final class ReadState {
    private byte[] source;
    private Decoded decoded;

    private Decoded find(byte[] candidate) {
      return Arrays.equals(source, candidate) ? decoded : null;
    }

    private void store(byte[] candidate, Decoded value) {
      source = candidate.clone();
      decoded = value;
    }
  }

  @FunctionalInterface
  public interface Reader<T> {
    T read() throws IOException;
  }

  public static <T> T withReadState(ReadState state, Reader<T> reader) throws IOException {
    return ScopedValue.where(READ_STATE, state).call(reader::read);
  }

  private record Decoded(PropertyMap properties, String yearText) {
  }

  private static Decoded decoded(byte[] tag) {
    var state = READ_STATE.isBound() ? READ_STATE.get() : null;
    Decoded cached = state == null ? null : state.find(tag);
    if (cached != null) {
      return cached;
    }
    var out = PropertyMap.builder();
    String yearText = "";
    if (tag.length == 128 && tag[0] == 'T' && tag[1] == 'A' && tag[2] == 'G') {
      add(out, "TITLE", text(tag, 3, 30));
      add(out, "ARTIST", text(tag, 33, 30));
      add(out, "ALBUM", text(tag, 63, 30));
      yearText = text(tag, 93, 4);
      int year = TextNumbers.parseInt(yearText);
      if (year != 0) {
        out.put("DATE", Integer.toString(year));
      }
      boolean v11 = tag[125] == 0 && tag[126] != 0;
      add(out, "COMMENT", v11 ? text(tag, 97, 28) : rawComment(tag));
      if (v11) {
        out.put("TRACKNUMBER", Integer.toString(tag[126] & 255));
      }
      add(out, "GENRE", genre(tag[127] & 255));
    }
    var result = new Decoded(out.build(), yearText);
    if (state != null) {
      state.store(tag, result);
    }
    return result;
  }

  public static void setStringHandler(StringHandler handler) {
    stringHandler = handler == null ? DEFAULT_HANDLER : handler;
  }

  public static String parseText(ByteVector data) {
    return stringHandler.parse(data);
  }

  public static ByteVector renderText(String value) {
    return new ByteVector(stringHandler.render(value));
  }

  private static final String[] GENRES = {
    "Blues",
    "Classic Rock",
    "Country",
    "Dance",
    "Disco",
    "Funk",
    "Grunge",
    "Hip-Hop",
    "Jazz",
    "Metal",
    "New Age",
    "Oldies",
    "Other",
    "Pop",
    "R&B",
    "Rap",
    "Reggae",
    "Rock",
    "Techno",
    "Industrial",
    "Alternative",
    "Ska",
    "Death Metal",
    "Pranks",
    "Soundtrack",
    "Euro-Techno",
    "Ambient",
    "Trip-Hop",
    "Vocal",
    "Jazz-Funk",
    "Fusion",
    "Trance",
    "Classical",
    "Instrumental",
    "Acid",
    "House",
    "Game",
    "Sound Clip",
    "Gospel",
    "Noise",
    "Alternative Rock",
    "Bass",
    "Soul",
    "Punk",
    "Space",
    "Meditative",
    "Instrumental Pop",
    "Instrumental Rock",
    "Ethnic",
    "Gothic",
    "Darkwave",
    "Techno-Industrial",
    "Electronic",
    "Pop-Folk",
    "Eurodance",
    "Dream",
    "Southern Rock",
    "Comedy",
    "Cult",
    "Gangsta",
    "Top 40",
    "Christian Rap",
    "Pop/Funk",
    "Jungle",
    "Native American",
    "Cabaret",
    "New Wave",
    "Psychedelic",
    "Rave",
    "Showtunes",
    "Trailer",
    "Lo-Fi",
    "Tribal",
    "Acid Punk",
    "Acid Jazz",
    "Polka",
    "Retro",
    "Musical",
    "Rock & Roll",
    "Hard Rock",
    "Folk",
    "Folk Rock",
    "National Folk",
    "Swing",
    "Fast Fusion",
    "Bebop",
    "Latin",
    "Revival",
    "Celtic",
    "Bluegrass",
    "Avant-garde",
    "Gothic Rock",
    "Progressive Rock",
    "Psychedelic Rock",
    "Symphonic Rock",
    "Slow Rock",
    "Big Band",
    "Chorus",
    "Easy Listening",
    "Acoustic",
    "Humour",
    "Speech",
    "Chanson",
    "Opera",
    "Chamber Music",
    "Sonata",
    "Symphony",
    "Booty Bass",
    "Primus",
    "Porn Groove",
    "Satire",
    "Slow Jam",
    "Club",
    "Tango",
    "Samba",
    "Folklore",
    "Ballad",
    "Power Ballad",
    "Rhythmic Soul",
    "Freestyle",
    "Duet",
    "Punk Rock",
    "Drum Solo",
    "A Cappella",
    "Euro-House",
    "Dancehall",
    "Goa",
    "Drum & Bass",
    "Club-House",
    "Hardcore Techno",
    "Terror",
    "Indie",
    "Britpop",
    "Worldbeat",
    "Polsk Punk",
    "Beat",
    "Christian Gangsta Rap",
    "Heavy Metal",
    "Black Metal",
    "Crossover",
    "Contemporary Christian",
    "Christian Rock",
    "Merengue",
    "Salsa",
    "Thrash Metal",
    "Anime",
    "Jpop",
    "Synthpop",
    "Abstract",
    "Art Rock",
    "Baroque",
    "Bhangra",
    "Big Beat",
    "Breakbeat",
    "Chillout",
    "Downtempo",
    "Dub",
    "EBM",
    "Eclectic",
    "Electro",
    "Electroclash",
    "Emo",
    "Experimental",
    "Garage",
    "Global",
    "IDM",
    "Illbient",
    "Industro-Goth",
    "Jam Band",
    "Krautrock",
    "Leftfield",
    "Lounge",
    "Math Rock",
    "New Romantic",
    "Nu-Breakz",
    "Post-Punk",
    "Post-Rock",
    "Psytrance",
    "Shoegaze",
    "Space Rock",
    "Trop Rock",
    "World Music",
    "Neoclassical",
    "Audiobook",
    "Audio Theatre",
    "Neue Deutsche Welle",
    "Podcast",
    "Indie Rock",
    "G-Funk",
    "Dubstep",
    "Garage Rock",
    "Psybient"
  };
  private static final Map<String, Integer> HISTORICAL_GENRE_ALIASES =
    Map.of(
      "Jazz+Funk", 29,
      "Folk/Rock", 81,
      "Bebob", 85,
      "Avantgarde", 90,
      "Dance Hall", 125,
      "Hardcore", 129,
      "BritPop", 132,
      "Negerpunk", 133);

  public static String genre(int index) {
    return index >= 0 && index < GENRES.length ? GENRES[index] : "";
  }

  public static List<String> genreList() {
    return new ArrayList<>(List.of(GENRES));
  }

  public static Map<String, Integer> genreMap() {
    var result = new TreeMap<String, Integer>();
    for (int i = 0; i < GENRES.length; i++) {
      result.put(GENRES[i], i);
    }
    return result;
  }

  /// Native ID3v1::genreIndex, including its eight historical spellings.
  public static int genreIndex(String name) {
    for (int i = 0; i < GENRES.length; i++) {
      if (GENRES[i].equals(name)) {
        return i;
      }
    }
    return HISTORICAL_GENRE_ALIASES.getOrDefault(name, 255);
  }

  /// Actual live common projection after native base Tag::setProperties on an ID3v1 tag.
  public static PropertyMap nativeProperties(PropertyMap input) {
    var result = PropertyMap.builder();
    for (String key : List.of("TITLE", "ARTIST", "ALBUM", "COMMENT")) {
      String value = input.first(key);
      if (!value.isEmpty()) {
        result.put(key, value);
      }
    }
    String genre = genre(genreIndex(input.first("GENRE")));
    if (!genre.isEmpty()) {
      result.put("GENRE", genre);
    }
    var year = TextNumbers.extractInt(input.first("DATE"));
    if (year.ok() && year.value() != 0) {
      result.put("DATE", Integer.toString(year.value()));
    }
    var track = TextNumbers.extractInt(input.first("TRACKNUMBER"));
    if (track.ok() && track.value() > 0 && track.value() < 256) {
      result.put("TRACKNUMBER", Integer.toString(track.value()));
    }
    return result.build();
  }

  public static boolean nativeIsEmpty(PropertyMap input) {
    return nativeProperties(input).isEmpty();
  }

  public static PropertyMap read(byte[] tag) {
    // A native file save uses the retained child rather than decoding its footer again.
    if (NativeSaveState.nativeSession()) {
      var retained = NativeSaveState.flacId3v1Properties();
      if (retained == null) {
        retained = NativeSaveState.id3v1Properties();
      }
      if (retained != null) {
        return retained;
      }
    }
    return decoded(tag).properties();
  }

  /// Native private year string survives even when its numeric getter is zero.
  public static String yearText(byte[] tag) {
    var retained = NativeSaveState.id3v1Fields();
    if (retained != null) {
      return retained.yearText();
    }
    if (READ_STATE.isBound()) {
      return decoded(tag).yearText();
    }
    return tag.length == 128 ? text(tag, 93, 4) : "";
  }

  public static byte[] render(PropertyMap properties) {
    byte[] result = new byte[128];
    result[0] = 'T';
    result[1] = 'A';
    result[2] = 'G';
    put(result, 3, 30, properties.first("TITLE"));
    put(result, 33, 30, properties.first("ARTIST"));
    put(result, 63, 30, properties.first("ALBUM"));
    var year = TextNumbers.extractInt(properties.first("DATE"));
    var nativeFields = NativeSaveState.id3v1Fields();
    put(
      result,
      93,
      4,
      nativeFields != null
        ? nativeFields.yearText()
        : year.ok() && year.value() != 0 ? Integer.toString(year.value()) : "");
    put(result, 97, 28, properties.first("COMMENT"));
    var track = TextNumbers.extractInt(properties.first("TRACKNUMBER"));
    if (track.ok() && track.value() > 0 && track.value() < 256) {
      result[126] = (byte) track.value();
    }
    result[127] = (byte) genreIndex(properties.first("GENRE"));
    if (nativeFields != null) {
      result[127] = (byte) nativeFields.genreNumber();
    }
    return result;
  }

  private static void add(PropertyMap.Builder out, String key, String value) {
    if (!value.isEmpty()) {
      out.put(key, value);
    }
  }

  private static String text(byte[] data, int offset, int length) {
    return parseText(
      new ByteVector(
        Arrays.copyOfRange(data, offset, offset + length)));
  }

  private static String rawComment(byte[] data) {
    int end = 97;
    while (end < 127 && data[end] != 0) {
      end++;
    }
    return new String(data, 97, end - 97, StandardCharsets.ISO_8859_1);
  }

  private static void put(byte[] out, int offset, int length, String text) {
    byte[] value = renderText(text).toByteArray();
    System.arraycopy(value, 0, out, offset, Math.min(length, value.length));
  }
}
