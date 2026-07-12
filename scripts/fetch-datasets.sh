#!/bin/sh
# Fetches the Quran datasets into app/src/main/assets/quran/ (gitignored).
#
# Datasets are NOT committed to this repo pending license confirmation from their
# sources (see README "Data and attributions" and the plan's U2 license gate):
#   - zonetecde/mushaf-layout  — 604-page Madani mushaf (Hafs) layout JSON:
#     per page, per line, word-level surah:ayah:word keys with Uthmani text.
#   - Waqar144/Quran_Mutashabihat_Data — similar-verse (mutashabihat) groups
#     curated for huffaz ("free to use, attribution appreciated").
#
# Usage: sh scripts/fetch-datasets.sh
set -e

ASSETS_DIR="$(dirname "$0")/../app/src/main/assets/quran"
TMP_DIR="${TMPDIR:-/tmp}/tarteel-companion-datasets"

mkdir -p "$ASSETS_DIR/pages" "$TMP_DIR"

echo "Fetching mushaf layout (zonetecde/mushaf-layout)..."
curl -sL -o "$TMP_DIR/mushaf-layout.tar.gz" \
  "https://github.com/zonetecde/mushaf-layout/archive/refs/heads/main.tar.gz"
tar -xzf "$TMP_DIR/mushaf-layout.tar.gz" -C "$TMP_DIR"
cp "$TMP_DIR"/mushaf-layout-main/mushaf/page-*.json "$ASSETS_DIR/pages/"

echo "Fetching mutashabihat data (Waqar144/Quran_Mutashabihat_Data)..."
curl -sL -o "$ASSETS_DIR/mutashabiha_data.json" \
  "https://raw.githubusercontent.com/Waqar144/Quran_Mutashabihat_Data/master/mutashabiha_data.json"

PAGE_COUNT=$(ls "$ASSETS_DIR/pages" | wc -l | tr -d ' ')
echo "Done. $PAGE_COUNT page files + mutashabiha_data.json in $ASSETS_DIR"
if [ "$PAGE_COUNT" != "604" ]; then
  echo "WARNING: expected 604 page files, got $PAGE_COUNT" >&2
  exit 1
fi
