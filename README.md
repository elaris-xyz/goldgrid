# Goldgrid — pick five, strike gold

A free number draw for Solana Seeker owners, built for **Clock In**, the Solana
Mobile hackathon. Site: https://elaris-xyz.github.io/goldgrid/ ·
[Pitch deck (PDF)](docs/goldgrid-pitch-deck.pdf)

A new round every five minutes: pick 5 numbers from 1 to 85, and when the timer
ends Switchboard randomness draws the winning five. The tickets with the most
matches split a pot that sponsors fund in SKR. Entry is free, full stop: a paid
entry would make this a lottery.

- **One Seeker, one ticket.** On mainnet a ticket is tied to the Seeker Genesis
  Token, checked on-chain against the SGT group (120,999 members). Extra tickets
  come from a streak — one per 7 consecutive rounds played, up to 5 — never bought.
- **Nobody can know the numbers in time to use them, or re-roll them.** Entries
  close first; the randomness is committed after that, and the reveal must be of
  that exact commitment (its seed slot is recorded), so whoever runs the draw
  cannot peek, re-commit and try again.
- **Every round has a winner when anyone matches.** The best match wins, not only
  5 of 5. If nobody matches anything, the pot rolls into the next round.
- **Sponsors grow the pot as people enter.** Each ticket moves 1 SKR from the
  sponsor budget into that round's pot.
- **Winners are paid automatically.** Paying out is permissionless and can only
  pay the ticket's owner, so the crank pays every winner and returns every other
  ticket's deposit right after the draw. A player signs once per round: the ticket.
- **The draw needs no server of ours.** Commit, reveal, scoring and payout are
  permissionless; a hosted crank runs them on GitHub Actions with a key that holds
  no authority, and anyone can run another. If a committed draw is never revealed
  (an oracle outage), it can be committed again after 300 slots.

## Try it in five minutes

The app runs on **Solana devnet**; nothing here costs real money.

