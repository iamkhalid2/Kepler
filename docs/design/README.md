# BetterBooks design

**Live prototype:** https://claude.ai/artifact/MwAQh5KMfsi1W8DkPURbnK (a private Artifact; share it from its Share menu)

## How to use the prototype
- Open the link. The canvas shows every screen side by side.
- **Main** (top-left, "Start here") is the full prototype, one continuous app. Press **Play** on it and tap through:
  onboarding → Home → Library → book hub → narration setup → reader → player → Learn → Review.
- The other phone boards each start the same prototype on one screen, for quick review: Home, Library, Hub, Setup, Read + Listen, Listen, Player, Ask, Map and Review. They are interactive too.
- The bottom row has the **Brand** sheet (logo concepts A/B/C, icon variants, color, type, motion and haptic tokens) and **Widgets & system surfaces** (Glance widgets, Android Auto, media notification).
- On an Android phone, the prototype vibrates on key actions, standing in for the haptic tokens.

## Things to try
1. **Morphs:** tap the mini player above the tab bar and it grows into the full player. Tap play to see the shape morph between square and circle.
2. **Read + Listen:** in the reader, switch the segmented control at the bottom. Sentences highlight as they are "spoken", and tapping a sentence plays from there. At the end of §1 a **check-in** appears (the learning loop).
3. **Selection menu:** in Read mode, tap a sentence and choose Explain. The app asks for your guess *before* it explains (Study mode).
4. **Aa:** themes (Paper, Sepia, Night, Black, Day), text size and fonts.
5. **Learn:** Ask (answers come with tappable citations), Recap, Map (tap the nodes), Concepts.
6. **Review:** answer in your own words, then Show answer, then grade with FSRS intervals and watch the completion moment.
7. **Setup:** voice previews, expression slider, quote voice or full cast, and the cost and time estimate. Generate morphs into a progress ring.

## Files
- `prototype/project/*.dc.html`: artboard sources. `Main.dc.html` holds the whole app; the other phone boards import it with a `start` screen.
- `brand/`: logo SVGs. Concept A is recommended:
  - `logo-a-seed-b.svg`: full icon.
  - `logo-a-foreground.svg`: adaptive-icon foreground.
  - `logo-a-monochrome.svg`: themed icon.
  - `logo-b-folio-wave.svg` and `logo-c-ribbon-play.svg`: the alternatives.

The spec behind all of this is [`../plan/experience.md`](../plan/experience.md).
