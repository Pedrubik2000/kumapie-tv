"""Japanese subtitles from Jimaku (jimaku.cc) for an anime episode (called from local/ProcessWorker.kt): the show's
AniList entry (AniList search, no key) -> its Jimaku entry -> the file for the episode (a season .zip: the episode's
file inside it). The API key (jimaku.cc > Account) is typed into kumapie's Settings."""
import io
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

from realdebrid import numbers

API = "https://jimaku.cc/api"
AGENT = "kumapie (github.com/Pedrubik2000/kumapie-tv)"
SUB_EXT = (".srt", ".ass", ".ssa", ".vtt")


def _get(url, key=None, raw=False):
    headers = {"User-Agent": AGENT}
    if key:
        headers["Authorization"] = key
    req = urllib.request.Request(url, headers=headers)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                body = r.read()
                return body if raw else json.loads(body)
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt < 3:  # rate limit per IP: wait what the server says
                time.sleep(float(e.headers.get("x-ratelimit-reset-after") or 5) + 0.5)
                continue
            if e.code == 401 and key:
                raise RuntimeError("Jimaku refused the API key: check it in Settings") from None
            raise RuntimeError(f"{url.split('?')[0]}: HTTP {e.code} {e.read().decode(errors='replace')[:200]}") from None


def anilist(show: str, season=None) -> dict | None:
    """{"id", "romaji", "english", "native"} of [show] on AniList; for season 2+ the entry named for that season."""
    query = "query($s:String){Page(perPage:8){media(search:$s,type:ANIME){id title{romaji english native}}}}"
    req = urllib.request.Request("https://graphql.anilist.co", method="POST",
                                 data=json.dumps({"query": query, "variables": {"s": show}}).encode(),
                                 headers={"Content-Type": "application/json", "User-Agent": AGENT})
    with urllib.request.urlopen(req, timeout=30) as r:
        found = json.loads(r.read())["data"]["Page"]["media"]
    if not found:
        return None
    pick = found[0]
    if season and season > 1:
        word = re.compile(rf"\b(?:{season}(?:st|nd|rd|th) Season|Season {season})\b|第{season}期", re.I)
        pick = next((m for m in found if any(word.search(t or "") for t in m["title"].values())), pick)
    return {"id": pick["id"], **pick["title"]}


def rank(files: list, episode, video_name: str = "") -> list:
    """Jimaku files for [episode], best first: the episode's own files (else season packs), subtitles over archives,
    then the most words shared with the video's file name (same release: WEB / BD / group)."""
    words = set(re.findall(r"[a-z0-9]+", video_name.lower()))

    def score(f):
        name = f["name"].lower()
        is_sub = name.endswith(SUB_EXT)
        same = numbers(name)[1] == episode if episode is not None else True
        return (same, is_sub, len(words & set(re.findall(r"[a-z0-9]+", name))), name.endswith(".srt"))

    usable = [f for f in files if f["name"].lower().endswith(SUB_EXT + (".zip",))]
    usable = [f for f in usable if f["name"].lower().endswith(".zip") or episode is None or numbers(f["name"])[1] == episode]
    return sorted(usable, key=score, reverse=True)


def candidates(key: str, show: str, season, episode, video_name: str = "") -> str:
    """JSON {"anilist", "entry", "files": [{"name", "url", "size"}...]} best first; files is empty when nothing fits."""
    media = anilist(show, season)
    q = {"anime": "true"}
    q.update({"anilist_id": media["id"]} if media else {"query": show})
    entries = _get(f"{API}/entries/search?" + urllib.parse.urlencode(q), key)
    if not entries and media:  # an entry without its AniList ID: by name
        entries = _get(f"{API}/entries/search?" + urllib.parse.urlencode({"anime": "true", "query": media["romaji"]}), key)
    if not entries:
        return json.dumps({"anilist": media, "entry": None, "files": []}, ensure_ascii=False)
    entry = entries[0]
    files = _get(f"{API}/entries/{entry['id']}/files", key)
    out = [{"name": f["name"], "url": f["url"], "size": f["size"]} for f in rank(files, episode, video_name)]
    return json.dumps({"anilist": media, "entry": entry.get("japanese_name") or entry["name"], "files": out}, ensure_ascii=False)


