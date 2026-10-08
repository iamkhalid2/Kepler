export const meta = {
  name: 'betterbooks-art-directions',
  description: 'Four distinct, non-generic art directions for BetterBooks on the Cobalt palette: design screens, then critique and elevate each',
  phases: [
    { title: 'Design', detail: '4 art directors, each builds Home, Reader, Player and a System board' },
    { title: 'Critique & elevate', detail: 'an independent critic per direction scores, fixes and pushes each further' },
  ],
}

const ROOT = '/Users/khalid/Documents/Codes/OpenAudiobook/docs/design/prototype/project'

const BRIEF = `
PRODUCT: BetterBooks, an open-source Android app that helps people INTERNALIZE books. Three pillars: Read (a best-in-class ebook reader), Listen (EPUBs turned into AI-narrated audiobooks with sentence-synced read-along), Remember (recall prompts, spaced-repetition review, ask-the-book, concept maps). Non-fiction matters as much as fiction. Tagline: "Read. Listen. Remember."

THE PROBLEM YOU ARE SOLVING: the user rejected the current prototype's design language as "too basic, like every other AI-generated app". It was: dark background, rounded 20-28px cards everywhere, pill buttons and pill chips, blurred glowing color blobs, a uniform grid of tiles, Geist everywhere, generic icon tiles. The user wants something SPECIAL and UNIQUE, ARTISTICALLY BEAUTIFUL, yet PRACTICAL for daily use. The user loves Revolut's craft (haptics, morphs, attention to detail) and chose the mature "Cobalt" palette.

COBALT PALETTE (the color foundation; you may tune neutrals and add a paper-white variant, but the identity is black/white + one electric cobalt blue, used sparingly):
bg #000000 · surface-1 #101012 · surface-2 #1A1A1E · hairline #2A2A30 · text #FFFFFF · text-2 #9B9BA3 · accent cobalt #4D8BFF (as text on black use #7AA8FF) · secondary aqua #2DD4BF (rare) · on-accent #020A1C · reading page #111114 / ink #D9D9DE · read-along highlight rgba(77,139,255,0.30) · positive #34D399 · warning #FBBF24 · negative #F87171.

LOGO: concept "Seed b": a lowercase b made of a rounded stem and a ring-shaped bowl with a solid seed dot in the bowl. On a 108-unit grid: stem rect x=31 y=25 w=11 h=59 rx=5.5; bowl ring centered (55,62) r=16.5 stroke-width 11; seed dot r=4.6 at (55,62). Recolor for Cobalt (e.g. white mark, cobalt seed). You may reinterpret it within your direction's style, keeping the b + seed idea.

SHARED CONTENT (use exactly this, it's public domain): the book is "Meditations" by Marcus Aurelius (George Long translation), currently in Book II, §1-2, listening at 1.4x, narrated by voice "Ava", 18 min left in Book II, read 34%, listened 41%, 38 cards. Book II sentences:
1 "Begin the morning by saying to thyself, I shall meet with the busy-body, the ungrateful, arrogant, deceitful, envious, unsocial."
2 "All these things happen to them by reason of their ignorance of what is good and evil."
3 "But I who have seen the nature of the good that it is beautiful, and of the bad that it is ugly, and the nature of him who does wrong, that it is akin to me, not only of the same blood or seed, but that it participates in the same intelligence and the same portion of the divinity, I can neither be injured by any of them, for no one can fix on me what is ugly, nor can I be angry with my kinsman, nor hate him."
4 "For we are made for co-operation, like feet, like hands, like eyelids, like the rows of the upper and lower teeth."
5 "To act against one another then is contrary to nature; and it is acting against one another to be vexed and to turn away."
6 "Whatever this is that I am, it is a little flesh and breath, and the ruling part."
7 "Throw away thy books; no longer distract thyself: it is not allowed; but as if thou wast now dying, despise the flesh; it is blood and bones and a network, a contexture of nerves, veins, and arteries."
Other library books (public domain): On the Origin of Species (Darwin, narration generating 62%), Walden (Thoreau), Pride and Prejudice (Austen, finished, full-cast audio), The Art of War (Sun Tzu), Frankenstein (Shelley), The Wealth of Nations (Smith), Moby-Dick (Melville). Home also shows: "Previously on…" recap after 4 days away ("Marcus opened Book II by bracing for difficult people, then argued we are built for co-operation, so anger works against our own nature."), Daily review "12 due · about 6 min" with a week of review days (M T W F done), a recent highlight ("For we are made for co-operation, like feet, like hands, like eyelids…" Meditations II.1). Tabs: Home · Library · Notebook. A persistent mini player ("now-bar") exists on Home.

SCREENS TO BUILD (each phone screen 390x844 px, fixed root):
1. HOME: greeting/date, resume (book in progress, mode-aware: listening, with resume action), Previously on…, daily review, generating status for Origin of Species, recent highlight, bottom tab bar, and the now-bar mini player.
2. READER in "Read + Listen" mode: the Book II text (sentences 1-7) beautifully typeset for long reading, the sentence currently being spoken (sentence 4) highlighted in your direction's way, a saved highlight on sentence 3 or 7, reading chrome (back, title, text settings, search, a "learn" entry point), progress through the book with chapter ticks and time left, transport controls (play/pause, prev/next sentence, speed 1.4x), and a mode switch Read / Read + Listen / Listen. Include ONE signature motion moment as CSS @keyframes (e.g. the read-along highlight moving across sentences in a loop, or progress breathing), honoring prefers-reduced-motion.
3. PLAYER (full screen): artwork/identity for the book, title, Book II · §1, scrubber with chapter marks and times, play/pause, -15/+30, speed, sleep timer, bookmark, narrator A/B, a "Read along" entry, and "Generated 6:20 ahead". Make the scrubber/progress a signature element of your direction.
4. SYSTEM board (840x844): a visual specification of your design language: type specimens (named fonts and sizes), color usage on Cobalt, shape and line vocabulary, the signature motif(s), iconography sample, the logo in your style, and one diagram of a signature interaction/motion (drawn as static frames). Labels short; no paragraphs of rationale.

QUALITY BAR: This must look like the work of a top independent design studio, not a template. A distinctive, ownable visual idea that someone would recognise in a screenshot with the logo hidden. Typography carries the design. Every element earns its place. Yet it must stay practical: reading text 18-20px in a proper reading serif with ~1.55-1.65 line height and 60-70 characters per line where possible, clear hierarchy, touch targets >= 44px, text contrast >= 4.5:1 (>= 3:1 at 24px+), honest affordances (it must be obvious what is tappable). Avoid AI-app tropes: gradient washes, glassmorphism, blurred glow blobs, left-border accent cards, emoji, uniform rounded cards everywhere, rows of pill chips, Inter/Roboto/Arial, generic icon-in-a-rounded-square tiles, lorem ipsum, fake status bars.

FORMAT (each artboard is one self-contained Design Component file; rules fail silently if broken):
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Short screen name</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link href="https://fonts.googleapis.com/css2?family=Font+One:wght@400;600&amp;family=Font+Two&amp;display=swap" rel="stylesheet">
<style>
body{margin:0}
/* only: page basics, @keyframes, animation/transition classes, ::placeholder, @media (prefers-reduced-motion) */
</style>
</helmet>
<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background-color: #000000; color: #FFFFFF; font-family: ...;">
  ... all markup, styles inline ...
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":390,"height":844}}'>
class Component extends DCLogic {
  renderVals() { return { /* values for {{holes}} */ }; }
}
</script>
</body>
</html>
Rules: keep the support.js line EXACTLY as shown; close every non-void element; quote every attribute; write & as &amp; inside attribute URLs; root is fixed at the board size; static visual styles go inline in style="…"; {{hole}} is a dotted lookup into renderVals() only, never an expression; repeats use <sc-for list="{{items}}" as="item" hint-placeholder-count="3">…{{item.x}}…</sc-for> and branches <sc-if value="{{flag}}" hint-placeholder-val="{{ true }}">…</sc-if> (it is fine and often simpler to write markup out literally with no holes at all); never put <sc-for> or <sc-if> inside an <svg>; no network except ONE Google Fonts css2 <link> in <helmet>; no <img> and no image files: draw all art (covers, patterns, generative visuals) with inline SVG and CSS; icons are inline stroke SVG with fill/stroke/stroke-width set in the <svg> element's style so children inherit (on child SVG elements use only geometry attributes like d, cx, cy, r, x, y, width, height, x1..y2, points, rx, transform, and put stroke-dasharray etc. in style="…"); never emoji; no <iframe>/<object>/<embed>; no fake status bar; all UI is markup (never script-built); use real <button> and <a href> elements for things that look tappable, with aria-label on icon-only buttons; data-props is single-quoted JSON; the script block must exist and define class Component extends DCLogic with renderVals().
`

