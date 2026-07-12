---
date: 2026-07-12
topic: tarteel-companion
---

# Tarteel Companion — Mistake Review App for Android

## Problem Frame

The user memorizes the Quran and recites from memory using the Tarteel app, which highlights mistakes on the mushaf page: **yellow** for pronunciation mistakes, **red** for wrong-word substitutions, and **brown** for spots where a prompt was needed to continue. The user screenshots these highlighted pages after sessions, but the screenshots pile up unreviewed — the mistake data exists but never becomes targeted review. Volume is meaningful: 2–3 mistakes per page when memorization is strong, 5–6 when weaker, so a multi-page session can produce dozens of spots.

Tarteel Premium logs historical mistakes in-app but offers no export, no API, and (currently) no study-plan or quiz feature built on that log. This is a personal tool that closes the loop: screenshots in → deterministic extraction of mistake positions → spaced flashcard study with similar-verse (mutashabihat) clarification and mnemonics → a cold-recall quiz on the same spots. If Tarteel later ships equivalent review features natively, this app can be happily retired — it is not a bet against their roadmap.

---

## Actors

- A1. The reciter (the user): imports screenshots, studies flashcards, self-grades recall, takes quizzes.
- A2. Tarteel app (external): produces the highlighted-mushaf screenshots; the only data source. Not integrated with — no API or export exists.
- A3. LLM service (Groq or Gemini Flash, free tier): phrases similar-verse distinctions and drafts mnemonics. Never a source of verse text and never required for the app to function.

---

## Key Flows

- F1. Import and extract
  - **Trigger:** After a Tarteel session, the user imports one or more screenshots (gallery picker and/or Android share sheet from the screenshots folder).
  - **Actors:** A1, A2 (as source)
  - **Steps:** App runs on-device extraction: identifies the mushaf page, locates highlighted words, classifies each highlight as yellow/red/brown, and anchors each to a canonical surah:ayah:word position with verse text taken verbatim from a bundled Quran database. The user sees the detected mistakes marked on the page and can correct, add, or remove any before saving.
  - **Outcome:** Each mistake spot is stored with position, type, and date; new/updated flashcards are scheduled.
  - **Failure path:** If extraction fails or misfires on a screenshot, the user can tag mistakes manually on the canonical page shown beside the screenshot.
  - **Covered by:** R1–R7

- F2. Study session
  - **Trigger:** The user opens the app and has due cards.
  - **Actors:** A1, A3 (mnemonic content, pre-generated)
  - **Steps:** For each due mistake spot, the app shows a full-passage recall card: the ayah (or few ayat) containing the spot with the previous mistake location marked. The user re-recites the passage (aloud or mentally), flips the card to see the full passage, the mistake spot, the similar-verse comparison (when the spot maps to a mutashabihat group) with differing words highlighted, and the mnemonic. The user self-grades Again/Hard/Good/Easy; spaced repetition schedules the next review.
  - **Outcome:** Studied spots are rescheduled by grade; spots studied today become eligible for a later cold-recall quiz.
  - **Covered by:** R8–R14

- F3. Cold-recall quiz
  - **Trigger:** Scheduled after study (e.g., the next day) for recently studied spots.
  - **Actors:** A1
  - **Steps:** Same full-passage recall cards, but stripped of all study aids — no mnemonic and no similar-verse comparison shown until after the user grades themselves. The session ends with a score across the quizzed spots.
  - **Outcome:** Spots graded poorly in the quiz return to the study queue; the score gives an honest read on whether the spot is fixed.
  - **Covered by:** R15–R17

---

## Requirements

**Import and extraction**
- R1. The user can import Tarteel screenshots via the system gallery picker and via the Android share sheet.
- R2. Extraction runs fully on-device with no network dependency — deterministic computer vision, not a cloud/LLM service. (Driver: avoiding API dependence and rate limits, and wanting extraction to be deterministic and trustworthy.)
- R3. Extraction identifies each highlighted word and classifies its highlight color as one of three mistake types: yellow = pronunciation, red = wrong word, brown = prompt needed.
- R4. Every extracted mistake is anchored to a canonical position (surah, ayah, word index), with the verse text always taken verbatim from a bundled canonical Quran text database — never from OCR output or an LLM.
- R5. Before saving, the user sees the detected mistakes marked on the page and can correct the type, remove false positives, or add missed spots.
- R6. If automated extraction fails on a screenshot, the user can fall back to manually tapping mistake words on the canonical page (with the screenshot shown for reference).
- R7. Re-importing a spot that already exists (same word position) updates that spot's history rather than creating a duplicate.

