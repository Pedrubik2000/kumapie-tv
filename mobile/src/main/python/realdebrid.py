"""Real-Debrid on the device (called from local/ProcessWorker.kt), after the PC's pipeline/rd.py: a magnet or a hoster /
Real-Debrid link -> its episode files, numbered from their names; then each file downloaded (resumable). The token
(real-debrid.com/apitoken) is typed into kumapie's Settings."""
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.real-debrid.com/rest/1.0"
VIDEO_EXT = (".mkv", ".mp4", ".avi", ".m4v", ".ts", ".webm")


def _call(token, method, path, data=None):
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    req = urllib.request.Request(API + path, data=body, method=method, headers={"Authorization": f"Bearer {token}"})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw = r.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            if e.code in (429, 502, 503, 504) and attempt < 4:
                time.sleep(2 ** (attempt + 1))
                continue
            detail = e.read().decode(errors="replace")
            if e.code == 401:
                raise RuntimeError("Real-Debrid refused the token: check it in Settings") from None
            raise RuntimeError(f"Real-Debrid {path}: HTTP {e.code} {detail[:200]}") from None
        except urllib.error.URLError:
            if attempt == 4:
                raise
            time.sleep(2 ** (attempt + 1))


def numbers(name: str):
    """(season, episode) from a file name like rd.py: S01E05, E05, " - 05", a lone 05; None where not found."""
    stem = os.path.splitext(os.path.basename(name))[0]
    m = re.search(r"[Ss](\d{1,2})[ ._-]?[Ee](\d{1,3})", stem)
    if m:
        return int(m.group(1)), int(m.group(2))
    season = re.search(r"(?:[Ss]taffel|[Ss]eason)[ ._-]?(\d{1,2})", stem)
    season = int(season.group(1)) if season else None
    for pat in (r"\b(?:[Ee][Pp]?|[Ff]olge)[ ._-]?(\d{1,3})\b", r" - (\d{1,3})\b", r"(?<![\dxp])(\d{1,2})(?![\dp])"):
        m = re.search(pat, stem)
        if m:
            return season, int(m.group(1))
    return season, None


def show_name(name: str) -> str:
    """A show's name guessed from a file or torrent name: up to its season/episode, year, quality or language tag."""
    stem = os.path.splitext(os.path.basename(name))[0]
    stem = re.sub(r"^\[[^\]]*\]\s*", "", stem)  # [Group] Show - 01
    head = re.split(r"[ ._\-\[(]+(?:S\d{1,2}(?:E\d{1,3})?|E\d{1,3}|Staffel|Season|Folge|\d{3,4}p|German|GER|DL|WEB|BluRay|(?:19|20)\d\d)\b"
                    r"| - \d", stem, maxsplit=1, flags=re.I)[0]
    return re.sub(r"[._]+", " ", head).strip(" -") or stem


def describe(name: str, single: bool = False) -> str:
    """JSON {"show", "title", "season", "number"} for a video file's name (one file alone: no lone-number guess)."""
    season, number = numbers(name)
    if single and not re.search(r"[Ss]\d{1,2}[ ._-]?[Ee]\d|[Ff]olge|[Ee][Pp]?[ ._-]?\d| - \d", name):
        number = None
    stem = re.sub(r"[._]+", " ", os.path.splitext(os.path.basename(name))[0]).strip()
    title = (f"S{season} E{number}" if season else f"Episode {number}") if number else stem
    return json.dumps({"show": show_name(name), "title": title, "season": season, "number": number}, ensure_ascii=False)


