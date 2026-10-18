# Pocket TTS Farsi export (build tooling, not shipped in the app)

Exports mehdi-hf/pocket-tts-farsi (v1) to one quantized ONNX file with
[pocket-tts-onnx](https://github.com/thewh1teagle/pocket-tts-onnx), with the
chosen voice baked in (at most 5 s of it).

    uv run python export_farsi.py --voice /path/to/ref.wav --check --samples

Output: `out/pocket-farsi-v1.onnx` (~230 MB, gitignored) and, with
`--samples`, `out/samples/NN-onnx.wav` / `NN-torch.wav` to compare by ear.
Adapted from smart-voices PR #4.

## Put the voice on a phone

The app downloads `pocket-farsi-v1.onnx` itself from Hugging Face
(`saostad/readly-voices`), like any other voice. For testing a new export,
copy the file by hand instead. From this directory, with a debug build
installed (so `run-as` works):

```
adb push out/pocket-farsi-v1.onnx /data/local/tmp/
adb shell run-as app.readly sh -c 'mkdir -p no_backup/voices/pocket-farsi-v1 && cp /data/local/tmp/pocket-farsi-v1.onnx no_backup/voices/pocket-farsi-v1/ && touch no_backup/voices/pocket-farsi-v1/.complete'
adb shell rm /data/local/tmp/pocket-farsi-v1.onnx
```
