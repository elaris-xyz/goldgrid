#!/usr/bin/env bash
# Deploys (or upgrades) daily_draw on devnet. The upload is ~360 transactions and
# this link drops some of them every run, so the upload goes into ONE buffer whose
# keypair is kept in keys/: every retry skips the chunks already written instead of
# starting over. The buffer is only closed once the program is live.
source /mnt/e/Arash/Code/vamahan/scripts/env.sh
cd /mnt/e/Arash/Code/vamahan
PROGRAM=$(solana address -k target/deploy/daily_draw-keypair.json)
BUFFER=keys/deploy-buffer.json
[ -f "$BUFFER" ] || solana-keygen new --no-bip39-passphrase --silent -o "$BUFFER"
solana balance
for attempt in $(seq 1 ${ATTEMPTS:-12}); do
  echo "== attempt $attempt"
  out=$(solana program deploy target/deploy/daily_draw.so        --program-id target/deploy/daily_draw-keypair.json        --buffer "$BUFFER"        --max-sign-attempts 30 --with-compute-unit-price 1000 2>&1)
  echo "$out" | grep -vE '^\s*$' | tail -3
  if echo "$out" | grep -q "^Program Id: $PROGRAM"; then
    solana program show "$PROGRAM" | head -8
    rm -f "$BUFFER"
    solana balance
    exit 0
  fi
  sleep 5
done
echo "not deployed after $attempt attempts; the buffer is kept for the next run"
solana balance
exit 1