**Study plan (flashcards)**
- R8. Each mistake spot becomes a full-passage recall card: the card prompts re-recitation of the whole ayah (or a small span of ayat) containing the spot, with the previous mistake location marked on reveal.
- R9. Grading is self-assessed (Again/Hard/Good/Easy); the app never listens to or verifies recitation audio.
- R10. Cards are scheduled by a spaced-repetition algorithm so weak spots recur more often and fixed spots fade out.
- R11. When a mistake spot's verse belongs to a known mutashabihat (similar-verses) group, the card's reveal side shows the similar verses side-by-side with the differing words highlighted, sourced deterministically from an established mutashabihat dataset — not inferred by an LLM.
- R12. Each mutashabihat comparison is accompanied by a mnemonic that anchors which wording belongs to which verse. Mnemonics are drafted by the LLM (A3) from the dataset's verse pairs; the user can edit or replace the mnemonic, and the edited version persists.
- R13. LLM mnemonic generation degrades gracefully: if offline or the API fails, the card still shows the verse comparison, and mnemonic generation is queued for later. Only canonical verse references/text are sent to the LLM — never screenshots.
- R14. Mistake type influences the card's reveal content: red-type spots emphasize the wrong-word position and its similar-verse contrast; brown-type spots emphasize the continuation point (what came next); yellow-type spots mark the word whose pronunciation slipped.

**Quiz**
- R15. A cold-recall quiz is offered on a delay after spots are studied (default: next day), using the same passage-recall format.
- R16. Quiz cards hide all study aids (mnemonic, similar-verse comparison) until after self-grading.
- R17. The quiz session ends with a per-spot and overall score; poorly graded spots return to the study queue.

---

## Acceptance Examples

- AE1. **Covers R3, R4.** Given a screenshot of a Tarteel page with one word highlighted red, when the user imports it, the app stores exactly one mistake of type "wrong word" anchored to that word's surah:ayah:word position, and the stored verse text matches the canonical Quran database character-for-character.
- AE2. **Covers R5, R6.** Given a screenshot where extraction detects two of three highlighted words, when the user reviews the confirm screen, they can tap the missed word on the canonical page to add it before saving.
- AE3. **Covers R11, R12, R13.** Given a red-type mistake on a verse that belongs to a mutashabihat group, when its card is revealed while the device is offline and no mnemonic has been generated yet, the card still shows the similar verses side-by-side with differences highlighted, and the mnemonic appears on a later review after connectivity returns.
- AE4. **Covers R15, R16, R17.** Given three spots studied on Monday, when the user opens Tuesday's quiz, the same passages are presented without mnemonics or comparisons, and after self-grading, any spot graded "Again" appears in the study queue with aids restored.

---

## Success Criteria

- Screenshots stop piling up: after each Tarteel session, importing takes a couple of minutes and every mistake spot becomes a scheduled card — nothing is left unprocessed.
- The user actually completes the loop: study sessions and next-day cold quizzes happen regularly, and quiz scores at previously weak spots trend upward (the same spot stops reappearing as a Tarteel mistake).
- Extraction is trustworthy enough that the confirm screen is usually a glance, not a rework session.
- Handoff quality: a planner can design the build from this document without inventing product behavior — every screen's purpose, the three mistake types, the grading model, and the quiz mechanics are specified here.

---

## Scope Boundaries

### Deferred for later

- Progress dashboards / analytics over time (streaks, per-surah heatmaps).
- Automatic detection of new Tarteel screenshots (watching the screenshots folder).
- Audio playback of a qari's recitation on cards.
- Cloud sync, backup, or multi-device support.
- iOS version.

### Outside this product's identity

- Speech recognition or any audio-based grading of recitation — that is Tarteel's core product; this app is self-graded by design.
- Teaching new memorization or tajweed instruction — this is a *review* companion for material already memorized, not a hifz or tajweed tutor.
- Replacing Tarteel or re-implementing its recitation-following features. If Tarteel ships native mistake-review, this app can be retired.
- A general-purpose flashcard app; cards exist only as projections of imported mistake spots.

---

## Key Decisions

