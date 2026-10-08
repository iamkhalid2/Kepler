# Shelf language: polish, theming, Compose recipes

Sources: `prototype/project/DirC-Home.dc.html` and `DirC-Reader.dc.html` (the bar), and `Shelf.dc.html` (the gap). 1 px = 1 dp.

## 1. What makes DirC polished

| # | Detail | DirC (copy these) | Shelf.dc.html |
|---|---|---|---|
| 1 | Lighting | One key light from the top-left. Front face: 135° gradient, white .18 → 0 at .42 → black .38. Edges are the cloth darkened by L×: head board .92, fore-edge board .77, hinge groove .63. Pages: lit head L .90, shaded fore-edge .57. | The gradient is baked in, so it turns with the book. Shading is a flat black overlay at `abs(ry)/150`, which greys the cloth. |
| 2 | Grain | Cloth: fractalNoise `1.5 0.45` (woven, 3.3:1), 2 octaves, α = 1.1n − .5 (peaks only), white, overlay at .5. Paper: 0.85, 3 octaves, warm; α .5 on slips, .06 on the page. | Cloth only, scaled 0.3–1.14×, so it shimmers. No paper grain. |
| 3 | Edges | Plank lip 0.9 px: white .22 → .12 → .02, fading away from the light. Hinge: 1.2 px groove at .95, then 0.8 px white at .14. Debossed medallion: groove stroke at .6, plus white at .16 offset 0.8 down. Keys: `inset 0 1 white .7, inset 0 -1 black .2, 0 2 black .9`. | Lip at .28 with no book touching it. Printed medallion. Flat cobalt buttons. |
| 4 | Contact | Ellipse rx = .64 × width, ry 3, black α 1, σ3, set on the plank line. Cloth bounce on the plank: α .32, σ5. Slip `0 12 24 -14`, page `0 10 22 -8`. | No contact shadows. `0 24 40 .6` makes things float. |
| 5 | Proportions | 2:3 cover 100×150 (26% of screen width). Depth 9 (9%, oblique dx 9 / dy −6). Squares 1.4, radius 1.5. Book left, text right. | Depth 24–39% of the width (bricks). Radius 2/5/5/2 (toy). Centred hero. |
| 6 | Restraint | The primary key is bone `#ECE6D9` with ink `#0E0E10`. The accent appears only on ribbons, a 0.9 px rule and the logo dot. Radii 3–5. The only motion is the ribbons, resting 34% of each cycle. | Cobalt r12 pills, white thumb and toast, radius morphs, ±9 px float loop. |
| 7 | Type | Fraunces `opsz` follows the size (12 at 10 px, 18 at 16, 48 at 29, 96 at 36). SOFT 100 display, 50 titles. Weight 360 / italic 340. Literata italic for authors. Caps 11 px, 600, .14–.16em. Reader: 19/30, small-caps opener, running head, folio, hung numeral. Body `#D9D9DE`, read `#ECE3D0`, spoken `#FFF`. | `opsz` 96 at 32 px. No folio or small caps. 700-weight buttons. |
| 8 | Ribbons | Bone for read, accent for listened. 3-stop silk with the sheen at .45 (`#2F63D8 #86B2FF #3A6FE4`). Notch .7 × width. Fold highlight 1.6 px white .55 on the plank lip. Head shade .45 → 0 over 20%. Swing 4°, −2.6, 1.4, −.6, .2 (ζ ≈ .2, T ≈ 1.9 s); the second ribbon starts +120 ms. | One plain ribbon, in the reader only. The library legend names ribbons that are never drawn. |

## 2. Theme-agnostic tokens

- **Neutrals** are fixed per mode and never tinted.
- **Accent** is the one user seed.
- **Materials** are derived from the accent.
- **Object lighting** is the same in light and dark. Only the environment (plank, shadow, bounce, page) changes with the mode.

All the maths runs in **OKLCH**: about 40 lines of pure Kotlin in `:core:design`, and hue doesn't drift when darkening. Every guard measures WCAG contrast.

