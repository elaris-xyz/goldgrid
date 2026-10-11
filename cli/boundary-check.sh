#!/usr/bin/env bash
# The case that broke: the app opened (cold) a few seconds before a draw, so its
# first screen straddles the round boundary. Repeats it N times.
#   bash boundary-check.sh [times]
set -u
cd "$(dirname "$0")"
ADB=/e/Android/sdk/platform-tools/adb.exe
PKG=com.vamahan.dailydraw
times=${1:-3}
for n in $(seq 1 "$times"); do
  # seconds until the next draw, from the chain
  left=$(timeout 60 node -e 'import("./lib.mjs").then(async L=>{const k=L.loadKeypair("../keys/crank.json");const {connection,program}=L.connect(k);const [{account:c}]=await program.account.config.all();const now=await L.chainNow(connection);console.log(L.roundTimes(c,L.currentRound(c,now)).draw-now)})' 2>/dev/null | grep -v bigint | tail -1)
  wait=$(( left - 14 )); [ "$wait" -lt 0 ] && wait=$(( wait + 300 ))
  echo "== run $n: relaunching ${wait}s from now, 14 s before a draw"
  sleep "$wait"
  $ADB shell am force-stop $PKG
  $ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 7
  for i in 1 2 3 4 5 6; do $ADB shell input swipe 876 700 876 2400 120; done
  timeout 200 node timer-check.mjs 8 2>&1 | grep -v bigint
done
