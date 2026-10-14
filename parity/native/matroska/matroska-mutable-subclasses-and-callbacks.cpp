// Element hooks, copied values, lazy data and ordinary partial state.
#include <iostream>
#include <sstream>
#include <fstream>
#include <filesystem>
#include <matroskaelement.h>
#include <matroskafile.h>
#include <matroskaattachedfile.h>
#include <matroskaattachments.h>
#include <matroskachapter.h>
#include <matroskachapteredition.h>
#include <matroskachapters.h>
#include <matroskatag.h>
#include <tbytevectorstream.h>
#include <tpropertymap.h>
#include <fileref.h>
using namespace TagLib;

std::string hx(const ByteVector &v) {
  auto h = v.toHex();
  return std::string(h.data(), h.size());
}

void row(const std::string &k, const std::string &v) { std::cout << k << '\t' << v << '\n'; }

class E : public Matroska::Element {
public:
  ByteVector next;
  bool accept = true;
  std::string calls;

  E(ID id) : Element(id) {
  }

  offset_t remembered() { return sizeRenderedOrWritten(); }

  bool sizeChanged(Matroska::Element &c, offset_t d) override {
    calls += std::to_string(c.id()) + ":" + std::to_string(c.offset()) + ":" + std::to_string(d) + ",";
    Element::sizeChanged(c, d);
    return accept;
  }

private:
  ByteVector renderInternal() override { return next; }
};

class Raw : public TagLib::File {
public:
  Raw(IOStream *s) : File(s) {
  }

  TagLib::Tag *tag() const override { return nullptr; }
  AudioProperties *audioProperties() const override { return nullptr; }
  bool save() override { return false; }
};

std::string state(E &e) {
  return std::to_string(e.id()) + ":" + std::to_string(e.offset()) + ":" + std::to_string(e.size()) + ":" +
         std::to_string(e.remembered()) + ":" + std::to_string(int(e.writeStyle())) + ":" +
         std::to_string(e.needsRender()) + ":" + std::to_string(e.wasMoved()) + ":" +
         std::to_string(e.isTrailingInSegment()) + ":" + hx(e.data());
}

std::string display(const Matroska::Chapter::Display &d) {
  return hx(d.string().data(String::UTF8)) + ":" + hx(d.language().data(String::UTF8));
}

std::string chapter(const Matroska::Chapter &c) {
  std::string s = std::to_string(c.uid()) + ":" + std::to_string(c.timeStart()) + ":" + std::to_string(c.timeEnd()) +
                  ":" + std::to_string(c.isHidden());
  for (auto &d: c.displayList())s += "/" + display(d);
  return s;
}

std::string edition(const Matroska::ChapterEdition &e) {
  std::string s = std::to_string(e.uid()) + ":" + std::to_string(e.isDefault()) + ":" + std::to_string(e.isOrdered());
  for (auto &c: e.chapterList())s += "/" + chapter(c);
  return s;
}

std::string attached(const Matroska::AttachedFile &a) {
  return hx(a.data()) + ":" + hx(a.fileName().data(String::UTF8)) + ":" + hx(a.mediaType().data(String::UTF8)) + ":" +
         std::to_string(a.uid()) + ":" + hx(a.description().data(String::UTF8));
}