1. **Install the APK** from the [latest release](https://github.com/elaris-xyz/goldgrid/releases/latest)
   on an Android phone (a Seeker, or any Android 8+ phone).
2. **Set up Phantom for devnet:** in Phantom, *Settings → Developer Settings →
   Testnet Mode* on, and pick *Solana Devnet*. (Without it Phantom refuses the
   connection with "Incorrect mode".)
3. **Open Goldgrid and connect.** An empty devnet wallet gets a *Get free devnet
   SOL* button; if the public faucet is busy, use https://faucet.solana.com.
   Entry is free — each ticket only holds a small SOL deposit that comes back
   after the draw.
4. **Pick five and enter.** Tap five numbers (or shake the phone for a quick pick)
   and *Enter round #… — free*, then approve in Phantom. Rounds are five minutes;
   entries close 20 s before the draw, and the last 30 s before that are
   *last call* (too late for a wallet approval to land), which the app shows.
5. **Wait for the draw.** The winning numbers turn over in the app, a
   notification arrives with the result, and a prize is paid into your wallet by
   itself — there is nothing to claim. *Verify this draw on Solana Explorer* shows
   the Switchboard commit and reveal behind every result.

With one player every ticket that matches anything wins the round's pot. A
ticket matches at least one number 26.7% of the time, so a solo judge wins about
one round in four; with ten players 95.5% of rounds have a winner, and it becomes
a race for the best match.

## Why SKR

Prizes are paid in SKR, the token of the Solana Seeker community, and SKR only
ever flows out: no token is taken to enter.

- **It belongs to the players.** Seeker owners already hold SKR, so a prize stays
  in the community that plays.
- **The money is visible first.** Sponsors `fund` the program's vault before anyone
  plays; every prize is backed by tokens on-chain.
- **Any mint works.** The prize mint is set once at `initialize`, and the program
  uses the token interface, so SPL Token and Token-2022 mints both work. On devnet
  it is a stand-in, *Test SKR (devnet)* (`GNafN4xs8TRPxKCNzUwhX9rwsVvNdCY5dbjW74yyG9r8`),
  with no value; its name and logo come from Metaplex metadata written by
  [`cli/token-metadata.mjs`](cli/token-metadata.mjs). Mainnet uses the real SKR mint.

What it makes possible next: sponsored rounds carrying the sponsor's name,
featured draws with a bigger pot, a sponsor's own token paid alongside SKR,
Seeker-only rounds (SGT gating is already in the program), and streak rewards
paid in SKR.

## Layout

| Path | What |
|---|---|
| `programs/daily_draw` | Anchor program (Rust). `logic.rs` holds the draw rules, `sgt.rs` the Seeker check |
| `cli/` | Devnet end-to-end test (`e2e-devnet.mjs`), the draw crank (`crank.mjs`), admin scripts, shared client (`lib.mjs`) |
| `android/` | The app: Kotlin + Compose, Mobile Wallet Adapter |
| `.github/workflows/crank.yml` | Runs the draw crank on GitHub Actions |
| `docs/` | The site and the test token's logo and metadata (`docs/token/`), served by GitHub Pages |
| `brand/` | The Goldgrid icon |
| `spikes/` | The Switchboard-on-devnet spike that validated the approach |
| `scripts/` | Toolchain and build helpers (WSL) |

## Build and test

Requires Rust, the Solana CLI (Agave 4.x) and Anchor 0.32.2.

```bash
anchor build
cargo test -p daily_draw --lib        # unit tests, incl. a real mainnet SGT fixture
cd cli && npm install && node e2e-devnet.mjs   # one full round on devnet
```

Devnet program: [`gvd3fv3QgWvTMzLfxN2HBKkspAeVwzGBCZkW9ixaucM`](https://explorer.solana.com/address/gvd3fv3QgWvTMzLfxN2HBKkspAeVwzGBCZkW9ixaucM?cluster=devnet)
— demo mode: 5-minute rounds, entries close 20 s before the draw. Only the program's upgrade
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

## Playing a round

A round lasts five minutes and has three phases, which the app shows as a bar
under the timer:

| Phase | When | What the player can do |
|---|---|---|
| **Open** | from the start until 50 s before the draw | pick five and enter (free) |
| **Last call** | the 30 s before entries close | pick for the next round; a wallet approval would likely land too late, so the app does not offer Enter |
| **Closed** | the last 20 s, until the draw | pick for the next round; the numbers are kept |

Rounds are named by their UTC date and their number that day, `2026-09-30 · #50`.
Right after the draw the crank pays winners and returns every other ticket's
deposit; the app reveals the winning balls and a notification says what happened.

## The app

- **Sign-in screen** that plays at every start: the logo assembles and picks its
  five, then a connected player goes straight to the game
- **Mobile Wallet Adapter** with Phantom (devnet: Testnet Mode on). The wallet only
  signs; the app sends through its own RPC and resends until confirmed. The wallet
  is remembered until the player disconnects
- **One screen to play**: the round card (phase, timer, pot and where it comes
  from), the 5-of-85 grid (tap, quick pick, or shake), an action button that always
  says what it is waiting for, and the player's tickets with results and a link
  to each draw on Solana Explorer
- **Activity** (tap the SKR balance): every ticket with its date, result and prize
- **Notifications** with the result picture and the Goldgrid chime; in Settings,
  wins, results and new-round reminders (off, hourly, every round) are separate
  switches, with sound on or off
- Everything the app needs to open is kept on the device, so it renders at once
  and refreshes in the background

## Status

- [x] Program: enter, commit/reveal with Switchboard, scoring, split, rollover,
      permissionless payout, rent return, schedule changes that only move forward
- [x] Devnet end-to-end test (re-roll attack, stranger payouts, rent returns)
- [x] Hosted crank on GitHub Actions with a fee-only key
- [x] Android app on a real device with Phantom: entry, results, automatic payout,
      notifications, settings
- [x] Site: https://elaris-xyz.github.io/goldgrid/
- [x] Signed release APK (1.0.1) for judges
- [ ] Mainnet: SGT gating live, a sponsored SKR pot

## License

© 2026 The Goldgrid authors. Goldgrid is **source-available** under the
[Business Source License 1.1](LICENSE): anyone may read, build, test, audit and
evaluate it (judging included), and modify it for non-production use. Running it,
or a derivative, in production needs the authors' permission; the one exception
is the draw crank, which anyone may run for the Goldgrid program. On 2030-10-01
the code becomes GPL-2.0-or-later. The Goldgrid name and logo are not licensed.

