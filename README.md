# Daily Draw — a free nightly draw for Seeker owners

Built for **Clock In**, the Solana Mobile hackathon.

Every day, until 23:00 UTC, each Seeker gets a free ticket: pick 5 numbers from
1 to 85. At midnight, Switchboard randomness draws the winning numbers, and the
tickets with the most matches split a pot that sponsors fund in SKR. Entry is
free, full stop: a paid entry would make this a lottery.

- **One Seeker, one ticket.** A ticket is tied to the Seeker Genesis Token, checked
  on-chain against the SGT group (120,999 members on mainnet). Extra tickets are
  earned by a streak — one per 7 consecutive days, up to 5 — never bought.
- **Nobody can know the numbers in time to use them, or re-roll them.** Entries
  close first; the randomness is committed after that, and the reveal must be of
  that exact commitment (its seed slot is recorded), so whoever runs the draw
  cannot peek, re-commit and try again.
- **Every night has a winner.** The best match wins, not only 5 of 5 (1 in
  32.8 million). If nobody matches anything, the pot carries to the next night.
- **Sponsors grow the pot as people enter.** Each ticket moves 1 SKR from the
  sponsor budget into that night's pot.
- **The draw needs no server.** Commit, reveal and scoring are permissionless;
  whoever opens the app after the draw time can run them. If a committed draw is
  never revealed (an oracle outage), it can be committed again after 300 slots.
- **Entering costs only the transaction fee.** Claiming closes the ticket and
  returns its rent; losing tickets are closed for their rent; a round closes once
  its tickets are, and its rent goes back to whoever created it.

## Layout

| Path | What |
|---|---|
| `programs/daily_draw` | Anchor program (Rust). `logic.rs` holds the draw rules, `sgt.rs` the Seeker check |
| `cli/` | Devnet end-to-end test (`e2e-devnet.mjs`), the draw crank (`crank.mjs`), admin scripts, shared client (`lib.mjs`) |
| `android/` | The app: Kotlin + Compose, Mobile Wallet Adapter |
| `.github/workflows/crank.yml` | Runs the draw crank on GitHub Actions |
| `spikes/` | The Switchboard-on-devnet spike that validated the approach |
| `scripts/` | Toolchain and build helpers (WSL) |

## Build and test

Requires Rust, the Solana CLI (Agave 4.x) and Anchor 0.32.2.

```bash
anchor build
cargo test -p daily_draw --lib        # unit tests, incl. a real mainnet SGT fixture
cd cli && npm install && node e2e-devnet.mjs   # one full night on devnet
```

Devnet program: [`gvd3fv3QgWvTMzLfxN2HBKkspAeVwzGBCZkW9ixaucM`](https://explorer.solana.com/address/gvd3fv3QgWvTMzLfxN2HBKkspAeVwzGBCZkW9ixaucM?cluster=devnet)
— demo mode: 10-minute rounds, entries open for 9. Only the program's upgrade
authority can initialize it; the admin can retime future rounds (`set_schedule`),
and the program refuses any schedule that would reuse a past round's number.

## Who runs the draw

Nobody has to be trusted to run it, and nobody's computer has to be on. Drawing a
round (Switchboard commit, reveal, scoring, closing) is permissionless: anyone can
send those transactions, and the program only accepts randomness committed after
entries closed and revealed from that same commit. `cli/crank.mjs` does it for
every round past its draw time; `.github/workflows/crank.yml` runs it on GitHub
Actions around the clock with a key that pays fees and holds no authority.

```bash
cd cli && node crank.mjs            # run a crank yourself, beside the hosted one
```

## Status

- [x] Program: enter, commit/reveal with Switchboard, scoring, split, rollover, claim, rent return
- [x] Devnet end-to-end test, including the re-roll attack and rent returns
- [x] Android app: wallet, round countdown on chain time, pick grid, shake to quick pick,
      entry (demo and SGT), results, claim, rent return
- [ ] In-app draw crank, widget and notifications
