#!/usr/bin/env bash
# Regenerates the bundled coin icons and the offline asset seed.
#
# Both land in app/src/main/assets/ and are committed, so a fresh clone builds
# without network access and the app searches coins offline on first launch.
#
# Two icon sources, in this order, because neither alone is enough:
#
#   1. ErikThiart/cryptocurrency-icons (MIT), keyed by slugified coin NAME.
#      Current: it carries the tokens that dominate today's top 100.
#   2. spothq/cryptocurrency-icons (CC0-1.0), keyed by TICKER. Older and much
#      smaller, but it fills gaps the first one has.
#
# spothq alone was measured at 17 of the top 25 and 32 of the top 100, which is
# not good enough for the default offline set, hence the pair. Both licences
# permit redistribution inside the APK, which is the whole reason these can be
# bundled rather than fetched at runtime.
#
# Metadata (names, tickers, market-cap ranks) comes from CoinPaprika, no key.
#
# Run this when you want newer coins. It is deliberately NOT part of the build:
# a build that reaches the network fails differently on every machine.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$REPO_ROOT/app/src/main/assets"
COINS="$ASSETS/coins"
SEED="$ASSETS/asset_seed.json"

# How many coins to seed. The top few hundred by market cap covers essentially
# every real portfolio; anything beyond is resolved online when searched for.
LIMIT="${1:-600}"
JOBS="${JOBS:-16}"

ERIK_BASE="https://raw.githubusercontent.com/ErikThiart/cryptocurrency-icons/master/128"
ERIK_TREE="https://api.github.com/repos/ErikThiart/cryptocurrency-icons/git/trees/master?recursive=1"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

slugify() {
  echo "$1" | tr '[:upper:]' '[:lower:]' \
    | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//'
}

echo "==> Fetching coin metadata (CoinPaprika, no key required)"
curl -sL --fail -o "$WORK/coins.json" "https://api.coinpaprika.com/v1/coins"

echo "==> Selecting the top $LIMIT active coins"
# rank<tab>id<tab>name<tab>symbol, one per line, sorted by rank.
tr '}' '}\n' < "$WORK/coins.json" \
  | grep '"is_active":true' \
  | grep -oE '"id":"[^"]*","name":"[^"]*","symbol":"[^"]*","rank":[0-9]+' \
  | sed -E 's/"id":"([^"]*)","name":"([^"]*)","symbol":"([^"]*)","rank":([0-9]+)/\4\t\1\t\2\t\3/' \
  | awk -F'\t' '$1 > 0' | sort -n -u > "$WORK/all.tsv"
# head is a separate step on purpose: closing the pipe early SIGPIPEs the stages
# upstream, and `set -o pipefail` turns that into a silent abort mid-script.
head -n "$LIMIT" "$WORK/all.tsv" > "$WORK/selected.tsv"

echo "    $(wc -l < "$WORK/selected.tsv") coins selected"

echo "==> Listing the MIT icon set (one API call, not one per coin)"
# The API pretty-prints, so the pattern has to tolerate the space after the
# colon. Without it this matches nothing, grep exits 1, and pipefail aborts the
# whole script one step after saying it was about to do the work.
curl -sL --fail "$ERIK_TREE" \
  | grep -oE '"path": *"128/[^"]+\.png"' \
  | sed 's|.*"128/||; s|\.png"||' | sort -u > "$WORK/erik.txt"
echo "    $(wc -l < "$WORK/erik.txt") icons available"

echo "==> Fetching the CC0 icon set (fallback, keyed by ticker)"
curl -sL --fail -o "$WORK/spothq.tar.gz" \
  "https://codeload.github.com/spothq/cryptocurrency-icons/tar.gz/refs/heads/master"
mkdir -p "$WORK/spothq"
tar xzf "$WORK/spothq.tar.gz" -C "$WORK/spothq" --strip-components=1

