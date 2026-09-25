#!/usr/bin/env bash
# Deploys (or upgrades) daily_draw on devnet. The upload is ~360 transactions;
# on a flaky link a failed run leaves a buffer, whose rent is recovered below.
source /mnt/e/Arash/Code/vamahan/scripts/env.sh
cd /mnt/e/Arash/Code/vamahan
solana balance
solana program deploy target/deploy/daily_draw.so \
  --program-id target/deploy/daily_draw-keypair.json \
  --max-sign-attempts 60 --with-compute-unit-price 1000 2>&1 | tail -5
solana program show 8X7udAY9fwDU1HY4gWGHvEQahZX6RYfoNYCx8UovxjNQ 2>&1 | head -8
solana program close --buffers 2>&1 | tail -2
solana balance
