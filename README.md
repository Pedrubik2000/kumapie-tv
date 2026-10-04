# kumapie-tv

Watch your own shows in a foreign language **scene by scene** on Android TV, Lingopie-style: every scene
shows how many words in it you don't know yet, subtitles stay hidden until you ask, and you can pick a word
for its meaning. You drive it with the TV remote or a Bluetooth gamepad.

The app is only the TV side. Your videos, their subtitles, the scenes, the word meanings and which words
you know live on your own PC, in a small server that the app talks to (see [Server API](#server-api)).
Part of **kuma3**, a personal language-learning setup (see also
[kuma3-anki](https://github.com/Pedrubik2000/kuma3-anki-app)).

> Status: early. Shows, episodes and the scene player work; the word picker and button mapping are next.

## Buttons

| | remote | gamepad |
|---|---|---|
| play / pause (at the end of a scene: next scene) | OK | A |
| next / previous scene | → / ← | D-pad → / ← |
| replay the line | ↑ | X |
| replay the scene | hold ← | Y |
| subtitles: none → German → German + English | ↓ | L = German, R = English |
| speed 0.75x | options | L2 |
| pause at the end of each scene / play on | options | R2 |
| options | hold ↓ | Start |
| back | Back | B |

Subtitles stay as you set them, from scene to scene and the next time you watch; while paused they show the whole scene, with
unknown words in red and words you're learning in yellow. The options are remembered on the TV.

## Install

1. On the TV, allow installing apps from your browser/file manager (Settings → Privacy → Security & restrictions → Unknown sources), or use `adb install`.
2. Download `kumapie-tv-vX.Y.Z.apk` from [Releases](https://github.com/Pedrubik2000/kumapie-tv/releases/latest) and install it.
3. Open **kumapie** and type your server's address, e.g. `https://my-pc.my-tailnet.ts.net:8445`.
   Typing with a remote is slow; from a PC with ADB you can send it instead:
   ```
   adb shell am start -n io.github.pedrubik2000.kumapie/.MainActivity --es server https://my-pc.my-tailnet.ts.net:8445
   ```

The app updates itself: when it starts it checks this repo's latest release and offers **Update**. The
first time, Android asks you to allow kumapie to install apps.

## Build

Android Studio, or the command line with JDK 17 and the Android SDK (`local.properties`: `sdk.dir=...`):

```
./gradlew assembleDebug
```

Debug builds are called `io.github.pedrubik2000.kumapie.debug`, so they install next to the release app.
They don't update themselves.

## Releases

Push an annotated tag; GitHub Actions builds a signed APK and publishes it (`.github/workflows/release.yml`):

```
git tag -a v0.2.0 -m "What changed (shown in the app's update dialog)"
git push origin v0.2.0
```

The version comes from the tag (`v1.2.3` → versionName `1.2.3`, versionCode `10203`). The release key is
never in the repo: the workflow reads it from the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD`. A local release build reads the same values from the environment
(`KUMAPIE_KEYSTORE`, `KUMAPIE_KEYSTORE_PASSWORD`, `KUMAPIE_KEY_ALIAS`, `KUMAPIE_KEY_PASSWORD`).

## Code

| file | what |
|---|---|
| `MainActivity.kt` | the screens and Back |
| `data/Api.kt` | the server API and its data |
| `data/Settings.kt` | what the TV remembers (server address, later: subtitle mode, buttons) |
| `ui/HomeScreen.kt`, `ui/ShowScreen.kt` | shows → episodes |
| `ui/PlayerScreen.kt` | the scene player: video, top bar, subtitles, options |
| `player/SceneController.kt` | scenes on top of the whole episode: where to stop, replays, progress |
| `player/PlayerKeys.kt` | which button does what (short and long presses) |
| `ui/SettingsScreen.kt`, `ui/UpdateDialog.kt` | settings, updates |
| `update/Updater.kt`, `update/InstallReceiver.kt` | self-update from GitHub Releases |
| `tools/make_icons.py` | draws the launcher banner and icon |

Compose for TV, Media3 ExoPlayer, Coil. JSON is parsed with `org.json`, no code generation.

## Server API

JSON over HTTP(S). The app needs these routes (the reference server is part of a private setup:
`services/feed/tv.py`, Python standard library only). Ids are 12 hex characters.

| route | returns |
|---|---|
| `GET /api/tv` | `{"api": 1, "language": "de", "translation": "en", "episodes": 14}` |
| `GET /api/tv/shows` | `{"shows": [{"id", "title", "kind", "poster", "episodes": [{"id", "title", "season", "episode", "duration", "thumb", "scenes", "seen", "easy", "resume"}]}]}`. `easy` = scenes at i+0/i+1 today, `resume` = seconds or null |
| `GET /api/tv/episode/<id>` | `{"id", "show", "title", "duration", "video", "resume", "scenes": [{"id", "i", "start", "end", "german", "level", "seen", "cues": [{"start", "end", "seg": [[text, word or null]]}], "english": [[start, end, text]], "g": {word: meaning}}], "words": {word: {"s": "k"/"l"/"u", "g": meaning}}}`. Times are seconds in the episode; `level` = unknown words in the scene; a scene's `g` overrides a word's meaning where it means something else there |
| `GET /api/tv/video/<id>` | the episode file (H.264/AAC MP4), with HTTP Range |
| `GET /api/tv/thumb/<id>.jpg`, `GET /api/tv/poster/<show>.jpg` | images |
| `POST /api/tv/progress` | body `{"episode", "pos", "seen": [scene ids], "watched": seconds}` |
| `GET /api/tv/stats` | `{"days": {"2026-10-04": seconds}}` |

## License

MIT
