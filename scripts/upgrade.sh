#!/usr/bin/env bash
# Upgrades the live program in place. A bigger build first needs the program data
# account extended; the upload then goes through deploy.sh's resumable buffer.
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."
PROGRAM=$(solana address -k target/deploy/daily_draw-keypair.json)
have=$(solana program show "$PROGRAM" | awk '/Data Length/ {print $3}')
need=$(stat -c %s target/deploy/daily_draw.so)
echo "program data $have bytes, build $need bytes"
solana balance
if [ "$need" -gt "$have" ]; then
  solana program extend "$PROGRAM" $((need - have + 4096)) 2>&1 | tail -1
fi
bash scripts/deploy.sh
