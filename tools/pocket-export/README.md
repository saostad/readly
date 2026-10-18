# Pocket TTS Farsi export (build tooling, not shipped in the app)

Exports mehdi-hf/pocket-tts-farsi (v1) to one quantized ONNX file with
[pocket-tts-onnx](https://github.com/thewh1teagle/pocket-tts-onnx), with the
chosen voice baked in (at most 5 s of it).

    uv run python export_farsi.py --voice /path/to/ref.wav --check --samples

Output: `out/pocket-farsi-v1.onnx` (~230 MB, gitignored) and, with
`--samples`, `out/samples/NN-onnx.wav` / `NN-torch.wav` to compare by ear.
Adapted from smart-voices PR #4.

## v2 (phoneme model)

Same architecture as v1 with new weights, a phoneme SentencePiece
(`tokenizer_ph.model`) and all three text flags off (no capitalisation,
terminal punctuation or space padding — recorded in the ONNX metadata for
the Kotlin engine). The v2 config metadata carries those flags and the
phoneme tokenizer, byte for byte.

    uv run python export_farsi.py --variant v2 --check --samples

Output: `out/pocket-farsi-v2.onnx` (~230 MB, gitignored); `--samples`
writes `out/samples/v2/NN-onnx.wav` / `NN-torch.wav`. Without `--voice`,
v2 bakes in the reference Voice A clip
(`.agents/handoff/ref/v2/voice-a.wav`). The check runs the reference v2
pipeline (normalize → sentences → torch G2P → phonemes) through both the
torch model and the ONNX file and compares token counts and durations for
the 4 check texts.

## G2P (Persian pronunciation model)

`mehdi-hf/Homo-GE2PE-Persian-HF` (tiny ByT5, 8.3 M params) as plain
encoder + decoder ONNX with no KV cache — every decode step feeds the full
decoder prefix. Beam search (width 5, max 512, early stopping, length
penalty 1.0) lives in the Kotlin `G2p` class; this script's numpy
implementation of the same algorithm is the check.

    uv run python export_g2p.py          # export + quantize + check
    uv run python export_g2p.py --check  # verify out/ge2pe/ against torch

Output: `out/ge2pe/encoder.onnx` + `decoder.onnx` (weight-only int8,
~10 MB together, gitignored). Dynamic int8 activations flipped near-tie
beam decisions on 8 of the 53 test sentences, so only the weights are
int8 and the compute stays fp32. `--check` requires byte-identical
strings to torch `generate` on every golden sentence (55 of them).

## v2 golden file

`make_v2_golden.py` writes `app/src/test/resources/pocket/v2-golden.jsonl`
(one row per test sentence: text, normalized, and per-sentence clean /
torch `g2p_raw` / translated phonemes) using the reference v2 code and
torch beam search. It runs in the reference env, from the repo root:

    ../farsi-voice-eval/.venvs/tts-pocket/bin/python tools/pocket-export/make_v2_golden.py

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
