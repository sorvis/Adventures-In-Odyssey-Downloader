# App icon concepts

Working notes for the Odyssey app's launcher icon. The current
build ships with the default Android scaffold icon — placeholder
since v0.1.0. This doc captures concept directions to feed into
an image-generation API later (when one's available locally).

## Product framing the icon needs to fit

The app is **not** a generic podcast player. The product niche, as
decided 2026-05-04, is:

- **Daily-aired Christian/educational radio shows** that listeners
  want to **collect over time**, not just stream once.
- Adventures in Odyssey is the flagship integration; **Your Story
  Hour** is the next planned provider.
- Generic RSS support is a possible byproduct of the multi-show
  plugin, but it's outside the niche — the icon shouldn't lean
  generic-podcast.

So the icon should evoke:
1. **Audio / spoken word** (not music; not video)
2. **Storytelling** (both target shows are narrative serial drama)
3. **Collecting / archive** (the user value-add vs streaming)
4. **Radio era** (both shows pre-date podcasting; nostalgia is a
   feature for the audience, not a bug)

What it should NOT lean on:
- AIO-specific imagery (Whit's End, the train, "Adventures!" lettering)
  — would be wrong once YSH and others land.
- Pure-religious iconography (cross, halo) — alienates YSH listeners
  who skew educational rather than denominational.
- Generic podcast clichés (microphone in a circle, RSS waves).

## Concept 1 — Cassette in a box (collect-over-time, leading)

**Visual.** A stylized analog cassette tape, sitting in a small
storage box / shelf cubby. Sound-wave pattern subtly visible across
the cassette's window strip. Optional: a stack of two more cassettes
behind the front one, suggesting the collection grows.

**Why it fits.** The cassette is the universal "I'm archiving radio"
shorthand. The box/shelf framing says "you keep these," not "you
listen and forget." Reads warm + nostalgic — the audience that
listens to AIO/YSH skews toward families who appreciate that.

**Palette.** Warm cream + medium-warm brown (cassette body) on a
muted teal or deep navy background. Single accent color (warm
amber) for the sound-wave detail.

**Mood.** Cozy. Library. Personal collection. Adult-audience-but-
appropriate-for-kids — same demographic both AIO + YSH target.

**Image-gen prompt.**
> Vector-style flat-color app icon, 512×512, square. A single warm
> beige cassette tape sitting in an open wooden cubby/shelf, viewed
> straight on. The cassette's reels are barely visible through the
> tape window, and a subtle horizontal sound-wave line runs across
> the tape strip. Behind the front cassette, two more cassettes
> peek out as silhouettes, suggesting a small collection. Background
> is a deep navy or muted teal. Color palette: warm cream, medium
> brown, single amber accent on the sound-wave line. No text, no
> logos. Soft rounded corners on cassette body. Style: clean modern
> flat illustration, slight warmth, family-friendly, evocative of
> a personal radio-show archive. Material You compatible — works at
> 48dp launcher size with a 66dp safe zone for adaptive icon mask.

---

## Concept 2 — Antenna + waveform + day mark

**Visual.** A small stylized radio antenna at the top of the icon,
with three concentric arcs/sound-waves emanating outward and
downward. Below the waves, a single dot or short tick — "today's
episode caught."

**Why it fits.** Most directly says "radio show, captured." The
"day mark" dot is the differentiator from a generic podcast antenna
icon — implies the daily-rhythm aspect of the niche.

**Palette.** Two-tone, high-contrast: deep navy or near-black
background, off-white or warm cream foreground for the antenna and
waves, single warm accent color (amber or coral) for the day mark.

**Mood.** Clean, calm, slightly retro-tech. Reads more "tool" than
"toy" — appropriate for the productivity/archive use case.

**Image-gen prompt.**
> Vector-style flat-color app icon, 512×512, square. A simple
> stylized broadcast antenna in the upper third of the canvas (a
> short vertical line with two diagonal lines forming a triangular
> base). Three concentric semicircle arcs radiate from the antenna's
> top, fanning down and outward. A single small filled circle ("day
> mark") sits centered below the bottom-most arc. Foreground in warm
> off-white, background in deep navy. The day-mark circle is a warm
> amber accent — only color in the icon. No text. Style: minimal,
> modern, slight retro-tech feel, flat illustration. Lines are
> medium-weight, balanced. Material You adaptive-icon-compatible
> with 66dp central safe zone.

---

## Concept 3 — Open book with sound waves rising

**Visual.** A small open book (just the silhouette — pages, spine,
gentle curve) with a few sound-wave lines rising from the open
pages. The waves grow slightly taller toward the right, suggesting
a story unfolding.

**Why it fits.** AIO + YSH are both narrative drama — story
podcasts before "story podcast" was a category. The book speaks to
the storytelling heart; the rising waves mean it's spoken story,
not silent reading. Frames the app as a library of audio stories.

**Palette.** Warm earth tones — terracotta book against a soft
cream background, with the sound waves in a deeper saddle brown.
Could optionally have a single gold accent on the spine.

**Mood.** Warm, inviting, slightly storybook. Most explicitly
"family-friendly storytelling" of the five concepts.

**Image-gen prompt.**
> Vector-style flat-color app icon, 512×512, square. A small open
> book seen from a 3/4 front angle — pages slightly fanned, simple
> spine, no text. Three to five thin sound-wave curves rise from
> the open page area, growing taller from left to right (suggesting
> a story building). Book is warm terracotta, sound waves are
> saddle brown, background is soft cream. Optional thin gold accent
> line on the spine. Style: friendly, modern flat illustration,
> rounded corners, warm storybook vibe — appropriate for a
> family-audience audio-archive app. No text. Material You
> compatible at 48dp with 66dp safe zone.

---

## Concept 4 — Owl with headphones

**Visual.** A simple, geometric owl head (round body, rounded
triangle ear tufts, large round eyes), wearing over-ear headphones.
Stylized, friendly, not photoreal.

**Why it fits.** Owl = wisdom + storytelling, a centuries-old
shorthand for "stories told to teach." Headphones modernize it.
Memorable + distinctive — would stand out in a launcher full of
abstract shapes. Works equally for AIO (wholesome family content)
and YSH (educational drama).

**Palette.** Two-tone owl (medium charcoal body, warm cream face
disc), with a small warm accent (amber or terracotta) on the
headphone ear cups. Background a soft sage or muted navy.

**Mood.** Friendly mascot. More personality than the other four
concepts — risk: could read "too kid-focused" if the user's image
of the audience is broader. Reward: high recognition, memorable.

**Image-gen prompt.**
> Vector-style flat-color app icon, 512×512, square. A geometric
> stylized owl, head and chest only, facing forward. Round body,
> two rounded triangular ear tufts, two large round eyes (solid
> color, no pupils — Twitter-style minimal). The owl wears over-ear
> headphones — visible band across the top of the head, two round
> ear cups covering each side. Owl body is a medium charcoal grey,
> face disc is warm cream, eyes are charcoal. Headphone ear cups
> are warm amber (the only saturated color). Background is muted
> sage green or soft navy. Style: friendly modern mascot, flat
> illustration, no shading, family-friendly, distinctive at small
> size. No text. Material You adaptive icon — central composition
> within 66dp safe zone.

---

## Concept 5 — Stacked episode discs

**Visual.** Three small circular "discs" (suggesting both vinyl
records AND audio-file UI affordances) stacked at slight offsets,
each with a tiny center hole and a single arc line suggesting a
sound wave or groove. A subtle plus symbol or upward arrow at the
top — "more arriving."

**Why it fits.** Speaks to the **collection growing daily** —
exactly the niche framing. The disc shape is friendly (vinyl
nostalgic + modern audio-card UI), and the offset stack reads as
"history accumulating."

**Palette.** Three-tone — discs in deep teal, muted plum, and warm
ochre, suggesting different shows/dates. Background soft cream.
Single small accent dot on the topmost disc.

**Mood.** Modern, slightly playful, abstract enough to scale to
small sizes. Easiest to make work as a monochrome notification
icon (single disc + arc).

**Image-gen prompt.**
> Vector-style flat-color app icon, 512×512, square. Three circular
> "audio discs" stacked with slight horizontal offsets — back disc
> top-left, middle disc center, front disc bottom-right. Each disc
> is a flat-colored circle with a small center hole and a single
> thin arc line near the edge suggesting a sound groove. Back disc
> deep teal, middle disc muted plum, front disc warm ochre.
> Background soft cream. A small upward-pointing chevron or arrow
> at the top-center, very subtle, hinting at "new episodes
> arriving." Style: clean modern flat illustration, no shading,
> friendly. No text. Distinctive shape language at 48dp launcher
> size; central composition fits 66dp adaptive-icon safe zone.

---

## Android packaging constraints (any chosen concept must clear)

**Adaptive icon.** Android 8+ uses an adaptive icon: 108×108dp
foreground layer + 108×108dp background layer, with the launcher
applying a mask (circle, squircle, rounded rect, etc.). The
**central 66dp circle** is the safe zone — content outside that
gets cropped on round-mask devices. Practical implication: the
focal element of every concept above must fit comfortably inside a
66dp circle, with the rest of the 108dp canvas being either pure
background or non-essential decoration.

**Notification small icon.** Android requires a separate
silhouette-only icon for status bar / notification (24×24dp,
solid white on transparent, no color). Concepts 2 (antenna+wave),
5 (stacked discs), and 1 (cassette) reduce cleanly to a one-color
silhouette. Concepts 3 and 4 are harder — the book and owl rely
on multi-color cues that flatten poorly. Pick that into the
decision.

**Density buckets.** Need raster fallbacks at mdpi/hdpi/xhdpi/
xxhdpi/xxxhdpi for the legacy round + square icons. SVG → PNG
export pipeline once a concept lands.

## Recommendation

When the image-gen API is available, **try Concept 1 (cassette in
a box) and Concept 5 (stacked discs) first.** Both directly speak
to the "collect daily radio over time" niche, both reduce well to
a notification silhouette, and both stay show-agnostic so YSH +
future providers slot in without rebranding.

Concept 4 (owl with headphones) is the highest-reward outlier —
most memorable but riskiest if the audience reads as too child-
focused. Worth trying if the first two don't land.

Concepts 2 and 3 are safer fallbacks — solid, but less distinctive
in a launcher.

## Implementation sketch (post-icon)

1. Pull the chosen rendered icon from the image-gen API → save
   the source SVG/PNG at `branding/icon-source.png`.
2. Use Android Studio's Asset Studio (or `magick` CLI + an
   icon-generator) to produce:
   - `mipmap-*/ic_launcher.png`
   - `mipmap-*/ic_launcher_round.png`
   - `mipmap-anydpi-v26/ic_launcher.xml` (adaptive icon)
   - `drawable/ic_notification.xml` (silhouette)
3. Wire into `AndroidManifest.xml` `<application android:icon=`,
   `android:roundIcon=`, and the foreground service notification
   builder (`NotificationCompat.Builder.setSmallIcon`).
4. Bump version, ship, watch Obtainium pick it up.

The existing app icon points at the default Android system asset
`@mipmap/ic_launcher` (or whatever AGP scaffolded). Replacement is
purely additive — no migration concerns, no Play Store gatekeeper
since the app is sideloaded via Obtainium.
