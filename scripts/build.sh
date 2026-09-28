#!/usr/bin/env bash
# Builds the Anchor program inside the solana-dev distro.
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."
export CARGO_NET_RETRY=10 CARGO_HTTP_TIMEOUT=120
anchor build "$@" 2>&1 | grep -vE "^\s+(Compiling|Downloaded|Downloading)"
