"""Text subtitles inside an MKV (called from local/ProcessWorker.kt; the tablet has no ffmpeg): the file's subtitle
tracks from its EBML structure, and one track written out as .srt / .ass. Subtitles inside the file are timed for
exactly this video, so they need no sync. Image subtitles (PGS, VobSub) can't be read as text and are skipped."""
import json
import os
import struct
import sys

# EBML element ids
SEGMENT, CLUSTER, TRACKS, TRACK_ENTRY, INFO, BLOCK_GROUP = 0x18538067, 0x1F43B675, 0x1654AE6B, 0xAE, 0x1549A966, 0xA0
CONTAINERS = {SEGMENT, CLUSTER, TRACKS, TRACK_ENTRY, INFO, BLOCK_GROUP}
TRACK_NUMBER, TRACK_TYPE, CODEC_ID, CODEC_PRIVATE, LANGUAGE, LANGUAGE_BCP47, NAME, FORCED = (
    0xD7, 0x83, 0x86, 0x63A2, 0x22B59C, 0x22B59D, 0x536E, 0x55AA)
TIMESTAMP_SCALE, CLUSTER_TIME, SIMPLE_BLOCK, BLOCK, BLOCK_DURATION = 0x2AD7B1, 0xE7, 0xA3, 0xA1, 0x9B
TEXT_CODECS = {"S_TEXT/UTF8": "srt", "S_TEXT/ASS": "ass", "S_TEXT/SSA": "ass", "S_ASS": "ass", "S_SSA": "ass"}
LANGS = {"ja": ("ja", "jpn", "jp"), "en": ("en", "eng"), "de": ("de", "ger", "deu")}


def _vint(f, keep_marker):
    first = f.read(1)
    if not first:
        raise EOFError
    b = first[0]
    length = 1
    while length <= 8 and not b & (0x80 >> (length - 1)):
        length += 1
    rest = f.read(length - 1)
    if len(rest) < length - 1:
        raise EOFError
    value = b if keep_marker else b & (0xFF >> length)
    for x in rest:
        value = (value << 8) | x
    unknown = not keep_marker and value == (1 << (7 * length)) - 1
    return value, unknown


def _elements(f, end):
    """(id, size or None when unknown, data offset) of every element before [end], descending into containers."""
    while f.tell() < end:
        try:
            eid, _ = _vint(f, True)
            size, unknown = _vint(f, False)
        except EOFError:
            return
        start = f.tell()
        yield eid, None if unknown else size, start
        if eid not in CONTAINERS and not unknown and f.tell() == start:
            f.seek(start + size)


def _uint(data):
    return int.from_bytes(data, "big") if data else 0


def read(path, want_track=None):
    """{"tracks": [subtitle tracks], "events": [(start s, duration s or None, payload)] of [want_track]}."""
    tracks, events, scale, cluster = [], [], 1_000_000, 0
    entry, last = None, None
    with open(path, "rb") as f:
        end = os.path.getsize(path)
        for eid, size, start in _elements(f, end):
            if eid == TRACK_ENTRY:
                entry = {"number": 0, "type": 0, "codec": "", "lang": "eng", "name": "", "forced": False, "private": b""}
                tracks.append(entry)
            elif entry is not None and eid in (TRACK_NUMBER, TRACK_TYPE, CODEC_ID, CODEC_PRIVATE, LANGUAGE, LANGUAGE_BCP47, NAME, FORCED):
                data = f.read(size)
                key = {TRACK_NUMBER: "number", TRACK_TYPE: "type", CODEC_ID: "codec", CODEC_PRIVATE: "private",
                       LANGUAGE: "lang", LANGUAGE_BCP47: "lang", NAME: "name", FORCED: "forced"}[eid]
                entry[key] = _uint(data) if key in ("number", "type") else bool(_uint(data)) if key == "forced" \
                    else data if key == "private" else data.decode("utf-8", "replace").rstrip("\0")
            elif eid == TIMESTAMP_SCALE:
                scale = _uint(f.read(size))
            elif eid == CLUSTER:
                entry = None
                if want_track is None:  # only the track list was asked for
                    break
            elif eid == CLUSTER_TIME:
                cluster = _uint(f.read(size))
            elif eid in (SIMPLE_BLOCK, BLOCK):
                track, _ = _vint(f, False)
                if track == want_track:
                    head = f.read(3)
                    rel = struct.unpack(">h", head[:2])[0]
                    payload = f.read(size - (f.tell() - start))
                    last = [(cluster + rel) * scale / 1e9, None, payload]
                    events.append(last)
                else:
                    last = None
                f.seek(start + size)
            elif eid == BLOCK_DURATION:
                duration = _uint(f.read(size))
                if last is not None:
                    last[1] = duration * scale / 1e9
    return {"tracks": [t for t in tracks if t["type"] == 0x11], "events": events}


def pick(tracks, lang):
    """The best text subtitle track for [lang]: full dialogue over forced / signs tracks, then the first."""
    codes = LANGS.get(lang, (lang,))
    ok = [t for t in tracks if t["codec"] in TEXT_CODECS and t["lang"].split("-")[0].lower() in codes]
    signs = lambda t: t["forced"] or any(w in t["name"].lower() for w in ("forced", "sign", "song", "karaoke"))
    ok.sort(key=lambda t: (signs(t), "full" not in t["name"].lower() and "main" not in t["name"].lower() and "dialog" not in t["name"].lower()))
    return ok[0] if ok else None


def _ts(t, ass):
    cs = round(t * (100 if ass else 1000))
    if ass:
        return f"{cs // 360000}:{cs // 6000 % 60:02}:{cs // 100 % 60:02}.{cs % 100:02}"
    return f"{cs // 3600000:02}:{cs // 60000 % 60:02}:{cs // 1000 % 60:02},{cs % 1000:03}"


def extract(path, lang, folder):
    """The [lang] text subtitles inside [path] as a file in [folder]; JSON {"path", "name"} or {} when there are none."""
    tracks = read(path)["tracks"]
    track = pick(tracks, lang)
    if track is None:
        return json.dumps({})
    events = read(path, track["number"])["events"]
    kind = TEXT_CODECS[track["codec"]]
    os.makedirs(folder, exist_ok=True)
    out = os.path.join(folder, f"embedded.{lang}.{kind}")
    with open(out, "w", encoding="utf-8") as w:
        if kind == "ass":
            w.write(track["private"].decode("utf-8", "replace").rstrip() + "\n")
            for start, dur, payload in events:
                # Block text: ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                parts = payload.decode("utf-8", "replace").split(",", 8)
                if len(parts) == 9:
                    w.write(f"Dialogue: {parts[1]},{_ts(start, True)},{_ts(start + (dur or 0), True)},{','.join(parts[2:])}\n")
        else:
            for i, (start, dur, payload) in enumerate(events, 1):
                w.write(f"{i}\n{_ts(start, False)} --> {_ts(start + (dur or 2), False)}\n{payload.decode('utf-8', 'replace').strip()}\n\n")
    return json.dumps({"path": out, "name": track["name"] or track["codec"]}, ensure_ascii=False)


if __name__ == "__main__":  # python mkvsubs.py <file.mkv> [lang]: its subtitle tracks, and the [lang] one written out
    info = read(sys.argv[1])
    for t in info["tracks"]:
        print(t["number"], t["codec"], t["lang"], t["name"], "forced" if t["forced"] else "")
    if len(sys.argv) > 2:
        got = json.loads(extract(sys.argv[1], sys.argv[2], os.path.dirname(os.path.abspath(sys.argv[1]))))
        print(got)
        if got:
            print(open(got["path"], encoding="utf-8").read()[-600:])
