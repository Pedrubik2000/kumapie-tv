---
version: 1
slug: "rubik2000-kumapie-mobile-ui-homescreen-kt-6dedda2e"
primary_target: "mobile/src/main/java/io/github/pedrubik2000/kumapie/mobile/ui/HomeScreen.kt"
related_targets: ["app/src/main/java/io/github/pedrubik2000/kumapie/ui/HomeScreen.kt"]
---

## Scope
Home in both apps: `mobile` (phone portrait + tablet landscape) and `app` (TV, D-pad). Mode: Operate.

## Task
Pedro opens kumapie to get back into what he was watching in one tap; second, to pick the next easy show by type.
States: loading, offline (last list, downloaded only), can't reach the PC, no episodes, nothing in progress.

## Direction contract
THESIS: Home is a "now watching" screen, not a catalogue. It refuses the streaming default of a Continue shelf of
posters equal in weight to every other shelf.
OWN-WORLD: The existing kumapie world: violet-black ground #1D1B26, surface #2A2734, bear brown #C98A4B for play,
progress and the selected tab; word-level colours (green i+0 / yellow i+1) only where they report ease.
Material 3 on mobile, Compose for TV components on TV.
STORY: He sees the episode he left, how long is left and how easy what's ahead is, presses play and is back in the
scene. Other shows in progress are one row lower; the library sits behind type tabs, easiest first.
FIRST VIEWPORT: Language chips, then a full-width resume card (show poster cropped wide with a scrim, title +
episode in Title Large, "14 min left · 61% easy ahead", a progress bar, a round brown play button on the art's
bottom edge). Under it up to two compact in-progress tiles. Then tabs Anime / Shows / Movies / YouTube / Novels
over the poster grid. Tablet: resume card + tiles left column, library right. TV: resume card focused first.
FORM: Continue stack + tabbed library, candidate 5 of 7. seed: none (unavailable)
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Decisions after review (2026-10-15)
- Ease on the resume card is the whole episode share of i+0/i+1 scenes (Episode carries no per-scene levels on Home), so it reads "59% easy", not "ahead".
- TV keeps the other shows in progress in the same row as the focused resume card, smaller: one D-pad step right reaches them.
- The brown "+" on the tablet rail stays: the user asked for an orange "+" on the tablet (2026-10-14).
- Resume/library split only at >= 840 dp; tablet upright and a phone on its side are one column with the card capped at 600 dp.
- Every other show in progress is listed under the big card, not just two (user, 2026-10-15).
- The tablet rail "+" is surface-coloured, not brown, so play is the first brown (user, 2026-10-15).
