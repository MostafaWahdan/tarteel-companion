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

## Open items for U4 implementation

- Header digit templates must be captured from the corpus (fixed UI font, Latin digits).
- Marker-ornament detection: distinctive circular glyphs; verify their cluster shape
  discriminates from words on more pages once available.
- Gray current-ayah band overlays (lighter background behind some lines) must not
  perturb line segmentation — use luminance deltas relative to a per-line background
  estimate, not a global background.
- JPEG only in corpus (Tarteel/phone saves JPG): thresholds above already tolerate
  ringing; PNG originals would only be cleaner.
