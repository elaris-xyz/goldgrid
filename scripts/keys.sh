#!/usr/bin/env bash
# Shows build outputs and syncs the program id into lib.rs and Anchor.toml.
source /mnt/e/Arash/Code/vamahan/scripts/env.sh
cd /mnt/e/Arash/Code/vamahan
ls -la target/deploy/ target/idl/
anchor keys sync
anchor keys list
