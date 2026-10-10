// Shared client for the daily_draw program: connection, PDAs, and the draw
// crank (commit -> reveal -> score -> close). The crank is permissionless
// on-chain, so this is the same work the app does when it finds a round
// waiting to be drawn.
import anchor from "@coral-xyz/anchor";
import {
  ComputeBudgetProgram,
  Connection,
  Keypair,
  PublicKey,
  SYSVAR_CLOCK_PUBKEY,
  SystemProgram,
  Transaction,
  TransactionInstruction,
  sendAndConfirmTransaction,
} from "@solana/web3.js";
import { createAssociatedTokenAccountIdempotentInstruction, getAssociatedTokenAddressSync } from "@solana/spl-token";
import fs from "node:fs";
import { createHash, randomBytes } from "node:crypto";

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
    // A VPN or RPC hiccup must not end a wait that has minutes left.
    let now;
    try {
      now = await chainNow(connection);
    } catch (e) {
      log(`clock read failed (${(e.message ?? e).toString().slice(0, 60)}); retrying`);
      await new Promise((r) => setTimeout(r, 5000));
      continue;
    }
    const left = unix + marginSecs - now;
    if (left <= 0) return;
    log(`waiting ${left}s (chain time)`);
    await new Promise((r) => setTimeout(r, Math.min(left, 20) * 1000));
  }
}

/** All tickets of a round: `round` is the first field after the 8-byte discriminator. */
export async function ticketsOf(program, id) {
  return program.account.ticket.all([{ memcmp: { offset: 8, bytes: anchor.utils.bytes.bs58.encode(u64le(id)) } }]);
}

/**
 * ORAO VRF supplies the randomness. It replaced Switchboard, which shut down in
 * September 2026; its devnet oracles went silent on 2026-10-10 and every draw
 * stalled. A request is one instruction and a fee (0.0003 SOL on devnet); ORAO's
 * oracles fulfil it within seconds, and the result stays in the request account.
 */
export const ORAO_VRF = new PublicKey("VRFzZoJdhFWL8rkvu87LpKM3RbcVezpMEc6X5GVDr7y");
const ORAO_NETWORK = PublicKey.findProgramAddressSync([Buffer.from("orao-vrf-network-configuration")], ORAO_VRF)[0];
export const oraoRequestPda = (seed) =>
  PublicKey.findProgramAddressSync([Buffer.from("orao-vrf-randomness-request"), seed], ORAO_VRF)[0];
const anchorDisc = (name) => createHash("sha256").update(name).digest().subarray(0, 8);

/** ORAO's request_v2: pays the fee to the network treasury and opens the request account. */
export async function oraoRequestIx(connection, payer, seed) {
  const net = await connection.getAccountInfo(ORAO_NETWORK);
  if (!net) throw new Error("ORAO VRF network account not found");
  // NetworkState: discriminator, then config { authority, treasury, ... }.
  const treasury = new PublicKey(net.data.subarray(8 + 32, 8 + 64));
  return new TransactionInstruction({
    programId: ORAO_VRF,
    keys: [
      { pubkey: payer, isSigner: true, isWritable: true },
      { pubkey: ORAO_NETWORK, isSigner: false, isWritable: true },
      { pubkey: treasury, isSigner: false, isWritable: true },
      { pubkey: oraoRequestPda(seed), isSigner: false, isWritable: true },
      { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
    ],
    data: Buffer.concat([anchorDisc("global:request_v2"), seed]),
  });
}

/** A request's state: RandomnessV2 = discriminator, then the enum tag (0 pending, 1 fulfilled). */
export async function oraoState(connection, request) {
  const info = await connection.getAccountInfo(request);
  if (!info) return { exists: false, fulfilled: false };
  const fulfilled = info.data[8] === 1;
  // Fulfilled { client, seed, randomness[64] }
  const randomness = fulfilled ? info.data.subarray(9 + 32 + 32, 9 + 32 + 32 + 64) : null;
  return { exists: true, fulfilled, randomness };
}

/**
 * A fresh ORAO request and our commit_draw in one transaction, so the request is
 * still unanswered when the program checks it. `previous` is the round's old,
 * still-unfulfilled request when re-committing after the timeout.
 */
export async function commitRound(program, payer, id, { previous = null } = {}) {
  const connection = program.provider.connection;
  const seed = randomBytes(32);
  const request = await oraoRequestIx(connection, payer.publicKey, seed);
  let ours = program.methods.commitDraw(new BN(id)).accountsPartial({ randomness: oraoRequestPda(seed) });
  if (previous) ours = ours.remainingAccounts([{ pubkey: previous, isSigner: false, isWritable: false }]);
  return send(connection, [request, await ours.instruction()], [payer], `round ${id}: commit (ORAO request)`);
}

/** Our reveal_draw once ORAO has fulfilled the request; waits up to `waitSecs` for it. */
export async function revealRound(program, payer, id, request, waitSecs = 60) {
  const connection = program.provider.connection;
  for (let waited = 0; ; waited += 3) {
    const state = await oraoState(connection, request);
    if (state.fulfilled) break;
    if (waited >= waitSecs) return null;
    await new Promise((r) => setTimeout(r, 3000));
  }
  const ours = await program.methods.revealDraw(new BN(id)).accountsPartial({ randomness: request }).instruction();
  return send(connection, [ours], [payer], `round ${id}: reveal`);
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
    await commitRound(program, payer, id);
    round = await program.account.round.fetch(roundPda(id));
  }

  if ("committed" in round.status) {
    const revealed = await revealRound(program, payer, id, round.randomness);
    if (!revealed) {
      // Unanswered: after the timeout a new request may replace it (the program
      // checks the old one is still unfulfilled); before it, wait.
      const slot = await connection.getSlot("confirmed");
      const readyAt = round.commitSlot.toNumber() + REVEAL_TIMEOUT_SLOTS + 1;
      if (slot < readyAt) {
        log(`round ${id}: waiting for ORAO to fulfil its request; a new request is allowed in ${readyAt - slot} slots`);
        return round;
      }
      await commitRound(program, payer, id, { previous: round.randomness });
      return program.account.round.fetch(roundPda(id));
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
