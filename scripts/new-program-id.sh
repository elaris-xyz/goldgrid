#!/usr/bin/env bash
# Gives the program a fresh address: the audit fixes change the Round layout,
# so old devnet accounts cannot be read by the new code. The old keypair stays
# backed up under keys/.
set -e
source /mnt/e/Arash/Code/vamahan/scripts/env.sh
cd /mnt/e/Arash/Code/vamahan
solana-keygen new --no-bip39-passphrase --silent --force -o target/deploy/daily_draw-keypair.json >/dev/null
anchor keys sync | tail -2
cp target/deploy/daily_draw-keypair.json keys/daily_draw-program-keypair-v2.json
anchor keys list
