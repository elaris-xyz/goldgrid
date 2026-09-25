#!/usr/bin/env bash
# Builds the Anchor program inside the solana-dev distro.
source /mnt/e/Arash/Code/vamahan/scripts/env.sh
cd /mnt/e/Arash/Code/vamahan
export CARGO_NET_RETRY=10 CARGO_HTTP_TIMEOUT=120
anchor build "$@" 2>&1 | grep -vE "^\s+(Compiling|Downloaded|Downloading)"
