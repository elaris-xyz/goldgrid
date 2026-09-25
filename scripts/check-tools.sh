#!/usr/bin/env bash
# Reports whether a platform-tools version is fully installed and runnable.
d="/root/.cache/solana/${1:-v1.54}/platform-tools"
cat "$d/version.md" 2>/dev/null | head -3
ls -la "$d"
"$d/rust/bin/rustc" --version
"$d/llvm/bin/clang" --version | head -1
du -sh "$d"
