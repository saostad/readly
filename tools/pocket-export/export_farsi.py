#!/usr/bin/env python3
"""Export Pocket TTS Farsi v1/v2 to a single quantized ONNX file and check it.

The upstream pocket-tts-onnx exporter builds models by config *name*
(``TTSModel.load_model(language=...)``). The Farsi models only exist as
``hf://`` configs, so this script monkey-patches ``load_model`` to route an
``hf://`` language string to ``config=`` and to bake the Farsi-verified
generation settings (temperature 0.3, eos_threshold -2.0) into the ONNX
metadata. The Kotlin engine enforces the same values at runtime.

v2 shares v1's architecture; it differs in weights, the phoneme tokenizer
(`tokenizer_ph.model`) and three text flags (no capitalisation, terminal
punctuation or space padding). v2 never sees Persian script: the check runs
the reference v2 pipeline (normalize -> sentences -> torch G2P -> phonemes).

Usage (from repo root):
    uv run --project tools/pocket-export python export_farsi.py [--variant v1|v2]
    uv run --project tools/pocket-export python export_farsi.py --check [--variant v2]

Voice: pass --voice <wav> (the owner's chosen clip, at most 5 s is used:
the training window). Without --voice, v1 uses the HF `example_voice.wav`
and v2 uses the reference Voice A clip. --samples writes the check sentences
as wavs to out/samples/ (v2: out/samples/v2/) for listening.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REF_V2 = HERE / "../../.agents/handoff/ref/v2"
VOICE_DIR = HERE / "voice"
CONFIGS = {
    "v1": "hf://mehdi-hf/pocket-tts-farsi/farsi.yaml",
    "v2": "hf://mehdi-hf/pocket-tts-farsi-v2/model.yaml",
}
OUTS = {
    "v1": HERE / "out" / "pocket-farsi-v1.onnx",
    "v2": HERE / "out" / "pocket-farsi-v2.onnx",
}
TEMPERATURE = 0.3
EOS_THRESHOLD = -2.0
G2P_MODEL_ID = "mehdi-hf/Homo-GE2PE-Persian-HF"

# Check sentences: short, medium, and number/punctuation heavy. These stand
# in for the private sentences.tsv, which is not on this machine; results on
# the real file are UNKNOWN until it is provided.
CHECK_TEXTS = [
    "سلام، حال شما چطور است؟",
    "امروز هوای تهران آفتابی است و دمای هوا بیست و پنج درجه است.",
    "قیمت این کتاب صد و بیست هزار تومان است و در سال هزار و چهارصد و دو چاپ شده است.",
    "او گفت: «فردا ساعت نه و نیم صبح جلسه داریم؛ لطفاً دیر نکنید!»",
]


def ensure_voice(variant: str, given: str | None) -> Path:
    if given:
        path = Path(given).expanduser().resolve()
        if not path.is_file():
            raise SystemExit(f"FAIL: voice file not found: {path}")
        return path
    if variant == "v2":
        # Voice A, the same clip as v1 (checked in with the v2 reference).
        path = REF_V2 / "voice-a.wav"
        if not path.is_file():
            raise SystemExit(f"FAIL: v2 reference voice not found: {path}")
        return path
    VOICE_DIR.mkdir(parents=True, exist_ok=True)
    dest = VOICE_DIR / "example_voice.wav"
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    from huggingface_hub import hf_hub_download

    path = hf_hub_download(
        repo_id="mehdi-hf/pocket-tts-farsi", filename="example_voice.wav"
    )
    dest.write_bytes(Path(path).read_bytes())
    return dest


def export(variant: str, voice: Path) -> Path:
    from pocket_tts import TTSModel

    original = TTSModel.load_model.__func__

    def patched(cls, language=None, config=None, **kwargs):
        if isinstance(language, str) and language.startswith("hf://"):
            kwargs.setdefault("temp", TEMPERATURE)
            kwargs.setdefault("eos_threshold", EOS_THRESHOLD)
            return original(cls, config=language, **kwargs)
        return original(cls, language=language, config=config, **kwargs)

    TTSModel.load_model = classmethod(patched)  # type: ignore[method-assign]

    import pocket_tts_onnx.export.export as exporter

    original_meta = exporter.build_metadata

    def patched_meta(tts_model, *args, **kwargs):
        # The stock metadata carries pad_with_spaces_for_short_inputs but not
        # the other two text flags; the Kotlin engine picks its front end
        # from all three, so record them for both variants.
        meta = original_meta(tts_model, *args, **kwargs)
        config = json.loads(meta["pocket_tts_config"])
        config["capitalize_first_letter"] = tts_model.capitalize_first_letter
        config["append_terminal_punctuation"] = (
            tts_model.append_terminal_punctuation
        )
        meta["pocket_tts_config"] = json.dumps(config)
        return meta

    exporter.build_metadata = patched_meta  # type: ignore[method-assign]

    out = OUTS[variant]
    out.parent.mkdir(parents=True, exist_ok=True)
    exporter.export_model(
        out,
        language=CONFIGS[variant],
        voices=[str(voice)],
        voice_seconds=5.0,
        quantize=True,
    )
    return out


def check(
    variant: str, onnx_path: Path, voice: Path, write_samples: bool = False
) -> int:
    """Compare the ONNX runtime against the upstream torch model.

    Prints per-sentence token counts and durations for both paths. The flow
    sampler is chaotic, so waveforms are never compared sample-by-sample;
    what matters is matching tokenization, similar duration, and no runaway
    (duration over the `tokens / 3.0 + 2.0` s cap scaled by 0.97).
    """
    failures = 0

    import pocket_tts_onnx.tts as onnx_tts_module
    from pocket_tts_onnx import PocketTTS

    if not hasattr(onnx_tts_module._Config, "capitalize_first_letter"):
        # The stock runtime predates the two text flags this script records;
        # teach its config dataclass to carry them (v1 defaults: true).
        import dataclasses

        old = onnx_tts_module._Config
        onnx_tts_module._Config = dataclasses.make_dataclass(
            "_Config",
            [(f.name, f.type, f.default) for f in dataclasses.fields(old)]
            + [
                ("capitalize_first_letter", bool, True),
                ("append_terminal_punctuation", bool, True),
            ],
        )

    onnx_tts = PocketTTS(str(onnx_path))
    voices = onnx_tts.voices()
    print(f"ONNX voices: {voices}, sample_rate={onnx_tts.sample_rate}")
    if not voices:
        print("FAIL: exported file carries no baked voice")
        return 1
    voice_name = voices[0]

    from pocket_tts import TTSModel

    model = TTSModel.load_model(
        config=CONFIGS[variant], temp=TEMPERATURE, eos_threshold=EOS_THRESHOLD
    )
    state = model.get_state_for_audio_prompt(str(voice))
    sp = model.flow_lm.conditioner.tokenizer.sp

    if variant == "v2":
        return check_v2(onnx_tts, voice_name, model, state, sp, write_samples)

    for text in CHECK_TEXTS:
        tokens = len(sp.encode(text))
        cap = (tokens / 3.0 + 2.0) * 0.97

        import numpy as np

        ref = np.asarray(
            model.generate_audio(
                state, text, frames_after_eos=0, copy_state=True
            ),
            dtype=np.float32,
        ).reshape(-1)
        ref_sec = len(ref) / model.sample_rate

        samples, rate = onnx_tts.create(
            text, voice=voice_name, temperature=TEMPERATURE
        )
        onnx_sec = len(samples) / rate
        if write_samples:
            import soundfile as sf

            out_dir = onnx_path.parent / "samples"
            out_dir.mkdir(parents=True, exist_ok=True)
            n = CHECK_TEXTS.index(text) + 1
            sf.write(out_dir / f"{n:02d}-onnx.wav", samples, rate)
            sf.write(out_dir / f"{n:02d}-torch.wav", ref, model.sample_rate)

        # Tokenization inside the ONNX file must match upstream exactly.
        onnx_tokens = len(onnx_tts.tokenizer.encode(text))
        same_tokens = onnx_tokens == tokens
        runaway = onnx_sec > cap or ref_sec > cap
        # Durations should be in the same ballpark; sampling noise moves the
        # EOS frame, so allow 35% either way.
        similar = abs(onnx_sec - ref_sec) <= max(0.35 * ref_sec, 0.3)
        ok = same_tokens and similar and not runaway
        failures += 0 if ok else 1
        print(
            f"{'OK ' if ok else 'FAIL'} tokens ref={tokens} onnx={onnx_tokens} "
            f"dur ref={ref_sec:.2f}s onnx={onnx_sec:.2f}s cap={cap:.2f}s "
            f"text={text[:40]}"
        )

    meta = json.loads(
        onnx_tts.session.get_modelmeta().custom_metadata_map["pocket_tts_config"]
    )
    print(
        "metadata: "
        f"sample_rate={meta['sample_rate']} temperature={meta['temperature']} "
        f"eos_threshold={meta['eos_threshold']} "
        f"frames_after_eos={meta['frames_after_eos']} quantized={meta['quantized']}"
    )
    if meta["sample_rate"] != 24000:
        print("FAIL: sample_rate is not 24000")
        failures += 1
    print(f"file size: {onnx_path.stat().st_size / 1e6:.1f} MB")
    return 1 if failures else 0


def check_v2(onnx_tts, voice_name: str, model, state, sp, write_samples: bool) -> int:
    """v2 check: the reference phoneme pipeline through torch and ONNX.

    Each check text goes through `normalize_for_model` -> HARD_BREAK split ->
    torch beam-search G2P -> `translate_phonemes` -> `strip_ezafe`, exactly
    like `.agents/handoff/ref/v2/synth.py`; both runtimes synthesize the
    spoken phonemes and the per-text audio is compared like v1.
    """
    sys.path.insert(0, str(REF_V2))
    import normalize_fa_v2
    import synthesize
    from pocket_helpers import clean_sentence_for_g2p, translate_phonemes

    import numpy as np

    failures = 0

    import torch
    from transformers import AutoTokenizer, T5ForConditionalGeneration

    print(f"loading {G2P_MODEL_ID} (torch G2P for the check)")
    g2p_tokenizer = AutoTokenizer.from_pretrained(G2P_MODEL_ID)
    g2p = T5ForConditionalGeneration.from_pretrained(G2P_MODEL_ID)
    g2p.eval()

    def phonemise(sentence: str) -> str:
        inputs = g2p_tokenizer(
            clean_sentence_for_g2p(sentence),
            return_tensors="pt",
            add_special_tokens=False,
        )
        with torch.no_grad():
            output = g2p.generate(
                **inputs, num_beams=5, max_length=512, early_stopping=True
            )
        return translate_phonemes(
            g2p_tokenizer.decode(output[0], skip_special_tokens=True)
        )

    # The ONNX runtime's English text prep would capitalize and punctuate;
    # v2 phonemes take whitespace tidying only (all three flags are false).
    import pocket_tts_onnx.tts as onnx_tts_module

    original_prepare = onnx_tts_module.prepare_text_prompt

    def phoneme_prepare(text, pad_with_spaces_for_short_inputs, remove_semicolons):
        text = text.strip().replace("\n", " ").replace("\r", " ")
        while "  " in text:
            text = text.replace("  ", " ")
        if text == "":
            raise ValueError("Text prompt cannot be empty")
        return text, 0

    onnx_tts_module.prepare_text_prompt = phoneme_prepare
    try:
        for n, text in enumerate(CHECK_TEXTS, 1):
            normalized = normalize_fa_v2.normalize_for_model(text)
            spoken = [
                synthesize.strip_ezafe(phonemise(s).strip())
                for s in synthesize.HARD_BREAK.split(normalized)
                if s.strip()
            ]
            spoken = [s for s in spoken if s]
            if not spoken:
                print(f"FAIL no phonemes for text: {text[:40]}")
                return failures + 1

            tokens = sum(len(sp.encode(s)) for s in spoken)
            cap = (tokens / 3.0 + 2.0) * 0.97

            pause = np.zeros(int(0.15 * model.sample_rate), dtype=np.float32)
            ref_parts, onnx_parts = [], []
            for s in spoken:
                ref_parts.append(
                    np.asarray(
                        model.generate_audio(
                            state, s, frames_after_eos=0, copy_state=True
                        ),
                        dtype=np.float32,
                    ).reshape(-1)
                )
                onnx_samples, rate = onnx_tts.create(
                    s, voice=voice_name, temperature=TEMPERATURE
                )
                onnx_parts.append(np.asarray(onnx_samples, dtype=np.float32))
            ref = np.concatenate(
                [p for part in ref_parts for p in (part, pause)][:-1]
            )
            samples = np.concatenate(
                [p for part in onnx_parts for p in (part, pause)][:-1]
            )
            ref_sec = len(ref) / model.sample_rate
            onnx_sec = len(samples) / rate
            if write_samples:
                import soundfile as sf

                out_dir = OUTS["v2"].parent / "samples" / "v2"
                out_dir.mkdir(parents=True, exist_ok=True)
                sf.write(out_dir / f"{n:02d}-onnx.wav", samples, rate)
                sf.write(out_dir / f"{n:02d}-torch.wav", ref, model.sample_rate)

            onnx_tokens = sum(
                len(onnx_tts.tokenizer.encode(s)) for s in spoken
            )
            same_tokens = onnx_tokens == tokens
            runaway = onnx_sec > cap or ref_sec > cap
            similar = abs(onnx_sec - ref_sec) <= max(0.35 * ref_sec, 0.3)
            ok = same_tokens and similar and not runaway
            failures += 0 if ok else 1
            print(
                f"{'OK ' if ok else 'FAIL'} tokens ref={tokens} onnx={onnx_tokens} "
                f"dur ref={ref_sec:.2f}s onnx={onnx_sec:.2f}s cap={cap:.2f}s "
                f"text={text[:40]}"
            )
    finally:
        onnx_tts_module.prepare_text_prompt = original_prepare

    meta = json.loads(
        onnx_tts.session.get_modelmeta().custom_metadata_map["pocket_tts_config"]
    )
    print(
        "metadata: "
        f"sample_rate={meta['sample_rate']} temperature={meta['temperature']} "
        f"eos_threshold={meta['eos_threshold']} "
        f"frames_after_eos={meta['frames_after_eos']} quantized={meta['quantized']} "
        f"capitalize_first_letter={meta.get('capitalize_first_letter')} "
        f"append_terminal_punctuation={meta.get('append_terminal_punctuation')} "
        f"pad_with_spaces_for_short_inputs={meta.get('pad_with_spaces_for_short_inputs')}"
    )
    if meta["sample_rate"] != 24000:
        print("FAIL: sample_rate is not 24000")
        failures += 1
    for flag in (
        "capitalize_first_letter",
        "append_terminal_punctuation",
        "pad_with_spaces_for_short_inputs",
    ):
        if meta.get(flag) is not False:
            print(f"FAIL: v2 metadata flag {flag} is {meta.get(flag)!r}, want False")
            failures += 1
    # The baked tokenizer must be the phoneme one, byte for byte.
    from pocket_tts.utils.utils import download_if_necessary

    want_tok = Path(
        download_if_necessary(
            "hf://mehdi-hf/pocket-tts-farsi-v2/tokenizer_ph.model"
        )
    ).read_bytes()
    import base64

    got_tok = base64.b64decode(
        onnx_tts.session.get_modelmeta()
        .custom_metadata_map["pocket_tts_tokenizer"]
    )
    if got_tok != want_tok:
        print("FAIL: baked tokenizer is not tokenizer_ph.model")
        failures += 1
    onnx_path = OUTS["v2"]
    print(f"file size: {onnx_path.stat().st_size / 1e6:.1f} MB")
    import hashlib

    print(f"sha256: {hashlib.sha256(onnx_path.read_bytes()).hexdigest()}")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=("v1", "v2"), default="v1")
    parser.add_argument("--check", action="store_true", help="verify after export (also runs without re-export if the file exists with --check-only)")
    parser.add_argument("--check-only", action="store_true", help="only verify the existing export")
    parser.add_argument("--voice", help="voice prompt wav to bake in (at most 5 s is used)")
    parser.add_argument("--samples", action="store_true", help="with --check: write ONNX and torch wavs to out/samples/ (v2: out/samples/v2/)")
    args = parser.parse_args()

    out = OUTS[args.variant]
    voice = ensure_voice(args.variant, args.voice)
    print(f"voice prompt: {voice}")
    if not args.check_only:
        export(args.variant, voice)
        print(f"wrote {out}")
    if args.check or args.check_only:
        if not out.exists():
            print(f"FAIL: {out} does not exist; export first", file=sys.stderr)
            return 1
        return check(args.variant, out, voice, args.samples)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
