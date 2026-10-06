"""Builds the app's offline German-English dictionary (SQLite) from kaikki.org's extraction of English Wiktionary.

    python tools/dictionary/build.py [kaikki-German.jsonl.gz] [out.sqlite]

Without a file it downloads https://kaikki.org/dictionary/German/kaikki.org-dictionary-German.jsonl.gz (~100 MB).
Standard library only (GitHub Actions builds it monthly, .github/workflows/dictionary.yml). Data: Wiktionary,
CC BY-SA 4.0 (attribution in the `meta` table, shown by the app).

Tables
- entries(word, lower, pos, head, ipa, senses): one per Wiktionary entry with real meanings. senses = JSON
  [[gloss, "tag tag", [[german example, english], ...]], ...] (a few short examples per sense).
- forms(form, lemma): inflected / other forms -> dictionary form, lowercase form ("ging" -> "gehen", "ruft an" ->
  "anrufen"), from form-of entries and from the entries' inflection lists; one row per pair.
- sounds(word, file): a human recording of the exact written word (lowercase key) on Wikimedia Commons, as
  "a/aa/De-Hund.ogg"; its mp3 is https://upload.wikimedia.org/wikipedia/commons/transcoded/<file>/<name>.mp3.
- meta(key, value).
"""
import gzip
import json
import sqlite3
import sys
import time
import urllib.request
from pathlib import Path

SOURCE = "https://kaikki.org/dictionary/German/kaikki.org-dictionary-German.jsonl.gz"
MP3 = "https://upload.wikimedia.org/wikipedia/commons/transcoded/"
USER_AGENT = "kumapie dictionary builder (https://github.com/Pedrubik2000/kumapie-tv)"
SKIP_FORM_TAGS = {"table-tags", "inflection-template", "class", "auxiliary", "romanization", "error-unknown-tag"}
MAX_EXAMPLES = 2
MAX_EXAMPLE_CHARS = 140


def examples(sense: dict) -> list:
    out = []
    for ex in sense.get("examples") or []:
        de, en = (ex.get("text") or "").strip(), (ex.get("english") or ex.get("translation") or "").strip()
        if de and en and len(de) <= MAX_EXAMPLE_CHARS and ex.get("type", "example") == "example":
            out.append([de, en])
        if len(out) == MAX_EXAMPLES:
            break
    return out


def build(src: Path, out: Path) -> None:
    out.unlink(missing_ok=True)
    db = sqlite3.connect(out)
    db.executescript("""
        create table meta (key text primary key, value text);
        create table entries (id integer primary key, word text, lower text, pos text, head text, ipa text, senses text);
        create table forms (form text, lemma text, primary key (form, lemma)) without rowid;
        create table sounds (word text primary key, file text) without rowid;
    """)
    entries, forms, sounds = [], set(), {}
    started = time.time()
    with gzip.open(src, "rt", encoding="utf-8") as f:
        for line in f:
            o = json.loads(line)
            word, pos = o.get("word", ""), o.get("pos", "")
            if not word:
                continue
            lower = word.lower()
            senses = []
            for s in o.get("senses") or []:
                tags = s.get("tags") or []
                if s.get("form_of"):
                    for target in s["form_of"]:
                        if target.get("word"):
                            forms.add((lower, target["word"]))
                    continue
                gloss = "; ".join(g for g in s.get("glosses") or [] if g)
                if gloss:
                    senses.append([gloss, " ".join(tags), examples(s)])
            if senses:
                ipa = next((x["ipa"] for x in o.get("sounds") or [] if x.get("ipa")), "")
                head = next((h.get("expansion", "") for h in o.get("head_templates") or [] if h.get("expansion")), word)
                entries.append((word, lower, pos, head, ipa, json.dumps(senses, ensure_ascii=False, separators=(",", ":"))))
                for fm in o.get("forms") or []:
                    form, tags = fm.get("form", ""), set(fm.get("tags") or [])
                    if form and form != "-" and not tags & SKIP_FORM_TAGS and form.lower() != lower:
                        forms.add((form.lower(), word))
            audio = next((x["mp3_url"] for x in o.get("sounds") or [] if x.get("mp3_url", "").startswith(MP3)), None)
            if audio and lower not in sounds:
                sounds[lower] = audio[len(MP3):].rsplit("/", 1)[0]  # "a/aa/De-Hund.ogg"
    db.executemany("insert into entries (word, lower, pos, head, ipa, senses) values (?, ?, ?, ?, ?, ?)", entries)
    db.executemany("insert into forms values (?, ?)", sorted(forms))
    db.executemany("insert into sounds values (?, ?)", sorted(sounds.items()))
    db.executescript("""
        create index entries_lower on entries (lower);
    """)
    db.executemany("insert into meta values (?, ?)", [
        ("source", SOURCE),
        ("built", time.strftime("%Y-%m-%d")),
        ("license", "CC BY-SA 4.0"),
        ("attribution", "Wiktionary contributors (en.wiktionary.org), extracted by kaikki.org (wiktextract); "
                        "recordings from Wikimedia Commons, each under its own free license"),
    ])
    db.commit()
    db.execute("vacuum")
    db.close()
    print(f"{len(entries)} entries, {len(forms)} forms, {len(sounds)} recordings, "
          f"{out.stat().st_size / 1e6:.0f} MB in {time.time() - started:.0f} s")


if __name__ == "__main__":
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("kaikki-German.jsonl.gz")
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("de-en.sqlite")
    if not src.exists():
        print(f"downloading {SOURCE}")
        req = urllib.request.Request(SOURCE, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req) as r, open(src, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
    build(src, out)
