#!/usr/bin/env bash
# Resumable install of the Agave (Solana) CLI: the one-shot installer dies when
# the connection drops mid-download, which it does here.
set -u
URL="https://release.anza.xyz/stable/solana-release-x86_64-unknown-linux-gnu.tar.bz2"
OUT=/root/downloads/solana-release.tar.bz2
mkdir -p /root/downloads
for i in $(seq 1 30); do
  curl -sSL --retry 5 --retry-delay 3 -C - -o "$OUT" "$URL" && break
  echo "attempt $i interrupted, resuming ($(du -h "$OUT" 2>/dev/null | cut -f1))"
  sleep 3
done
tar -tjf "$OUT" >/dev/null || { echo "archive incomplete"; exit 1; }
rm -rf /opt/solana-release && tar -xjf "$OUT" -C /opt && echo "extracted to /opt/solana-release"
