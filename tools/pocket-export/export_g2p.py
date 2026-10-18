#!/usr/bin/env python3
"""Export Homo-GE2PE-Persian-HF (ByT5 G2P) to encoder + decoder ONNX.

The G2P turns one cleaned Persian sentence into a phoneme string for Pocket
TTS Farsi v2. This script exports the torch model as two plain graphs with NO
KV cache (every decode step feeds the full decoder prefix; the model is 8.3 M
params, so a step is milliseconds): `out/ge2pe/encoder.onnx` and
`out/ge2pe/decoder.onnx`. Both are weight-only int8 (per-channel int8
weights, fp32 compute) when the check still passes on the quantized files,
otherwise the fp32 files ship.

Inputs are never padded (one sentence per call), so neither graph takes an
attention mask. ByT5 byte ids: UTF-8 byte b -> id b + 3; 0 = pad (also the
decoder start token), 1 = EOS, 2 = unk; 259..383 are sentinels.

Usage (from repo root):
    uv run --project tools/pocket-export python tools/pocket-export/export_g2p.py
    uv run --project tools/pocket-export python tools/pocket-export/export_g2p.py --check

--check runs a numpy beam search over the exported files (the same algorithm
the Kotlin G2p runs: width 5, max 512, early stopping, length penalty 1.0)
and requires byte-identical strings to torch `generate` on all 53 sentences.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
OUT_DIR = HERE / "out" / "ge2pe"
FP32_DIR = HERE / "out" / "ge2pe-fp32"
SENTENCES = HERE / "../../.agents/handoff/ref/v2/sentences.txt"
G2P_MODEL_ID = "mehdi-hf/Homo-GE2PE-Persian-HF"

PAD_ID = 0
EOS_ID = 1
BYTE_OFFSET = 3
NUM_BEAMS = 5
MAX_LENGTH = 512
LENGTH_PENALTY = 1.0
OPSET = 17

GOLDEN = HERE / "../../app/src/test/resources/pocket/v2-golden.jsonl"


def encode_bytes(text: str) -> list[int]:
    return [b + BYTE_OFFSET for b in text.encode("utf-8")]


def decode_bytes(ids: list[int]) -> str:
    return bytes(i - BYTE_OFFSET for i in ids if 3 <= i <= 258).decode("utf-8", errors="ignore")


def export() -> tuple[Path, Path]:
    import torch
    from transformers import T5ForConditionalGeneration

    print(f"loading {G2P_MODEL_ID}")
    model = T5ForConditionalGeneration.from_pretrained(G2P_MODEL_ID)
    model.eval()
    # Eager attention decomposes to plain MatMul/Add/Softmax, which traces.
    # The causal mask is built explicitly in DecoderWrapper (below): the
    # mask helpers return None and rely on SDPA's is_causal, which the trace
    # decomposes into unmasked attention.
    model.config._attn_implementation = "eager"

    class EncoderWrapper(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.encoder = m.encoder

        def forward(self, input_ids):
            return self.encoder(input_ids=input_ids, return_dict=False)[0]

    class DecoderWrapper(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.decoder = m.decoder
            self.lm_head = m.lm_head

        def forward(self, decoder_input_ids, encoder_hidden_states):
            # Explicit 4D causal mask from dynamic shape ops (a 4D mask is
            # passed through untouched, so the tracer cannot drop it).
            seq = decoder_input_ids.shape[1]
            pos = torch.arange(seq, device=decoder_input_ids.device)
            causal = pos[:, None] >= pos[None, :]
            mask = torch.zeros(
                1, 1, seq, seq,
                dtype=torch.float32,
                device=decoder_input_ids.device,
            ).masked_fill(~causal, torch.finfo(torch.float32).min)
            hidden = self.decoder(
                input_ids=decoder_input_ids,
                attention_mask=mask,
                encoder_hidden_states=encoder_hidden_states,
                use_cache=False,
                return_dict=False,
            )[0]
            return self.lm_head(hidden)

    FP32_DIR.mkdir(parents=True, exist_ok=True)
    enc_path = FP32_DIR / "encoder.onnx"
    dec_path = FP32_DIR / "decoder.onnx"
    with torch.no_grad():
        input_ids = torch.ones(1, 8, dtype=torch.long)
        torch.onnx.export(
            EncoderWrapper(model),
            (input_ids,),
            str(enc_path),
            input_names=["input_ids"],
            output_names=["last_hidden_state"],
            dynamic_axes={
                "input_ids": {0: "batch", 1: "seq"},
                "last_hidden_state": {0: "batch", 1: "seq"},
            },
            opset_version=OPSET,
            dynamo=False,
        )
        print(f"wrote {enc_path} ({enc_path.stat().st_size / 1e6:.1f} MB)")
        hidden = torch.zeros(1, 16, model.config.d_model)
        torch.onnx.export(
            DecoderWrapper(model),
            (torch.ones(1, 4, dtype=torch.long), hidden),
            str(dec_path),
            input_names=["decoder_input_ids", "encoder_hidden_states"],
            output_names=["logits"],
            dynamic_axes={
                "decoder_input_ids": {0: "batch", 1: "seq"},
                "encoder_hidden_states": {0: "batch", 1: "enc_seq"},
                "logits": {0: "batch", 1: "seq"},
            },
            opset_version=OPSET,
            dynamo=False,
        )
        print(f"wrote {dec_path} ({dec_path.stat().st_size / 1e6:.1f} MB)")
    return enc_path, dec_path


def weight_only_int8(src: Path, dst: Path, exclude: set[str]) -> None:
    """Per-channel int8 weights with fp32 compute (MatMul stays fp32).

    Dynamic quantization (int8 activations) flips near-tie beam decisions on
    8 of the 53 sentences, and static is worse; the beam search amplifies even
    0.1-nat perturbations over 50 steps. Rounding only the weights keeps the
    compute exact, so all rows survive. Nodes in `exclude` keep fp32 weights.
    """
    import onnx
    from onnx import helper, numpy_helper

    model = onnx.load(str(src))
    graph = model.graph
    inits = {i.name: i for i in graph.initializer}
    new_nodes = []
    for node in list(graph.node):
        if node.op_type != "MatMul" or len(node.input) < 2:
            continue
        w_name = node.input[1]
        if w_name not in inits:
            continue  # activation-activation MatMul (QK^T, A@V): untouched
        if node.name in exclude:
            continue
        w = numpy_helper.to_array(inits[w_name])
        if w.ndim != 2:
            continue
        amax = np.abs(w).max(axis=0)
        scale = np.where(amax == 0, 1.0, amax / 127.0).astype(np.float32)
        q = np.clip(np.round(w / scale), -128, 127).astype(np.int8)
        q_name, s_name, z_name = w_name + "_wq8", w_name + "_ws8", w_name + "_wz8"
        graph.initializer.append(numpy_helper.from_array(q, q_name))
        graph.initializer.append(numpy_helper.from_array(scale, s_name))
        graph.initializer.append(
            numpy_helper.from_array(np.zeros_like(scale, dtype=np.int8), z_name)
        )
        dq_out = w_name + "_dq8"
        new_nodes.append(
            helper.make_node(
                "DequantizeLinear", [q_name, s_name, z_name], [dq_out],
                name=w_name + "/DequantizeLinear", axis=1,
            )
        )
        node.input[1] = dq_out
        graph.initializer.remove(inits[w_name])
    graph.node.extend(new_nodes)
    onnx.save(model, str(dst))
    print(f"wrote {dst} ({dst.stat().st_size / 1e6:.1f} MB)")


# MatMul nodes that keep fp32 weights. Empty: weight-only int8 matches torch
# on all 55 golden sentences with no exclusions (dynamic int8 activations
# flipped 8 rows, so compute stays fp32).
QUANT_EXCLUDE: set[str] = set()


def quantize(enc_fp32: Path, dec_fp32: Path, exclude: set[str]) -> tuple[Path, Path]:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    enc_out, dec_out = OUT_DIR / "encoder.onnx", OUT_DIR / "decoder.onnx"
    weight_only_int8(enc_fp32, enc_out, exclude)
    weight_only_int8(dec_fp32, dec_out, exclude)
    return enc_out, dec_out


class OnnxG2p:
    """Numpy beam search over the exported encoder/decoder.

    A line-by-line port of `GenerationMixin._beam_search` in transformers
    5.20.1 for batch size 1, `num_beams=5`, `max_length=512`,
    `early_stopping=True`, `length_penalty=1.0`, `do_sample=False`, one EOS
    token. The Kotlin G2p ports this again; the three must agree exactly.
    """

    def __init__(self, encoder_path: Path, decoder_path: Path):
        import onnxruntime as ort

        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        self.encoder = ort.InferenceSession(str(encoder_path), options)
        self.decoder = ort.InferenceSession(str(decoder_path), options)

    def generate(self, text: str) -> str:
        ids = encode_bytes(text)
        enc = self.encoder.run(
            None, {"input_ids": np.asarray([ids], dtype=np.int64)}
        )[0]
        enc5 = np.repeat(enc, NUM_BEAMS, axis=0)
        # Running beams all have length cur_len, so one batched decoder call
        # per step works (no padding, no mask).
        running = [[PAD_ID] for _ in range(NUM_BEAMS)]
        running_scores = [0.0] + [-1e9] * (NUM_BEAMS - 1)
        finalized: list[tuple[list[int], float, bool]] = [
            ([PAD_ID], -1e9, False) for _ in range(NUM_BEAMS)
        ]
        cur_len = 1
        beams_to_keep = 2 * NUM_BEAMS
        heuristic_ok = True
        while True:
            batch = np.asarray(running, dtype=np.int64)
            logits = self.decoder.run(
                None,
                {
                    "decoder_input_ids": batch,
                    "encoder_hidden_states": enc5,
                },
            )[0][:, -1, :]
            shifted = logits - logits.max(axis=1, keepdims=True)
            log_probs = shifted - np.log(np.exp(shifted).sum(axis=1, keepdims=True))
            flat = (log_probs + np.asarray(running_scores)[:, None]).reshape(-1)
            vocab = log_probs.shape[1]
            # Stable top-K: score desc, flat index asc (HF torch.topk order).
            topk = sorted(range(flat.size), key=lambda i: (-flat[i], i))[:beams_to_keep]
            topk_scores = [float(flat[i]) for i in topk]
            topk_seqs = [running[i // vocab] + [i % vocab] for i in topk]
            hits_stop = [
                (s[-1] == EOS_ID) or (len(s) >= MAX_LENGTH) for s in topk_seqs
            ]
            # Running beams for the next step: best non-finished top-K.
            masked = [
                s if not hit else s - 1.0e9
                for s, hit in zip(topk_scores, hits_stop)
            ]
            order = sorted(range(beams_to_keep), key=lambda i: (-masked[i], i))[:NUM_BEAMS]
            running = [topk_seqs[i] for i in order]
            running_scores = [masked[i] for i in order]
            # Finalize: finished beams among the top num_beams only, merged
            # with the previous finalized set, best num_beams kept. Ties keep
            # the earlier entry (HF merges old-then-new and top-Ks).
            gen_len = cur_len + 1 - 1  # tokens now, prompt len 1
            new = []
            for i in range(beams_to_keep):
                score = topk_scores[i] / (gen_len**LENGTH_PENALTY)
                if all(done for _, _, done in finalized):
                    score = -1.0e9  # early stopping: beams are full
                if not heuristic_ok:
                    score = -1.0e9
                just_done = hits_stop[i] and i < NUM_BEAMS
                if not just_done:
                    score = -1.0e9
                new.append((topk_seqs[i], score, just_done))
            merged = list(finalized) + new
            order = sorted(range(len(merged)), key=lambda i: (-merged[i][1], i))
            finalized = [merged[i] for i in order[:NUM_BEAMS]]
            cur_len += 1
            # Early-stop heuristic (HF _check_early_stop_heuristic, lp=1.0):
            # the best running score, length-penalized at the hypothetical
            # best length, can still beat the worst finished beam.
            hypo_len = cur_len - 1
            best_running = running_scores[0] / (hypo_len**LENGTH_PENALTY)
            worst_done = min(
                (s for _, s, done in finalized if done), default=-1e9
            )
            heuristic_ok = heuristic_ok and (best_running > worst_done)
            improvement = heuristic_ok
            open_beam = not all(done for _, _, done in finalized)
            valid = not all(hits_stop)
            if not (improvement and open_beam and valid):
                break
        best = sorted(finalized, key=lambda e: -e[1])[0][0]
        if EOS_ID in best:
            best = best[: best.index(EOS_ID)]
        return decode_bytes([t for t in best if t != PAD_ID])


def check(enc_path: Path, dec_path: Path, torch_ref=None) -> int:
    """ONNX beam search must match torch generate on every golden sentence.

    Inputs come from the committed golden file (the exact cleans the Kotlin
    test uses): normalized text, split into sentences — the only inputs the
    G2P ever sees. Live torch also has to agree with the golden's `g2p_raw`,
    so a stale golden fails loudly instead of hiding drift.
    """
    import json

    import torch

    if not GOLDEN.exists():
        print(f"FAIL: {GOLDEN} is missing; run make_v2_golden.py first")
        return 1
    rows = [json.loads(line) for line in GOLDEN.read_text(encoding="utf-8").splitlines() if line.strip()]
    cleans = [
        (f"{i + 1:02d}" + (f".{j + 1}" if len(row["sentences"]) > 1 else ""), s["clean"], s["g2p_raw"])
        for i, row in enumerate(rows)
        for j, s in enumerate(row["sentences"])
    ]
    if torch_ref is None:
        from transformers import AutoTokenizer, T5ForConditionalGeneration

        print(f"loading {G2P_MODEL_ID} (torch reference)")
        tokenizer = AutoTokenizer.from_pretrained(G2P_MODEL_ID)
        model = T5ForConditionalGeneration.from_pretrained(G2P_MODEL_ID)
        model.eval()
    else:
        tokenizer, model = torch_ref
    print(f"loading ONNX {enc_path.parent}")
    g2p = OnnxG2p(enc_path, dec_path)
    failures = 0
    total_ms = 0.0
    for label, cleaned, golden_raw in cleans:
        inputs = tokenizer(cleaned, return_tensors="pt", add_special_tokens=False)
        with torch.no_grad():
            output = model.generate(
                **inputs, num_beams=NUM_BEAMS, max_length=MAX_LENGTH, early_stopping=True
            )
        want = tokenizer.decode(output[0], skip_special_tokens=True)
        if want != golden_raw:
            print(f"FAIL {label} {cleaned[:48]!r}")
            print(f"  golden is stale: torch now says {want!r}, golden has {golden_raw!r}")
            return 1
        start = time.perf_counter()
        got = g2p.generate(cleaned)
        total_ms += (time.perf_counter() - start) * 1000.0
        ok = got == want
        failures += 0 if ok else 1
        print(f"{'OK ' if ok else 'FAIL'} {label} {cleaned[:48]!r}")
        if not ok:
            print(f"  torch: {want!r}")
            print(f"  onnx:  {got!r}")
    print(f"{len(cleans) - failures}/{len(cleans)} match; "
          f"onnx {total_ms / max(1, len(cleans)):.0f} ms/sentence")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify after export")
    parser.add_argument("--check-only", action="store_true", help="only verify the existing export")
    parser.add_argument("--fp32", action="store_true", help="ship fp32 even if int8 passes")
    parser.add_argument("--quant-only", action="store_true", help="only quantize the existing fp32 export and check it")
    parser.add_argument("--exclude", default="", help="comma-separated node-name substrings to keep fp32 (bisection aid; default: QUANT_EXCLUDE)")
    args = parser.parse_args()

    exclude = QUANT_EXCLUDE
    if args.exclude:
        import onnx

        substrs = [s for s in args.exclude.split(",") if s]
        exclude = set()
        for f in (FP32_DIR / "encoder.onnx", FP32_DIR / "decoder.onnx"):
            for n in onnx.load(str(f)).graph.node:
                if n.op_type == "MatMul" and any(s in n.name for s in substrs):
                    exclude.add(n.name)
        print(f"excluding {len(exclude)} nodes: {sorted(exclude)}")

    if not args.check_only and not args.quant_only:
        enc_fp32, dec_fp32 = export()
        print("checking fp32 export against torch...")
        if check(enc_fp32, dec_fp32) != 0:
            print("FAIL: fp32 export does not match torch; not quantizing")
            return 1
    else:
        enc_fp32, dec_fp32 = FP32_DIR / "encoder.onnx", FP32_DIR / "decoder.onnx"
    if not args.check_only:
        if args.fp32:
            OUT_DIR.mkdir(parents=True, exist_ok=True)
            (OUT_DIR / "encoder.onnx").write_bytes(enc_fp32.read_bytes())
            (OUT_DIR / "decoder.onnx").write_bytes(dec_fp32.read_bytes())
            print("shipped fp32 (--fp32)")
        else:
            enc_int8, dec_int8 = quantize(enc_fp32, dec_fp32, exclude)
            print("checking int8 export against torch...")
            if check(enc_int8, dec_int8) != 0:
                print("int8 does not match torch on every sentence; shipping fp32")
                (OUT_DIR / "encoder.onnx").write_bytes(enc_fp32.read_bytes())
                (OUT_DIR / "decoder.onnx").write_bytes(dec_fp32.read_bytes())
            else:
                print("int8 matches torch on every sentence; shipped int8")
    if args.check or args.check_only:
        enc = OUT_DIR / "encoder.onnx"
        dec = OUT_DIR / "decoder.onnx"
        if not enc.exists() or not dec.exists():
            print(f"FAIL: {OUT_DIR} does not hold encoder.onnx + decoder.onnx; export first")
            return 1
        return check(enc, dec)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
