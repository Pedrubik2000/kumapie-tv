"""YouTube downloads on the device with yt-dlp (called from local/ProcessWorker.kt).

YouTube's signature / n challenges need a JavaScript runtime: QuickJS-NG, built for Android and shipped in the APK as
libqjs.so (tools/quickjs/build.sh). There is no ffmpeg here, so yt-dlp downloads the H.264 video and the AAC audio as
two files (no merge) and Kotlin muxes them with MediaMuxer; format 18 (360p, one file) is the fallback."""
import json
import os

import yt_dlp


def download(url: str, folder: str, qjs: str, log) -> str:
    """Downloads [url] into [folder]; answers JSON {"title", "duration", "video", "audio"} ("audio" is null when the
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

    opts = {
        "format": "bv*[vcodec^=avc1][height<=720][ext=mp4]+ba[ext=m4a]/18/b[ext=mp4]",
        "outtmpl": os.path.join(folder, "video.%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "fixup": "never",
        "progress_hooks": [hook],
        "js_runtimes": {"quickjs": {"path": qjs}},
    }
    # Without ffmpeg yt-dlp downloads the requested video and audio separately ("<name>.f136.mp4", "<name>.f140.m4a")
    # instead of merging them; the files are found in the folder afterwards.
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=True)
    files = [os.path.join(folder, n) for n in os.listdir(folder) if not n.endswith((".part", ".ytdl"))]
    videos = [f for f in files if f.endswith(".mp4")]
    audios = [f for f in files if f.endswith(".m4a")]
    if not videos:
        raise RuntimeError("yt-dlp gave no MP4 video")
    return json.dumps({"title": info.get("title") or "YouTube video", "duration": info.get("duration") or 0,
                       "video": max(videos, key=os.path.getsize), "audio": audios[0] if audios else None,
                       "channel": info.get("channel") or info.get("uploader") or ""}, ensure_ascii=False)