const DIRECTIONS = [
  { key: 'A', name: 'Folio', idea: `EDITORIAL. The app as a beautifully typeset literary magazine / fine book. Typography is the interface: a high-contrast display serif (suggested: Instrument Serif, or Fraunces at high optical size) with italics used expressively, a refined reading serif (suggested: Newsreader or Literata), a small precise sans for labels (suggested: Geist or Hanken Grotesk) and a mono for numerals. Layout borrows book design: running heads, folios (page numbers), small caps, drop caps, oversized numerals, captions, asymmetric columns, generous margins, hairline rules (1px) instead of boxes. Almost no cards: content sits on the "page", separated by rules and whitespace. Buttons are typographic (underlined words, outlined rectangles with 2px radius, or text with arrows). Cobalt behaves like fountain-pen ink: annotations, links, the read-along mark. Mostly black with white type (Cobalt), with a paper-white variant shown on the System board.` },
  { key: 'B', name: 'Grid', idea: `SWISS / INTERNATIONAL TYPOGRAPHIC STYLE, made contemporary. A strict, visible modular grid; flush-left ragged-right grotesk (suggested: Archivo with its width axis, Schibsted Grotesk, or Instrument Sans) plus a mono (suggested: IBM Plex Mono); extreme scale contrast (giant 96-140px numerals next to 11px labels); solid full-bleed cobalt color fields used structurally (whole blocks of blue, not accents); square corners (0-2px radius); no shadows; rotated vertical labels; numbered sections; data shown as precise typeset tables and bars. The reader is set like a Swiss-designed book with sentence numbers in the margin. Bold, confident, mature, practical.` },
  { key: 'C', name: 'Shelf', idea: `TACTILE BOOK-OBJECT. Books treated as crafted physical objects, rendered with restraint and light, not cheesy skeuomorphism. The library is a shelf of SPINES with generated spine typography; covers have real thickness, with the page block's visible edge showing reading progress; a ribbon bookmark is a real UI element (it marks your place and chapter); subtle paper/cloth grain via inline SVG feTurbulence filters at low opacity; soft directional light; embossed/debossed type effects using careful text-shadow; black lacquer + cobalt bookcloth + warm paper. Suggested type: Fraunces (soft, characterful serif) for display, Literata for reading, a humanist sans (Albert Sans or Figtree) for UI. Motion is physical (ribbons swing, pages settle). The player treats the book itself as the object (e.g. the scrubber is the page block/ribbon).` },
  { key: 'D', name: 'Signal', idea: `GENERATIVE INSTRUMENT. Each book gets a unique generative SIGNATURE drawn from its structure: e.g. concentric rings of chapter arcs, a dot-matrix waveform of its narration, a constellation of its concepts; the signature is the book's identity everywhere (home, player, library). The UI is a precise instrument: true black, 1px lines, tick scales and rulers, a dot-matrix display face for numerals and headers (suggested: Doto), mono labels (suggested: Geist Mono or JetBrains Mono), a clean grotesk for body UI (suggested: Hanken Grotesk), and a proper reading serif for book text (Newsreader or Literata). Cobalt is the signal light, used like an LED. The player's generative ring IS the scrubber. The read-along highlight is a scanning beam. Inspired by audio equipment and scientific instruments, without copying any brand.` },
]

