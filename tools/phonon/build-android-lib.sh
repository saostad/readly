#!/usr/bin/env bash
# Builds Phonon's libptts_ffi.so for android arm64 and copies it into the app.
set -euo pipefail

REPO_URL="https://github.com/gradium-ai/xn-ptts"
COMMIT="6f42148"
ANDROID_API=26
ABI="arm64-v8a"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
out_dir="$repo_root/app/src/main/jniLibs/$ABI"

if [[ -n "${XN_PTTS_DIR:-}" ]]; then
    src_dir="$XN_PTTS_DIR"
else
    src_dir="$(mktemp -d)"
    trap 'rm -rf "$src_dir"' EXIT
    echo "Cloning $REPO_URL into $src_dir"
    git clone "$REPO_URL" "$src_dir"
fi

echo "Checking out $COMMIT"
git -C "$src_dir" checkout --detach "$COMMIT"

missing=()
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    missing+=("ANDROID_NDK_HOME (for example export ANDROID_NDK_HOME=\"\$ANDROID_HOME/ndk/30.0.16248370\")")
fi
if ! command -v cargo-ndk >/dev/null 2>&1; then
    missing+=("cargo-ndk (install it with: cargo install cargo-ndk)")
fi
if (( ${#missing[@]} > 0 )); then
    echo "Missing build requirements:" >&2
    for item in "${missing[@]}"; do
        echo "  - $item" >&2
    done
    exit 1
fi

# Same flags as xn-ptts android/build.sh: stripped symbols, 16 KB pages, dotprod/fp16.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C strip=symbols -C link-arg=-Wl,-z,max-page-size=16384 -C target-cpu=generic -C target-feature=+dotprod,+fp16"

cargo ndk -t "$ABI" -P "$ANDROID_API" -o "$src_dir/jniLibs" build --release -p ptts-ffi

mkdir -p "$out_dir"
cp "$src_dir/jniLibs/$ABI/libptts_ffi.so" "$out_dir/libptts_ffi.so"
echo "Wrote $out_dir/libptts_ffi.so"
sha256sum "$out_dir/libptts_ffi.so" 2>/dev/null || true
