<img src="assets/icon.svg" width="64" height="64" alt="">

# readly

Created in [T3 Code](https://t3.codes).

## Build

Prerequisites:

- JDK 17 (`/usr/bin/java` on the dev host)
- Android SDK with platform `android-37` and build-tools `37.0.0` (the pinned
  GeckoView 158 AAR mandates compileSdk 37; target/min stay 34/26); point
  Gradle at it with a `local.properties` file in the repo root (not
  committed): `sdk.dir=/home/saeid/Android/Sdk`

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

## Updating the voice list

The Piper voices (`app/src/main/assets/piper_voices.json`, every
`vits-piper-*.tar.bz2` archive in the sherpa-onnx `tts-models` release except
`-fp16`, which failed to load on-device) are generated from the GitHub API:

```sh
python3 scripts/gen_piper_catalog.py
```

It needs network access (the release listing plus `checksum.txt` for the few
assets uploaded before GitHub tracked digests). Re-run it when new Piper
voices appear; the Kotlin test `PiperCatalogJsonTest` validates the file.

## Use

In any app, tap **Share** and choose **Readly**, or select some text and choose
**Read aloud**. Readly accepts links, plain text, Markdown and HTML, including
shared `.md`, `.html` and `.txt` files.

On first launch Readly downloads the Kokoro voice (a one-time 320 MB
download); afterwards everything runs on the phone and reading works offline.

Before anything is spoken, Readly cleans it up: Markdown and HTML formatting,
links and bare URLs, footnotes, tables (read as comma-separated cells), code
blocks (replaced with "Code block skipped.") and emoji are removed, so only
readable prose reaches the voice. Shared links open as extracted articles; if
no main article is found, Readly says so and reads the whole page instead.

Playback runs in the background with notification, lock-screen and headset
controls (previous/next sentence, pause/play, stop). The voice and speed picked
in the app apply from the next sentence.

## Reading websites

Shared and pasted links open automatically in the article engine: an embedded
GeckoView loads the real Bypass Paywalls Clean add-on (Firefox edition,
bundled byte-identical, never patched), the page processes, and the rendered
article is captured and read aloud — no Open page / Read page taps. The
browser stays visible for genuine sign-in/human-verification only; complete
it if the page asks and the capture retries on its own. The capture helper
talks to the app over one owned message port per page with a fresh request
token per load, so stale snapshots from a previous load are rejected even
when the address is unchanged. If the engine itself fails to start, Reload
retries the setup explicitly (one attempt per tap). There is no parallel
native fetch before the engine (that would read previews and skip the add-on).

When the page is recognizably not a readable article — a subscription
prompt, an access-denied or human-verification page, a login wall, or a page
with no article body — Readly says why instead of reading navigation or a
teaser aloud. Blocked articles can still fail: a wall the engine cannot pass
or publisher-side restrictions end with an explanation and a Reload/Cancel
choice, never a silent fallback. The guard is heuristic (visible page
markers, never mere length), so it cannot prove completeness; an unmarked
teaser is indistinguishable from a short article, and sentence counts never
prove full access.

The engine is an independent session with its own cookies (it does not reuse
other browsers' logins) and opens ordinary HTTPS website addresses,
including cross-domain login flows; unsafe addresses, pop-ups, downloads and
website permissions never open. Engine pins: GeckoView
158.0.20261007201254 (arm64 only, minSdk 26), BPC magnolia@12.34 v4.4.7.2.
The engine makes the APK large (~200 MB debug): the GeckoView native
libraries dominate the size.

Article-extension updates: the engine checks the add-on's own update URL at
most once a day while the article engine is in use (never at app startup
just to check). Settings → Website reading shows the installed extension
version, last extension check, and the full error when a check fails;
**Automatic updates** (default on, shared with the rules below) enables the
daily check, and **Check updates** runs one immediately even when automatic
checks are off (a manual check may start the engine). A failed check keeps
the known-good installed add-on with a visible warning; readings never wait
for a check. A hung check fails on its own after a minute instead of
spinning forever, and every attempt is recorded before the network runs so
restarts never hammer the update server. Separately, Readly keeps a small
imported-rules catalog from
[Bypass Paywalls Clean](https://gitflic.ru/project/magnolia1234/bypass-paywalls-chrome-clean)
(copyright © 2020 magnolia1234, MIT License; the full license ships with the
bundled rules) for native/extraction compatibility only — request headers
and a few declarative page-cleanup operations. Its import counts (domains,
request rules, cleanup rules) describe imported data, never verified access,
and are shown separately from the extension status. Previously downloaded
New York Times rules stay in place until newer compatible rules replace them.
The legacy manual WebView browser remains in the app only as an unused
fallback class; no share, paste, or retry route opens it.

## Use Readly as the phone's voice

Readly can be the phone's default text-to-speech engine, so TalkBack, Maps and
other apps speak with your downloaded voices:

1. Open Settings → System → Languages → Text-to-speech output.
2. Tap Preferred engine and choose **Readly**.
3. Tap the engine's Play sample button to hear it.

On the Voices screen, **System voice** picks which downloaded voice other apps
use, or leave it on Automatic for the fastest voice in each language. Speech
from other apps is spoken as written (only links and emoji are removed); the
app's own cleanup rules for shared text don't apply.

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