echo "==> Matching icons to coins"
mkdir -p "$COINS"
rm -f "$COINS"/*.png "$COINS"/*.webp 2>/dev/null || true

: > "$WORK/todo.tsv"     # slug<tab>url, for the parallel download below
: > "$WORK/seed.tsv"     # rank<tab>id<tab>name<tab>symbol<tab>iconSlug
spot_hits=0
none=0

while IFS=$'\t' read -r rank id name symbol; do
  [ -z "$rank" ] && continue
  ticker="$(echo "$symbol" | tr '[:upper:]' '[:lower:]')"
  icon=""

  # 1. The MIT set, by slugified name, then by the id with its ticker prefix
  #    stripped ("btc-bitcoin" -> "bitcoin").
  for cand in "$(slugify "$name")" "$(echo "$id" | sed -E 's/^[^-]+-//')"; do
    [ -z "$cand" ] && continue
    if grep -qxF -- "$cand" "$WORK/erik.txt"; then
      printf '%s\t%s/%s.png\n' "$ticker" "$ERIK_BASE" "$cand" >> "$WORK/todo.tsv"
      icon="$ticker"
      break
    fi
  done

  # 2. The CC0 set, by ticker, already on disk.
  if [ -z "$icon" ] && [ -f "$WORK/spothq/128/color/$ticker.png" ]; then
    cp "$WORK/spothq/128/color/$ticker.png" "$COINS/$ticker.png"
    icon="$ticker"
    spot_hits=$((spot_hits + 1))
  fi

  # 3. No artwork anywhere. The app draws a monogram tile for these.
  [ -z "$icon" ] && none=$((none + 1))

  printf '%s\t%s\t%s\t%s\t%s\n' "$rank" "$id" "$name" "$symbol" "$icon" >> "$WORK/seed.tsv"
done < "$WORK/selected.tsv"

echo "    downloading $(wc -l < "$WORK/todo.tsv") icons with $JOBS parallel jobs"
# --fail so a 404 leaves no truncated file behind; -s so the log stays readable.
xargs -P "$JOBS" -I{} sh -c '
  slug=$(printf "%s" "{}" | cut -f1)
  url=$(printf "%s" "{}" | cut -f2)
  curl -sL --fail --max-time 30 -o "'"$COINS"'/$slug.png" "$url" || true
' < "$WORK/todo.tsv"

# Anything that 404'd or came back empty is not a real icon.
find "$COINS" -name '*.png' -size -100c -delete 2>/dev/null || true

# WebP is roughly a third the size of these PNGs, which is worth several MB in
# the APK. It is optional because cwebp is not installed everywhere and a
# missing encoder must not block a refresh. AssetIcon resolves either extension.
if command -v cwebp > /dev/null 2>&1; then
  echo "    converting to WebP"
  find "$COINS" -name '*.png' -print0 \
    | xargs -0 -P "$JOBS" -I{} sh -c 'cwebp -quiet -q 90 "{}" -o "${1%.png}.webp" && rm -f "{}"' _ {}
else
  echo "    cwebp not installed, keeping PNG (install it to cut the APK by ~3 MB)"
fi

echo "==> Writing the seed"
awk -F'\t' '
  BEGIN { print "["; sep = "" }
  {
    icon = ($5 == "") ? "null" : "\"" $5 "\""
    gsub(/"/, "\\\"", $3)
    printf "%s{\"id\":\"%s\",\"name\":\"%s\",\"symbol\":\"%s\",\"rank\":%s,\"icon\":%s}\n", \
      sep, $2, $3, $4, $1, icon
    sep = ","
  }
  END { print "]" }
' "$WORK/seed.tsv" > "$SEED"

have=$(find "$COINS" -name '*.png' | wc -l)
echo
echo "==> Done"
echo "    seed:   $SEED ($(wc -c < "$SEED") bytes, $(wc -l < "$WORK/seed.tsv") coins)"
echo "    icons:  $have on disk ($spot_hits from the CC0 set), $none coins have no artwork"
echo "    size:   $(du -sh "$COINS" | cut -f1)"
echo
echo "    Commit both. The app reads them offline on first launch."
