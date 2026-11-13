<img src="assets/icon.svg" width="64" height="64" alt="">

# Readly

Read articles, shared text, and files aloud on Android with on-device voices.
Readly supports English, Persian, and other downloadable voices, integrates with
Android Auto, and can serve as your phone's text-to-speech engine. After downloading
a voice, speech generation runs offline on your phone.

[Download the latest APK](https://github.com/saostad/readly/releases/latest) ·
[Build from source](#build) · [MIT license](LICENSE)

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

## Releases

Every push to `main` runs `.github/workflows/release.yml`: unit tests, lint, then
a GitHub release `vX.Y.Z` with `readly-vX.Y.Z.apk` attached. If anything fails,
no release is made. `scripts/next_version.sh` picks the version from the commits
since the last tag: `feat:` bumps the minor number, `type!:` or a
`BREAKING CHANGE` footer bumps the major, and anything else bumps the patch.
The versionCode is `major*1000000 + minor*1000 + patch`.

The release APK is signed with the same debug key as local builds (the
`DEBUG_KEYSTORE_BASE64` secret; the `SIGNING_CERT_SHA256` repository variable is
checked against the APK), so releases and local builds install over each other.
Local builds have versionCode 1, so installing one over a release needs
`adb install -r -d` (allow a downgrade).

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

In Readly, tap **Open file** on the home screen to choose a PDF, photo,
screenshot, text, Markdown, or HTML file in Android's file browser. The file
starts reading with the same OCR settings, limits, and playback controls used
for shared files. Cancelling the browser leaves the current reading alone.

In any app, tap **Share** and choose **Readly**, or select some text and choose
**Read aloud**. Readly accepts links, plain text, Markdown and HTML, including
shared `.md`, `.html`, `.txt`, and `.pdf` files, photos, and screenshots.

On first launch Readly downloads the Kokoro voice (a one-time 320 MB
download); afterwards everything runs on the phone and reading works offline.

Other voices can be downloaded from **Voices**, including Phonon, an
experimental English voice that is about 3.5× faster than real time on a Pixel
11 Pro (53 MB download). To build its native library, see
`tools/phonon/README.md`.

Before anything is spoken, Readly cleans it up: Markdown and HTML formatting,
links and bare URLs, footnotes, tables (read as comma-separated cells), code
blocks (replaced with "Code block skipped.") and emoji are removed, so only
readable prose reaches the voice. Readly reads only the text you'd see on the
page: picture descriptions (alt text) and text meant only for screen readers
are skipped unless you turn on **Read image descriptions** in **Voice & speed**. Shared links open as extracted articles; if
no main article is found, Readly says so and reads the whole page instead.

English text is then read the way a person says it, on every English voice:
numbers, dates, times, money, units, codes, phone numbers, Roman numerals and
symbols become words ("Order #4471 ships 14/03" is read "Order number four
four seven one ships the fourteenth of March"). The screen keeps the text as
written. In **Voice & speed** you can turn this off (**Read numbers and
symbols**), read ambiguous dates like 09/10 day first (**Dates day first**; a
date like 14/03 elsewhere in the text decides on its own), and turn on
**Respell hard names**, which reads names and foreign words from a built-in
list (Siobhan as "Shivawn"; off by default because it can hurt names a voice
already says well). If a rule can't run on some text, Readly reads that part as
written and says so.

The rules are the smart-voices normalizer (`app/.../text/EnglishNormalizer.kt`,
a port of Underdog Woof's `normalize.py` and `respell.py`, Underdog
Non-Commercial License 1.0, Copyright 2026 Conway). `EnglishNormalizerTest`
checks it against the same 531-line golden file smart-voices uses
(`app/src/test/resources/normalize_golden.json`); the name list is
`app/src/main/resources/app/readly/text/respell.tsv`.

Playback runs in the background with notification, lock-screen and headset
controls (previous/next sentence, pause/play, stop). The voice and speed picked
in the app apply from the next sentence.

### Photos and screenshots

Take a photo with your camera, or take a screenshot, then use **Share → Readly**
from your gallery or screenshot preview, or choose the picture with **Open file**
inside Readly. Readly recognizes the text on your phone
and starts reading it with your usual playback controls. No camera permission or
upload is needed. Share one still picture at a time. JPEG, PNG, static WebP, and
HEIC/AVIF images that your Android device can decode are supported; animated
images are rejected with an explanation.

Camera rotation and mirroring metadata are applied before OCR. Large images are
decoded at a bounded size, and transparent areas are placed on white. For tiny text
in a long scrolling screenshot, crop to the relevant section or increase the OCR
resolution in **Voice & speed → Documents & OCR**. The default picture layout is
**Automatic layout**. Choose **Scattered text / signs** for isolated labels,
**Text block** for a dense screenshot, or **Single line** for one line. Layout and language
settings apply to new imports. OCR is an approximation: blur, glare, perspective,
handwriting, and complex layouts can cause mistakes. An image with no recognized
text shows an error instead of starting empty playback.

Pictures use the same language settings and private saved-document history as
PDFs below. Turning off scanned-PDF OCR does not disable explicitly shared pictures.

### PDF textbooks and documents

Tap **Open file** in Readly and choose a PDF, or share a PDF file from your file
manager or PDF app and choose **Readly**. Readly
copies it into private app storage and extracts text from every page in
order, with progress and a Cancel button, then reads it with the usual voice and
playback controls. Extraction runs on the phone, without uploading the document.

Selectable-text PDFs use their text layer. Scanned pages are recognized on the
phone with Tesseract OCR; English and Persian models are bundled, so OCR needs no
upload, account, or separate download. Readly shows which pages used OCR and warns
that recognition can make mistakes. It reports pages with no readable text and
fails explicitly if an entire document is unreadable. Password-protected PDFs must
be unlocked before sharing. Complex columns, tables, equations, unusual fonts, and
poor scans can affect reading order or recognition accuracy.

In **Voice & speed → Documents & OCR**, adjust the file-size limit (default 200 MB)
and extracted-text limit (default 2,000,000 characters). OCR is on by default;
automatic language selection first recognizes each scanned page or picture with
both models, then repeats OCR with Persian if Persian/Arabic-script letters
outnumber other letters, or English otherwise. This script heuristic can misidentify
short or blurry text and does not distinguish Persian from other Arabic-script
languages. You can instead choose English, Persian, or both for mixed-language
pages, or turn scanned-PDF OCR off. Its page resolution is
adjustable from 800 to 4000 pixels on the longest side (default 2400); larger pages
use more memory and take longer. Imports over a limit fail
with a message; books are never silently truncated. Large PDFs are passed to the
reader by a private file id, so their text does not overflow Android's share-message
size limit. Keep the import screen open until the book is ready; interrupted
imports are marked failed and can be shared again. Source files, page text, OCR
model provenance and processing status are saved privately. Use **Imported documents**
in the same settings section to read a saved book again without repeating OCR, or
**Clear imported documents** to remove saved sources and text. The temporary playback
handoff file is removed after the service loads it.

### Browsing your library

Open file and Paste text stay at the top of the phone screen. The bottom
navigation stays visible while browsing **Websites**, **Latest**, **Library**, or
**Recents**, with the current reading above it. **Library → Documents** lists
imported PDFs and pictures; tap one to read its saved text without repeating OCR.
**Library → Saved articles** contains links saved for later.

Websites use compact rows with small artwork by default. In **Voice & speed**,
turn **Compact website lists** off to return to large tiles. This setting also
requests list or grid presentation in Android Auto; the car host renders those
hints using its own layout. Changing the setting refreshes the browser lists.

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

## Android Auto

Readly shows up in Android Auto as a media app. In **Voice & speed →
Android Auto websites** add news sites or feed addresses (and an optional
name). In the car, open Readly. There are four tabs:

- **Websites**: compact website rows by default ("3 new" counts unheard articles); pick one
  to see its article titles, then tap a title to hear it.
- **Latest**: the newest articles from all your sites, taking turns.
- **Saved**: links you shared on the phone with **Share → Read later**.
- **Recents**: what you listened to, half-finished articles first.

The phone uses the same article lists, heard marks and Shuffle actions, with
fixed bottom navigation for **Websites**, **Latest**, **Library**, and **Recents**.
**Library** contains imported PDFs and pictures as well as **Saved articles**.
**Open file** and **Paste text** stay in the header. **Add website** is a compact
action below the website list; large tiles remain available in settings.

On the phone, each article has a **⋮** menu (or long-press the row): **Mark as
heard / unheard**, **Save to Read later**, and a delete that fits the list:
**Hide article** in a website list or Latest (hidden in the car too), **Remove
from Saved**, or **Remove from Recents** (forgets its listening history). Each
change except saving offers **Undo** at the top of the list.

Every list starts with **Shuffle**, which plays its articles in a random
order. Rows show whether you heard an article ("Heard", "40% heard"). A
half-heard article picks up at the sentence where it stopped, and Android Auto
can offer it as "continue listening" when the car starts. "Hey Google, play *the verge* on Readly" reads that
site's newest article; other words pick the closest title; "play Readly"
resumes a paused reading.

How the titles are found, per site:

1. If the address is an RSS/Atom feed, its items.
2. Otherwise the feed the page advertises (`<link rel="alternate" …>`), then
   `feed`, `rss`, `rss.xml`, `feed.xml` or `atom.xml` under the address.
3. Otherwise the page's own links. Navigation, header, footer and sidebars are
   dropped, and each same-site link is scored on whether it looks like an
   article (headline-length text, a heading around it, a slug, date or ID in
   the path). These lists are marked "Guessed from page links", because a few
   non-articles can slip in.

A section page whose advertised feed covers the whole site can be switched to
**Page links only**. **Preview** shows exactly what the car will list (tap a
title to read it on the phone). **Articles per website** (default 25) and
**Refresh lists after** (default 15 minutes) are set on the same screen. When a
refresh fails, the car shows the older list with an "older list" row that
retries when tapped.

On the car's playback screen, ⏮ / ⏭ move to the previous or next article in
the site's list, and ⏪ / ⏩ move one sentence back or forward. **Play next**
(a checkbox on the websites screen, and an on/off button on the car's
playback screen; off by default) starts the next article when one ends,
until the end of the list. The square next to the title shows the website's
name. The big line shows the sentence being read, with the article title under
it (a checkbox on the websites screen turns this off), and the bar shows how
far through the article you are, estimated from the voice's pace. On the
phone's Now playing screen the same bar shows time played and the article's
length; drag it to jump to another sentence.

The playback screen also has a speed button that shows the current speed and
steps through 1.0×, 1.2×, 1.5× and 1.8× (the line under the sentence confirms
the new speed for a few seconds), and the
car's Up next list (the rest of the list the article came from). On the
websites screen you can turn these on or off:

- **Load the next article early** (on): the next article opens while one
  plays, so it starts at once and still plays without signal. Uses some data.
- **Say the website, title and length** (on): a short English intro before
  each new article. The length uses the voice's pace from earlier readings.
- **Pause during navigation prompts** (on): Readly pauses for Maps directions
  instead of reading quietly under them, and continues afterwards.
- **Start the next unheard article when Android Auto opens Readly** (off).

The websites screen also lists your saved links, with Remove buttons.

If you see the pause button but hear nothing in the car, another app may have
the car's speakers: Readly now asks for them again on every play and says so
on screen when the phone refuses.

Articles picked in the car are read with the same article engine as the phone
(GeckoView with the paywall add-on), running without a screen. Pages that need
a sign-in or a human check can't be completed in the car; if the engine can't
open a page, the quick reader tries it and the reading says so.

Readly isn't installed from the Play Store, so Android Auto hides it until you
enable unknown sources. Open Android Auto's settings and tap **Version** ten
times to unlock developer settings. Then go to ⋮ → **Developer settings** and
turn on **Unknown sources**. To try it without a car, use the Desktop Head
Unit from the Android SDK.

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

## License

Readly's original source code is licensed under the [MIT License](LICENSE).
Third-party code, extensions, models, and voice assets retain their respective
licenses. See the bundled [Bypass Paywalls Clean license](app/src/main/assets/bpc/LICENSE),
the [OCR model license](app/src/main/assets/ocr/LICENSE),
[OCR library notices](app/src/main/assets/ocr/notices/README.txt),
[PDFBox notices](app/src/main/assets/pdfbox/NOTICE.txt), and the
[Phonon documentation](tools/phonon/README.md) for included notices and
licensing information; other voice models remain subject to their providers' terms.