```kotlin
@Immutable data class BbPalette(val isDark: Boolean, val bg: Color, val surface1: Color,
  val surface2: Color, val hairline: Color, val text: Color, val text2: Color, val body: Color,
  val page: Color, val key: Color, val onKey: Color, val accent: Color, val accentText: Color,
  val onAccent: Color, val secondary: Color, val positive: Color, val warning: Color, val negative: Color)
@Immutable data class Cloth(val face: Color, val head: Color, val board: Color, val groove: Color,
  val ink: Color, val inkMuted: Color, val bounce: Color)
@Immutable data class Silk(val edgeA: Color, val sheen: Color, val edgeB: Color)
@Immutable data class BbMaterials(val cloth: Cloth, val silk: Silk, val boneRibbon: Silk,
  val paper: Paper, val env: Env, val marks: Marks)
// Paper: bone stops + grain α · Env: plank, lip α, contact α, bounce α · Marks: read-along, selection, pencil
fun bbTheme(seed: Color, dark: Boolean): Pair<BbPalette, BbMaterials>
fun clothFor(base: Color, p: BbPalette): Cloth      // per-book covers and cover-art seeds (#42)
```

`LocalBb` provides the theme, and the same values feed the M3 `ColorScheme`. A **System** preset seeds from `dynamicDarkColorScheme(ctx).primary` on API 31+.

**Rules.** The seed is (L, C, H). `shade(c, k)` = (L·k, C·k, H), the one darkening operation.

| Token | Rule |
|---|---|
| accent | The seed, guarded to ≥ 3:1 against bg (lifted in dark, lowered in light). |
| accentText | If the seed is < 7:1: (L+.08, C×.74). Then guard to ≥ 7:1 dark / ≥ 4.5:1 light. Cobalt gives `#78A8FE` (DirC `#7AA8FF`). |
| onAccent | (.15, .04, H) if ≥ 4.5:1 against accent, otherwise near-white. |
| cloth.face | (clamp(L−.30, .26, .55), C×.71, H+3.5°). Cobalt gives `#19347F` (DirC `#19347E`). |
| head / board / groove | shade ×.924 / .77 / .627. Cobalt gives `#152E71 #0E2257 #081740`, matching DirC. |
| cover light | Alpha stops over any cloth: white .18 → 0 at .42 → black .38. If face L > .7, use black .24, because pale cloth greys quickly. |
| ink / inkMuted | (.973, .008, H) if ≥ 4.5:1 against the face, otherwise (.20, .01, H). inkMuted (.85, .054, H) is pushed toward ink until ≥ 4.5:1. |
| bounce | (face L+.106, C×1.24). α .32 dark / .16 light. |
| silk | r = clamp(L, .55, .80). edgeA (r−.12, C×1.02), sheen (r+.11, C×.66), edgeB (r−.083, C), all at H+2°. Shift all three until edgeB is ≥ 3:1 against bg. |
| bone | Paper, never tinted: H 86, C .02. L .951 / .926 / .902 / .76 / .70 / .568 / .466 gives `#F4EEE2 #ECE6D9 #E6DECD #B9B19F #A69E8C #7D7667 #5F594C`. The bone ribbon is `#B9B19F #F4EEE2 #C6BEAC` in dark and graphite `#5F594C #A69E8C #7D7667` in light. |
| plank | Dark: top `#000→#0C0C0E`, front `#070708`, lip .22/.12/.02. Light: top `#F4F2EE→#EAE8E2`, front `#E0DED8`, lip .9/.55/.15 over a 0.5 dp black .12 seam. Contact α 1.0 / .30. |
| read-along | Line: accent α .30 / .22. Sentence: α .12 / .09. Selection: α .35 / .25. Saved highlight is a pencil sideline (`#A39B8A` / `#787161`). Read text `#ECE3D0` / `#4C473C`. **Guard:** blend over the page; if body text falls below 4.5:1, step α down by .02 to .10, then fall back to an underline. |
| secondary | (.785, .133, H−80°). Cobalt gives exactly `#2DD4BF`. |
| clash | If the accent is within 20° of positive, warning or negative, rotate that colour 30° away. |

