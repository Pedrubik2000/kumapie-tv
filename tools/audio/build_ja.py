"""Japanese word audio for kumapie: the "Local Audio Server for Yomitan" collection (NHK16, Shinmeikai 8, JapanesePod101,
Forvo speakers; opus) into one SQLite file the app reads (lang/WordAudio.kt).

    python build_ja.py <extracted user_files folder> <out.sqlite>

Tables: words(term, reading, source, file) - a recording found by its written form or its reading (hiragana);
audio(source, file, data) - the opus files. Sources in the order kumapie prefers them: nhk16, shinmeikai8, jpod, forvo.
The collection itself: magnet in kumapie_languages_plan.md (local-yomichan-audio-collection-2023-06-11-opus)."""
import json
import os
import sqlite3
import sys


def hira(s):
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in s or "")


def main(root, out):
    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.executescript("""
        CREATE TABLE words(term TEXT, reading TEXT, source TEXT, file TEXT);
        CREATE TABLE audio(source TEXT, file TEXT, data BLOB, PRIMARY KEY(source, file)) WITHOUT ROWID;
    """)
    rows = []

    # NHK16: entries with kana, kanji forms and a sound file per accent.
    for e in json.load(open(os.path.join(root, "nhk16_files", "entries.json"), encoding="utf-8")):
        for acc in e.get("accents", []):
            f = acc.get("soundFile")
            if not f:
                continue
            kana = hira(e.get("kana", ""))
            for term in set(e.get("kanji", []) + [e.get("kana", "")]):
                if term:
                    rows.append((term, kana, "nhk16", "audio/" + f))
    # Shinmeikai 8 and JapanesePod101: headwords -> files; files -> kana reading.
    for src, folder in (("shinmeikai8", "shinmeikai8_files"), ("jpod", "jpod_files")):
        idx = json.load(open(os.path.join(root, folder, "index.json"), encoding="utf-8"))
        media = idx["meta"].get("media_dir", "media")
        for term, files in idx["headwords"].items():
            for f in files:
                rows.append((term, hira(idx["files"].get(f, {}).get("kana_reading", "")), src, media + "/" + f))
    # Forvo: <speaker>/<word>.opus, no reading.
    forvo = os.path.join(root, "forvo_files")
    for speaker in sorted(os.listdir(forvo)):
        for f in os.listdir(os.path.join(forvo, speaker)):
            if f.endswith(".opus"):
                rows.append((f[:-5], "", "forvo", speaker + "/" + f))
    db.executemany("INSERT INTO words VALUES (?,?,?,?)", rows)
    db.execute("CREATE INDEX words_term ON words(term)")
    db.execute("CREATE INDEX words_reading ON words(reading)")

    folders = {"nhk16": "nhk16_files", "shinmeikai8": "shinmeikai8_files", "jpod": "jpod_files", "forvo": "forvo_files"}
    done = 0
    for src, file in sorted({(r[2], r[3]) for r in rows}):
        path = os.path.join(root, folders[src], file)
        if os.path.exists(path):
            db.execute("INSERT OR IGNORE INTO audio VALUES (?,?,?)", (src, file, open(path, "rb").read()))
            done += 1
            if done % 20000 == 0:
                print(done, "files", flush=True)
                db.commit()
    db.commit()
    print(len(rows), "words,", done, "files ->", out)
    db.execute("VACUUM")
    db.close()


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
