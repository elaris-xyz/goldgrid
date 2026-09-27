#!/usr/bin/env bash
# Sends test SOL from the deployer to a player's wallet (devnet only).
source /mnt/e/Arash/Code/vamahan/scripts/env.sh
solana transfer "$1" "${2:-0.1}" --allow-unfunded-recipient --with-compute-unit-price 1000 2>&1 | tail -2
solana balance "$1"