const ALL = ['Home', 'Reader', 'Player', 'System']
const MISSING = (args && args.missing) || {}

const designPrompt = (d) => {
  const missing = MISSING[d.key] || []
  const existing = ALL.filter((s) => !missing.includes(s))
  return `You are an award-winning art director and product designer. ${BRIEF}

YOUR DIRECTION: ${d.key} · "${d.name}". ${d.idea}
Push this idea all the way; make it unmistakably different from the other directions (Editorial "Folio", Swiss "Grid", tactile "Shelf", generative "Signal") and from generic dark card UIs. Make bold, specific choices.

A previous session started this direction and was interrupted. ALREADY WRITTEN (read them fully first; they define the established language: fonts, motifs, colors, spacing; keep them unless something is broken): ${existing.map((s) => `${ROOT}/Dir${d.key}-${s}.dc.html`).join(', ')}
STILL MISSING, write these now in exactly the same language (one complete file per write; create nothing else; do not read or touch any files of other directions):
${missing.map((s) => `- ${ROOT}/Dir${d.key}-${s}.dc.html (${s === 'System' ? '840x844; set data-props $preview to 840x844' : '390x844'})`).join('\n')}
Do not publish anything. After writing, re-read the new file(s) once to check the FORMAT rules (closed tags, quoted attributes, support.js line, holes only reference renderVals keys, no sc-for/sc-if inside svg, no emoji, no <img>) and fix any issue.
Return: a concise design rationale (<= 200 words) for the whole direction: the core visual idea, named fonts, how Cobalt is used, the signature motif and the signature motion, and anything you'd develop further.`
}

