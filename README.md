# Daily Draw — a free nightly draw for Seeker owners

Built for **Clock In**, the Solana Mobile hackathon.

Every day, until 23:00 UTC, each Seeker gets a free ticket: pick 5 numbers from
1 to 85. At midnight, Switchboard randomness draws the winning numbers, and the
tickets with the most matches split a pot that sponsors fund in SKR. Entry is
free, full stop: a paid entry would make this a lottery.

- **One Seeker, one ticket.** A ticket is tied to the Seeker Genesis Token, checked
  on-chain against the SGT group (120,999 members on mainnet). Extra tickets are
  earned by a streak — one per 7 consecutive days, up to 5 — never bought.
- **Nobody can know the numbers in time to use them.** Entries close first; the
  randomness is committed after that and revealed in the same transaction that
  reads it.
- **Every night has a winner.** The best match wins, not only 5 of 5 (1 in
  32.8 million). If nobody matches anything, the pot carries to the next night.
- **Sponsors grow the pot as people enter.** Each ticket moves 1 SKR from the
  sponsor budget into that night's pot.
- **The draw needs no server.** Commit, reveal and scoring are permissionless;
  whoever opens the app after the draw time can run them.

## Layout

| Path | What |
|---|---|
| `programs/daily_draw` | Anchor program (Rust). `logic.rs` holds the draw rules, `sgt.rs` the Seeker check |
| `cli/` | Devnet end-to-end test (`e2e-devnet.mjs`) and the shared client/crank (`lib.mjs`) |
| `spikes/` | The Switchboard-on-devnet spike that validated the approach |
| `scripts/` | Toolchain and build helpers (WSL) |

## Build and test

Requires Rust, the Solana CLI (Agave 4.x) and Anchor 0.32.2.

```bash
anchor build
cargo test -p daily_draw --lib        # 11 unit tests, incl. a real mainnet SGT fixture
cd cli && npm install && node e2e-devnet.mjs   # one full night on devnet
```

Devnet program: [`8X7udAY9fwDU1HY4gWGHvEQahZX6RYfoNYCx8UovxjNQ`](https://explorer.solana.com/address/8X7udAY9fwDU1HY4gWGHvEQahZX6RYfoNYCx8UovxjNQ?cluster=devnet)
— demo mode: 2-minute rounds, entries close at 90 s.

## Status

- [x] Program: enter, commit/reveal with Switchboard, scoring, split, rollover, claim
- [x] Devnet end-to-end test passing (rollover and a two-winner split both exercised)
- [ ] Android app (Kotlin, Jetpack Compose, Mobile Wallet Adapter)
