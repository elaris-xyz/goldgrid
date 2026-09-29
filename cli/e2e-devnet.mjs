// End-to-end on devnet, against the deployed program and the real Switchboard
// oracle: set up (once), fund a pot, enter tickets from three players, check the
// rules that must refuse, attempt the re-roll attack, run the draw, pay the
// winners, and return every ticket's rent. Exits non-zero on any mismatch, so a
// green run means the whole night works, not just a unit.
import anchor from "@coral-xyz/anchor";
import * as sb from "@switchboard-xyz/on-demand";
import { createMint, getAccount, getOrCreateAssociatedTokenAccount, mintTo, TOKEN_PROGRAM_ID } from "@solana/spl-token";
import { Keypair, LAMPORTS_PER_SOL, SystemProgram } from "@solana/web3.js";
import fs from "node:fs";
import {
  chainNow,
  commitRound,
  configPda,
  connect,
  crankerRandomness,
  crankRound,
  currentRound,
  loadKeypair,
  log,
  programDataPda,
  REVEAL_TIMEOUT_SLOTS,
  roundPda,
  roundTimes,
  send,
  SGT_GROUP,
  sleepUntilChain,
  switchboardProgram,
  ticketPda,
  ticketsOf,
  vaultPda,
} from "./lib.mjs";

const { BN } = anchor;
const ADMIN_PATH = process.env.ADMIN_KEYPAIR ?? new URL("../spikes/switchboard-devnet/payer.json", import.meta.url);
const STATE = new URL("./devnet-state.json", import.meta.url);
const SKR = 10 ** 6; // test token has 6 decimals
const skipAttack = process.argv.includes("--skip-attack");

const admin = loadKeypair(ADMIN_PATH);
const { connection, program } = connect(admin);
const state = fs.existsSync(STATE) ? JSON.parse(fs.readFileSync(STATE, "utf8")) : {};
if (state.program !== program.programId.toBase58()) {
  // A new program id means a new config: start from a fresh mint too.
  Object.keys(state).forEach((k) => delete state[k]);
  state.program = program.programId.toBase58();
}
const save = () => fs.writeFileSync(STATE, JSON.stringify(state, null, 1));
let failures = 0;
const check = (ok, what) => {
  log(`${ok ? "PASS" : "FAIL"}  ${what}`);
  if (!ok) failures++;
};
const sleepUntil = (unix) => sleepUntilChain(connection, unix);
const expectError = async (promise, code, what) => {
  try {
    await promise;
    check(false, `${what} (was accepted)`);
  } catch (e) {
    check(String(e).includes(code) || JSON.stringify(e.logs ?? []).includes(code), `${what} -> ${code}`);
  }
};

log("admin", admin.publicKey.toBase58(), "balance", (await connection.getBalance(admin.publicKey)) / LAMPORTS_PER_SOL, "SOL");

// 1. One-time setup: a test mint standing in for SKR, and the config.
if (!state.mint) {
  const mint = await createMint(connection, admin, admin.publicKey, null, 6);
  state.mint = mint.toBase58();
  save();
  log('created test mint "SKR (devnet)"', state.mint);
}
const mint = new anchor.web3.PublicKey(state.mint);
const adminTokens = await getOrCreateAssociatedTokenAccount(connection, admin, mint, admin.publicKey);

let config = await program.account.config.fetchNullable(configPda());
if (!config) {
  const queue = await sb.getDefaultDevnetQueue();
  const params = {
    genesisTs: new BN(Math.floor((await chainNow(connection)) / 60) * 60),
    roundSecs: new BN(120),
    entrySecs: new BN(90),
    perTicketBonus: new BN(1 * SKR),
    requireSgt: false,
    sgtGroup: SGT_GROUP,
    sbProgram: queue.program.programId,
    sbQueue: queue.pubkey,
  };
  const init = (signer) =>
    program.methods
      .initialize(params)
      .accountsPartial({ admin: signer.publicKey, program: program.programId, programData: programDataPda(), mint, tokenProgram: TOKEN_PROGRAM_ID })
      .signers([signer])
      .rpc();
  // Someone who is not the upgrade authority must not be able to set the config.
  const stranger = Keypair.generate();
  await send(connection, [SystemProgram.transfer({ fromPubkey: admin.publicKey, toPubkey: stranger.publicKey, lamports: 0.02 * LAMPORTS_PER_SOL })], [admin], "fund a stranger");
  await expectError(init(stranger), "NotUpgradeAuthority", "initialize by a non-authority is refused");
  await init(admin);
  log("initialized by the upgrade authority: 2-minute rounds, entries close at 90s, demo mode (no SGT)");
  config = await program.account.config.fetch(configPda());
}

