#!/usr/bin/env bash
# Backs up the program keypair to E: (target/ lives on the Linux side) and
# installs the devnet deployer wallet.
set -e
cp /mnt/e/Arash/Code/vamahan/target/deploy/daily_draw-keypair.json /mnt/e/Arash/Code/vamahan/keys/daily_draw-program-keypair.json
mkdir -p /root/.config/solana
cp /mnt/e/Arash/Code/vamahan/spikes/switchboard-devnet/payer.json /root/.config/solana/id.json
source /mnt/e/Arash/Code/vamahan/scripts/env.sh
solana config set --url https://api.devnet.solana.com >/dev/null
solana address
solana balance