const critiquePrompt = (d, rationale) => `You are an exacting design critic and senior product designer from a top studio, brought in to review and ELEVATE one art direction for BetterBooks. ${BRIEF}

DIRECTION UNDER REVIEW: ${d.key} · "${d.name}". Intent: ${d.idea}
Designer's rationale: ${rationale}

Files (read all four fully first): ${ROOT}/Dir${d.key}-Home.dc.html, ${ROOT}/Dir${d.key}-Reader.dc.html, ${ROOT}/Dir${d.key}-Player.dc.html, ${ROOT}/Dir${d.key}-System.dc.html

1. Critique hard against: (a) UNIQUENESS: would it be mistaken for a generic AI-generated dark card app? Is there an ownable idea visible in every screen? (b) ARTISTIC QUALITY: typography (pairing, scale, rhythm, details like small caps, tabular numerals, hanging punctuation), composition, restraint, consistency of the system; (c) PRACTICALITY: reading comfort (size, measure, leading), hierarchy, obvious tap targets >= 44px, contrast >= 4.5:1, the now-bar and transport usable one-handed, nothing decorative blocking function; (d) FIDELITY to the brief's required content per screen; (e) FORMAT correctness (support.js line, closed tags, quoted attributes, holes only reference renderVals keys, no sc-for/sc-if inside svg, inline styles, single Google Fonts link, no emoji, no <img>, child SVG elements use style for stroke-dasharray etc.).
2. Then EDIT the four files in place to fix every problem you found and push the direction further toward exceptional (stay within the direction's intent; do not homogenize it toward the others). Keep the same four file paths; create no other files; do not publish.
3. Return: scores 1-10 BEFORE and AFTER for uniqueness, artistry, practicality; the 5 most important changes you made; and remaining risks or ideas for the full build. Keep it under 250 words.`

phase('Design')
const results = await pipeline(
  DIRECTIONS,
  (d) => (MISSING[d.key] && MISSING[d.key].length)
    ? agent(designPrompt(d), { label: `complete:${d.key}-${d.name}`, phase: 'Design' })
    : Promise.resolve('(The designer finished all four files but did not return a rationale; infer the intent from the files and the direction brief.)'),
  (rationale, d) => agent(critiquePrompt(d, rationale), { label: `critique:${d.key}-${d.name}`, phase: 'Critique & elevate' })
    .then((crit) => ({ key: d.key, name: d.name, rationale, critique: crit })),
)
return results.filter(Boolean)
