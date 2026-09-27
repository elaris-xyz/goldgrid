// Shared client for the daily_draw program: connection, PDAs, and the draw
// crank (commit -> reveal -> score -> close). The crank is permissionless
// on-chain, so this is the same work the app does when it finds a round
// waiting to be drawn.
import anchor from "@coral-xyz/anchor";
import * as sb from "@switchboard-xyz/on-demand";
import {
  ComputeBudgetProgram,
  Connection,
  Keypair,
  PublicKey,
  SYSVAR_CLOCK_PUBKEY,
  Transaction,
  sendAndConfirmTransaction,
} from "@solana/web3.js";
import fs from "node:fs";

const { AnchorProvider, BN, Program, Wallet } = anchor;

export const RPC = process.env.RPC_URL ?? "https://api.devnet.solana.com";
export const idl = JSON.parse(fs.readFileSync(new URL("./idl/daily_draw.json", import.meta.url), "utf8"));
export const PROGRAM_ID = new PublicKey(idl.address);
export const SGT_GROUP = new PublicKey("GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te");
const BPF_UPGRADEABLE_LOADER = new PublicKey("BPFLoaderUpgradeab1e11111111111111111111111");
/** Mirrors REVEAL_TIMEOUT_SLOTS in the program. */
export const REVEAL_TIMEOUT_SLOTS = 300;

const t0 = Date.now();
export const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1).padStart(6)}s]`, ...a);

export function loadKeypair(path) {
  return Keypair.fromSecretKey(Uint8Array.from(JSON.parse(fs.readFileSync(path, "utf8"))));
}

export function connect(payer) {
  const connection = new Connection(RPC, "confirmed");
  const provider = new AnchorProvider(connection, new Wallet(payer), { commitment: "confirmed" });
  return { connection, provider, program: new Program(idl, provider) };
}

const u64le = (n) => new BN(n).toArrayLike(Buffer, "le", 8);
const pda = (...seeds) => PublicKey.findProgramAddressSync(seeds, PROGRAM_ID)[0];
export const configPda = () => pda(Buffer.from("config"));
export const vaultPda = () => pda(Buffer.from("vault"));
export const roundPda = (id) => pda(Buffer.from("round"), u64le(id));
export const seekerPda = (identity) => pda(Buffer.from("seeker"), identity.toBuffer());
export const ticketPda = (id, identity, index) =>
  pda(Buffer.from("ticket"), u64le(id), identity.toBuffer(), Buffer.from([index]));
export const programDataPda = () =>
  PublicKey.findProgramAddressSync([PROGRAM_ID.toBuffer()], BPF_UPGRADEABLE_LOADER)[0];

export async function send(connection, ixs, signers, label) {
  const tx = new Transaction().add(ComputeBudgetProgram.setComputeUnitLimit({ units: 400_000 }), ...ixs);
  const sig = await sendAndConfirmTransaction(connection, tx, signers, { commitment: "confirmed" });
  log(`${label} ok  https://explorer.solana.com/tx/${sig}?cluster=devnet`);
  return sig;
}

export function roundTimes(config, id) {
  const start = config.genesisTs.toNumber() + id * config.roundSecs.toNumber();
  return { close: start + config.entrySecs.toNumber(), draw: start + config.roundSecs.toNumber() };
}

export function currentRound(config, nowSecs) {
  return Math.floor((nowSecs - config.genesisTs.toNumber()) / config.roundSecs.toNumber());
}

/**
 * The chain's unix time. Never use the device clock for round timing: this
 * machine ran 215 s fast, and a phone's clock can be anything. The program
 * reads the Clock sysvar, so that is the only clock that decides a round.
 */
export async function chainNow(connection) {
  const info = await connection.getAccountInfo(SYSVAR_CLOCK_PUBKEY);
  return Number(info.data.readBigInt64LE(32));
}

/** Sleeps until the chain clock reaches `unix` (plus a small margin). */
export async function sleepUntilChain(connection, unix, marginSecs = 2) {
  for (;;) {
    const left = unix + marginSecs - (await chainNow(connection));
    if (left <= 0) return;
    log(`waiting ${left}s (chain time)`);
    await new Promise((r) => setTimeout(r, Math.min(left, 20) * 1000));
  }
}

/** All tickets of a round: `round` is the first field after the 8-byte discriminator. */
export async function ticketsOf(program, id) {
  return program.account.ticket.all([{ memcmp: { offset: 8, bytes: anchor.utils.bytes.bs58.encode(u64le(id)) } }]);
}

export async function switchboardProgram() {
  return (await sb.getDefaultDevnetQueue(RPC)).program;
}

/**
 * The cranker's randomness account, created once and re-committed every round:
 * a new account per draw leaks its rent every time. Kept in a local keypair
 * file; `fresh` makes a new one when Switchboard refuses to re-commit the old.
 */
