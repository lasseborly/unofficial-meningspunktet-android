#!/usr/bin/env bash
# Regenerate launcher icons (legacy square/round + adaptive foreground) from the
# meningspunktet.dk logo. Adaptive background stays white (@color/ic_launcher_background
# in values/ic_launcher_background.xml). Idempotent: canvases rebuilt from scratch.
# Requires: ImageMagick.
set -euo pipefail
cd "$(dirname "$0")/.."
LOGO=assets/logo.svg
RES=android/app/src/main/res

logo() { # size out.png -> logo on transparent bg
  convert -background none -density 1200 -resize "${1}x${1}" "$LOGO" "$2"
}

# density: launcher-px ; adaptive foreground canvas is always 108dp
for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  dir="$RES/mipmap-${d%%:*}"; px=${d##*:}; fg=$(( px * 108 / 48 ))
  mkdir -p "$dir"
  c=$(( px / 2 ))

  # Legacy square: white disc-free canvas, logo ~62%
  logo $(( px * 62 / 100 )) /tmp/lg_$$.png
  convert -size "${px}x${px}" xc:white \( /tmp/lg_$$.png \) -gravity center \
          -composite -strip "$dir/ic_launcher.png"

  # Legacy round: white circle, same logo
  convert -size "${px}x${px}" xc:none -fill white \
          -draw "circle $c,$c $c,0" /tmp/rd_$$.png
  convert /tmp/rd_$$.png \( /tmp/lg_$$.png \) -gravity center \
          -composite -background none -strip "$dir/ic_launcher_round.png"

  # Adaptive foreground: transparent 108dp canvas (fg px); logo at 55% of the
  # canvas sits inside the 66/108 safe zone the launcher masks with. The PNG
  # must be the full canvas size - a smaller bitmap gets scaled up by the
  # launcher, zooming and cropping the logo.
  logo $(( fg * 55 / 100 )) /tmp/fg_$$.png
  convert -size "${fg}x${fg}" xc:none /tmp/fg_$$.png -gravity center \
          -composite -strip "$dir/ic_launcher_foreground.png"
done
rm -f /tmp/lg_$$.png /tmp/rd_$$.png /tmp/fg_$$.png

echo "launcher icons generated"
