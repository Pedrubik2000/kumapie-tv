"""Builds the app's German word-recordings index (SQLite) from kaikki.org's extraction of English Wiktionary.

    python tools/dictionary/build.py [kaikki-German.jsonl.gz] [out.sqlite]

Without a file it downloads https://kaikki.org/dictionary/German/kaikki.org-dictionary-German.jsonl.gz (~100 MB).
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

SOURCE = "https://kaikki.org/dictionary/German/kaikki.org-dictionary-German.jsonl.gz"
MP3 = "https://upload.wikimedia.org/wikipedia/commons/transcoded/"
USER_AGENT = "kumapie dictionary builder (https://github.com/Pedrubik2000/kumapie-tv)"


def build(src: Path, out: Path) -> None:
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
            audio = next((x["mp3_url"] for x in o.get("sounds") or [] if x.get("mp3_url", "").startswith(MP3)), None)
            if lower and audio and lower not in sounds:
                sounds[lower] = audio[len(MP3):].rsplit("/", 1)[0]  # "a/aa/De-Hund.ogg"
    db.executemany("insert into sounds values (?, ?)", sorted(sounds.items()))
    db.executemany("insert into meta values (?, ?)", [
        ("source", SOURCE),
        ("built", time.strftime("%Y-%m-%d")),
        ("attribution", "Recordings from Wikimedia Commons, each under its own free license; found through "
                        "Wiktionary (kaikki.org extraction)"),
    ])
    db.commit()
    db.execute("vacuum")
    db.close()
    print(f"{len(sounds)} recordings, {out.stat().st_size / 1e6:.1f} MB in {time.time() - started:.0f} s")


if __name__ == "__main__":
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("kaikki-German.jsonl.gz")
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("de-recordings.sqlite")
    if not src.exists():
        print(f"downloading {SOURCE}")
        req = urllib.request.Request(SOURCE, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req) as r, open(src, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
    build(src, out)
