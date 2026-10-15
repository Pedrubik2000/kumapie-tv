---
name: kumapie
description: Immersion player over your own library; every word coloured by your own Anki cards.
colors:
  violet-night: "#1D1B26"
  dusk-surface: "#2A2734"
  bear-brown: "#C98A4B"
  pale-lilac-text: "#F2F0F7"
  muted-lilac: "#A9A4B8"
  word-learning-orange: "#FFAA4D"
  word-unknown-red: "#FF6B6B"
  word-due-green: "#7CC47F"
  word-mined-blue: "#6FA8FF"
  level-zero-green: "#7CC47F"
  level-one-yellow: "#E6C463"
  on-accent-black: "#000000"
typography:
  headline-tv:
    fontFamily: "Platform default (Roboto on TV)"
    fontSize: "32sp"
    fontWeight: 400
    lineHeight: "40sp"
  title-large:
    fontFamily: "Platform default"
    fontSize: "22sp"
    fontWeight: 400
    lineHeight: "28sp"
  title-medium:
    fontFamily: "Platform default"
    fontSize: "16sp"
    fontWeight: 500
    lineHeight: "24sp"
  title-small:
    fontFamily: "Platform default"
    fontSize: "14sp"
    fontWeight: 500
    lineHeight: "20sp"
  body-large:
    fontFamily: "Platform default"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "24sp"
  body-medium:
    fontFamily: "Platform default"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "20sp"
  body-small:
    fontFamily: "Platform default"
    fontSize: "12sp"
    fontWeight: 400
    lineHeight: "16sp"
rounded:
  bar: "2dp"
  thumb: "8dp"
  poster: "10dp"
  tile: "12dp"
  card: "16dp"
  full: "9999dp"
spacing:
  xs: "8dp"
  sm: "12dp"
  md: "16dp"
  lg: "24dp"
  tv-edge: "32dp"
components:
  resume-card:
    backgroundColor: "{colors.dusk-surface}"
    textColor: "{colors.pale-lilac-text}"
    typography: "{typography.title-large}"
    rounded: "{rounded.card}"
    padding: "0 16dp 16dp"
  play-button:
    backgroundColor: "{colors.bear-brown}"
    textColor: "{colors.on-accent-black}"
    rounded: "{rounded.full}"
    size: "64dp"
  progress-tile:
    backgroundColor: "{colors.dusk-surface}"
    textColor: "{colors.pale-lilac-text}"
    typography: "{typography.title-small}"
    rounded: "{rounded.tile}"
    padding: "8dp"
  poster:
    textColor: "{colors.pale-lilac-text}"
    typography: "{typography.title-small}"
    rounded: "{rounded.poster}"
    width: "118dp"
  progress-bar:
    backgroundColor: "{colors.dusk-surface}"
    rounded: "{rounded.bar}"
    height: "4dp"
  chip-language-selected:
    backgroundColor: "{colors.bear-brown}"
    textColor: "{colors.on-accent-black}"
  chip-language:
    textColor: "{colors.pale-lilac-text}"
  tab-selected:
    textColor: "{colors.bear-brown}"
    typography: "{typography.title-small}"
  tab:
    textColor: "{colors.muted-lilac}"
  nav-rail-add:
    backgroundColor: "{colors.dusk-surface}"
    textColor: "{colors.pale-lilac-text}"
  tv-text-field:
    backgroundColor: "{colors.dusk-surface}"
    textColor: "{colors.pale-lilac-text}"
    rounded: "{rounded.thumb}"
    padding: "12dp 16dp"
---

# Design System: kumapie

## Overview

**Creative North Star: "The Bear's Den at Night"**

A dark, quiet room where the show's own art is the only bright thing, and the bear's brown marks the one thing to do next. The ground is a violet-tinted near-black, cards sit one tonal step above it, and every bit of colour beyond the brown carries a fixed meaning about words and ease. The interface steps back so posters and scenes do the talking.

Density is calm on phone and tablet (Material 3 defaults, generous 16dp gutters) and lean-back on TV (32dp edges, a focused card that grows). One design spans three inputs: touch on phone and tablet, D-pad focus on TV, with the same colours, the same resume-first order and the same poster/neutral-poster pairing everywhere.

The system is plain on purpose: no custom typeface, no decorative ornament, no shadows. Material 3 on mobile and Compose for TV supply the components; kumapie supplies the palette, the radii and the rules about what may be brown or coloured.