**Neutrals**

| Token | Dark (decision #97) | Light ("linen") |
|---|---|---|
| bg, surface1, surface2 | `#000000 #101012 #1A1A1E` | `#F4F2EE #FBF9F5 #FFFEFB` |
| hairline | `#2A2A30` | `#D6D4CE` |
| text | `#FFFFFF` | `#1A1814` |
| text2 | `#9B9BA3` | `#66635C` (5.4:1) |
| body | `#D9D9DE` | `#2B2924` |
| page | `#111114` | `#FEFCF7` |
| key / onKey | `#ECE6D9 / #0E0E10` | `#201E1A / #ECE6D9` |
| positive / warning / negative | (decision #97) | `#007D57 #8D6900 #C34146` |

**Presets** (computed with these rules):

| Preset | accent | accentText | onAccent | cloth | silk | secondary | light accent / text |
|---|---|---|---|---|---|---|---|
| **Cobalt** | `#4D8BFF` | `#78A8FE` | `#030A1C` | `#19347F` | `#3063D8 #8BB1FF #3C6FE2` | `#2DD4BF` | `#4A88FB / #2D68D9` |
| Vermilion | `#F76046` | `#FD8D77` | `#190502` | `#791C00` | `#CD350E #FF9D86 #D94523` | `#D59FF7` | `#F05940 / #CC361E` |
| Verdigris | `#3FCBA8` | `#3FCBA8` | `#000F0A` | `#006756` | `#00A387 #97E7D0 #00B092` | `#D7B647` | `#009E80 / #007E65` |
| Saffron | `#FEBF32` | `#FEBF32` | `#120A00` | `#8C6900` | `#C08F00 #FFDD98 #CE9A00` | `#FF93B2` | `#B48300 / #8F6700` |

The clash rule fires three times: Vermilion's negative becomes `#ED71AC`, Verdigris's positive `#8ECA5D`, and Saffron's warning `#FFB78B`. The light-mode silk guard darkens Verdigris and Saffron.

## 3. Compose recipes

**Book: one draw node rendering a projected box.** Per-face `graphicsLayer` composables are out: each layer has its own camera, so seams open past about 35° of yaw, and they need manual z-order and six RenderNodes per book.

```kotlin
@Immutable data class BookPose(val center: Offset, val heightPx: Float,
  val yaw: Float,   // 90 shelf spine, 24 hero, 0 player
  val pitch: Float, // -3..-6
  val lift: Float,  // 0 on the plank .. 1 held (shadow spread, bounce α)
  val open: Float)  // front board hinge, 0..-165°
@Composable fun BookObject(pose: () -> BookPose, look: BookLook, modifier: Modifier)
```

- **Faces.** In `drawWithCache`, `obtainGraphicsLayer()` records the front and spine faces once per size bucket: cloth, grain, type and medallion. `onDrawBehind` reads `pose()`, projects the 8 vertices (camera distance 8 × height) and culls back faces. The box is convex, so nothing needs sorting. Plain faces are `Path` quads. Textured faces use `Matrix.setPolyToPoly(src, dst, 4)`, then `nativeCanvas.concat`, then `drawLayer`.
- **Light.** l = norm(−.35, −.55, .76) and k = .63 + .49·max(0, n·l). That gives front 1.0, head .90, spine .80 and fore-edge .63, which is DirC's ratios. Face colour = `shade(base, k)`, read from a 32-step lookup table. The gradient's .42 midpoint moves to .42 + .35·sin(yaw), so the sheen slides as the book turns.
- **Page block.** Bone × k, three 0.5 dp hairlines, the read share drawn in `thumbed`, inset 1.4%.
- **Grain.** Ship a 256² noise tile in `drawable-nodpi`, generated by a JVM task in `build-logic` so screenshots stay deterministic. Draw it inside the face layer with `ImageShader(tile, Repeated, Repeated)`, α .5, `BlendMode.Overlay`. Overlay maps to `PorterDuff.OVERLAY` on API 28, so there is no fallback branch. The grain gets perspective for free. **AGSL is skipped in v1**: it would only add animated sheen.

**Morph: one `BookStage`** overlay sits above the nav host, so a single instance carries velocity through interruptions. Screens publish anchors (`Modifier.bookAnchor(id, role)`), and the target pose is a function of the route and its anchor. Each channel is its own `Animatable`:

| Channel | Spring ζ / stiffness |
|---|---|
| center, heightPx | .82 / 320 |
| yaw | .72 / 260 (≈3° overshoot, so the book "lands") |
| pitch | .90 / 400 |
| lift | 1.0 / 800 (shadows never bounce) |
| open | .85 / 220 |

Shelf to hero: lift, then yaw at +60 ms, then position at +90 ms. Hero to player: yaw 0, pitch −6, height ×1.14. Player chrome fades in (1.0/1600) once yaw is below 8°. Predictive back is on by default at targetSdk 37; `PredictiveBackHandler` drives `snapTo(lerp(from, to, progress))`. **Shared elements** interpolate bounds only, and they pop when the source has scrolled out of a `LazyRow`. Keep them for flat parts: the title, and the now-bar → transport morph.

**Page open.** `open` rotates the front board about its hinge, showing a paper liner inside. Once open > .55, an animated clip reveals the Readium page that is already laid out underneath, from the projected page quad to full screen (.9/380). Never 3D-transform the WebView: it re-rasterises every frame.

**Card flip.** A single plane is where `graphicsLayer` is right: `rotationY = a; cameraDistance = 12f * density; scale = 1 + .04·sin a`. Swap to the back (pre-rotated 180°) at 90°. Use spring .86/380; Shelf's 1.25 overshoot reads as rubber. Shade α = .35·(1 − cos a), the contact shadow's width scales by |cos a|, and a haptic tick fires at 90°.

**Ribbon.** Built in `drawWithCache`: notched path, 3-stop brush, head shade, fold highlight, and `rotate(θ, topCenter)`. θ comes from `animateTo(0f, spring(.2f, 12f), initialVelocity = v)`:
- Home: v ≈ 17°/s gives a ≈4° peak with T ≈ 1.9 s.
- Reader: stiffness 40 and v ≈ 7°/s on each sentence change.
- Book acceleration also feeds v.
- The second ribbon gets .85·v, 120 ms later.
- Swings are event-driven only.

**Performance (Find X9, 120 Hz = 8.3 ms):**
- Read the pose and θ only in draw or layer lambdas, so there is no recomposition during motion.
- Hero face layers: 720×1080 px (≈3 MB), LRU of 3.
- Shelf spines at rest are drawn flat; only the active book is 3D.
- Shadows are radial-gradient ellipses, never blur. RenderEffect blur re-runs every frame and doesn't exist before API 31.
- No infinite animations: idle screens must let the panel drop its refresh rate.
- Baseline Profile for Library → Hub → Player. Release gate: Macrobenchmark `FrameTimingMetric`, P95 < 8.3 ms.
- At animator scale 0: snap poses, no swings, and the flip becomes a crossfade (#64).

## 4. Risks and v1 cuts

**Risks**
- Projection: use `setPolyToPoly` + `nativeCanvas.concat` (safe on API 28). Don't rely on Compose's 4×4 → 3×3 matrix conversion.
- Scaled-up layers blur type. Record at the largest pose size, with a separate shelf layer.
- Overlay grain drawn outside the face layer blends with the background.
- The stage is `clearAndSetSemantics {}`. Anchors own labels and 48 dp targets.
- Light mode is unproven: contact α 1 on linen reads as a hole. Roborazzi: every screen × 2 modes × 4 presets.
- Grey cover-art seeds: `clothFor` floors C at .03 and L at .26.

**Cut for v1:**
- AGSL, including page curl (#54).
- Per-face composables.
- Scrub-turns-the-book and the float loop.
- Looping ribbons.
- 3D for non-active spines.
- Thickness from page count (use 3 buckets at 6/9/12% of width).
- Tilt lighting.
- Colour bounce in light mode.