// 2. A sponsor funds the pot.
await mintTo(connection, admin, mint, adminTokens.address, admin, 1000 * SKR);
const budgetBefore = config.sponsorBudget.toNumber();
await program.methods
  .fund(new BN(100 * SKR))
  .accountsPartial({ sponsor: admin.publicKey, mint, sponsorTokens: adminTokens.address, tokenProgram: TOKEN_PROGRAM_ID })
  .rpc();
config = await program.account.config.fetch(configPda());
check(config.sponsorBudget.toNumber() === budgetBefore + 100 * SKR, "fund credits the 100 SKR the vault received");

// 3. Three players plus a latecomer, each paid a little SOL for fees by the admin.
const players = [Keypair.generate(), Keypair.generate(), Keypair.generate()];
const latecomer = Keypair.generate();
await send(
  connection,
  [...players, latecomer].map((p) => SystemProgram.transfer({ fromPubkey: admin.publicKey, toPubkey: p.publicKey, lamports: 0.03 * LAMPORTS_PER_SOL })),
  [admin],
  "fund 4 players with 0.03 SOL"
);

// Enter in a round with at least 40 seconds of entry window left.
let id = currentRound(config, await chainNow(connection));
if (roundTimes(config, id).close - (await chainNow(connection)) < 40) {
  await sleepUntil(roundTimes(config, id).draw);
  id = currentRound(config, await chainNow(connection));
}
log(`entering round ${id}`);

const enter = (player, picks, index = 0) =>
  program.methods
    .enter(new BN(id), index, picks)
    .accountsPartial({ player: player.publicKey, identity: player.publicKey, sgtTokens: null, round: roundPda(id), ticket: ticketPda(id, player.publicKey, index) })
    .signers([player])
    .rpc();

await enter(players[0], [1, 2, 3, 4, 5]);
await enter(players[1], [10, 20, 30, 40, 50]);
await enter(players[2], [85, 60, 61, 62, 63]);
log("3 tickets entered");
await expectError(enter(players[0], [7, 8, 9, 11, 12], 1), "TicketLimit", "second ticket on a 1-day streak is refused");
await expectError(enter(players[1], [3, 3, 4, 5, 6], 1), "InvalidPicks", "duplicate numbers are refused");
await expectError(enter(players[2], [0, 1, 2, 3, 4], 1), "InvalidPicks", "number 0 is refused");

// Only our players' tickets: someone using the app may enter this round too.
const mine = (tickets) => tickets.filter((t) => players.some((p) => p.publicKey.equals(t.account.owner)));
let round = await program.account.round.fetch(roundPda(id));
check(mine(await ticketsOf(program, id)).length === 3, "our 3 tickets are on-chain");
check(round.openTickets === round.tickets, "every entered ticket counts as open");

// 4. A real, freshly committed randomness account before draw time: only the
//    draw-time guard can refuse it (a random key would fail the owner check first).
await expectError(commitRound(program, admin, id), "TooEarly", "commit before draw time is refused");
await sleepUntil(roundTimes(config, id).close);
await expectError(enter(latecomer, [7, 8, 9, 11, 12]), "EntriesClosed", "a new player entering after close is refused");
await sleepUntil(roundTimes(config, id).draw);

// 5. The re-roll attack: commit, peek at the value, re-commit the same account,
//    then reveal with our instruction. The program must refuse the new value.
if (!skipAttack) {
  await commitRound(program, admin, id);
  round = await program.account.round.fetch(roundPda(id));
  const sbProgram = await switchboardProgram();
  const { randomness } = await crankerRandomness(program, admin, sbProgram);
  const peek = await randomness.revealIx(admin.publicKey);
  await send(connection, [peek], [admin], "attacker reveals privately (without reveal_draw)");
  let recommitted = false;
  try {
    await send(connection, [await randomness.commitIx(config.sbQueue, admin.publicKey)], [admin], "attacker re-commits the same account");
    recommitted = true;
  } catch (e) {
    log(`Switchboard refused the re-commit (${(e.message ?? e).toString().slice(0, 80)})`);
  }
  if (recommitted) {
    const ours = await program.methods.revealDraw(new BN(id)).accountsPartial({ randomness: round.randomness }).instruction();
    await expectError(
      (async () => send(connection, [await randomness.revealIx(admin.publicKey), ours], [admin], "attacker reveals the re-rolled value"))(),
      "RandomnessExpired",
      "a re-rolled value is refused"
    );
    // The committed value can no longer be revealed; the round must recover
    // with fresh randomness once the reveal timeout has passed.
    await expectError(commitRound(program, admin, id, { fresh: true }), "RevealPending", "a fresh commit before the timeout is refused");
    const waitFor = round.commitSlot.toNumber() + REVEAL_TIMEOUT_SLOTS + 1;
    while ((await connection.getSlot("confirmed")) < waitFor) {
      log(`waiting for the reveal timeout (${waitFor - (await connection.getSlot("confirmed"))} slots)`);
      await new Promise((r) => setTimeout(r, 15000));
    }
  }
}

