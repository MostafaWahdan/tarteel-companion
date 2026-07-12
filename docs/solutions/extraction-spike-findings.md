---
title: U3 extraction spike findings
date: 2026-07-12
status: verdict-reached
---

# U3 Extraction Spike — Findings

Corpus: 18 real Tarteel session-view screenshots (1080×2412 JPEG, two pages — Al-A'raf
p.163 and Ali 'Imran p.60 — each screenshotted repeatedly, two display themes).
Harness: `app/src/test/kotlin/com/tarteelcompanion/extraction/SpikeHarnessTest.kt`
(writes `app/build/spike-report.txt`; re-run after any Tarteel redesign).

## Layout verdict: (b) — reflowed text, but with better anchors than planned

Tarteel's session view does NOT reproduce the printed 604-page line layout: text
reflows to screen width at the user's font size. Force-fitting to printed line word
counts is off the table. However two anchors make the pivot easy:

1. **The header states the page directly** — "Page 163 | Juz 9 | Hizb 17" (sometimes
   plus the current ayah, e.g. "3:79") in Latin digits in a fixed UI font. Page ID =
   template-matching ten digit glyphs in the header band. No Arabic OCR anywhere.
2. **Ayah markers are separate ornament clusters** (circled Arabic-Indic numerals)
   between ayat. Matching the digits inside markers yields ayah numbers, giving
   in-page alignment anchors even for partially scrolled screenshots.

**U4 pipeline (pivot design):** header digits → page number → dataset word list for
the page; segment text-band lines (luminance projection) and words (gap analysis,
RTL); merge marker ornaments; align word clusters to the page's word sequence between
ayah-marker anchors; a colored cluster at position k anchors to dataset word k.
Validation gate: word-count mismatch between markers, unreadable header, or unknown
theme → NEEDS_MANUAL.

## Measured color calibration (JPEG-averaged cluster RGB)

| Mark | Meaning | Measured swatch range | Notes |
|------|---------|----------------------|-------|
| Red text | wrong word (R3 red) | (150–170, 85–100, 85–105) | often with dotted underline (small satellite clusters) |
| Yellow text | pronunciation/tashkeel (R3 yellow) | (170–200, 145–165, 70–80) | G−B ≥ 70 separates it from red |
| Brown text | prompted (R3 brown) | (120–135, 77–83, 63–74) | darker, smaller R−G gap than red |
| Olive blobs | history-heatmap glow (ignore) | e.g. (48, 65, 18) | green-dominant hue AND fill ≥ 0.5 — excluded |

Classifier rule set (validated over 161 clusters, both themes): pixel is a candidate
when max−min > 42 and max > 60; cluster into 4-connected components ≥ 40 px within
the text band (y ∈ [13%, 88%] of screen height); text marks have fill < 0.45 and
height 20–220 px; bucket by (R−G, G−B) plane per the table. Two background themes
observed (bg luminance ≈ 22 dark, ≈ 92 light); the chroma rule needs no per-theme
palette so far — keep the theme detector as a validation input only.

## Mutashabihat dataset characterization (bundled Waqar144 set)

Ayah-keyed, 1,344 source entries / >1,000 groups after parsing; group sizes are
card-friendly (oversized >12-member groups are <5% — asserted in
`QuranRepositoryTest`). Keying granularity matches U9's (group, target-ayah) design.
No pivot needed.

## U4 implementation findings (2026-07-12, post-build)

Empirical conclusions from building the pipeline against the corpus:

- **Blind page identification from geometry is NOT achievable.** A width model
  (per-letter advance classes) aligned via DP fits wrong pages as well as the true
  page — globally (best impostor 0.135 vs true 0.159), per-line (0.011 vs 0.033), and
  even within a ±30-page window. Adjacent pages are also not separable (162 beat 163
  once). Do not retry threshold tuning; the signal is fundamentally too weak.
- **Known-page anchoring WORKS: 18/18 screenshots.** Given the page number, the
  two-stage alignment (line-total DP partition, then within-line run DP with
  proportional fallback) maps colored clusters to word positions with in-surah
  accuracy across the corpus, including the ground-truth 3:79 pronunciation marks.
- **Shipped v1 flow:** first screenshot of a batch → user types the page number
  (visible in Tarteel's own header) → auto-detect pre-marks the mistakes; subsequent
  screenshots anchor at the previous confirmed page automatically, and the confirm
  screen remains the authority for page corrections (re-anchors on demand).
- **Guards that matter:** px-per-unit must be a plausible fraction of line height
  (0.08–0.8×) or short pages "fit" dense screenshots at absurd scales; smooth color
  fills (history blobs, ayah-band overlays) must be excluded from ink via a local-
  contrast gate or they weld whole lines into one run; a 3% horizontal inset removes
  scrollbar artifacts.
- **Future work if full automation is wanted:** read the header's Latin page digits
  via self-calibrating templates (each manual page confirmation associates header
  glyph crops with known digits; once 0–9 are covered, imports are fully automatic).

## Open items for U4 implementation

- Header digit templates must be captured from the corpus (fixed UI font, Latin digits).
- Marker-ornament detection: distinctive circular glyphs; verify their cluster shape
  discriminates from words on more pages once available.
- Gray current-ayah band overlays (lighter background behind some lines) must not
  perturb line segmentation — use luminance deltas relative to a per-line background
  estimate, not a global background.
- JPEG only in corpus (Tarteel/phone saves JPG): thresholds above already tolerate
  ringing; PNG originals would only be cleaner.
