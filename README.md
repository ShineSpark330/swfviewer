# SWF Asset Manager (Stages 1–2)

Android app for browsing and previewing the contents of Flash `.swf` files.

**Stage 1 – parser + asset browser**
- Opens `.swf` from storage (or "Open with" from a file manager). Plain (`FWS`) and zlib-compressed (`CWS`) files.
  LZMA (`ZWS`) files show a clear "not supported yet" message.
- Lists assets grouped by type with filter chips + search (name, `#id`): images, shapes, sprites, sounds,
  fonts, text, buttons, morph shapes, binary data, scripts, video. Names come from SymbolClass/ExportAssets.
- "Raw tag list" shows every top-level tag in file order.

**Stage 2 – preview**
- Images: lossless (8/15/32-bit, with alpha), JPEG, JPEG + alpha, JPEG with shared tables. Pinch-zoom, pan,
  double-tap to reset, crisp pixels when zoomed.
- Shapes: solid, linear/radial gradient and bitmap fills (tiled or clamped), curves, holes, shared edges, lines.
- Sprites and the whole movie ("Play movie" in the menu): frame player with play/pause + scrubber, nested
  clips loop on their own frame count, mask layers (clipDepth) are applied, colour transforms supported.
- Sounds: MP3, PCM and ADPCM play through `MediaPlayer`. Nellymoser/Speex show an "unsupported" message.

**Not done yet (on purpose):** text/font/button/morph-shape rendering, ActionScript, filters/blend modes,
frame scripts (so interactive movies only play their timeline), import/export/delete (Stage 3).

## Layout
- `swfcore/` – pure Java, no Android dependencies: parser, shape/image/sound decoders, timeline, renderer.
- `app/` – Android UI (Java, AppCompat + Material 3). `AndroidSurface` draws shapes on a `Canvas`.
- `tools/` – `make_test_swf.py` builds a synthetic SWF covering every feature; `DesktopCheck.java` dumps the
  asset list and renders PNG/WAV/MP3 via Java2D; `Fuzz.java` feeds corrupted files to the parser;
  `check.sh` runs all of it. `tools/sample/` has the generated test files (open them on the phone to try the app).

## Build
Open this folder in Android Studio (Koala or newer) and run the `app` configuration, or:
`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
Requires JDK 17 and the Android SDK (compileSdk 34, minSdk 24).

## Get the APK from GitHub (no Android Studio needed)
1. Push this folder to a GitHub repository.
2. Open the **Actions** tab → **Build APK** → latest run (or **Run workflow**).
3. Download the `SwfAssetManager-debug` artifact, unzip it, and install `app-debug.apk` on your phone.
