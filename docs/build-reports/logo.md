# Kepler brand mark and app icon

The mark is Kepler's second law: a bone elliptical orbit, a solid sector swept from its focus (equal areas in equal times, the steady rhythm of reading and listening), a small disc at the focus, and one Cobalt dot at the end of the sweep (the frontier; the brief reserves accent for the logo dot). The long, narrow sweep doubles as a ribbon laid across the orbit and, pointing right, a quiet play glyph. Flat bone and ink, tilted 18 degrees, no book, telescope, gradient or glow. Legible at 48 px and in one colour (halo cut via clip, not a background fill).

Files: docs/design/brand/{kepler-mark,kepler-mark-mono,kepler-lockup}.svg, README.md (old logo-*.svg deleted); app/src/main/res/drawable/ic_launcher_{foreground,monochrome}.xml; mipmap-anydpi-v26/ic_launcher.xml (background kepler_night #0F1E40); values/colors.xml (bb_ink kept for themes.xml); core/designsystem/.../KpMark.kt (`KpMark(modifier, size, ink, accent, contentDescription)`); README.md header image.

Verified: :core:designsystem:compileDebugKotlin and :app:processDebugResources pass.

DESIGN ASSUMPTIONS: wordmark on linen uses #2F63D8 dot for contrast; KpMark ink defaults to palette.text.
