#!/usr/bin/env bash
# Proves the demo flavour is isolated by build, not by a runtime flag.
#
# Checks that each flavour's APK contains its own implementations and none of the
# other's. Demo code being merely unreachable in the real app is not the claim;
# the claim is that it is not there at all.
#
# This exists because the check was first done by hand, with a relative APK path
# after a `cd`, and with stderr sent to /dev/null. The extraction failed, the
# grep searched files that did not exist, everything came back "absent", and that
# was reported as verification. It happened to be true. It was not shown.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${JAR:-jar}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Class simple names that must appear in exactly one flavour.
DEMO_ONLY=(DemoPortfolio RealDemoSeeder DemoWindowSecurityPolicy)
FULL_ONLY=(NoDemoSeeder RealWindowSecurityPolicy)
# A control, so a broken extraction cannot pass as a clean result.
BOTH=(PortfolioViewModel)

fail=0

for flavour in full demo; do
  # Absolute, because the extraction runs after a cd and a relative path would
  # silently resolve to nothing.
  apk=$(ls "$REPO_ROOT"/app/build/outputs/apk/"$flavour"/debug/*arm64*.apk 2>/dev/null | head -1 || true)
  if [ -z "$apk" ]; then
    echo "No $flavour debug APK. Build it first:"
    echo "  gradle :app:assemble${flavour^}Debug"
    exit 1
  fi

  dir="$WORK/$flavour"
  mkdir -p "$dir"
  (cd "$dir" && "$JAR" xf "$apk")

  dex_count=$(ls "$dir"/classes*.dex 2>/dev/null | wc -l)
  if [ "$dex_count" -eq 0 ]; then
    echo "$flavour: extracted no dex files, so nothing below would mean anything"
    exit 1
  fi
  echo "==> $flavour ($dex_count dex files)"

  strings_file="$dir/strings.txt"
  cat "$dir"/classes*.dex | strings > "$strings_file"

  check() {
    local cls="$1" expected="$2"
    if grep -q "$cls" "$strings_file"; then actual=present; else actual=absent; fi
    if [ "$actual" = "$expected" ]; then
      printf "    ok       %-28s %s\n" "$cls" "$actual"
    else
      printf "    FAILED   %-28s expected %s, was %s\n" "$cls" "$expected" "$actual"
      fail=1
    fi
  }

  for cls in "${BOTH[@]}"; do check "$cls" present; done
  if [ "$flavour" = "demo" ]; then
    for cls in "${DEMO_ONLY[@]}"; do check "$cls" present; done
    for cls in "${FULL_ONLY[@]}"; do check "$cls" absent; done
  else
    for cls in "${DEMO_ONLY[@]}"; do check "$cls" absent; done
    for cls in "${FULL_ONLY[@]}"; do check "$cls" present; done
  fi
done

echo
if [ "$fail" -ne 0 ]; then
  echo "FAILED: the flavours are not cleanly separated."
  exit 1
fi
echo "OK: each flavour carries only its own implementations."
