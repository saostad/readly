<img src="assets/icon.svg" width="64" height="64" alt="">

# readly

Created in [T3 Code](https://t3.codes).

## Build

Prerequisites:

- JDK 17 (`/usr/bin/java` on the dev host)
- Android SDK with platform `android-34` and build-tools `36.1.0`; point Gradle
  at it with a `local.properties` file in the repo root (not committed):
  `sdk.dir=/home/saeid/Android/Sdk`

Build a debug APK (downloads the sherpa-onnx AAR on first run):

```sh
./gradlew assembleDebug
```

Install it on a connected device:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For wireless debugging, pair then connect:
`adb pair <ip:port>` then `adb connect <ip:port>`.

## Use

In any app, tap **Share** and choose **Readly**, or select some text and choose
**Read aloud**. Readly accepts links, plain text, Markdown and HTML, including
shared `.md`, `.html` and `.txt` files.

Before anything is spoken, Readly cleans it up: Markdown and HTML formatting,
links and bare URLs, footnotes, tables (read as comma-separated cells), code
blocks (replaced with "Code block skipped.") and emoji are removed, so only
readable prose reaches the voice. Shared links open as extracted articles; if
no main article is found, Readly says so and reads the whole page instead.

Playback runs in the background with notification, lock-screen and headset
controls (previous/next sentence, pause/play, stop). The voice and speed picked
in the app apply from the next sentence.

## Troubleshooting

Watch what Readly is doing with:

```sh
adb logcat -s Readly:*
```

Each spoken sentence logs a line with `rtf=` (synthesis time divided by audio
time). Under 1 means the phone makes speech faster than it plays, so playback
is seamless; over 1 means it can't keep up, so you may hear short gaps between
sentences. An `underrun` warning marks the exact chunk where playback had to
wait for the voice.
