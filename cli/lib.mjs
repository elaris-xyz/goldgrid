// Shared client for the daily_draw program: connection, PDAs, and the draw
// crank (commit -> reveal -> score). The crank is permissionless on-chain, so
// this is the same work the app does when it finds a round waiting to be drawn.
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

/**
 * Runs the draw for a round that has passed its draw time: Switchboard commit
 * and our commit_draw in one transaction, then Switchboard reveal and our
 * reveal_draw in one transaction, then scores every ticket.
 */
export async function crankRound(program, payer, id) {
  const connection = program.provider.connection;
  const config = await program.account.config.fetch(configPda());
  let round = await program.account.round.fetch(roundPda(id));

  if ("open" in round.status) {
    const queue = await sb.getDefaultDevnetQueue(RPC);
    const rngKp = Keypair.generate();
    const [randomness, createIx] = await sb.Randomness.create(queue.program, rngKp, config.sbQueue, payer.publicKey);
    await send(connection, [createIx], [payer, rngKp], `round ${id}: randomness account`);
    const commitIx = await randomness.commitIx(config.sbQueue, payer.publicKey);
    const ours = await program.methods
      .commitDraw(new BN(id))
      .accountsPartial({ randomness: rngKp.publicKey })
      .instruction();
    await send(connection, [commitIx, ours], [payer], `round ${id}: commit`);
    round = await program.account.round.fetch(roundPda(id));
  }

  if ("committed" in round.status) {
    const queue = await sb.getDefaultDevnetQueue(RPC);
    const randomness = new sb.Randomness(queue.program, round.randomness);
    for (let attempt = 1; ; attempt++) {
      try {
        const revealIx = await randomness.revealIx(payer.publicKey);
        const ours = await program.methods
          .revealDraw(new BN(id))
          .accountsPartial({ randomness: round.randomness })
          .instruction();
        await send(connection, [revealIx, ours], [payer], `round ${id}: reveal (attempt ${attempt})`);
        break;
      } catch (e) {
        if (attempt >= 8) throw e;
        log(`reveal attempt ${attempt} failed: ${(e.message ?? e).toString().slice(0, 160)}`);
        await new Promise((r) => setTimeout(r, 3000));
      }
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
  return round;
}
