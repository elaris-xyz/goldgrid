#!/usr/bin/env bash
# Where the space inside the solana-dev distro goes.
du -sh /root/.cache/solana/* /root/downloads /root/target/* /root/.cargo/registry /root/.cargo/git /root/.rustup /opt/solana-release /root/.avm /root/.nvm /root/.local/share 2>/dev/null | sort -h
df -h / | tail -1
