"""Builds the app's word-recordings index (SQLite) for a language from kaikki.org's extraction of English Wiktionary.

    python tools/dictionary/build.py [kaikki-German.jsonl.gz] [out.sqlite] [de|en]

Without a file it downloads kaikki.org's file for the language (German ~100 MB, English ~520 MB). English prefers
American recordings (en-us-*.ogg), then British, then anyone's (Lingua Libre).
Standard library only (GitHub Actions builds it monthly, .github/workflows/dictionary.yml). Meanings come from
Yomitan dictionaries in the app now (wty-de-en); this file only says which words have a person's recording.

Tables
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

SOURCES = {"de": "https://kaikki.org/dictionary/German/kaikki.org-dictionary-German.jsonl.gz",
           "en": "https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz"}
MP3 = "https://upload.wikimedia.org/wikipedia/commons/transcoded/"
USER_AGENT = "kumapie dictionary builder (https://github.com/Pedrubik2000/kumapie-tv)"


def rank(lang: str, sound: dict) -> int:
    """Which recording of a word wins (lower first): English American, then British, then the rest."""
    if lang != "en":
        return 0
    name = (sound.get("audio") or "").lower()
    tags = set(sound.get("tags") or [])
    if name.startswith("en-us") or tags & {"US", "General-American"}:
        return 0
    if name.startswith(("en-uk", "en-gb")) or tags & {"UK", "Received-Pronunciation"}:
        return 1
    return 2


def build(src: Path, out: Path, lang: str = "de") -> None:
    out.unlink(missing_ok=True)
    db = sqlite3.connect(out)
    db.executescript("""
        create table meta (key text primary key, value text);
        create table sounds (word text primary key, file text) without rowid;
    """)
    sounds = {}
    started = time.time()
    with gzip.open(src, "rt", encoding="utf-8") as f:
        for line in f:
            o = json.loads(line)
            lower = o.get("word", "").lower()
            found = [(rank(lang, x), x["mp3_url"]) for x in o.get("sounds") or [] if x.get("mp3_url", "").startswith(MP3)]
            best = min(found, key=lambda t: t[0]) if found else None  # the first of the best rank
            if lower and best and (lower not in sounds or best[0] < sounds[lower][0]):
                sounds[lower] = (best[0], best[1][len(MP3):].rsplit("/", 1)[0])  # "a/aa/De-Hund.ogg"
    db.executemany("insert into sounds values (?, ?)", sorted((w, f) for w, (_, f) in sounds.items()))
    db.executemany("insert into meta values (?, ?)", [
        ("source", SOURCES[lang]),
        ("built", time.strftime("%Y-%m-%d")),
        ("attribution", "Recordings from Wikimedia Commons, each under its own free license; found through "
                        "Wiktionary (kaikki.org extraction)"),
    ])
    db.commit()
    db.execute("vacuum")
    db.close()
    print(f"{len(sounds)} recordings, {out.stat().st_size / 1e6:.1f} MB in {time.time() - started:.0f} s")


if __name__ == "__main__":
    lang = sys.argv[3] if len(sys.argv) > 3 else "de"
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(f"kaikki-{lang}.jsonl.gz")
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(f"{lang}-recordings.sqlite")
    if not src.exists():
        print(f"downloading {SOURCES[lang]}")
        req = urllib.request.Request(SOURCES[lang], headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req) as r, open(src, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
    build(src, out, lang)
