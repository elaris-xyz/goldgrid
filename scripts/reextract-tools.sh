#!/usr/bin/env bash
# Re-extracts a platform-tools archive that cargo-build-sbf downloaded fully
# but did not finish unpacking.
set -e
v="${1:-v1.54}"
d="/root/.cache/solana/$v/platform-tools"
a="/root/downloads/platform-tools-$v-full.tar.bz2"
mv "$d/platform-tools-linux-x86_64.tar.bz2" "$a"
tar -tjf "$a" >/dev/null && echo "archive ok"
rm -rf "$d" && mkdir -p "$d" && tar -xjf "$a" -C "$d"
"$d/rust/bin/rustc" --version
rm -f "/root/downloads/platform-tools-$v.tar.bz2"