**Key Characteristics:**
- Violet-black ground, one-step-lighter surfaces, depth by tone only.
- Bear brown is the single action/progress/selection accent.
- Word and level colours are information, never decoration.
- Show art carries the colour; missing art gets a muted name-hued neutral poster, never an episode frame.
- Resume first: the last episode is the largest thing on Home on every device.

## Colors

A dark, desaturated violet field with one warm brown accent and a small, strictly semantic set of word colours.

### Primary
- **Bear Brown** (bear-brown): play buttons, progress fills, the selected language chip, the selected tab indicator and label, the selected nav item, text cursors and TV focus borders. Content on it is black.

### Neutral
- **Violet Night** (violet-night): the screen ground on every device; also the TV resume card's scrim colour (at 85%).
- **Dusk Surface** (dusk-surface): every container one step up: resume card, in-progress tiles, nav bar/rail containers, text fields, progress-bar tracks, tab dividers, the tablet rail's add button. All Material surfaceContainer roles map to it.
- **Pale Lilac** (pale-lilac-text): primary text and icons.
- **Muted Lilac** (muted-lilac): secondary lines (episode, time left, easy share), unselected tabs, Material onSurfaceVariant.

### Tertiary (semantic only)
- **Highlight Orange** (word-learning-orange): never a word colour (subtitle words are only white, red, green, blue; a learning word is red). Marks counts and "today" in stats and help.
- **Unknown Red** (word-unknown-red): a never-studied word; also the i+2-and-up level badge.
- **Due Green** (word-due-green): a word whose own card is due today.
- **Mined Blue** (word-mined-blue): mined, never reviewed.
- **Level Green / Level Yellow** (level-zero-green, level-one-yellow): i+0 and i+1 level badges.

