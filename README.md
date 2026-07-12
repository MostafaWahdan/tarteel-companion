# Tarteel Companion

A personal Android app that turns [Tarteel](https://tarteel.ai) recitation-session
screenshots into a targeted Quran memorization review loop:

1. **Import** screenshots where Tarteel marked your mistakes — yellow (pronunciation),
   red (wrong word), brown (needed prompting).
2. **Extract** the exact mistake positions fully on-device with deterministic image
   analysis (no OCR, no cloud) anchored against canonical Quran text.
3. **Study** with spaced-repetition full-passage recall cards, similar-verse
   (mutashabihat) comparisons, and Arabic mnemonics.
4. **Quiz** yourself cold the next day on the same spots — pass at a mature interval
   and the spot graduates.

This is a personal tool. It does not listen to recitation, teach tajweed, or replace
Tarteel — see `docs/brainstorms/` for the product scope and `docs/plans/` for the
implementation plan.

## Bring your own API key

This repo is public and ships **no API key**. Mnemonic drafting uses Google Gemini
Flash via an API key that you supply in the app's Settings screen (free tier from
[Google AI Studio](https://aistudio.google.com/)). The key is stored encrypted,
on-device only, excluded from backups, and never appears in logs. **No key = no
mnemonic generation; everything else works fully offline.**

Only canonical Quran verse text is ever sent to the LLM — never your screenshots or
personal data.

## Data and attributions

- Quran text, mushaf layout, and mutashabihat data come from Tarteel's
  [Quranic Universal Library (QUL)](https://qul.tarteel.ai). These datasets are **not
  committed to this repo** pending confirmation of redistribution terms — fetch them
  locally into `app/src/main/assets/quran/` (see the plan's U2 for the required files).
- Arabic rendering uses the KFGQPC Uthmanic Script HAFS font from the
  [King Fahd Glorious Quran Printing Complex](https://fonts.qurancomplex.gov.sa/).
- Spaced repetition uses a vendored copy of
  [FSRS-Kotlin](https://github.com/open-spaced-repetition/FSRS-Kotlin) (MIT).

## Privacy note on `samples/`

The extraction test corpus in `samples/` contains real recitation screenshots (i.e.,
a record of one person's memorization mistakes). It is gitignored by default — do not
commit screenshots unless you deliberately choose to publish your own.

## Building

Standard Android toolchain: JDK 17, Android SDK 35. `./gradlew assembleDebug` builds;
`./gradlew test` runs the JVM suites (extraction golden tests skip gracefully when
`samples/` is empty).