int main(int argc, char **argv) {
  for (int style = 0; style < 3; style++)
    for (bool fail: {false, true}) {
      E e(0xffffffffU), a(2), same(0xffffffffU), stop(3);
      e.setOffset(4);
      e.setSize(3);
      e.setAppendOffset(20);
      e.setWriteStyle(Matroska::WriteStyle(style));
      e.setIsLastElement(false);
      e.setIsTrailingInSegment(true);
      a.setOffset(7);
      same.setOffset(9);
      stop.setOffset(12);
      stop.accept = !fail;
      e.addSizeListener(&a);
      e.addSizeListeners({&same, &stop});
      std::string k = "element/" + std::to_string(style) + "/" + std::to_string(fail);
      row(k + "/before", state(e));
      e.next = "abcdef";
      bool result = e.render();
      row(k + "/render",
          std::to_string(result) + "\t" + state(e) + "\t" + state(a) + "\t" + state(same) + "\t" + state(stop) + "\t" +
          a.
          calls + "/" + same.calls + "/" + stop.calls);
      row(k + "/cached", std::to_string(e.render()) + "\t" + state(e));
      if (!fail) {
        ByteVectorStream s(ByteVector(20, 'x'));
        Raw f(&s);
        e.write(f);
        row(k + "/write", state(e) + "\t" + hx(*s.data()) + "\t" + std::to_string(s.tell()));
      }
      e.clearSizeListeners();
      e.setID(42);
      e.setNeedsRender(true);
      e.next = "a";
      e.adjustOffset(-1);
      bool shrunk = e.render();
      row(k + "/shrink", std::to_string(shrunk) + "\t" + state(e));
    }
  {
    E e(7), other(8);
    e.setData("abc");
    e.setSize(99);
    e.setOffset(3);
    other.setOffset(4);
    e.addSizeListener(&other);
    e.emitSizeChanged(-2);
    row("element/direct", state(e) + "/" + state(other));
    e.clearSizeListeners();
    e.emitSizeChanged(9);
    row("element/clear", state(other));
  }
  Matroska::Chapter::Display d("First", "eng"), dc = d;
  row("copy/display", display(d) + "/" + display(dc));
  Matroska::Chapter c(1, 2, {d}, 0xffffffffffffffffULL, true), cc = c;
  row("copy/chapter", chapter(c) + "|" + chapter(cc));
  Matroska::ChapterEdition e({c}, true, true, 77), ec = e;
  row("copy/edition", edition(e) + "|" + edition(ec));
  Matroska::AttachedFile a("data", "file.bin", "application/bin", 0xffffffffffffffffULL, "Desc"), ac = a;
  row("copy/attachment", attached(a) + "|" + attached(ac));
  Matroska::Chapters ch;
  ch.addChapterEdition(e);
  ch.addChapterEdition(e);
  ch.removeChapterEdition(77);
  row("collection/chapters",
      std::to_string(ch.chapterEditionList().size()) + "/" + edition(ch.chapterEditionList().front()));
  Matroska::Attachments at;
  at.addAttachedFile(a);
  at.addAttachedFile(a);
  at.removeAttachedFile(a.uid());
  row("collection/attachments",
      std::to_string(at.attachedFileList().size()) + "/" + attached(at.attachedFileList().front()));
  {
    Matroska::Tag t;
    t.addSimpleTags({
      Matroska::SimpleTag("A", String("One")),
      Matroska::SimpleTag("A", String("Two"), Matroska::SimpleTag::Album, "eng", false, 1, 2, 3, 4)
    });
    t.removeSimpleTag("A", Matroska::SimpleTag::Album, 1, 2, 3, 4);
    row("tag/add-list",
        std::to_string(t.simpleTagsList().size()) + "/" + hx(t.simpleTagsList().front().toString().data(String::UTF8)));
    t.clearSimpleTags();
    row("tag/clear-list", std::to_string(t.isEmpty()));
  }
  for (unsigned int value: {0U, 1U, 2147483647U, 2147483648U, 4294967295U}) {
    Matroska::Tag t;
    t.setYear(value);
    t.setTrack(value);
    auto p = t.properties();
    std::string fields = std::to_string(t.year()) + "/" + std::to_string(t.track());
    for (auto key: {"DATE", "TRACKNUMBER"}) {
      fields += "/";
      if (p.contains(key))fields += hx(p[key].front().data(String::UTF8));
    }
    row("tag/unsigned/" + std::to_string(value), fields);
  }
  std::filesystem::path root = argv[1];
  std::ifstream in(root / "inputs/upstream-no-tags.mka", std::ios::binary);
  std::string raw((std::istreambuf_iterator<char>(in)), {});
  ByteVector original(raw.data(), raw.size());
  for (int origin: {0, 3, 7}) {
    ByteVectorStream s(original);
    s.seek(origin);
    row("support/" + std::to_string(origin),
        std::to_string(Matroska::File::isSupported(&s)) + "/" + std::to_string(s.tell()));
  }
  {
    ByteVectorStream s(original);
    Matroska::File f(&s, false);
    auto tag = f.tag(true);
    auto ch = f.chapters(true);
    auto at = f.attachments(true);
    bool saved = f.save();
    row("empty/discard",
        std::to_string(saved) + "/" + std::to_string(f.tag(false) == nullptr) + "/" +
        std::to_string(f.chapters() == nullptr) + "/" + std::to_string(f.attachments() == nullptr) + "/" +
        hx(*s.data()));
  }
  {
    ByteVectorStream s(original);
    Matroska::File f(&s, false);
    f.attachments(true)->addAttachedFile(Matroska::AttachedFile("PAYLOAD", "p.bin", "application/bin", 17));
    f.save();
    s.seek(0);
    Matroska::File read(&s, false);
    s.data()->resize(s.data()->size() - 3);
    s.seek(5);
    auto a = read.attachments()->attachedFileList().front();
    row("deferred/short", attached(a) + "/" + std::to_string(s.tell()));
  }
  {
    ByteVectorStream s(original);
    FileRef ref(&s);
    s.data()->resize(s.data()->size() + 1000);
    row("fileref/lazy",
        std::to_string(dynamic_cast<Matroska::File *>(ref.file()) != nullptr) + "/" + std::to_string(
          ref.audioProperties()->bitrate()));
  }
  {
    ByteVectorStream s(original);
    Matroska::File f(&s, false);
    f.tag(true)->setTitle("Large title");
    f.save(Matroska::WriteStyle::DoNotShrink);
    f.tag(true)->setTitle("s");
    f.save(Matroska::WriteStyle::Compact);
    row("style/sticky", hx(*s.data()));
  }
}
