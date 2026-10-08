# Kepler brand mark

## The mark

An elliptical orbit drawn as a bone hairline-weight ring, a solid sector swept from its focus, a small
body at that focus, and one accent dot at the end of the sweep.

- **Why Kepler.** His second law: a line from the sun sweeps equal areas in equal times. The sector
  is that area. Reading and listening are the same rhythm: steady sessions that add up, slow and
  long at the far end of a book, quick near the close.
- **Why this sector.** It is the long, narrow sweep from the far side of the orbit, so it also reads
  as a ribbon laid across the page and, pointing right, as a quiet play glyph (Listen) without
  drawing one.
- **Why the dot.** The accent is reserved for the listened ribbon, the frontier rule and the logo
  dot (DESIGN-BRIEF §3). The body sits where the sweep ends: your position, the frontier.
- **What it avoids.** No open book, telescope, planet with rings, gradient, glow or sparkle. Bone and
  ink, one accent, flat shapes, tilted 18 degrees so it reads as an object at rest, not a diagram.

## Files

| File | Use |
|---|---|
| `kepler-mark.svg` | Full-colour mark on the launcher ground (README, store, social). |
| `kepler-mark-mono.svg` | One colour, transparent. Recolour freely; the dot keeps its halo cut. |
| `kepler-lockup.svg` | Mark + wordmark on linen. Wordmark is Fraunces 360, opsz 72, tracking -0.02em (`KpFonts.display`). Outline the text before print or if Fraunces is not installed. |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Adaptive icon foreground, inside the 66 dp safe zone. |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | Themed-icon layer (Android 13+). |
| `core/designsystem/.../KpMark.kt` | `KpMark(size, ink, accent)` for onboarding and about. |

All four drawings share one geometry (108 unit icon, centre 55,54, rotation -18 degrees): ellipse
a 27, b 18.5, stroke 3.5; focus at +19.67; sector from 145 to 215 degrees (parametric); focus disc
r 3.2; body r 4.5 with a 6.5 halo cut through ring and sector. Change it in one place, change it
everywhere.

## Colour

| Token | Value | Note |
|---|---|---|
| `kepler_night` | `#0F1E40` | Launcher ground. Cobalt (`#4D8BFF`, hue 262) taken down to ink, chroma kept low so it reads as dyed cloth, not a screen. |
| `kepler_bone` | `#ECE6D9` | Shelf-language bone L .926. Never tinted. |
| `kepler_cobalt` | `#4D8BFF` | The seed. On linen use `#2F63D8` (silk edge) for contrast. |

In the app the mark follows the palette: `ink = Kp.palette.text`, `accent = Kp.palette.accent`, so
it re-derives for every accent preset and for light mode (ink mark, accent dot).

## Clear space and minimum size

- **Clear space:** the height of the ring's minor axis divided by two (one "b", about 28% of the mark
  width) on every side. Nothing enters it, including the wordmark: in the lockup the wordmark starts
  one b after the ring's rightmost point.
- **Minimum size:** 24 dp / 24 px for the mark alone (the halo cut closes below that; drop the halo
  and use the mono mark at 16 px only for favicons). Lockup minimum 96 px wide.
- **Do not** stretch, re-tilt, outline, add shadows or gradients, put the dot in any colour other than
  the accent or the ink, or place the full-colour mark on a busy cover image.
