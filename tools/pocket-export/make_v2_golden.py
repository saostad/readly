#!/usr/bin/env python3
"""Write the v2 text-front-end golden file from the reference pipeline.

For each sentence in `.agents/handoff/ref/v2/sentences.txt`, runs the exact
v2 reference path (`normalize_for_model` -> HARD_BREAK split ->
`clean_sentence_for_g2p` -> torch beam-search G2P -> `translate_phonemes`)
and writes one JSON row per test sentence to
`app/src/test/resources/pocket/v2-golden.jsonl`:

    {"text": ..., "normalized": ..., "sentences": [
      {"clean": ..., "g2p_raw": ..., "phonemes": ...}]}

The Kotlin v2 front end and G2P tests compare against this file. Run with the
reference env (torch + transformers + pocket-tts), from the repo root:

    ../farsi-voice-eval/.venvs/tts-pocket/bin/python tools/pocket-export/make_v2_golden.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REF = HERE / "../../.agents/handoff/ref/v2"
OUT = HERE / "../../app/src/test/resources/pocket/v2-golden.jsonl"
G2P_MODEL_ID = "mehdi-hf/Homo-GE2PE-Persian-HF"

sys.path.insert(0, str(REF))

import normalize_fa_v2  # noqa: E402
import synthesize  # noqa: E402
from pocket_helpers import (  # noqa: E402
    clean_sentence_for_g2p,
    translate_phonemes,
)


def main() -> int:
    import torch
    from transformers import AutoTokenizer, T5ForConditionalGeneration

    sentences = (REF / "sentences.txt").read_text(encoding="utf-8").splitlines()
    print(f"loading {G2P_MODEL_ID}")
    tokenizer = AutoTokenizer.from_pretrained(G2P_MODEL_ID)
    model = T5ForConditionalGeneration.from_pretrained(G2P_MODEL_ID)
    model.eval()

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8") as handle:
        for text in sentences:
            normalized = normalize_fa_v2.normalize_for_model(text)
            parts = [
                s.strip()
                for s in synthesize.HARD_BREAK.split(normalized)
                if s.strip()
            ]
            if not parts:
                print(f"FAIL: no sentences after split: {text[:60]!r}")
                return 1
            row = {"text": text, "normalized": normalized, "sentences": []}
            for sentence in parts:
                cleaned = clean_sentence_for_g2p(sentence)
                inputs = tokenizer(
                    cleaned, return_tensors="pt", add_special_tokens=False
                )
                with torch.no_grad():
                    output = model.generate(
                        **inputs,
                        num_beams=5,
                        max_length=512,
                        early_stopping=True,
                    )
                raw = tokenizer.decode(output[0], skip_special_tokens=True)
                if not raw.strip():
                    print(f"FAIL: G2P returned no phonemes for: {sentence[:60]!r}")
                    return 1
                row["sentences"].append(
                    {
                        "clean": cleaned,
                        "g2p_raw": raw,
                        "phonemes": translate_phonemes(raw),
                    }
                )
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"wrote {OUT} ({len(sentences)} rows)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
