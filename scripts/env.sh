# Source inside the solana-dev WSL distro to get Rust, Solana CLI and Anchor on PATH.
# `anchor` points straight at 0.32.2: the avm shim downloads tools on every call.
source "$HOME/.cargo/env" 2>/dev/null
mkdir -p "$HOME/bin" && ln -sf "$HOME/.avm/bin/anchor-0.32.2" "$HOME/bin/anchor"
export PATH="$HOME/bin:/opt/solana-release/bin:$HOME/.avm/bin:$PATH"
