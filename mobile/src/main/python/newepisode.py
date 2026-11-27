"""A new episode made on the device (called from Kotlin, local/ProcessWorker.kt), the way the PC's pipeline does it:
audio -> Soniox (dojo skills/scripts/transcribe.py) -> German cues (srt_spaced.py) -> scenes (tools/feed/feed.py's
exchanges) with their words (Sudachi, from Kotlin) -> the episode JSON the player reads (services/feed/tv.py's format).
The English cues come from Kotlin (the device's translator) or from Soniox's translation."""
import hashlib
import json
import re
import time

import requests

import srt_spaced

SONIOX_API = "https://api.soniox.com/v1"
SAME = 0.05     # cues that start within this of each other are shown together: one line ([build])


# ---------------------------------------------------------------- Soniox

def transcribe(audio_path: str, key: str, language: str, translate_to: str, log) -> str:
    """Soniox async: upload, transcribe (with one-way translation to [translate_to] if set), fetch, clean up.
    Answers JSON {"words": canonical words, "english": [[start, end, text], ...] or []}."""
    auth = {"Authorization": f"Bearer {key}"}
    log.log("Uploading the audio…")
    with open(audio_path, "rb") as f:
        r = requests.post(f"{SONIOX_API}/files", headers=auth, files={"file": f}, timeout=3600)
    if not r.ok:
        raise RuntimeError(f"Soniox upload: HTTP {r.status_code} {r.text[:300]}")
    file_id = r.json()["id"]
    try:
        body = {"model": "stt-async-v5", "file_id": file_id, "language_hints": [language],
                "enable_speaker_diarization": True}
        if translate_to:
            body["translation"] = {"type": "one_way", "target_language": translate_to}
        r = requests.post(f"{SONIOX_API}/transcriptions", headers={**auth, "Content-Type": "application/json"},
                          json=body, timeout=120)
        if not r.ok:
            raise RuntimeError(f"Soniox: HTTP {r.status_code} {r.text[:300]}")
        tid = r.json()["id"]
        try:
            log.log("Transcribing…")
            deadline = time.monotonic() + 3600
            while True:
                if time.monotonic() > deadline:
                    raise RuntimeError("Soniox took over an hour")
                try:
                    s = requests.get(f"{SONIOX_API}/transcriptions/{tid}", headers=auth, timeout=60)
                except requests.RequestException:
                    time.sleep(3)
                    continue
                if s.status_code in (401, 403, 404):
                    raise RuntimeError(f"Soniox: HTTP {s.status_code} {s.text[:300]}")
                status = s.json().get("status") if s.ok else None
                if status == "completed":
                    break
                if status == "error":
                    raise RuntimeError(f"Soniox: {s.text[:300]}")
                time.sleep(3)
            r = requests.get(f"{SONIOX_API}/transcriptions/{tid}/transcript", headers=auth, timeout=120)
            if not r.ok:
                raise RuntimeError(f"Soniox transcript: HTTP {r.status_code}")
            raw = r.json()
        finally:
            try:
                requests.delete(f"{SONIOX_API}/transcriptions/{tid}", headers=auth, timeout=60)
            except requests.RequestException:
                pass
    finally:
        try:
            requests.delete(f"{SONIOX_API}/files/{file_id}", headers=auth, timeout=60)
        except requests.RequestException:
            pass
    tokens = raw.get("tokens", [])
    spoken = [t for t in tokens if t.get("translation_status") != "translation"]
    return json.dumps({"words": canonical(spoken), "english": translation_cues(tokens), "raw": raw}, ensure_ascii=False)


def canonical(tokens: list) -> list:
    """Soniox tokens -> the canonical word list srt_spaced reads (as transcribe.py's soniox_to_canonical)."""
    words = []
    for t in tokens:
        text = t.get("text", "")
        start, end = t.get("start_ms", 0) / 1000.0, t.get("end_ms", 0) / 1000.0
        speaker = t.get("speaker")
        sid = f"speaker_{speaker}" if speaker is not None else "speaker_0"
        if t.get("is_audio_event"):
            words.append({"text": text, "start": start, "end": end, "type": "audio_event", "speaker_id": sid})
        elif text.strip() == "":
            words.append({"text": text, "start": start, "end": end, "type": "spacing", "speaker_id": sid})
        else:
            if text != text.lstrip(" "):
                words.append({"text": " ", "start": start, "end": start, "type": "spacing", "speaker_id": sid})
                text = text.lstrip(" ")
            words.append({"text": text, "start": start, "end": end, "type": "word", "speaker_id": sid})
    return words


