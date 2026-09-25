// Spike: can we get Switchboard randomness on devnet end to end, and turn it
// into a nightly draw of 5 distinct numbers from 1..85?
import * as sb from "@switchboard-xyz/on-demand";
import {
  Connection,
  Keypair,
  LAMPORTS_PER_SOL,
  Transaction,
  sendAndConfirmTransaction,
} from "@solana/web3.js";
import fs from "node:fs";

const RPC = process.env.RPC_URL ?? "https://api.devnet.solana.com";
const conn = new Connection(RPC, "confirmed");
const t0 = Date.now();
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a);

function loadPayer() {
  if (fs.existsSync("payer.json")) {
    return Keypair.fromSecretKey(Uint8Array.from(JSON.parse(fs.readFileSync("payer.json", "utf8"))));
  }
  const kp = Keypair.generate();
  fs.writeFileSync("payer.json", JSON.stringify([...kp.secretKey]));
  return kp;
}

async function send(ixs, signers, label) {
  const tx = new Transaction().add(...ixs);
  const sig = await sendAndConfirmTransaction(conn, tx, signers, { commitment: "confirmed" });
  log(`${label} ok  https://explorer.solana.com/tx/${sig}?cluster=devnet`);
  return sig;
}

// 5 distinct numbers in 1..85 from 32 random bytes: partial Fisher-Yates,
// two bytes per draw so the modulo bias stays under 0.2%.
function drawFive(bytes) {
  const pool = Array.from({ length: 85 }, (_, i) => i + 1);
  const out = [];
  for (let i = 0; i < 5; i++) {
    const r = (bytes[2 * i] << 8) | bytes[2 * i + 1];
    const j = i + (r % (85 - i));
    [pool[i], pool[j]] = [pool[j], pool[i]];
    out.push(pool[i]);
  }
  return out.sort((a, b) => a - b);
}

const payer = loadPayer();
log("payer", payer.publicKey.toBase58());

let bal = await conn.getBalance(payer.publicKey);
if (bal < 0.05 * LAMPORTS_PER_SOL) {
  try {
    const sig = await conn.requestAirdrop(payer.publicKey, LAMPORTS_PER_SOL);
    await conn.confirmTransaction(sig, "confirmed");
  } catch (e) {
    log("airdrop failed:", e.message);
  }
  bal = await conn.getBalance(payer.publicKey);
}
log("balance", bal / LAMPORTS_PER_SOL, "SOL");
if (bal < 0.01 * LAMPORTS_PER_SOL) {
  log("NO FUNDS: send devnet SOL to", payer.publicKey.toBase58(), "and re-run");
  process.exit(2);
}

const queue = await sb.getDefaultDevnetQueue(RPC);
const program = queue.program;
log("queue", queue.pubkey.toBase58(), "program", program.programId.toBase58());

// 1. create the randomness account
const rngKp = Keypair.generate();
const [randomness, createIx] = await sb.Randomness.create(program, rngKp, queue.pubkey, payer.publicKey);
await send([createIx], [payer, rngKp], "create");

// 2. commit: binds the account to a slot hash nobody knows yet
const commitIx = await randomness.commitIx(queue.pubkey, payer.publicKey);
await send([commitIx], [payer], "commit");

// 3. reveal: the oracle signs the committed slot hash inside its enclave
let revealed = false;
for (let attempt = 1; attempt <= 10 && !revealed; attempt++) {
  try {
    const revealIx = await randomness.revealIx(payer.publicKey);
    await send([revealIx], [payer], `reveal (attempt ${attempt})`);
    revealed = true;
  } catch (e) {
    log(`reveal attempt ${attempt} failed:`, (e.message ?? String(e)).slice(0, 200));
    await new Promise((r) => setTimeout(r, 3000));
  }
}
if (!revealed) {
  log("RESULT: FAIL - reveal never succeeded");
  process.exit(1);
}

// 4. read the value back from chain, exactly as the program would
const data = await randomness.loadData();
const value = Uint8Array.from(data.value);
log("value", Buffer.from(value).toString("hex"));
log("seedSlot", data.seedSlot?.toString(), "revealSlot", data.revealSlot?.toString());
log("winning numbers:", drawFive(value).join(" "));
log("RESULT: PASS");
