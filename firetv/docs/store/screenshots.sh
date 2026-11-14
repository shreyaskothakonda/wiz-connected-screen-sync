#!/usr/bin/env bash
# Takes 1920x1080 PNG screenshots of the app on the Fire TV Stick over ADB, into this folder.
#
# Usage:  ./screenshots.sh <stick-ip> [name]
#   With a name, takes one shot:  ./screenshots.sh 192.168.1.20 02-bulbs-found
#   Without, walks you through the full list below, waiting for Enter before each shot.
#
# Screens to capture (Amazon wants 3 to 10, landscape 1920x1080, no transparency):
#   01-home-ready        App open, bulbs listed, Instant preset selected, Start button focused.
#   02-find-bulbs        Right after pressing Find bulbs, "Found N bulbs" in the status line.
#   03-syncing           While syncing (Start pressed, consent accepted), status swatch showing a colour.
#   04-preset-movie      Movie preset selected, its description and brightness floor visible.
#   05-brightness        Brightness row focused, mid-way value.
#   06-bulb-menu         Identify / Rename / Remove menu open on one bulb.
#   07-no-picture        (optional) A protected-content app playing: status "No picture", shows the limitation honestly.
#
# Tips: hide any bulb names you do not want public (Rename first). Fire OS draws the consent dialog
# with the system theme; take 03 after dismissing it. screencap captures the app's own UI, not protected video.
set -euo pipefail
cd "$(dirname "$0")"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ip="${1:?usage: $0 <stick-ip> [name]}"
"$ADB" connect "$ip:5555" >/dev/null

shoot() {
  local name="$1"
  "$ADB" exec-out screencap -p > "screenshot-$name.png"
  # Fire TV Stick output is already 1920x1080; sips reports the size so a mismatch is obvious.
  sips -g pixelWidth -g pixelHeight "screenshot-$name.png" | tail -2 | tr '\n' ' '
  echo " -> screenshot-$name.png"
}

if [ $# -ge 2 ]; then shoot "$2"; exit 0; fi

for name in 01-home-ready 02-find-bulbs 03-syncing 04-preset-movie 05-brightness 06-bulb-menu 07-no-picture; do
  read -r -p "Set up '$name' on the TV, then press Enter (s to skip): " ans
  [ "$ans" = "s" ] && continue
  shoot "$name"
done
echo "Done. Review the PNGs, then upload them as Fire TV screenshots (and reuse them as tablet screenshots)."
