#!/usr/bin/env bash
# Compares the program on devnet with the local build, byte for byte.
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."
PROGRAM=$(solana address -k target/deploy/daily_draw-keypair.json)
solana program dump "$PROGRAM" /tmp/deployed.so >/dev/null
local_sum=$(sha256sum target/deploy/daily_draw.so | cut -c1-16)
size=$(stat -c %s target/deploy/daily_draw.so)
chain_sum=$(head -c "$size" /tmp/deployed.so | sha256sum | cut -c1-16)
echo "local $local_sum  deployed $chain_sum"
solana program show "$PROGRAM" | grep -E "Last Deployed|Data Length"
