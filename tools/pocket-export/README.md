# Pocket TTS Farsi export (build tooling, not shipped in the app)

Exports mehdi-hf/pocket-tts-farsi (v1) to one quantized ONNX file with
[pocket-tts-onnx](https://github.com/thewh1teagle/pocket-tts-onnx), with the
chosen voice baked in (at most 5 s of it).

    uv run python export_farsi.py --voice /path/to/ref.wav --check --samples

Output: `out/pocket-farsi-v1.onnx` (~230 MB, gitignored) and, with
`--samples`, `out/samples/NN-onnx.wav` / `NN-torch.wav` to compare by ear.
Adapted from smart-voices PR #4.
