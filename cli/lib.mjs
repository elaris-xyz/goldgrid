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
import { createAssociatedTokenAccountIdempotentInstruction, getAssociatedTokenAddressSync } from "@solana/spl-token";
import fs from "node:fs";

const { AnchorProvider, BN, Program, Wallet } = anchor;

/**
 * The keyed endpoint the app uses, from android/local.properties (git-ignored), so
 * a local run gets the same RPC as the app without the key living in the repo.
 */
function localRpc() {
  try {
    const props = fs.readFileSync(new URL("../android/local.properties", import.meta.url), "utf8");
    const line = props.split(/\r?\n/).find((l) => l.startsWith("rpc.url="));
    return line?.slice("rpc.url=".length).trim().replaceAll("\\:", ":");
  } catch {
    return undefined;
  }
}

export const RPC = process.env.RPC_URL ?? localRpc() ?? "https://api.devnet.solana.com";
export const idl = JSON.parse(fs.readFileSync(new URL("./idl/daily_draw.json", import.meta.url), "utf8"));
export const PROGRAM_ID = new PublicKey(idl.address);
export const SGT_GROUP = new PublicKey("GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te");
const BPF_UPGRADEABLE_LOADER = new PublicKey("BPFLoaderUpgradeab1e11111111111111111111111");
/** Mirrors REVEAL_TIMEOUT_SLOTS in the program. */
export const REVEAL_TIMEOUT_SLOTS = 300;

const t0 = Date.now();
export const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1).padStart(6)}s]`, ...a);

export function loadKeypair(path) {
  return keypairFromJson(fs.readFileSync(path, "utf8"));
}

/** A keypair from its JSON byte array, as solana-keygen writes it and a CI secret holds it. */
export function keypairFromJson(json) {
  return Keypair.fromSecretKey(Uint8Array.from(JSON.parse(json)));
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

/**
 * Sends and confirms, resending when the blockhash expires first. An expired
 * blockhash means the transaction can never land, so a resend cannot apply it
 * twice; on this link a dropped send is routine, not a failure of the step.
 */
export async function send(connection, ixs, signers, label, attempts = 4) {
  for (let attempt = 1; ; attempt++) {
    const tx = new Transaction().add(ComputeBudgetProgram.setComputeUnitLimit({ units: 400_000 }), ...ixs);
    try {
      const sig = await sendAndConfirmTransaction(connection, tx, signers, { commitment: "confirmed" });
      log(`${label} ok  https://explorer.solana.com/tx/${sig}?cluster=devnet`);
      return sig;
    } catch (e) {
      if (e?.name !== "TransactionExpiredBlockheightExceededError" || attempt >= attempts) throw e;
      log(`${label}: expired before landing, resending (${attempt}/${attempts - 1})`);
    }
  }
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
  // One randomness account per payer: its authority is whoever created it, so the
  // crank's and the admin's (the e2e test's) must never be the same file.
  const file = new URL(`./.randomness-${payer.publicKey.toBase58().slice(0, 8)}.json`, import.meta.url);
  const config = await program.account.config.fetch(configPda());
  // In CI there is no file to keep it in: the keypair comes from a secret.
  const fromEnv = process.env.RANDOMNESS_SECRET;
  let kp = fresh ? null : fromEnv ? keypairFromJson(fromEnv) : fs.existsSync(file) ? loadKeypair(file) : null;
  if (kp && (await program.provider.connection.getAccountInfo(kp.publicKey))) {
    return { kp, randomness: new sb.Randomness(sbProgram, kp.publicKey) };
  }
  kp = kp && !fresh ? kp : Keypair.generate();
  const [randomness, createIx] = await sb.Randomness.create(sbProgram, kp, config.sbQueue, payer.publicKey);
  await send(program.provider.connection, [createIx], [payer, kp], "randomness account created");
  if (!fromEnv || fresh) fs.writeFileSync(file, JSON.stringify([...kp.secretKey]));
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
/**
 * A settled round's winning numbers stay readable this long before the crank
 * closes the round: the app and its result notification read them from the
 * round after the payout has already closed the player's ticket.
 */
export const ROUND_KEEP_SECS = 3600;

/**
 * Pays every winner of a settled round and returns every other ticket's rent,
 * so no player has to come back and sign. Permissionless: the program sends the
 * prize and the rent only to each ticket's owner, whoever sends the transaction.
 */
export async function payoutRound(program, payer, id, round) {
  const connection = program.provider.connection;
  const config = await program.account.config.fetch(configPda());
  const tokenProgram = (await connection.getAccountInfo(config.mint)).owner;
  for (const t of await ticketsOf(program, id)) {
    const owner = t.account.owner;
    const who = owner.toBase58().slice(0, 6);
    const won = round.best > 0 && t.account.matches === round.best && !t.account.claimed;
    const ixs = [];
    if (won) {
      const ata = getAssociatedTokenAddressSync(config.mint, owner, true, tokenProgram);
      ixs.push(createAssociatedTokenAccountIdempotentInstruction(payer.publicKey, ata, owner, config.mint, tokenProgram));
      ixs.push(await program.methods.claim(new BN(id))
        .accountsPartial({ owner, ticket: t.publicKey, mint: config.mint, ownerTokens: ata, tokenProgram }).instruction());
    } else {
      ixs.push(await program.methods.closeTicket(new BN(id)).accountsPartial({ owner, ticket: t.publicKey }).instruction());
    }
    try {
      await send(connection, ixs, [payer], `round ${id}: ${won ? `paid ${round.share.toNumber() / 1e6} SKR to` : "returned the deposit of"} ${who}`);
    } catch (e) {
      log(`round ${id}: payout to ${who} failed: ${(e.message ?? String(e)).slice(0, 160)}`);
    }
  }
  return program.account.round.fetch(roundPda(id));
}

export async function crankRound(program, payer, id, { payout = true, keepSecs = ROUND_KEEP_SECS } = {}) {
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

  if (payout && "settled" in round.status && round.openTickets > 0) {
    round = await payoutRound(program, payer, id, round);
  }

  const keptLongEnough = (await chainNow(connection)) >= round.drawTs.toNumber() + keepSecs;
  if ("settled" in round.status && round.openTickets === 0 && keptLongEnough) {
    const ix = await program.methods.closeRound(new BN(id)).accountsPartial({ creator: round.creator }).instruction();
    await send(connection, [ix], [payer], `round ${id}: closed, rent back to its creator`);
  }
  return round;
}