def download(key: str, url: str, name: str, episode, folder: str) -> str:
    """One Jimaku file -> a subtitle file in [folder] (from a .zip: the member for [episode]); answers its path."""
    data = _get(url, key, raw=True)
    if name.lower().endswith(".zip"):
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            subs = [n for n in z.namelist() if n.lower().endswith(SUB_EXT)]
            match = [n for n in subs if episode is None or numbers(n)[1] == episode]
            if not match:
                raise RuntimeError(f"No file for episode {episode} in {name}")
            name = os.path.basename(sorted(match, key=lambda n: not n.lower().endswith(".srt"))[0])
            data = z.read(next(n for n in match if os.path.basename(n) == name))
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, re.sub(r'[\\/:*?"<>|]', "_", name))
    with open(path, "wb") as f:
        f.write(data)
    return path


def _secs(h, m, s, frac):
    return int(h) * 3600 + int(m) * 60 + int(s) + int(frac) / 10 ** len(frac)


def _text(raw: bytes) -> str:
    for enc in ("utf-8-sig", "utf-16", "cp932"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            pass
    return raw.decode("utf-8", "replace")


def cues(path: str, lang: str = "ja") -> str:
    r"""A subtitle file (.srt / .vtt / .ass / .ssa) -> cues [[start, end, text]] in time order, as JSON: styling
    dropped, ASS signs (lines placed with \pos / \move, drawings) left out, line breaks joined with a space; Japanese
    only what is said ([spoken]), other languages without SDH brackets and music."""
    text = _text(open(path, "rb").read()).replace("\r", "")
    out = []
    if path.lower().endswith((".ass", ".ssa")):
        fields = None
        events = False
        for line in text.splitlines():
            if line.startswith("["):
                events = line.strip().lower() == "[events]"
            elif line.startswith("Format:") and events:
                fields = [f.strip().lower() for f in line[7:].split(",")]
            elif line.startswith("Dialogue:") and fields:
                row = dict(zip(fields, line[9:].split(",", len(fields) - 1)))
                body = row.get("text", "")
                if not dialogue(row.get("style", ""), body):
                    continue
                body = re.sub(r"\{[^}]*\}", "", body)
                body = re.sub(r"\\[Nnh]", " ", body).strip()
                t = [re.match(r"\s*(\d+):(\d+):(\d+)\.(\d+)", row.get(k, "")) for k in ("start", "end")]
                if body and all(t):
                    out.append([_secs(*t[0].groups()), _secs(*t[1].groups()), body])
    else:
        stamp = r"(\d+):(\d+):(\d+)[,.](\d+)"
        for block in re.split(r"\n\s*\n", text):
            m = re.search(stamp + r"\s*-->\s*" + stamp, block)
            if m:
                body = " ".join(l.strip() for l in block[m.end():].strip().splitlines())
                body = re.sub(r"<[^>]+>|\{[^}]*\}", "", body).strip()
                if body:
                    out.append([_secs(*m.groups()[:4]), _secs(*m.groups()[4:]), body])
    out.sort(key=lambda c: c[0])
    clean = spoken if lang == "ja" else plain
    seen, kept = set(), []
    for a, b, t in out:
        t = clean(t)
        if t and (a, b, t) not in seen:  # layered karaoke / shadow lines repeat the same text
            seen.add((a, b, t))
            kept.append([round(a, 3), round(b, 3), t])
    return json.dumps(kept, ensure_ascii=False)


SONG_OR_SIGN = re.compile(r"sign|\bop\d*\b|\bed\d*\b|opening|ending|song|karaoke|kfx|lyric|\brom|romaji|kanji|title|insert|"
                          r"typeset|\bts\b|stars|eyecatch|logo|card", re.I)


def dialogue(style: str, body: str) -> bool:
    r"""An ASS line that is said: not in a sign / song / karaoke style, no karaoke timing (\k), no drawing; a line placed
    with \pos counts only in a dialogue-looking style (fansubs place Default lines too)."""
    if SONG_OR_SIGN.search(style) or re.search(r"\\(k[fo]?\d|p[1-9])", body):
        return False
    return not re.search(r"\\(pos|move)", body) or re.search(r"default|main|dialog|alt|italic|top|flash|overlap", style, re.I)


def plain(text: str) -> str:
    """A line in a spaced language without SDH's [door opens] / (laughs) and music; "" when nothing is left."""
    text = re.sub(r"\[[^\]]*\]|\([^)]*\)|[♪♬]", "", text)
    return re.sub(r"\s+", " ", text).strip(" -")


def spoken(text: str) -> str:
    """Only what is said: SDH's readings 後藤(ごとう), speaker names and sounds （園児）（拍手）, music ♪ dropped;
    "" when nothing Japanese is left."""
    text = re.sub(r"\([^)]*\)", "", text)
    text = re.sub(r"（[^）]*）|［[^］]*］|\[[^\]]*\]|[♪♬～〜]", "", text).strip()
    return re.sub(r"\s+", " ", text) if re.search(r"[぀-ヿ一-鿿]", text) else ""


if __name__ == "__main__":  # python jimaku.py: the picking rules (and AniList, online)
    files = [{"name": n, "url": "", "size": 0} for n in (
        "[Erai-raws] Sousou no Frieren - 07 [1080p][Multiple Subtitle].ja.ass",
        "Sousou.no.Frieren.S01E07.WEBRip.Netflix.ja[cc].srt",
        "Sousou.no.Frieren.S01E08.WEBRip.Netflix.ja[cc].srt",
        "Frieren BD 1-28.zip",
        "Frieren.S01E07.7z")]
    got = [f["name"] for f in rank(files, 7, "Sousou.no.Frieren.S01E07.1080p.NF.WEB-DL.mkv")]
    assert got == [files[1]["name"], files[0]["name"], "Frieren BD 1-28.zip"], got
    assert anilist("Sousou no Frieren")["id"] == 154587
    assert anilist("Sousou no Frieren", 2)["id"] == 182255
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("Frieren/Frieren - 06.srt", "six")
        z.writestr("Frieren/Frieren - 07.srt", "seven")
    import tempfile, unittest.mock as mock
    with mock.patch(__name__ + "._get", return_value=buf.getvalue()), tempfile.TemporaryDirectory() as d:
        assert open(download("", "", "pack.zip", 7, d)).read() == "seven"
    with tempfile.TemporaryDirectory() as d:
        ass = os.path.join(d, "a.ass")
        with open(ass, "w", encoding="utf-8") as f:
            f.write("[V4+ Styles]\nFormat: Name, Fontname, Fontsize\nStyle: Default,Arial,20\n\n"
                    "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                    r"Dialogue: 0,0:00:05.00,0:00:07.50,Default,,0,0,0,,{\an8}本当に、\Nありがとう" "\n"
                    r"Dialogue: 0,0:00:01.00,0:00:03.00,Sign,,0,0,0,,{\pos(10,10)}看板" "\n")
        assert json.loads(cues(ass)) == [[5.0, 7.5, "本当に、 ありがとう"]], cues(ass)
        srt = os.path.join(d, "a.srt")
        with open(srt, "w", encoding="utf-8-sig") as f:
            f.write("1\n00:00:01,000 --> 00:00:02,500\n<i>え？</i>\nうん\n\n2\n00:01:00,100 --> 00:01:01,000\n（拍手）\n")
        assert json.loads(cues(srt)) == [[1.0, 2.5, "え？ うん"]], cues(srt)
    assert spoken("（後藤(ごとう)ひとり）私なんかが…") == "私なんかが…"
    assert spoken("♪～") == "" and spoken("（馬車の進行音）") == ""
    if key := os.environ.get("JIMAKU_API_KEY"):
        print(json.dumps(json.loads(candidates(key, "Sousou no Frieren", None, 7))["files"][:3], ensure_ascii=False, indent=1))
    print("ok")
