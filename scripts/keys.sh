#!/usr/bin/env bash
# Shows build outputs and syncs the program id into lib.rs and Anchor.toml.
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."
ls -la target/deploy/ target/idl/
anchor keys sync
anchor keys list
