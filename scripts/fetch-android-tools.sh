#!/usr/bin/env bash
# Resumable downloads of JDK 17 and the Android command-line tools into E:\Android
# (C: is nearly full). Checks the byte count: curl sometimes exits 0 on a
# connection that closed mid-file, and the JDK once "finished" at 68 of 191 MB.
set -u
cd /e/Android/downloads
fetch() { # file url expected-bytes
  for i in $(seq 1 60); do
    have=$(stat -c %s "$1" 2>/dev/null || echo 0)
    [ "$have" -eq "$3" ] && { echo "$1 complete ($have bytes)"; return 0; }
    [ "$have" -gt "$3" ] && { echo "$1 too large, restarting"; rm -f "$1"; }
    curl -sSL --retry 5 --retry-delay 3 -C - -o "$1" "$2" || echo "$1: attempt $i interrupted at $(du -h "$1" | cut -f1)"
    sleep 2
  done
  return 1
}
fetch jdk17.zip "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip" 190817615 || exit 1
fetch cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-win-9862592_latest.zip" 138709020 || exit 1
unzip -tq jdk17.zip && unzip -q -o jdk17.zip -d /e/Android/ && echo "jdk extracted"
unzip -tq cmdline-tools.zip && rm -rf /e/Android/sdk/cmdline-tools && mkdir -p /e/Android/sdk/cmdline-tools \
  && unzip -q -o cmdline-tools.zip -d /e/Android/sdk/cmdline-tools/ && mv /e/Android/sdk/cmdline-tools/cmdline-tools /e/Android/sdk/cmdline-tools/latest && echo "cmdline-tools extracted"
ls /e/Android
