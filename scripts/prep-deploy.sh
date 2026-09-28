#!/usr/bin/env bash
# Backs up the program keypair to E: (target/ lives on the Linux side) and
# installs the devnet deployer wallet.
set -e
cp "$(dirname "$0")"/../target/deploy/daily_draw-keypair.json "$(dirname "$0")"/../keys/daily_draw-program-keypair.json
mkdir -p /root/.config/solana
cp "$(dirname "$0")"/../spikes/switchboard-devnet/payer.json /root/.config/solana/id.json
source "$(dirname "$0")/env.sh"
solana config set --url https://api.devnet.solana.com >/dev/null
solana address
solana balance
