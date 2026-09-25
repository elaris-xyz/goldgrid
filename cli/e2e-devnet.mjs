// End-to-end on devnet, against the deployed program and the real Switchboard
// oracle: set up (once), fund a pot, enter tickets from three players, check the
// rules that must refuse, run the draw, and pay the winners. Exits non-zero on
// any mismatch, so a green run means the whole night works, not just a unit.
import anchor from "@coral-xyz/anchor";
import * as sb from "@switchboard-xyz/on-demand";
import {
  createMint,
  getAccount,
  getOrCreateAssociatedTokenAccount,
  mintTo,
  TOKEN_PROGRAM_ID,
} from "@solana/spl-token";
import { Keypair, LAMPORTS_PER_SOL, SystemProgram } from "@solana/web3.js";
import fs from "node:fs";
import {
  connect,
  configPda,
  chainNow,
  crankRound,
  currentRound,
  loadKeypair,
  log,
  roundPda,
  roundTimes,
  send,
  SGT_GROUP,
  sleepUntilChain,
  ticketPda,
  ticketsOf,
  vaultPda,
} from "./lib.mjs";

const { BN } = anchor;
const ADMIN_PATH = process.env.ADMIN_KEYPAIR ?? new URL("../spikes/switchboard-devnet/payer.json", import.meta.url);
const STATE = new URL("./devnet-state.json", import.meta.url);
const SKR = 10 ** 6; // test token has 6 decimals

const admin = loadKeypair(ADMIN_PATH);
const { connection, program } = connect(admin);
const state = fs.existsSync(STATE) ? JSON.parse(fs.readFileSync(STATE, "utf8")) : {};
const save = () => fs.writeFileSync(STATE, JSON.stringify(state, null, 1));
let failures = 0;
const check = (ok, what) => {
  log(`${ok ? "PASS" : "FAIL"}  ${what}`);
  if (!ok) failures++;
};
const sleepUntil = (unix) => sleepUntilChain(connection, unix);

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
  const genesis = Math.floor((await chainNow(connection)) / 60) * 60;
  await program.methods
    .initialize({
      genesisTs: new BN(genesis),
      roundSecs: new BN(120),
      entrySecs: new BN(90),
      perTicketBonus: new BN(1 * SKR),
      requireSgt: false,
      sgtGroup: SGT_GROUP,
      sbProgram: queue.program.programId,
      sbQueue: queue.pubkey,
    })
    .accountsPartial({ admin: admin.publicKey, mint, tokenProgram: TOKEN_PROGRAM_ID })
    .rpc();
  log("initialized: 2-minute rounds, entries close at 90s, demo mode (no SGT)");
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
check(config.sponsorBudget.toNumber() === budgetBefore + 100 * SKR, "fund adds 100 SKR to the sponsor budget");

// 3. Three players, each paid a little SOL for fees by the admin (no faucet).
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
    .accountsPartial({
      player: player.publicKey,
      identity: player.publicKey,
      sgtTokens: null,
      round: roundPda(id),
      ticket: ticketPda(id, player.publicKey, index),
    })
    .signers([player])
    .rpc();

const expectError = async (promise, code, what) => {
  try {
    await promise;
    check(false, `${what} (was accepted)`);
  } catch (e) {
    check(String(e).includes(code), `${what} -> ${code}`);
  }
};

await enter(players[0], [1, 2, 3, 4, 5]);
await enter(players[1], [10, 20, 30, 40, 50]);
await enter(players[2], [85, 60, 61, 62, 63]);
log("3 tickets entered");
await expectError(enter(players[0], [7, 8, 9, 11, 12], 1), "TicketLimit", "second ticket on a 1-day streak is refused");
await expectError(enter(players[1], [3, 3, 4, 5, 6], 1), "InvalidPicks", "duplicate numbers are refused");
await expectError(enter(players[2], [0, 1, 2, 3, 4], 1), "InvalidPicks", "number 0 is refused");

let round = await program.account.round.fetch(roundPda(id));
check(round.tickets === 3, "round counts 3 tickets");
const carriedIn = round.pot.toNumber() - 3 * SKR;
check(carriedIn >= 0, `pot = carry-in ${carriedIn / SKR} + 1 SKR per ticket`);

// 4. Nobody may draw early; entries close before the draw.
await expectError(
  program.methods.commitDraw(new BN(id)).accountsPartial({ randomness: Keypair.generate().publicKey }).rpc(),
  "Error",
  "commit before draw time is refused"
);
await sleepUntil(roundTimes(config, id).close);
await expectError(enter(latecomer, [7, 8, 9, 11, 12]), "EntriesClosed", "a new player entering after close is refused");

// 5. The draw, then scoring, exactly as the app would run it.
await sleepUntil(roundTimes(config, id).draw);
round = await crankRound(program, admin, id);
log(`winning numbers: ${round.winning.join(" ")}  best=${round.best} winners=${round.winners} share=${round.share.toNumber() / SKR} SKR`);
check("settled" in round.status, "round settled after scoring");

const tickets = await ticketsOf(program, id);
const matchesOk = tickets.every((t) => {
  const expected = t.account.picks.filter((n) => round.winning.includes(n)).length;
  return t.account.matches === expected;
});
check(matchesOk, "every ticket's recorded matches equal a recount from the winning numbers");
const best = Math.max(...tickets.map((t) => t.account.matches));
check(round.best === best, `best match recorded (${round.best}) equals the recount (${best})`);

// 6. Winners claim; losers cannot.
config = await program.account.config.fetch(configPda());
for (const t of tickets) {
  const player = players.find((p) => p.publicKey.equals(t.account.owner));
  const ata = await getOrCreateAssociatedTokenAccount(connection, admin, mint, player.publicKey);
  const claim = program.methods
    .claim(new BN(id))
    .accountsPartial({ owner: player.publicKey, ticket: t.publicKey, mint, ownerTokens: ata.address, tokenProgram: TOKEN_PROGRAM_ID })
    .signers([player])
    .rpc();
  if (round.best > 0 && t.account.matches === round.best) {
    await claim;
    const bal = (await getAccount(connection, ata.address)).amount;
    check(Number(bal) === round.share.toNumber(), `winner ${player.publicKey.toBase58().slice(0, 6)} received ${Number(bal) / SKR} SKR`);
    await expectError(
      program.methods
        .claim(new BN(id))
        .accountsPartial({ owner: player.publicKey, ticket: t.publicKey, mint, ownerTokens: ata.address, tokenProgram: TOKEN_PROGRAM_ID })
        .signers([player])
        .rpc(),
      "AlreadyClaimed",
      "second claim is refused"
    );
  } else {
    await expectError(claim, "NotAWinner", `non-winner ${player.publicKey.toBase58().slice(0, 6)} cannot claim`);
  }
}
if (round.best === 0) log(`nobody matched: the pot of ${round.pot.toNumber() / SKR} SKR rolled into the next round`);

const vault = await getAccount(connection, vaultPda());
log(`vault holds ${Number(vault.amount) / SKR} SKR; sponsor budget ${config.sponsorBudget.toNumber() / SKR}, carry ${config.carry.toNumber() / SKR}`);
log(failures === 0 ? "RESULT: PASS" : `RESULT: FAIL (${failures})`);
process.exit(failures === 0 ? 0 : 1);