- **Full-passage recall over word-level cloze cards**: reviewing means re-reciting the whole ayah/passage containing the mistake, matching how recitation actually works, rather than isolated word drills.
- **Self-grading (Anki-style) over app-verified recitation**: keeps the app entirely out of speech recognition; proven flashcard model; buildable solo.
- **Quiz = cold recall test**: same card format, delayed, stripped of aids, scored — study with support, test without it. Chosen over objective multiple-choice questions.
- **On-device CV extraction over LLM-vision extraction**: chosen to avoid API dependence and rate limits and for deterministic, trustworthy extraction — accepted as the heaviest-build option with known brittleness to Tarteel UI changes (see Assumptions).
- **Dataset + LLM hybrid for intelligence**: similar verses come deterministically from an established mutashabihat dataset (no hallucination risk on which verses are similar); the LLM (free-tier Groq or Gemini Flash) only phrases distinctions and drafts mnemonics; verse text is always verbatim from the canonical database.
- **Personal tool framing**: single user, no accounts, no monetization; scope decisions favor build simplicity over generality.

---

## Dependencies / Assumptions

- Tarteel's mushaf rendering and highlight colors stay visually stable enough for deterministic extraction; a Tarteel redesign may require re-tuning the CV pipeline (accepted risk; manual tagging R6 is the safety net).
- An established mutashabihat dataset exists in usable form and covers the user's memorized portions (unverified — research during planning).
- A canonical Quran text database with word-level indexing matching Tarteel's mushaf layout is available (widely true — e.g., Uthmani/Hafs text datasets — but the specific layout match is unverified).
- Free-tier LLM access (Groq or Gemini Flash) remains available for mnemonic drafting; the app must remain fully usable without it (R13).
- Yellow vs. brown highlight distinction is reliably separable in pixel color space on real screenshots (unverified — validate early in planning with actual screenshots).

---

## Outstanding Questions

### Deferred to Planning

- [Affects R2–R4][Needs research] CV pipeline design: how to identify which mushaf page/ayat a screenshot shows (page-number OCR, text-shape matching against known layouts, or Arabic OCR), and how to segment and index words on the page.
- [Affects R4][Needs research] Which canonical Quran database and word-indexing scheme best matches Tarteel's mushaf rendering.
- [Affects R11][Needs research] Which mutashabihat dataset to use and its licensing/format.
- [Affects R10][Technical] Spaced-repetition algorithm choice (e.g., SM-2 vs. FSRS) and how quiz results feed back into scheduling.
- [Affects R12][Technical] LLM provider choice (Groq vs. Gemini Flash free tier), prompt design for mnemonics, and mnemonic language (Arabic, English, or both).
- [Technical] Android stack choice (native Kotlin/Compose vs. cross-platform) — personal single-platform app, so decide in planning by CV-library ergonomics.

---

## Planning Addendum (2026-07-12)

Product decisions resolved with the user during `/ce-plan` (flow analysis surfaced these as gaps):

- R18. Card front: a study/quiz card's prompt side shows the surah name + ayah number plus the tail of the preceding ayah as a lead-in cue (surah name only at surah start). The target passage text appears only on reveal. (Extends R8.)
- R19. Card grouping: when multiple due spots fall in the same ayah, they render as one card; the single self-grade applies to each contained spot. Spots remain the unit of scheduling and quiz scoring. (Extends R8/R10.)
- R20. Graduation: a spot graduates when it passes a cold-recall quiz at Good or better while its SRS interval is mature (≥ ~3 weeks). Graduated spots are archived (visible in history, excluded from queues) and reactivate with an SRS lapse if the same position is re-imported. (Extends R10/R17.)
- R21. Screenshot source: the user's screenshots are of Tarteel's session view, where mistake words are rendered as colored glyphs (colored text), not background-highlight rectangles. Extraction targets colored-glyph detection first.
- R22. Mnemonics and similar-verse distinction explanations are written in Arabic only.
- R23. The repo is public (github.com/MostafaWahdan/tarteel-companion) and no API key ships in the repo or APK: each user (including the author) enters their own LLM API key in a settings screen, stored on-device only. No key = no mnemonic generation; everything else works.

---

## Next Steps

-> Plan created: `docs/plans/2026-07-12-001-feat-tarteel-companion-v1-plan.md`. The highest-risk item is front-loaded there: a feasibility spike on the on-device extraction pipeline using real Tarteel screenshots (R2–R4), before the study/quiz layers.
