# Stuff Reader

Android app that reads a text file (e.g. `stuff.txt`) aloud, line after line, rotating through different female text-to-speech voices. Plays forever, starting over when it reaches the end.

- Open the file from the app, or via "Open with" / Share from a file manager
- "Randomize lines" (on by default): a new shuffle every time the app opens and every round; turn it off to read in order (remembers position)
- Remembers the file; Play/Stop, previous/next, restart (reshuffles when randomizing)
- Voice list, grouped by engine (tap a heading to fold it); each voice shows its engine and name. Tap ▶ to preview. Voices come from:
  - **Every installed speech engine** (Google, Samsung, SherpaTTS, RHVoice, eSpeak, …), English voices only; female voices pre-ticked (detected from Google/Samsung voice names)
  - **Piper** neural voices, run on the phone with sherpa-onnx. Ten female English voices, downloaded in the app
    (~20–30 MB each, int8 builds from sherpa-onnx's `tts-models` release). 64-bit ARM phones only.
  - **Cloud voices** with your own API key: Google Cloud Text-to-Speech, OpenAI, ElevenLabs. Female English voices are listed;
    each line's audio is cached on the phone, so it's only paid for once.
- "Get more voices" also links to free engines on the Play Store and to Google's extra voice data
- If no voice can be identified as female, all voices are used with a raised pitch
- Cast to a Chromecast, Google/Nest speaker or Cast-enabled TV on the same Wi-Fi: each line is
  synthesized to a WAV, served from a tiny HTTP server on the phone, and played on the
  Default Media Receiver. Volume keys control the cast device. (Cast protocol implemented
  directly in `CastClient.java`; no Play Services dependency.)

## Build

Needs Java and Ubuntu's Android packages (no Gradle / Android Studio):

```bash
sudo apt-get install android-sdk-platform-23 aapt apksigner dalvik-exchange zipalign
./build.sh
```

The first build downloads the Piper runtime into `third_party/` (not committed): sherpa-onnx 1.13.0 + onnxruntime arm64 libraries
from the `@siteed/sherpa-onnx.rn` npm package (1.13 is the last sherpa-onnx with the espeak-ng phonemizer Piper needs), and Apache
commons-compress for unpacking voice downloads. `src/com/k2fsa/sherpa/onnx/` is a Java mirror of sherpa-onnx's Kotlin TTS API.

Output: `StuffReader.apk`. `debug.keystore` (password `android`) is committed so rebuilt APKs keep the same signature and install as upgrades.