def translation_cues(tokens: list) -> list:
    """Soniox's token stream (spoken German, then its English translation, then more German...) -> English sentences
    [[start, end, text]]. Translated tokens have no times: each translated stretch gets the times of the German stretch
    just before it."""
    out = []
    seg_start = seg_end = None   # the German since the last translation
    eng, eng_start, eng_end = "", None, None

    def flush():
        nonlocal eng, eng_start, eng_end
        if eng.strip() and eng_start is not None:
            out.append([round(eng_start, 3), round(eng_end, 3), eng.strip()])
        eng, eng_start, eng_end = "", None, None

    for t in tokens:
        if t.get("translation_status") == "translation":
            if eng == "" and seg_start is not None:
                eng_start, eng_end = seg_start, seg_end
                seg_start = seg_end = None
            eng += t.get("text", "")
        else:
            if eng:
                flush()
            if t.get("start_ms") is not None and t.get("text", "").strip():
                seg_start = t["start_ms"] / 1000 if seg_start is None else seg_start
                seg_end = t.get("end_ms", t["start_ms"]) / 1000
    flush()
    return out


def cues(transcript_json: str) -> str:
    """Soniox result -> German cues [[start, end, text]] as srt_spaced makes them (lines joined)."""
    words = srt_spaced.words_of({"words": json.loads(transcript_json)["words"]})
    return json.dumps([[round(s, 3), round(e, 3), " ".join(t.split("\n"))] for s, e, t in srt_spaced.build_cues(words)],
                      ensure_ascii=False)


# ---------------------------------------------------------------- scenes (tools/feed/feed.py)

def unsplit(cues):
    """Subtitles converted from styled ones cut a line that overlaps another into slices, each a cue with the same
    text, the next starting the ms the last ends (BOFURI 11: ホーリージャベリン 7:09.908-7:10.325, 7:10.325-7:11.618);
    kumapie's scene box listed each. -> (the kept cues' indices, the cues with each line once, its end the last
    slice's). Only for Japanese ([build]): its subtitles are downloaded ones; German and English are mostly our
    transcripts, whose cues touch, so "What?" "What?" there is said twice."""
    kept, out, last = [], [], {}  # last: text -> its cue in out
    for i, (a, b, t) in enumerate(cues):
        j = last.get(t)
        if j is not None and abs(a - out[j][1]) <= 0.001:
            out[j] = (out[j][0], b, t)
            continue
        last[t] = len(out)
        kept.append(i)
        out.append((a, b, t))
    return kept, out


def segments(text, tokens):
    out, pos = [], 0
    for surface, at, infl, _ in sorted(tokens, key=lambda t: t[1]):
        if not infl or at < pos:
            continue
        if at > pos:
            out.append([text[pos:at], None])
        out.append([surface, infl])
        pos = at + len(surface)
    if pos < len(text):
        out.append([text[pos:], None])
    return out


def nearest(scenes, line):
    """The scene a translation line belongs to: the one it overlaps most, else the closest (English subtitles split
    and time lines differently, so some fall between two scenes)."""
    def score(sc):
        p = sc[0]
        a, b = p[0][0], p[-1][1]
        overlap = min(b, line[1]) - max(a, line[0])
        return overlap if overlap > 0 else -min(abs(line[0] - b), abs(a - line[1]))
    return max(range(len(scenes)), key=lambda i: score(scenes[i])) if scenes else -1


