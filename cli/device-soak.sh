#!/usr/bin/env bash
# Soak test on a USB-connected phone: the app is checked against the chain through
# a cold start, normal running across round boundaries, a trip to the background,
# and a force-stop. Each step prints timer-check's verdict.
set -u
cd "$(dirname "$0")"
ADB=/e/Android/sdk/platform-tools/adb.exe
PKG=com.vamahan.dailydraw
launch() { $ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
top() { for i in 1 2 3 4 5 6; do $ADB shell input swipe 876 700 876 2400 150; done; }
step() { echo "== $1"; timeout 900 node timer-check.mjs "$2" 2>&1 | grep -v bigint | tee -a /e/Android/soak-samples.log | tail -1; }

$ADB shell input keyevent KEYCODE_WAKEUP
$ADB shell am force-stop $PKG; launch; sleep 8; top
step "cold start, running across a round boundary (6 min)" 55
$ADB shell input keyevent KEYCODE_HOME; sleep 90; launch; sleep 3; top
step "back from 90 s in the background" 12
$ADB shell am force-stop $PKG; sleep 5; launch; sleep 8; top
step "after a force-stop and relaunch" 12
$ADB shell input keyevent KEYCODE_HOME; sleep 330; launch; sleep 3; top
step "back from 5.5 min in the background (a whole round missed)" 12
for i in 1 2 3 4 5 6 7 8; do $ADB shell input swipe 876 2200 876 700 250; done
step "ticket list (any Draw delayed?)" 3
