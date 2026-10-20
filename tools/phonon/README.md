# Phonon English voice

Phonon is a small English text-to-speech model from
[Gradium](https://github.com/gradium-ai/xn-ptts). It runs on the phone's CPU and is
about 4× faster than real time on a Pixel 11 Pro. Readly uses it as an
**experimental** voice: you install it by hand, and it is not offered for
download in the app.

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

## Copy the model to the phone

Install a **debug** build first (a release build does not allow `run-as`). Then
download these files from
[huggingface.co/gradium/phonon-31m-en](https://huggingface.co/gradium/phonon-31m-en)
at commit `52433c46975bfa13144c56204c9f4ee1c5b148aa`:

- `config.json`
- `model.q8.gguf`
- `tokenizer.json`

Put them in a folder called `phonon-31m-en`, and add a `voices` folder with the
six voices. Each voice comes from
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