export async function crankerRandomness(program, payer, sbProgram, { fresh = false } = {}) {
  const file = new URL("./.randomness-keypair.json", import.meta.url);
  const config = await program.account.config.fetch(configPda());
  let kp = !fresh && fs.existsSync(file) ? loadKeypair(file) : null;
  if (kp && (await program.provider.connection.getAccountInfo(kp.publicKey))) {
    return { kp, randomness: new sb.Randomness(sbProgram, kp.publicKey) };
  }
  kp = Keypair.generate();
  const [randomness, createIx] = await sb.Randomness.create(sbProgram, kp, config.sbQueue, payer.publicKey);
  await send(program.provider.connection, [createIx], [payer, kp], "randomness account created");
  fs.writeFileSync(file, JSON.stringify([...kp.secretKey]));
  return { kp, randomness };
}

/** Switchboard commit and our commit_draw in one transaction. */
export async function commitRound(program, payer, id, { fresh = false } = {}) {
  const sbProgram = await switchboardProgram();
  const config = await program.account.config.fetch(configPda());
  const { kp, randomness } = await crankerRandomness(program, payer, sbProgram, { fresh });
  const commitIx = await randomness.commitIx(config.sbQueue, payer.publicKey);
  const ours = await program.methods.commitDraw(new BN(id)).accountsPartial({ randomness: kp.publicKey }).instruction();
  return send(program.provider.connection, [commitIx, ours], [payer], `round ${id}: commit`);
}

/** Switchboard reveal and our reveal_draw in one transaction, with retries for the oracle. */
export async function revealRound(program, payer, id, randomnessKey, attempts = 8) {
  const sbProgram = await switchboardProgram();
  const randomness = new sb.Randomness(sbProgram, randomnessKey);
  for (let attempt = 1; ; attempt++) {
    try {
      const revealIx = await randomness.revealIx(payer.publicKey);
      const ours = await program.methods.revealDraw(new BN(id)).accountsPartial({ randomness: randomnessKey }).instruction();
      return await send(program.provider.connection, [revealIx, ours], [payer], `round ${id}: reveal (attempt ${attempt})`);
    } catch (e) {
      if (attempt >= attempts) throw e;
      log(`reveal attempt ${attempt} failed: ${(e.message ?? e).toString().slice(0, 160)}`);
      await new Promise((r) => setTimeout(r, 3000));
    }
  }
}

/**
 * Advances a round that has passed its draw time as far as it can go:
 * commit, reveal, score, and close once every ticket is closed. A commit whose
 * randomness can no longer reveal it (re-committed, or the oracle is gone) is
 * replaced with fresh randomness after REVEAL_TIMEOUT_SLOTS.
 */
export async function crankRound(program, payer, id) {
  const connection = program.provider.connection;
  let round = await program.account.round.fetch(roundPda(id));

  if ("open" in round.status) {
    try {
      await commitRound(program, payer, id);
    } catch (e) {
      log(`commit with the reused randomness account failed (${(e.message ?? e).toString().slice(0, 100)}); using a fresh one`);
      await commitRound(program, payer, id, { fresh: true });
    }
    round = await program.account.round.fetch(roundPda(id));
  }

  if ("committed" in round.status) {
    const sbProgram = await switchboardProgram();
    const data = await new sb.Randomness(sbProgram, round.randomness).loadData();
    const revealable = data.seedSlot.toString() === round.commitSlot.toString();
    if (revealable) {
      await revealRound(program, payer, id, round.randomness);
    } else {
      const slot = await connection.getSlot("confirmed");
      const readyAt = round.commitSlot.toNumber() + REVEAL_TIMEOUT_SLOTS + 1;
      if (slot < readyAt) {
        log(`round ${id}: its randomness was re-committed; fresh commit allowed in ${readyAt - slot} slots`);
        return round;
      }
      await commitRound(program, payer, id, { fresh: true });
      round = await program.account.round.fetch(roundPda(id));
      await revealRound(program, payer, id, round.randomness);
    }
    round = await program.account.round.fetch(roundPda(id));
  }

  if ("revealed" in round.status) {
    const tickets = (await ticketsOf(program, id)).filter((t) => t.account.matches === 255);
    for (let i = 0; i < tickets.length; i += 20) {
      const batch = tickets.slice(i, i + 20);
      const ix = await program.methods
        .scoreTickets(new BN(id))
        .remainingAccounts(batch.map((t) => ({ pubkey: t.publicKey, isSigner: false, isWritable: true })))
        .instruction();
      await send(connection, [ix], [payer], `round ${id}: scored ${i + batch.length}/${tickets.length}`);
    }
    round = await program.account.round.fetch(roundPda(id));
  }

  if ("settled" in round.status && round.openTickets === 0) {
    const ix = await program.methods.closeRound(new BN(id)).accountsPartial({ creator: round.creator }).instruction();
    await send(connection, [ix], [payer], `round ${id}: closed, rent back to its creator`);
  }
  return round;
}
