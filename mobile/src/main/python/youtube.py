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

    base = {"noplaylist": True, "quiet": True, "no_warnings": True, "fixup": "never", "progress_hooks": [hook],
            "js_runtimes": {"quickjs": {"path": qjs}}}
    # No ffmpeg, so never ask yt-dlp to merge: the H.264 video and the AAC audio are two downloads (Kotlin muxes
    # them); if either is missing, format 18 (360p, video and sound in one file).
    video = audio = None
    info = {}
    try:
        with yt_dlp.YoutubeDL({**base, "format": "bv*[vcodec^=avc1][height<=720][ext=mp4]",
                               "outtmpl": os.path.join(folder, "video.%(ext)s")}) as ydl:
            info = ydl.extract_info(url, download=True)
            video = os.path.join(folder, "video.mp4")
        with yt_dlp.YoutubeDL({**base, "format": "ba[ext=m4a]",
                               "outtmpl": os.path.join(folder, "audio.%(ext)s")}) as ydl:
            ydl.extract_info(url, download=True)
            audio = os.path.join(folder, "audio.m4a")
    except yt_dlp.utils.DownloadError as e:
        log.log(f"Separate video/audio failed ({e}); trying the 360p file")
        for n in os.listdir(folder):
            os.remove(os.path.join(folder, n))
        with yt_dlp.YoutubeDL({**base, "format": "18/b[ext=mp4][acodec!=none]",
                               "outtmpl": os.path.join(folder, "video.%(ext)s")}) as ydl:
            info = ydl.extract_info(url, download=True)
        video, audio = os.path.join(folder, "video.mp4"), None
    if not os.path.exists(video):
        raise RuntimeError("yt-dlp gave no MP4 video")
    return json.dumps({"title": info.get("title") or "YouTube video", "duration": info.get("duration") or 0,
                       "video": video, "audio": audio if audio and os.path.exists(audio) else None,
                       "channel": info.get("channel") or info.get("uploader") or ""}, ensure_ascii=False)
