#!/usr/bin/env bash
# Rebuilds the sample SWFs, compiles the core + desktop tools, and runs the checks.
# Needs: JDK 8+, python3 with Pillow (pip install pillow), optionally ffmpeg (for a real MP3).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${TMPDIR:-/tmp}/swfmanager-check"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/render"
python3 tools/make_test_swf.py tools/sample
javac -d "$OUT/classes" swfcore/src/main/java/com/swfmanager/core/*.java tools/DesktopCheck.java tools/Fuzz.java tools/EditCheck.java
java -Djava.awt.headless=true -cp "$OUT/classes" DesktopCheck tools/sample/test_cws.swf "$OUT/render"
java -Djava.awt.headless=true -cp "$OUT/classes" Fuzz tools/sample/test_fws.swf 1000
# Editing round trips (add / replace / rename / delete / save). Uses a real MP3 if ffmpeg is around.
if command -v ffmpeg >/dev/null 2>&1; then
  ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=440:duration=1" -ar 44100 -ac 2 -b:a 128k "$OUT/t.mp3"
  MP3="$OUT/t.mp3"
else
  MP3=""
fi
for f in tools/sample/test_fws.swf tools/sample/test_cws.swf; do
  java -Djava.awt.headless=true -cp "$OUT/classes" EditCheck "$f" $MP3
done
echo "Renders written to $OUT/render"