def episodes(token: str, source: str, log) -> str:
    """The video files of [source] as JSON [{"name", "single", "link"}]: for a magnet, once Real-Debrid
    has it (waiting while it downloads); a hoster or Real-Debrid link is one file."""
    if not source.startswith("magnet:"):
        name = urllib.parse.unquote(source.rstrip("/").rsplit("/", 1)[-1])
        if ".download.real-debrid.com/" not in source:  # only those carry the file name
            name = _call(token, "POST", "/unrestrict/link", {"link": source})["filename"]
        return json.dumps([{"name": name, "single": True, "link": source}], ensure_ascii=False)

    tid = _call(token, "POST", "/torrents/addMagnet", {"magnet": source})["id"]
    log.log("Real-Debrid: reading the magnet…")
    while (info := _call(token, "GET", f"/torrents/info/{tid}"))["status"] == "magnet_conversion":
        time.sleep(3)
    videos = sorted((f for f in info["files"] if f["path"].lower().endswith(VIDEO_EXT)
                     and "sample" not in f["path"].lower() and f["bytes"] > 50_000_000), key=lambda f: f["path"])
    if not videos:
        raise RuntimeError("No video files in it: " + ", ".join(f["path"] for f in info["files"][:5]))
    if info["status"] == "waiting_files_selection":
        _call(token, "POST", f"/torrents/selectFiles/{tid}", {"files": ",".join(str(f["id"]) for f in videos)})
    last = None
    while (info := _call(token, "GET", f"/torrents/info/{tid}"))["status"] != "downloaded":
        if info["status"] in ("error", "magnet_error", "virus", "dead"):
            raise RuntimeError(f"Real-Debrid failed: {info['status']}")
        status = f"Real-Debrid: {info['status'].replace('_', ' ')} {info.get('progress', 0)}%"
        if status != last:
            log.log(status)
            last = status
        time.sleep(10)
    # One link per selected file, in file-id order.
    selected = sorted((f for f in info["files"] if f["selected"]), key=lambda f: f["id"])
    link_of = {f["id"]: link for f, link in zip(selected, info["links"])}
    out = []
    for f in videos:
        if f["id"] in link_of:
            out.append({"name": os.path.basename(f["path"]), "single": len(videos) == 1, "link": link_of[f["id"]]})
    return json.dumps(out, ensure_ascii=False)


def download(token: str, link: str, folder: str, log) -> str:
    """A hoster / Real-Debrid link -> its file in [folder] (resumes a .part left by an earlier try); answers the path."""
    if ".download.real-debrid.com/" in link:
        url, name = link, urllib.parse.unquote(link.rsplit("/", 1)[-1])
    else:
        got = _call(token, "POST", "/unrestrict/link", {"link": link})
        url, name = got["download"], got["filename"]
    os.makedirs(folder, exist_ok=True)
    dest = os.path.join(folder, re.sub(r'[\\/:*?"<>|]', "_", name))
    if os.path.exists(dest):
        return dest
    part = dest + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    req = urllib.request.Request(url, headers={"Range": f"bytes={have}-"} if have else {})
    with urllib.request.urlopen(req, timeout=60) as r:
        if have and r.status != 206:
            have = 0
        total = have + int(r.headers.get("Content-Length", 0))
        with open(part, "ab" if have else "wb") as f:
            done, shown = have, -1
            while chunk := r.read(1 << 20):
                f.write(chunk)
                done += len(chunk)
                pct = int(100 * done / total) if total else 0
                if pct != shown:
                    shown = pct
                    log.log(f"Downloading {name}: {pct}% of {total / 1e9:.1f} GB")
    os.rename(part, dest)
    return dest


if __name__ == "__main__":  # python realdebrid.py: the name rules
    assert numbers("Show.S02E05.German.DL.1080p.mkv") == (2, 5)
    assert numbers("[Group] Frieren - 07 [1080p].mkv") == (None, 7)
    assert numbers("Dark Staffel 1 Folge 3.mp4") == (1, 3)
    assert numbers("movie.2019.1080p.mkv") == (None, None)
    assert show_name("Dark.S01E01.German.DL.1080p.WEB.x264.mkv") == "Dark"
    assert show_name("[SubsPlease] Sousou no Frieren - 07 (1080p).mkv") == "Sousou no Frieren"
    assert show_name("How.to.Sell.Drugs.Online.Fast.2019.German.mkv") == "How to Sell Drugs Online Fast"
    assert json.loads(describe("Dark.S01E03.German.mkv"))["title"] == "S1 E3"
    assert json.loads(describe("Der.Film.2019.mkv", single=True))["title"] == "Der Film 2019"
    assert json.loads(describe("[NH] Bofuri - 04 (BD 1080p) [1CB1].mkv", single=True))["title"] == "Episode 4"
    print("ok")