// 6. The draw, then scoring, exactly as the app would run it.
// The crank would pay everyone at once; hold that back to test payouts one by one.
round = await crankRound(program, admin, id, { payout: false, keepSecs: 0 });
log(`winning numbers: ${round.winning.join(" ")}  best=${round.best} winners=${round.winners} share=${round.share.toNumber() / SKR} SKR`);
check("settled" in round.status, "round settled after scoring");

const all = await ticketsOf(program, id);
check(all.every((t) => t.account.matches === t.account.picks.filter((n) => round.winning.includes(n)).length),
  "every ticket's recorded matches equal a recount from the winning numbers");
const best = Math.max(...all.map((t) => t.account.matches));
check(round.best === best, `best match recorded (${round.best}) equals the recount (${best})`);

// 7. Paying out is permissionless and only ever pays the ticket's owner. Here a
//    stranger (the admin, who signs nothing for the players) sends every payout:
//    prizes and rent must land with the players, never with the sender. Losers
//    cannot claim, winners cannot be closed unpaid, and a claim cannot be
//    redirected to the sender's own token account.
config = await program.account.config.fetch(configPda());
const strangerAta = await getOrCreateAssociatedTokenAccount(connection, admin, mint, admin.publicKey);
for (const t of mine(all)) {
  const player = players.find((p) => p.publicKey.equals(t.account.owner));
  const short = player.publicKey.toBase58().slice(0, 6);
  const ata = await getOrCreateAssociatedTokenAccount(connection, admin, mint, player.publicKey);
  const accounts = { owner: player.publicKey, ticket: t.publicKey, mint, ownerTokens: ata.address, tokenProgram: TOKEN_PROGRAM_ID };
  const claim = (to = ata.address) => program.methods.claim(new BN(id)).accountsPartial({ ...accounts, ownerTokens: to }).rpc();
  const close = () => program.methods.closeTicket(new BN(id)).accountsPartial({ owner: player.publicKey, ticket: t.publicKey }).rpc();
  const lamportsBefore = await connection.getBalance(player.publicKey);
  if (round.best > 0 && t.account.matches === round.best) {
    await expectError(close(), "UnclaimedPrize", `winner ${short} cannot be closed before being paid`);
    await expectError(claim(strangerAta.address), "ConstraintTokenOwner", `a stranger cannot redirect ${short}'s prize to themselves`);
    await claim();
    const bal = (await getAccount(connection, ata.address)).amount;
    check(Number(bal) === round.share.toNumber(), `winner ${short} received ${Number(bal) / SKR} SKR from a stranger's transaction`);
  } else {
    await expectError(claim(), "NotAWinner", `non-winner ${short} cannot claim`);
    await close();
  }
  check((await connection.getAccountInfo(t.publicKey)) === null, `ticket of ${short} is closed`);
  check((await connection.getBalance(player.publicKey)) > lamportsBefore, `${short} got the ticket rent back`);
}
if (round.best === 0) log(`nobody matched: the pot of ${round.pot.toNumber() / SKR} SKR rolled into the next round`);

// 8. With every ticket closed, the round closes and its rent goes to its creator.
round = await program.account.round.fetch(roundPda(id));
if (round.openTickets === 0) {
  await crankRound(program, admin, id, { keepSecs: 0 });
  check((await connection.getAccountInfo(roundPda(id))) === null, "round closed once all its tickets were closed");
} else {
  log(`round ${id} still has ${round.openTickets} open tickets from other players; not closed`);
}

const vault = await getAccount(connection, vaultPda());
config = await program.account.config.fetch(configPda());
log(`vault holds ${Number(vault.amount) / SKR} SKR; sponsor budget ${config.sponsorBudget.toNumber() / SKR}, carry ${config.carry.toNumber() / SKR}`);
log(failures === 0 ? "RESULT: PASS" : `RESULT: FAIL (${failures})`);
process.exit(failures === 0 ? 0 : 1);
