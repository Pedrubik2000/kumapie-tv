# kumapie

Learn Japanese from the anime you watch. kumapie streams an episode with its Japanese lines on screen, every word
coloured by what you know. Pick a word to see its meaning, keep it as a card, and review your cards while you watch.
For Android phones and tablets, and Android TV.

> Status: public test, Japanese only. The apps also have a second mode for the author's family, with a small server
> on a PC (German and English too); see [With a PC server](#with-a-pc-server) below. The UI is in English or Spanish.

## Get it

Both apps are on the [Releases page](https://github.com/Pedrubik2000/kumapie-tv/releases) (no Play Store):

- **Phone or tablet**: `kumapie-vX.Y.Z-mobile.apk`. [Obtainium](https://github.com/ImranR98/Obtainium) keeps it
  up to date: add `https://github.com/Pedrubik2000/kumapie-tv`.
- **Android TV / Google TV**: `kumapie-tv-vX.Y.Z.apk`, for example with the Downloader app and the release's link.

Both apps look for a newer release when they start and offer to update.

## What you need

- A **[Real-Debrid](https://real-debrid.com)** account (paid). Videos are streamed through it, found by Torrentio.
- A free **[Jimaku](https://jimaku.cc)** account. Japanese subtitles come from it when a file has none of its own.
- Optional: an **[AniList](https://anilist.co)** account with a public profile, for your lists in Discover.
- About 110 MB for two downloads on the first run: the Japanese dictionaries (27 MB) and the word splitter (80 MB).

## First steps

1. Open kumapie and choose **Use without a server**.
2. The setup asks for your languages, your Jimaku key, your Real-Debrid token and your AniList username, and starts
   the downloads. kumapie builds your own Torrentio link from your token and adds Kitsu for anime titles. **On a TV**,
   scan the QR code with your phone and fill in the page it opens (same Wi-Fi), so nothing is typed with the remote.
3. **Discover**: your AniList lists, then Kitsu's rows and search. Pick a show, an episode, then a file. Files your
   device probably can't play (HEVC 10-bit, AV1, 4K, by their names) are at the bottom of the list.
4. It plays with Japanese lines, the file's own or Jimaku's. If the lines are early or late: the ±0.5 s buttons on
   the phone, ↑ / ↓ on the TV (kumapie also tries to fit them by itself). Once the lines are in, the episode becomes
   kumapie's word player: tap a word on the phone, hold OK on the TV. **Mine** keeps it as a card; due cards are
   rated right there.
5. At the end of an episode the next one starts, from the same release when there is one.

Settings has all of this later (addons, keys), plus **Report a bug or share an idea**.

## Ideas and bugs

[GitHub issues](https://github.com/Pedrubik2000/kumapie-tv/issues). Say which app (phone or TV), its version
(at the bottom of Settings), the device, and what you did.

## Credits

- Dictionaries: [JMdict and JMnedict](https://www.edrdg.org/), © the Electronic Dictionary Research and Development
  Group, used under [CC BY-SA 4.0](https://www.edrdg.org/edrdg/licence.html), in the Yomitan builds of
  [yomidevs/jmdict-yomitan](https://github.com/yomidevs/jmdict-yomitan), downloaded on the first run.
- Word splitter: [Sudachi](https://github.com/WorksApplications/Sudachi) and SudachiDict (Apache-2.0), with a port of
  [Jiten](https://github.com/Sirush/Jiten)'s parser (Apache-2.0); dictionary lookups through
  [hoshidicts](https://github.com/bee-san/hoshidicts) (GPL-3.0).
- Subtitles from [Jimaku](https://jimaku.cc); titles from [Kitsu](https://kitsu.app) and [AniList](https://anilist.co).
- Videos come from your own Real-Debrid account through [Torrentio](https://torrentio.strem.fun). kumapie hosts no
  video and is not affiliated with any of these services.

## With a PC server

The apps were first built for one family, with an optional server on a PC (`kumapie-server`, not needed above): it
syncs episodes and progress between devices, makes episodes from your own files (German and English too), and feeds
the TV app. kumapie is part of **kuma3**, a personal language-learning setup (see
[kuma3-anki](https://github.com/Pedrubik2000/kuma3-anki-app), the AnkiDroid build it can read cards from). The
sections below describe the apps with all of that.

### Phone and tablet (`:mobile`)

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

### TV (`:app`)

The same scenes and colours on Android TV, driven by the remote or a Bluetooth gamepad (on its own, as in First steps, or with the PC server).
You pick a profile, a language and an episode; a help screen lists the buttons (Settings > Change buttons remaps
them).

| | remote | gamepad |
|---|---|---|
| play / pause (at the end of a scene: next scene) | OK | A |
| next / previous scene | → / ← | D-pad → / ← |
| replay the line / the scene | ↑ / hold ← | X / Y |
| subtitle mode | ↓ | L / R |
| pick a word (the card shows its meaning; hold ↓ / Select marks it known) | hold OK | hold A, or Select |
| add the picked word to Anki (again: see its card) | hold ↑ | Y |
| rate the picked word's card (any word with a mined card), its meaning shown | OK, then ← Again, ↓ Hard, → Good, ↑ Easy | A, then the D-pad the same way |
| undo that rating, until the next word card | hold ↑ | Y, or Start |
| hear a word that has a card (A rates it) | after rating it | R2 |
| options | hold ↓ | Start |
| back | Back | B |

A bar along the bottom says what the buttons do now, with PS5 glyphs after a gamepad press and the remote's names
after a remote press (Settings turns it off). Ratings go to kuma3 on the TV, which is never opened: kumapie asks it to sync with AnkiWeb when an episode opens
(the phone's reviews come in) and when you leave the player (the TV's go out). That needs a kuma3 with `kuma3/sync`.

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
| `app` | the TV app: screens, player, button mapping, help, stats, profiles; `ui/Arcade.kt` = its look (slabs, hint bar, PS5 glyphs), `ui/TvMining.kt` = mining and ratings |

Compose (Material 3 on mobile, Compose for TV), Media3 ExoPlayer, Coil, WorkManager. JSON is parsed with
`org.json`.

- Lint: `./gradlew detekt slopCheck` (CI too). detekt's few rules are in `detekt.yml`; old findings in `<module>/detekt-baseline.xml` and `slop.baseline`, which only shrink.
- No comments in `:app` and `:core` `src/main`: the why goes in the commit message. `:lang` and `:mobile` may not gain comments.
- A file is 400 lines at most (`lang/jiten/` exempt); one already over may not grow; no file name ending in a digit.

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
