"""A new episode made on the device (called from Kotlin, local/ProcessWorker.kt), the way the PC's pipeline does it:
audio -> Soniox (dojo skills/scripts/transcribe.py) -> German cues (srt_spaced.py) -> scenes (tools/feed/feed.py's
exchanges) with their words (spaCy, german.py) -> the episode JSON the player reads (services/feed/tv.py's format).
The English cues come from Kotlin (the device's translator) or from Soniox's translation."""
import difflib
import hashlib
import json
import re
import time

import requests

import srt_spaced
from german import _morphemizer
from morphemizer import VERB_POS

SONIOX_API = "https://api.soniox.com/v1"
MAX_GAP = 1.5   # [feed] max_gap: silence that ends a scene
MAX_SECS = 15   # [feed] max_secs: longer runs are split at their biggest pause
PAD = 0.3       # [feed] pad


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

def split_run(run, max_secs):
    if len(run) == 1 or run[-1][1] - run[0][0] <= max_secs:
        return [run]
    best = max(range(1, len(run)),
               key=lambda i: run[i][0] - run[i - 1][1] + (1.0 if run[i - 1][2].rstrip()[-1:] in ".!?" else 0)
               - 0.001 * abs(i - len(run) / 2))
    return split_run(run[:best], max_secs) + split_run(run[best:], max_secs)


def exchanges(cues):
    runs = []
    for c in cues:
        if runs and c[0] - runs[-1][-1][1] <= MAX_GAP:
            runs[-1].append(c)
        else:
            runs.append([c])
    pieces = [p for r in runs for p in split_run(r, MAX_SECS)]
    out = []
    for i, p in enumerate(pieces):
        before = pieces[i - 1][-1][1] if i else 0.0
        after = pieces[i + 1][0][0] if i + 1 < len(pieces) else p[-1][1] + PAD
        out.append((p, round(max(p[0][0] - PAD, before, 0.0), 3), round(min(p[-1][1] + PAD, after), 3)))
    return out


def is_german(text, english):
    letters = [ch for ch in text if ch.isalpha()]
    if not letters or sum(ch.isascii() or ch in "äöüÄÖÜß" for ch in letters) < 0.8 * len(letters):
        return False
    norm = lambda s: re.sub(r"\W+", " ", s.lower()).strip()
    return not english or difflib.SequenceMatcher(None, norm(text), norm(english)).ratio() < 0.8


def parse(mz, texts):
    """Cue texts -> per cue [[surface, offset, inflection or None, lemma or None]] (feed.make_parser)."""
    out = []
    for text, doc in zip(texts, mz.nlp.pipe([mz.prepare(t) for t in texts], batch_size=256)):
        toks = mz.tokens(doc)
        for t, tok in zip(doc, list(toks)):
            if mz.join_separable_verbs and t.dep_ == "svp" and t.head.pos_ in VERB_POS and t.head.i != t.i:
                toks[t.i] = type(tok)(tok.text, tok.lemma, tok.pos, tok.dep, toks[t.head.i].morph, tok.skipped)
        low, pos, row = text.lower(), 0, []
        for tok in toks:
            at = low.find(tok.text.lower(), pos)
            surface = text[at:at + len(tok.text)] if at >= 0 else tok.text
            if at >= 0:
                pos = at + len(tok.text)
            m = tok.morph
            row.append([surface, at, m.inflection if m else None, m.lemma if m else None])
        out.append(row)
    return out


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


def build(model_dir: str, episode_id: str, show: str, title: str, duration: float, video: str,
          cues_json: str, english_json: str) -> str:
    """German and English cues -> the episode JSON (scenes, their cues as word segments, English, every word)."""
    cues = [tuple(c) for c in json.loads(cues_json)]
    english = json.loads(english_json)
    mz = _morphemizer(model_dir)
    scenes, words, lemmas = [], {}, {}
    plan = []
    for i, (p, start, end) in enumerate(exchanges(cues)):
        en = [[e[0], e[1], re.sub(r"\s*\([^)]*\)", "", e[2]).strip()] for e in english
              if p[0][0] <= (e[0] + e[1]) / 2 <= p[-1][1]]
        german = is_german(" ".join(c[2] for c in p), " ".join(e[2] for e in en))
        plan.append((i, p, start, end, en, german))
    texts = [c[2] for (_, p, _, _, _, g) in plan if g for c in p]
    parsed = iter(parse(mz, texts))
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
    return json.dumps({"id": episode_id, "show": show, "title": title, "duration": duration, "video": video,
                       "resume": None, "scenes": scenes, "words": words}, ensure_ascii=False)
