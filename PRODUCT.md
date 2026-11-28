# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
- **Pedro.** Learns Japanese, on his Pixel 8a, Galaxy Tab S7+ and the TVs, in short sessions; the PC is an optional
  server. Design decisions favour his workflow.
- Japanese is the only language since v0.44.5 (2026-11-27): German, English and the family profiles were removed.
  The menus stay in English and Spanish.

## Product Purpose
Watch your own shows, anime, movies, YouTube and novels in Japanese, scene by scene, with every
word coloured by your cards (kumapie's own store); look words up, mine them and rate their cards without leaving the player.
Success on Home: getting back into what you were watching in one tap.

## Positioning
Lingopie-style immersion over your own library, tied to your own spaced-repetition collection: each show and
episode carries how easy it is for you right now (share of i+0 scenes, unknown words per scene).

## Operating Context
- Phone (portrait, bottom navigation) and tablet (landscape, side rail) in `:mobile`; Android TV in `:app`, driven by
  a remote or gamepad with D-pad focus.
- Offline first: the mobile app works from what's on the device; the PC server (dojo-feed) is optional, also on
  the TV (which needs an AniList login instead).

## Capabilities and Constraints
- Home today: Continue (episodes left partway), rows per media type (anime, shows, movies, YouTube,
  visual novels, other), easiest first; per show an easy line ("62% easy · 9 left" / "Finished"); add an episode;
  refresh; offline and error states; navigation to Feed, i+1 scenes, Downloads (with a job badge), Stats, Cards that
  don't stick, Japanese dictionary, Settings. The TV has no profile picker: it is Pedro's.
- Posters come from AniList/Jikan/Kitsu/TMDB/TVmaze/Wikidata; a neutral poster stands in when there is none. No
  spoilers: unseen episode pictures stay blurred.
- UI strings in English and Spanish (`tr()`); word colours white/green/blue/red have fixed meanings.
- Native Kotlin + Jetpack Compose (Material 3 on mobile, Compose for TV); shared colours in `core` (`Colors`).

## Evidence on Hand
Real posters and the user's own library through the server; no marketing claims or testimonials exist and none
should be made up.

## Product Principles
1. Resume first: the thing you were watching is one tap away.
2. Difficulty is information: the easy share helps pick what's next, it never nags.
3. Offline is normal, not an error.
4. One design for phone, tablet and TV, adapted to each input (touch vs. D-pad focus).
5. Plain: big targets, short labels, no jargon.

## Feature map (TV, no PC)
What the TV does for someone with no PC, in their words. Each line is proved on the TV emulator by
`C:\dojo\work\kumapie\proof.sh <feature>` (screenshot in `proof\<feature>.png`); `proof.sh all` runs them all.
1. `language`: choose a language with no PC and reach Home.
2. `back`: the remote's Back key goes one setup step back (Main server to Language, AniList to Main server, shown as done), and the Back button on Word colouring returns to Videos.
2b. `onboarding_pc`: Main server > Type the address > a kumapie PC (a stub on 127.0.0.1 answering GET /api/tv, reached as 10.0.2.2:<port>): the app probes it, asks who is watching, and lands on Home.
3. `settings`: Settings opens with a Torrentio URL that has options.
4. `show`: open a show and see the words coloured.
5. `word`: pause, open a word, rate it.
5b. `shift`: Options > Subtitle timing +0.5 s moves Jimaku's subtitles; the same file opened again keeps the move, another file of the episode starts at +0.0 s.
6. `undo`: undo a rating, and the word is back as it was.
7. `home`: Back from the player goes to Home.
8. `next`: the episode ends and the next one starts.
9. `export`: Settings > Export everything writes one file of words, cards, reviews and progress for another device to import.
10. `sync`: Settings > Sync with another device pairs the TV with the tablet (a code shown on the TV, allowed on the tablet) and syncs over the Wi-Fi: words known, marks, reviews, places, stats and the review history by word: a word with a card on the TV has its colour on the tablet and Mine there says "Already a card on <TV>" once (two emulators: `proof_mobile.sh sync`).
11. `home_rail`: on Home the side rail is entered only with ← from the first column of a row; → from the rail returns to the row card that had the focus (↑/→/← inside the row never enter it).
12. `title_page`: OK on a Home row's card opens its title page (Episodes); Back returns to Home with the same card focused.
13. `library_back`: rail > Library; Back = Home (not the app's exit, not Discover).
14. `discover_back`: rail > Discover; Back = Home. (`discover` is the other half: Back from a title opened in Discover returns to the same poster, and from a search to its results.)
