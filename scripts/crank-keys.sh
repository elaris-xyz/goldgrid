#!/usr/bin/env bash
# Creates the crank's own keypair and randomness keypair (once) and funds the crank
# from the deployer. The crank key pays fees only; it holds no authority.
source /mnt/e/Arash/Code/vamahan/scripts/env.sh
cd /mnt/e/Arash/Code/vamahan
for k in crank crank-randomness; do
  [ -f keys/$k.json ] || solana-keygen new --no-bip39-passphrase --silent -o keys/$k.json
done
CRANK=$(solana address -k keys/crank.json)
echo "crank $CRANK  randomness $(solana address -k keys/crank-randomness.json)"
[ -f cli/.randomness-keypair.json ] && mv cli/.randomness-keypair.json keys/deployer-randomness-old.json
cp keys/crank-randomness.json cli/.randomness-keypair.json
solana transfer "$CRANK" "${1:-2}" --allow-unfunded-recipient --with-compute-unit-price 1000 2>&1 | tail -1
solana balance "$CRANK"
