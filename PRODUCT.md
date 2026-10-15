# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
- **Pedro (primary).** Learns German (beginner), also English and Japanese. Studies only on his Pixel 8a and Galaxy
  Tab S7+, in short sessions; the PC is a server. Design decisions favour his workflow first.
- **His parents, Giovanna and Jackson.** Native Spanish speakers learning English from English-language shows and
  English-dubbed K-dramas, on phones and the TV app, with a Spanish UI. Everything must still work plainly for them.

## Product Purpose
Watch your own shows, anime, movies, YouTube and novels in the language you're learning, scene by scene, with every
word coloured by your Anki (kuma3) cards; look words up, mine them and rate their cards without leaving the player.
Success on Home: getting back into what you were watching in one tap.

## Positioning
Lingopie-style immersion over your own library, tied to your own spaced-repetition collection: each show and
episode carries how easy it is for you right now (share of i+0 scenes, unknown words per scene).

## Operating Context
- Phone (portrait, bottom navigation) and tablet (landscape, side rail) in `:mobile`; Android TV in `:app`, driven by
  a remote or gamepad with D-pad focus.
- Offline first: the mobile app works from what's on the device; the PC server (dojo-feed) syncs episodes and
  progress and is required for the TV.
- One language shown at a time on Home, switched when a person learns several.

## Capabilities and Constraints
- Home today: language switch, Continue (episodes left partway), rows per media type (anime, shows, movies, YouTube,
  visual novels, other), easiest first; per show an easy line ("62% easy · 9 left" / "Finished"); add an episode;
  refresh; offline and error states; navigation to Feed, i+1 scenes, Downloads (with a job badge), Stats, Cards that
  don't stick (German), Japanese dictionary (Japanese), Settings. TV adds profiles ("Who's watching?").
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
5. Plain for the parents: big targets, short Spanish labels, no jargon.
