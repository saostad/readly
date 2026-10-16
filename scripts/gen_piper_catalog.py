#!/usr/bin/env python3
"""Regenerate the Piper voice catalog from the sherpa-onnx tts-models release.

Reads every `vits-piper-*.tar.bz2` asset (minus `-fp16`, which failed to load
on-device) from the GitHub API and writes `app/src/main/assets/piper_voices.json`.

Usage (from the repo root):
    python3 scripts/gen_piper_catalog.py

Needs network access to api.github.com and github.com. Stdlib only. Set
GITHUB_TOKEN to raise the API rate limit (60 requests/hour without one; this
script makes two).

Hash sources: the API `digest` field is authoritative (checksum.txt in the
release is stale for ~50 re-uploaded assets, verified by cross-check). Assets
uploaded before GitHub tracked digests (9 of them, e.g. the Persian
`fa-haaniye_low` voice) fall back to checksum.txt.
"""

import json
import os
import re
import sys
import urllib.request

API_URL = "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/tts-models"
CHECKSUM_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/checksum.txt"
)
OUT_PATH = os.path.join("app", "src", "main", "assets", "piper_voices.json")

QUALITIES = ("x_low", "low", "medium", "high")
QUALITY_RANK = {q: i for i, q in enumerate(QUALITIES)}
# x_low must come before low: "…-google-x_low" ends with both "-x_low" and "_low".
QUALITY_SUFFIXES = ("-x_low", "_x_low", "-low", "_low", "-medium", "_medium", "-high", "_high")
LANG_RE = re.compile(r"^[a-z]{2,3}(?:_[A-Za-z]{2})?$")


def parse(base):
    """Split `lang-voice-quality` (quality joined by `-`, or `_` in old names).

    Returns (lang, voice, quality) or None when there is no standard quality.
    """
    for suffix in QUALITY_SUFFIXES:
        if base.endswith(suffix):
            quality = suffix[1:]
            rest = base[: -len(suffix)]
            if "-" not in rest:
                return None
            lang, voice = rest.split("-", 1)
            if LANG_RE.match(lang) and voice:
                return lang, voice, quality
            return None
    return None


def fetch(url, timeout=120):
    headers = {"User-Agent": "readly-catalog-gen", "Accept": "application/vnd.github+json"}
    token = os.environ.get("GITHUB_TOKEN")
    if token and "api.github.com" in url:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def main():
    release = json.loads(fetch(API_URL))
    checksums = {}
    for line in fetch(CHECKSUM_URL).decode("utf-8").splitlines():
        parts = line.split()
        if len(parts) == 2:
            checksums[parts[0]] = parts[1]

    entries = []
    skipped_fp16 = 0
    skipped_unparseable = []
    fallback_used = []
    for asset in release.get("assets", []):
        name = asset.get("name", "")
        if not (name.startswith("vits-piper-") and name.endswith(".tar.bz2")):
            continue
        if "-fp16" in name:
            skipped_fp16 += 1
            continue
        digest = asset.get("digest") or ""
        sha = digest.split(":", 1)[1] if digest.startswith("sha256:") else ""
        if not sha:
            sha = checksums.get(name, "")
            if sha:
                fallback_used.append(name)
        if not re.fullmatch(r"[0-9a-f]{64}", sha):
            sys.exit("error: no usable sha256 for " + name)

        base = name[len("vits-piper-"):-len(".tar.bz2")]
        compact = base.endswith("-int8")
        if compact:
            base = base[: -len("-int8")]
        parsed = parse(base)
        if parsed is None:
            skipped_unparseable.append(name)
            continue
        lang, voice, quality = parsed
        top_dir = name[: -len(".tar.bz2")]
        model_file = top_dir[len("vits-piper-"):]
        if model_file.endswith("-int8"):
            model_file = model_file[: -len("-int8")]
        model_file += ".onnx"
        entries.append(
            {
                "id": "piper-%s-%s-%s%s"
                % (lang, voice, quality, "-compact" if compact else ""),
                "archive": name,
                "size": asset["size"],
                "sha256": sha,
                "topDir": top_dir,
                "modelFile": model_file,
                "lang": lang,
                "voice": voice,
                "quality": quality,
                "compact": compact,
            }
        )

    # Legacy duplicates (same id, e.g. an underscore-joined re-upload): keep the
    # API-digested one. Sort fallback entries last so dedup prefers the API hash.
    by_id = {}
    skipped_dup = []
    for e in sorted(entries, key=lambda e: e["archive"] in fallback_used):
        if e["id"] in by_id:
            skipped_dup.append(e["archive"])
            continue
        by_id[e["id"]] = e
    entries = sorted(
        by_id.values(),
        key=lambda e: (
            e["lang"].lower(),
            e["voice"].lower(),
            QUALITY_RANK[e["quality"]],
            e["compact"],
            e["id"],
        ),
    )

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(entries, f, indent=2, ensure_ascii=False)
        f.write("\n")

    langs = sorted({e["lang"] for e in entries})
    print("wrote %s: %d entries, %d languages" % (OUT_PATH, len(entries), len(langs)))
    print("skipped fp16: %d" % skipped_fp16)
    for n in skipped_unparseable:
        print("skipped (no standard quality in name): " + n)
    for n in skipped_dup:
        print("skipped (legacy duplicate id): " + n)
    for n in fallback_used:
        if n not in skipped_unparseable and n not in skipped_dup:
            print("sha256 from checksum.txt (no API digest): " + n)


if __name__ == "__main__":
    main()
