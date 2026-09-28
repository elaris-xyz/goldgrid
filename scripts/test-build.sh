#!/usr/bin/env bash
# Runs the program's native tests, then the SBF build (whose size decides whether the
# deployed program account must be extended before an upgrade).
source /mnt/e/Arash/Code/vamahan/scripts/env.sh
cd /mnt/e/Arash/Code/vamahan
CARGO_TARGET_DIR=/root/target/vamahan cargo test -p daily_draw --lib 2>&1 | grep -E "test result|FAILED|panicked|error" | head
bash scripts/build.sh | tail -5
ls -l target/deploy/daily_draw.so