### Named Rules
**The One Brown Rule.** Bear brown means "act here / you are here / how far along": play, progress, the current selection, focus. Nothing decorative is brown, and secondary actions (the tablet rail's "+") stay surface-coloured so play is the first brown on screen.

**The Colour Means Something Rule.** Green, yellow, red and blue are reserved for word state and level/ease; orange only marks counts and "today" in stats and help. They never appear as decoration, category colour or chrome.

## Typography

**Display Font:** none; platform default (Roboto on TV; the device's system font on phone and tablet)
**Body Font:** platform default via Material 3 / TV Material type roles

**Character:** Unbranded and legible; the posters carry the personality, type just reports.

### Hierarchy
- **Headline** (headline-tv): the "kumapie" wordmark on TV only.
- **Title Large** (title-large): the resume card's show title (up to two lines), TV shelf headings, empty/error state titles.
- **Title Medium** (title-medium): small TV resume cards' titles; the single-type heading when there is only one tab.
- **Title Small** (title-small): poster titles (two lines), in-progress tile titles, tabs.
- **Body Large** (body-large): TV poster titles and the big TV card's episode line.
- **Body Medium** (body-medium): the resume card's episode and "N min left · NN% easy" lines; offline notice.
- **Body Small** (body-small): poster sub-lines (easy share · left), tile episode and time lines.

### Named Rules
**The Material Roles Rule.** Use the Material type roles by name; never set raw sizes on Home text. The one raw size is the TV text field (20sp).

## Layout

Single scroll column on phone; a resume column beside the library on wide screens; horizontal shelves on TV.

- **Navigation switch:** a bottom navigation bar under 600dp wide or under 560dp tall (a phone on its side keeps the bar); a navigation rail otherwise. The bar shows Home, Feed, i+1, Downloads and More (a bottom sheet with the rest); the rail lists everything, with short labels.
- **Resume/library split:** only from 840dp wide. The resume column takes 42%, the library 58%, each scrolling on its own. On a short landscape screen the resume card narrows so its 16:9 art plus text fit the height.
- **One column (under 840dp):** language chips, the resume card capped at 600dp wide, every other show in progress as tiles (also capped at 600dp), then tabs and the poster grid.
- **Poster grid:** a flow grid with 16dp padding, 12dp horizontal and 16dp vertical gaps. Phone: at least three across (one more per 130dp), widths divided to fill. Rail layouts: fixed 112dp posters.
- **Rhythm:** 16dp page gutters, 12dp between stacked cards, 8dp between tiles and between chips, 20dp before the tabs.
- **TV:** 32dp outer padding; the resume row is a horizontal row of 16:9 cards (360dp for the last episode, 220dp for the others, bottom-aligned, 24dp gaps); then one shelf per media type of 110dp posters, 24dp apart. Rows carry padding so a focused card can grow 10% without clipping.

## Elevation & Depth

Flat. Depth comes from tone alone: Violet Night ground, Dusk Surface containers, and show art as the brightest layer. Art fades into its card through a vertical scrim (transparent at 45-50% to the card or ground colour at the bottom), so titles read as part of the card. TV focus adds scale (1.1) and a brown border, not a shadow.

### Named Rules
**The Tone-Not-Shadow Rule.** No drop shadows on Home surfaces; a container is one tonal step up from its ground, and focus is shown by scale and border.

## Shapes

Soft, rounded rectangles scaled to the object: 2dp on progress bars, 8dp on tile thumbnails and text fields, 10dp on posters, 12dp on in-progress tiles, 16dp on the resume card. Play buttons and chips are pill/circle shapes from Material. Art is always clipped to its container's radius; posters are 2:3, resume art is 16:9 cropped from the top.

## Components

### Resume Card (signature)
The last episode, the largest thing on Home.
- **Shape:** card radius (16dp), Dusk Surface, full width with 16dp side margins, max 600dp in one column.
- **Art:** the show poster cropped 16:9 from the top over a neutral poster, scrimmed into the card.
- **Play:** a 64dp brown circle (36dp black icon) straddling the art's bottom-right edge, 32dp below it; the whole card is also tappable.
- **Text:** title in Title Large (two lines, clear of the button), then episode and "N min left · NN% easy" in Body Medium muted lilac, then a full-width progress bar.
- **TV:** a focusable Card with the bar inside the art's bottom and the text under it; focused first.

### In-Progress Tile
Every other show in progress, one row each.
- 12dp radius Dusk Surface, 8dp padding, a 48dp-wide 2:3 thumbnail (8dp radius), Title Small title, two Body Small muted lines, a progress bar. One tap plays.
- **TV:** a 220dp 16:9 card in the same row as the big one, one D-pad step right.

### Poster
- 2:3 art, 10dp radius, title (Title Small, two lines) and an easy line (Body Small, muted) beneath. A neutral poster (the title on a name-derived hue at 35% saturation, 32% value) sits under the image and shows when art is missing. Never an episode frame.
- **TV:** 110dp Card, Body Large title on one line; focus grows it 10% with a brown border.

### Progress Bar
- 4dp tall, 2dp radius, Dusk Surface track, brown fill. Used for episode progress everywhere.

### Chips
- **Language:** Material filter chips, outlined when unselected; selected fills brown with black label. Shown only when the library has two or more languages.
- **TV:** language switches are TV buttons; the selected one fills brown with Violet Night text.

### Tabs
- Material primary scrollable tab row on a transparent container: brown indicator and label when selected, muted lilac otherwise, a Dusk Surface divider. Media types in a fixed order (Anime, Shows, Movies, YouTube, Visual novels, Other), only those with shows.

### Navigation
- **Bottom bar:** Material navigation bar on Dusk Surface; the selected item gets a brown label and indicator pill. Download jobs show as a Badge.
- **Rail:** Material navigation rail with an add FAB in its header, Dusk Surface container and Pale Lilac icon.
- **Top bar:** "kumapie" title, add (phone only) and refresh actions.
- **TV:** a header row of buttons (profile, Stats, Settings) right of the wordmark.

### Inputs / Fields
- **TV text field:** Dusk Surface, 8dp radius, 12/16dp padding; a 2dp brown border and brown cursor when focused.

## Do's and Don'ts

### Do:
- **Do** put the last episode first and largest on every device, with its play control in bear brown.
- **Do** use Dusk Surface for every container and keep secondary actions on it, so brown stays for play, progress and selection.
- **Do** draw a neutral poster under every poster image.
- **Do** switch to the rail only at 600dp wide and 560dp tall, and split resume/library only from 840dp.
- **Do** leave room for TV focus growth (10%) in row padding.

### Don't:
- **Don't** use green, yellow, red or blue for anything but word state and level/ease, or orange as a word colour.
- **Don't** show an episode frame where a poster is missing; unseen episode pictures stay blurred.
- **Don't** add drop shadows; depth is tonal.
- **Don't** let the resume card grow past 600dp wide in one column.