def build(episode_id: str, show: str, title: str, duration: float, video: str,
          cues_json: str, english_json: str, lang: str, parsed_json: str) -> str:
    """Target-language and English cues -> the episode JSON: every subtitle line as a "scene" of its own (the line is
    kumapie's only unit, kumapie_plan.md "no more scenes"; the JSON keeps the key so older files and builds still
    read), its words as segments, the translation lines that overlap it, every word. The words come parsed from Kotlin
    ([parsed_json]: per cue, in order, [[surface, offset, dictionary form or None, None]]: Sudachi for Japanese)."""
    cues = [tuple(c) for c in json.loads(cues_json)]
    english = json.loads(english_json)
    kept, cues = unsplit(cues) if lang == "ja" else (range(len(cues)), cues)
    if not parsed_json:
        raise RuntimeError("kumapie makes Japanese episodes only")
    given = json.loads(parsed_json)
    parsed = iter([given[i] for i in kept])
    scenes, words, lemmas = [], {}, {}
    plan = []
    # Pieces shown together are one line: downloaded subtitles cut a subtitle into cues with the same start (a
    # name, a thought, each row); the PC has the same rule (ranges.together) for the files made before.
    lines = []
    for c in cues:
        if lines and abs(c[0] - lines[-1][0][0]) < SAME:
            lines[-1].append(c)
        else:
            lines.append([c])
    # A line's translation: the English lines it overlaps most (else the closest line gets them).
    owner = [nearest([(p,) for p in lines], e) for e in english]
    for i, p in enumerate(lines):
        en = [[e[0], e[1], re.sub(r"\s*\([^)]*\)", "", e[2]).strip()] for e, o in zip(english, owner) if o == i]
        plan.append((i, p, round(p[0][0], 3), round(max(c[1] for c in p), 3), en, True))
    for i, p, start, end, en, german in plan:
        sid = hashlib.sha1(f"{episode_id}|{start:.3f}|{end:.3f}".encode()).hexdigest()[:12]
        sc_cues = []
        for c in p:
            toks = next(parsed) if german else []
            for _, _, infl, lemma in toks:
                if infl:
                    words.setdefault(infl, {"s": "u", "g": ""})
                    if lemma:
                        lemmas.setdefault(infl, lemma)
            sc_cues.append({"start": c[0], "end": c[1], "seg": segments(c[2], toks) if german else [[c[2], None]]})
        scenes.append({"id": sid, "i": i, "start": start, "end": end, "german": german, "level": None, "seen": False,
                       "cues": sc_cues, "english": en, "g": {}, "defs": {}})
    for infl, lemma in lemmas.items():
        words[infl]["lemma"] = lemma
    return json.dumps({"id": episode_id, "show": show, "title": title, "lang": lang, "duration": duration, "video": video,
                       "resume": None, "scenes": scenes, "words": words}, ensure_ascii=False)


if __name__ == "__main__":  # self-check: python newepisode.py
    assert unsplit([(1, 2, "a"), (1.5, 2.5, "b"), (2, 3, "a"), (3.04, 4, "a")]) == \
        ([0, 1, 3], [(1, 3, "a"), (1.5, 2.5, "b"), (3.04, 4, "a")])
    ep = json.loads(build("ep", "Show", "1", 30.0, "", json.dumps([[1.0, 2.5, "ねこ"], [2.6, 4.0, "いぬ だ"], [20.0, 21.0, "♪ うた"], [20.0, 22.0, "♪ うた"]]),
                          json.dumps([[1.1, 2.4, "A cat"], [2.7, 3.9, "It's a dog (barks)"], [4.5, 5.0, "Hm"]]), "ja",
                          json.dumps([[["ねこ", 0, "猫", None]], [["いぬ", 0, "犬", None], ["だ", 3, "だ", None]], [], []])))
    sc = ep["scenes"]
    assert [len(x["cues"]) for x in sc] == [1, 1, 2] and [x["i"] for x in sc] == [0, 1, 2], sc  # shown together: one line
    assert [(x["start"], x["end"]) for x in sc] == [(1.0, 2.5), (2.6, 4.0), (20.0, 22.0)]
    assert [[e[2] for e in x["english"]] for x in sc] == [["A cat"], ["It's a dog", "Hm"], []], sc
    assert sc[1]["cues"][0]["seg"] == [["いぬ", "犬"], [" ", None], ["だ", "だ"]] and set(ep["words"]) == {"猫", "犬", "だ"}
    assert len({x["id"] for x in sc}) == 3
    print("ok")
