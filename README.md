# kumapie

An immersion app for Android phones and tablets, with a companion Android TV app. You watch your own shows, anime,
movies and YouTube videos in the language you're learning **scene by scene**, Lingopie-style. Every word is coloured
by your Anki cards. Tap a word to look it up, mine it as a card (with the scene's video clip) or rate its card
without leaving the player.

It works offline first. Episodes, dictionaries, the word splitter and your known words all live on the device. A
small server on your own PC is optional: it syncs episodes and progress between devices and feeds the TV app.
kumapie is part of **kuma3**, a personal language-learning setup (see
[kuma3-anki](https://github.com/Pedrubik2000/kuma3-anki-app), the AnkiDroid build it reads cards from).

> Status: personal project, built for one family's use. Languages: German and English (spaCy), Japanese (a Kotlin
> port of [Jiten](https://github.com/Sirush/Jiten)'s parser over Sudachi). The UI is in English or Spanish.

## Phone and tablet (`:mobile`)

- **Home**: one language at a time (a switch when you learn several), rows by media type (anime, shows, movies,
  YouTube), Continue watching. Posters and descriptions come from AniList / Jikan / Kitsu for anime and TMDB (with
  your own key) or TVmaze / Wikidata for shows and movies; "Fix match" corrects a wrong one.
- **Player**: plays scene by scene in landscape or upright. Swipe left/right to change scenes; tap the video to
  play or pause. The ⋮ menu holds the subtitle mode (none, blurred while playing, transcript, transcript +
  translation), the subtitle timing, 0.75x and "pause at the end of each scene".
- **Word colours** (from your Anki cards, Mined decks only):

  | colour | means |
  |---|---|
  | white | studied and not due, or marked known |
  | green | its card is due today |
  | blue | it has a Mined card that was never reviewed |
  | red | no card yet, or the card is still learning |

  Each scene shows how many unknown words it has, so you can pick i+0 / i+1 scenes.
- **Popup** (tap a word): Yomitan dictionaries (through [hoshidicts](https://github.com/bee-san/hoshidicts)), word
  audio, and rating rows. One rating answers every due card of the word, with Undo. A word with no card is mined
  first: a 🐻 note in "<Language> Mined" with the sentence, translation, audio and the scene's clip (VP9/Opus WebM).
- **Adding episodes**: share a link or a file with kumapie. It downloads (Real-Debrid, yt-dlp), finds
  subtitles (Jimaku for Japanese) and syncs them with [alass](https://github.com/kaegi/alass), or transcribes
  them on the device with Parakeet. The work runs in a processing queue that retries lost connections.
- **No spoilers**: episode pictures stay blurred until you've watched the episode; the feed only shows scenes
  you've already seen. Long-press an episode for "Mark as (not) watched".
- **Also**: a feed of seen scenes, condensed audio, novels (pictures, voices and music, one line per scene), a
  dictionary/search screen, stats, Improve (fix a card that doesn't stick), and a Wi-Fi transfer between two
  devices.
- **Offline**: everything a device does (progress, lookups, marks) waits on it and syncs when the PC answers.
  Progress lives in the Anki collection, so it follows the collection.

## TV (`:app`)

The same scenes and colours on Android TV, driven by the remote or a Bluetooth gamepad. It needs the PC server.
You pick a profile, a language and an episode; a help screen lists the buttons (Settings > Change buttons remaps
them).

| | remote | gamepad |
|---|---|---|
| play / pause (at the end of a scene: next scene) | OK | A |
| next / previous scene | → / ← | D-pad → / ← |
| replay the line / the scene | ↑ / hold ← | X / Y |
| subtitle mode | ↓ | L / R |
| pick a word (the card shows its meaning; hold ↓ marks it known) | hold OK | hold A, or Select |
| options | hold ↓ | Start |
| back | Back | B |

## Build

JDK 17 and the Android SDK (`local.properties`: `sdk.dir=...`), or Android Studio:

```
./gradlew :mobile:assembleDebug :app:assembleDebug
```

Debug builds install next to the release apps (`…kumapie.mobile.debug`, `…kumapie.debug`) and don't update
themselves. The `dev` build type is a quick test build that is signed with the release key and installs over the
real app. On Windows, Gradle needs a short `TEMP` path.

`tools/` holds the native parts and their build scripts: hoshidicts, alass, QuickJS, llama, audio and the
dictionary data.

## Releases

Pushing an annotated tag `vX.Y.Z` makes GitHub Actions (`.github/workflows/release.yml`) build and publish both
signed APKs (`kumapie-vX.Y.Z-mobile.apk`, `kumapie-tv-vX.Y.Z.apk`). The version comes from the tag (`v1.2.3` →
versionCode `10203`). The release key is never in the repo: CI reads `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD`, and a local release build reads `KUMAPIE_KEYSTORE`, `KUMAPIE_KEYSTORE_PASSWORD`,
`KUMAPIE_KEY_ALIAS` and `KUMAPIE_KEY_PASSWORD`. Without them the release build is unsigned.

## Code

Four modules. Paths are under `src/main/java/io/github/pedrubik2000/kumapie/`.

| module | what |
|---|---|
| `core` | shared by both apps: server API (`data/Api.kt`, `data/Backend.kt`), settings, scene controller and word picker, subtitles, meaning card, colours, Spanish strings (`i18n/`), self-update |
| `lang` | the language layer: `Language.kt` (`Languages` registry; `Spaced` for German/English, `Japanese`), `KnownWords.kt`, `CardIndex.kt` and `AnkiCards.kt` (reading kuma3), `Miner.kt` and `WebmMuxer.kt` (mining), `Dictionary.kt` / `Hoshidicts.kt` (Yomitan), `JitenSplitter.kt` + `jiten/` (Japanese splitting) |
| `mobile` | the phone/tablet app: `ui/` screens, `local/` (episodes on the device, processing queue, sync with the PC, subtitle sync, Wi-Fi transfer, Parakeet), `offline/` (downloads, pending reports), `listen/` (condensed audio). Python (spaCy, metadata) runs through Chaquopy from `src/main/python` |
| `app` | the TV app: screens, player, button mapping, help, stats, profiles |

Compose (Material 3 on mobile, Compose for TV), Media3 ExoPlayer, Coil, WorkManager. JSON is parsed with
`org.json`.

## Server

The PC side is a private Python server (standard library only). Each phone or tablet adds `?person=<profile>` to
its calls. Its routes:

| route | what |
|---|---|
| `GET /api/tv`, `/api/tv/people`, `/api/tv/shows`, `/api/tv/episode/<id>` | server info, profiles, shows and episodes (scenes, cues, words, meanings) |
| `GET /api/tv/video/<id>`, `/api/tv/file/<id>/<name>`, `/api/tv/thumb/…`, `/api/tv/poster/…` | media (MP4 with HTTP Range) and images |
| `GET /api/tv/stats`, `/api/tv/history` | watch time and history |
| `POST /api/tv/progress`, `/api/tv/device_seen`, `/api/tv/lookup`, `/api/tv/known`, `/api/tv/meta` | progress and seen scenes, lookups, marks, a show's metadata match |
| `POST /api/tv/known_words` | a device's known words for one person and language |
| `GET/PUT/POST /api/sync/<device>/<episode>/…` | episodes made on a device go up to the PC (resumable), are deleted everywhere or re-timed |
| `GET /api/models/<file>` | data files for the Japanese splitter |
| `GET /api/tts?w=<word>&lang=…` | a word read aloud (MP3) |

## License

GPL-3.0 (since v0.23.0; earlier releases were MIT). Includes [hoshidicts](https://github.com/bee-san/hoshidicts)
(GPL-3.0) and a port of Jiten's parser (Apache-2.0).
