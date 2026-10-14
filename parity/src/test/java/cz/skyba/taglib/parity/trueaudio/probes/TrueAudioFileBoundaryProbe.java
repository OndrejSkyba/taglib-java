package cz.skyba.taglib.parity.trueaudio.probes;


import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.ReadOptions;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.trueaudio.TrueAudioFile;

public class TrueAudioFileBoundaryProbe {
  public static void main(String[] args) {
    var data = new ByteVector("ID3");
    var stream = new ByteVectorStream(data);
    String outcome;
    try (var f = new TrueAudioFile(stream, ReadOptions.TAGS_ONLY)) {
      f.id3v2Tag().setTitle("new");
      outcome = SaveOutcome.save(f) ? "unexpected success" : "false";
    }
    if (!data.equals(stream.data())) throw new AssertionError("boundary changed bytes: memory-grow");
    System.out.println("memory-grow\t" + outcome + "\tunchanged");
  }
}
