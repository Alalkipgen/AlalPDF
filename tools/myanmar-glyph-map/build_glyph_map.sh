#!/usr/bin/env bash
# Rebuilds app/src/main/assets/myanmar/glyph_map_v1.tsv from the fonts in fonts.txt.
# Needs: curl, sha256sum, python3 with fontTools.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); cache=${FONT_CACHE:-$here/.fonts}; mkdir -p "$cache"
files=()
while read -r sum url; do
  [[ -z "$sum" || "$sum" == \#* ]] && continue
  f="$cache/$(echo "$url" | sha256sum | cut -c1-12)-$(basename "$url")"
  [[ -f "$f" ]] || curl -fsSL -o "$f" "$url"
  echo "$sum  $f" | sha256sum -c --quiet
  files+=("$f")
done < "$here/fonts.txt"
cd "$here" && python3 gen_myanmar_glyph_map.py "$here/../../app/src/main/assets/myanmar/glyph_map_v1.tsv" "${files[@]}"
