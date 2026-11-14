#!/usr/bin/env bash
# Regenerates the Appstore icon assets in this folder from the in-app bulb drawable.
# Output: icon-512.png, icon-114.png (tablet icons), firetv-icon-1280x720.png, firetv-background-1920x1080.png.
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck disable=SC1091
source ../../jdk.sh
java -Djava.awt.headless=true MakeIcons.java ../../app/src/main/res/drawable/ic_bulb.xml .
ls -la *.png
