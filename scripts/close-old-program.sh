#!/usr/bin/env bash
# Closes the first devnet deployment (8X7ud…), which carries the draw re-roll bug,
# and returns its rent to the deployer. Irreversible: the address can never be reused.
source "$(dirname "$0")/env.sh"
OLD=8X7udAY9fwDU1HY4gWGHvEQahZX6RYfoNYCx8UovxjNQ
solana program close $OLD --bypass-warning 2>&1 | tail -3
solana program show $OLD 2>&1 | head -3
solana balance
