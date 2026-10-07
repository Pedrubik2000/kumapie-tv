"""YouTube downloads on the device with yt-dlp (called from local/ProcessWorker.kt).

YouTube's signature / n challenges need a JavaScript runtime: QuickJS-NG, built for Android and shipped in the APK as
libqjs.so (tools/quickjs/build.sh). There is no ffmpeg here, so yt-dlp downloads the H.264 video and the AAC audio as
two files (no merge) and Kotlin muxes them with MediaMuxer; format 18 (360p, one file) is the fallback."""
import json
import re
import os

import yt_dlp


CHANNEL = re.compile(r"(https?://(?:www\.|m\.)?youtube\.com/(?:@[^/?#]+|channel/[^/?#]+|c/[^/?#]+|user/[^/?#]+))")


def listing(url: str, kind: str, count: int, qjs: str = "") -> str:
    """The newest [count] videos of a channel as JSON ["https://…", …]: [kind] "shorts" or "videos" of the channel at
    [url] (a channel link, or any video of it), or "playlist" for the playlist in [url]. Only lists (no download)."""
    opts = {"quiet": True, "extract_flat": "in_playlist", "playlistend": int(count), "noplaylist": False}
    if qjs:
        opts["js_runtimes"] = {"quickjs": {"path": qjs}}
    with yt_dlp.YoutubeDL(opts) as ydl:
        if kind == "playlist":
            target = url
        else:
            m = CHANNEL.match(url)
            channel = m.group(1) if m else None
            if channel is None:  # a video: its channel
                info = ydl.extract_info(url, download=False, process=False)
                channel = info.get("channel_url") or info.get("uploader_url")
                if not channel:
                    raise RuntimeError("YouTube didn't say whose video this is")
            target = channel.rstrip("/") + ("/shorts" if kind == "shorts" else "/videos")
        info = ydl.extract_info(target, download=False)
    ids = [e.get("id") for e in (info.get("entries") or []) if e and e.get("id")][:int(count)]
    if not ids:
        raise RuntimeError(f"No {kind} found at {target}")
    return json.dumps([f"https://www.youtube.com/shorts/{i}" if kind == "shorts" else f"https://www.youtube.com/watch?v={i}" for i in ids])


def download(url: str, folder: str, qjs: str, log, height: int = 720) -> str:
    """Downloads [url] into [folder] at up to [height] p (H.264, never AV1); answers JSON {"title", "duration", "video", "audio"} ("audio" is null when the
    video file already has the sound)."""
    os.makedirs(folder, exist_ok=True)
    last = [0]

    def hook(d):
        if d.get("status") == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            done = d.get("downloaded_bytes") or 0
            pct = int(100 * done / total) if total else 0
            if pct != last[0]:
                last[0] = pct
                log.log(f"Downloading {d.get('info_dict', {}).get('format_id', '')}: {pct}%")

    class Logs:  # yt-dlp's messages -> logcat (warnings explain refused formats)
        def debug(self, m):
            if m.startswith("[youtube]") or "player" in m:
                log.log("yt-dlp: " + m[:300])
        def info(self, m):
            pass
        def warning(self, m):
            log.log("yt-dlp warning: " + m[:300])
        def error(self, m):
            log.log("yt-dlp error: " + m[:300])

    base = {"noplaylist": True, "quiet": True, "logger": Logs(), "fixup": "never", "progress_hooks": [hook],
            "js_runtimes": {"quickjs": {"path": qjs}}}
    # No ffmpeg, so never ask yt-dlp to merge: the H.264 video and the AAC audio are two downloads (Kotlin muxes
    # them); if either is missing, format 18 (360p, video and sound in one file).
    # One YoutubeDL session for finding and downloading: YouTube refuses (HTTP 403) files fetched outside the session
    # that found them. "<video>,<audio>" downloads both formats as separate files (no merging, so no ffmpeg). The
    # session builds its format choice when it starts, so the chosen one replaces ydl.format_selector.
    opts = {**base, "format": "18", "outtmpl": os.path.join(folder, "%(format_id)s.%(ext)s")}
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False, process=False)  # look only; the format is chosen below
        # The quality is the short side, as YouTube names it (a 720p Short is 720x1280): the best H.264 MP4 video
        # whose short side is at most [height].
        fits = [f for f in info.get("formats", []) if (f.get("vcodec") or "").startswith("avc1") and f.get("ext") == "mp4"
                and f.get("acodec") in (None, "none") and f.get("width") and f.get("height")
                and f.get("protocol", "https") == "https" and min(f["width"], f["height"]) <= int(height)]
        if fits:
            best = max(fits, key=lambda f: (min(f["width"], f["height"]), f.get("tbr") or 0))
            log.log(f"Video: {best['width']}x{best['height']}")
            ydl.format_selector = ydl.build_format_selector(f"{best['format_id']},ba[ext=m4a]")
        else:
            log.log("No separate H.264 video: the 360p file")
            ydl.format_selector = ydl.build_format_selector("18/b[ext=mp4][acodec!=none]")
        ydl.process_ie_result(info, download=True)
    files = [os.path.join(folder, n) for n in os.listdir(folder) if not n.endswith((".part", ".ytdl"))]
    videos = [f for f in files if f.endswith(".mp4")]
    audios = [f for f in files if f.endswith(".m4a")]
    if not videos:
        raise RuntimeError("yt-dlp gave no MP4 video")
    video, audio = max(videos, key=os.path.getsize), (audios[0] if audios else None)
    if not os.path.exists(video):
        raise RuntimeError("yt-dlp gave no MP4 video")
    return json.dumps({"title": info.get("title") or "YouTube video", "duration": info.get("duration") or 0,
                       "video": video, "audio": audio if audio and os.path.exists(audio) else None,
                       "channel": info.get("channel") or info.get("uploader") or ""}, ensure_ascii=False)
