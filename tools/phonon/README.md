# Phonon English voice

Phonon is a small English text-to-speech model from
[Gradium](https://github.com/gradium-ai/xn-ptts). It runs on the phone's CPU and is
about 4× faster than real time on a Pixel 11 Pro. Readly uses it as an
**experimental** voice. The app downloads it for you: in **Voices**, pick
**Phonon** and download the 53 MB pack. It is not part of the first-launch
download. This page covers rebuilding the native library and installing a model
by hand.

Licences: the xn-ptts code is MIT or Apache-2.0. The
[`phonon-31m-en`](https://huggingface.co/gradium/phonon-31m-en) weights and the
[`phonon-voices`](https://huggingface.co/gradium/phonon-voices) voices are
Apache-2.0.

## Build the native library

You need the Rust Android target, `cargo-ndk` and an Android NDK:

```sh
rustup target add aarch64-linux-android
cargo install cargo-ndk
sdkmanager "ndk;30.0.16248370"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
```

Then run the script from the repository root (it clones the pinned xn-ptts
commit into a temp dir, builds, and copies `libptts_ffi.so` to
`app/src/main/jniLibs/arm64-v8a/`):

```sh
tools/phonon/build-android-lib.sh
```

To build from an existing checkout instead, set `XN_PTTS_DIR` to its path. The
build takes about 10 minutes. The `.so` is git-ignored: every checkout builds
its own.

## Install by hand (optional, for testing a new model)

The app normally downloads the pack, so this is only for a model you built or
copied yourself. Install a **debug** build first (a release build does not allow
`run-as`). Then download these files from
[huggingface.co/gradium/phonon-31m-en](https://huggingface.co/gradium/phonon-31m-en)
at commit `52433c46975bfa13144c56204c9f4ee1c5b148aa`:

- `config.json`
- `model.q8.gguf`
- `tokenizer.json`

Put them in a folder called `phonon-31m-en`, and add a `voices` folder with the
eight voices. Each voice comes from
`https://huggingface.co/gradium/phonon-voices/resolve/main/voices/<id>.safetensors`,
saved as `voices/<name>.safetensors`:

| Voice | Id |
|---|---|
| zoey | `NbpkqMVS3CJeq2j8` |
| everly | `vjFyp2BcCyTlUmYb` |
| easton | `LMDIYE0Oa377Qh6Z` |
| micah | `40QrMVgAxFiUnP9f` |
| freya | `GgfEkEJtxZR7gnpy` |
| alfie | `X6NceqF285Gr04gb` |
| hadley | `bXwrkHhURL1VFJdG` |
| preston | `qIxlZ7Ei8KvWlyQH` |

So the folder looks like this:

```
phonon-31m-en/
  config.json
  model.q8.gguf
  tokenizer.json
  voices/zoey.safetensors
  voices/everly.safetensors
  voices/easton.safetensors
  voices/micah.safetensors
  voices/freya.safetensors
  voices/alfie.safetensors
  voices/hadley.safetensors
  voices/preston.safetensors
```

Push it into the app's no-backup folder:

```sh
adb push phonon-31m-en /data/local/tmp/
adb shell run-as app.readly mkdir -p no_backup/voices
adb shell run-as app.readly cp -r /data/local/tmp/phonon-31m-en no_backup/voices/
adb shell run-as app.readly touch no_backup/voices/phonon-31m-en/.complete
```

The last command marks the pack as installed; without it Readly treats the
files as a half-copied download. Restart Readly, then pick **Phonon 31m** under
Voices → Phonon.

## How the hosted archive was made

Readly downloads
`https://huggingface.co/saostad/readly-voices/resolve/main/phonon-31m-en/phonon-31m-en.tar.bz2`
(52,850,882 bytes, SHA-256
`a7afb1bb47bf99b233875795bacb43b9911c88f8c9e7e5f3f3692769df46dae0`). It is a
normal tar.bz2 with a single top dir, `phonon-31m-en/`, holding the files
listed above plus:

- `LICENSE`: the Apache-2.0 text.
- `README.md`: the sources (the `gradium/phonon-31m-en` commit and the
  `phonon-voices` files listed above).

Reproducible build from a folder that already has those files:

```sh
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='2026-10-20 00:00Z' \
    -cjf phonon-31m-en.tar.bz2 phonon-31m-en
```
