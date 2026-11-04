# Stuff Reader

Android app that reads a text file (e.g. `stuff.txt`) aloud, line after line, rotating through different female text-to-speech voices.

- Open the file from the app, or via "Open with" / Share from a file manager
- Remembers the file and position; Play/Stop, previous/next, restart, repeat
- Voice list: female voices pre-ticked (detected from Google/Samsung voice names); long-press to preview
- If no voice can be identified as female, all voices are used with a raised pitch

## Build

Needs Java and Ubuntu's Android packages (no Gradle / Android Studio):

```bash
sudo apt-get install android-sdk-platform-23 aapt apksigner dalvik-exchange zipalign
./build.sh
```

Output: `StuffReader.apk`. `debug.keystore` (password `android`) is committed so rebuilt APKs keep the same signature and install as upgrades.
