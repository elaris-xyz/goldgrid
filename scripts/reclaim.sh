#!/usr/bin/env bash
# Frees re-downloadable space inside solana-dev: the unused v1.51.1 toolchain
# (only the Anchor 1.2 shim wanted it), downloaded archives, the duplicate
# native-test target dir, and a failed agave-install leftover.
du -sh /root/.local/share/* 2>/dev/null
rm -rf /root/.cache/solana/v1.51.1 /root/downloads/* /root/target/vamahan
rm -rf /root/.local/share/solana/install/releases 2>/dev/null
fstrim -av 2>&1 | tail -1
df -h / | tail -1
