#!/usr/bin/env bash
# Resumable download of Solana platform-tools (the SBF compiler). cargo-build-sbf
# fetches it in one shot and gives up when the connection drops, which it does here.
set -u
VER="${1:-v1.51.1}"
URL="https://github.com/anza-xyz/platform-tools/releases/download/$VER/platform-tools-linux-x86_64.tar.bz2"
OUT="/root/downloads/platform-tools-$VER.tar.bz2"
DEST="/root/.cache/solana/$VER/platform-tools"
mkdir -p /root/downloads
for i in $(seq 1 60); do
  curl -sSL --retry 5 --retry-delay 3 -C - -o "$OUT" "$URL" && break
  echo "attempt $i interrupted at $(du -h "$OUT" 2>/dev/null | cut -f1), resuming"
  sleep 3
done
tar -tjf "$OUT" >/dev/null || { echo "archive incomplete"; exit 1; }
rm -rf "$DEST" && mkdir -p "$DEST" && tar -xjf "$OUT" -C "$DEST" && echo "platform-tools $VER ready in $DEST"
