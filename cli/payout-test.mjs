// Checks on devnet that paying out a settled round is permissionless and only
// ever pays the ticket's owner: `node payout-test.mjs <round>`. A brand-new key,
// unrelated to the players and to us, sends every payout. Needs no Switchboard,
// so it runs where the oracle gateways cannot be reached.
import anchor from "@coral-xyz/anchor";
import { getAccount, getAssociatedTokenAddressSync, getOrCreateAssociatedTokenAccount } from "@solana/spl-token";
import { Keypair, SystemProgram, Transaction, sendAndConfirmTransaction } from "@solana/web3.js";
import { configPda, connect, loadKeypair, log, roundPda, ticketsOf } from "./lib.mjs";

const { BN, AnchorProvider, Program, Wallet } = anchor;
const id = Number(process.argv[2]);
if (!Number.isInteger(id)) throw new Error("usage: node payout-test.mjs <settled round id>");

const admin = loadKeypair(new URL("../spikes/switchboard-devnet/payer.json", import.meta.url));
const { connection, program: adminProgram } = connect(admin);
const stranger = Keypair.generate();
await sendAndConfirmTransaction(connection, new Transaction().add(
  SystemProgram.transfer({ fromPubkey: admin.publicKey, toPubkey: stranger.publicKey, lamports: 50_000_000 }),
), [admin]);
const program = new Program(adminProgram.idl, new AnchorProvider(connection, new Wallet(stranger), { commitment: "confirmed" }));
log(`stranger ${stranger.publicKey.toBase58()} sends every payout`);

let failures = 0;
const check = (ok, what) => { log(`${ok ? "PASS" : "FAIL"}  ${what}`); if (!ok) failures++; };
async function refused(promise, code, what) {
  try { await promise; check(false, `${what} (it went through)`); }
  catch (e) { const got = e.error?.errorCode?.code ?? String(e.message).slice(0, 80); check(got === code, `${what} -> ${got}`); }
}

const config = await program.account.config.fetch(configPda());
const tokenProgram = (await connection.getAccountInfo(config.mint)).owner;
const round = await program.account.round.fetch(roundPda(id));
if (!("settled" in round.status)) throw new Error(`round ${id} is not settled`);
const strangerTokens = await getOrCreateAssociatedTokenAccount(connection, admin, config.mint, stranger.publicKey, true, "confirmed", undefined, tokenProgram);

for (const t of await ticketsOf(program, id)) {
  const owner = t.account.owner;
  const who = owner.toBase58().slice(0, 6);
  const ownerTokens = await getOrCreateAssociatedTokenAccount(connection, admin, config.mint, owner, true, "confirmed", undefined, tokenProgram);
  const claim = (to) => program.methods.claim(new BN(id))
    .accountsPartial({ owner, ticket: t.publicKey, mint: config.mint, ownerTokens: to, tokenProgram }).rpc();
  const close = () => program.methods.closeTicket(new BN(id)).accountsPartial({ owner, ticket: t.publicKey }).rpc();
  const lamportsBefore = await connection.getBalance(owner);
  const strangerBefore = (await getAccount(connection, strangerTokens.address, "confirmed", tokenProgram)).amount;
  if (round.best > 0 && t.account.matches === round.best) {
    await refused(close(), "UnclaimedPrize", `winner ${who} cannot be closed before being paid`);
    await refused(claim(strangerTokens.address), "ConstraintTokenOwner", `the stranger cannot redirect ${who}'s prize to themselves`);
    const before = (await getAccount(connection, ownerTokens.address, "confirmed", tokenProgram)).amount;
    await claim(ownerTokens.address);
    const after = (await getAccount(connection, ownerTokens.address, "confirmed", tokenProgram)).amount;
    check(Number(after - before) === round.share.toNumber(), `winner ${who} received ${Number(after - before) / 1e6} SKR from the stranger's transaction`);
  } else {
    await refused(claim(ownerTokens.address), "NotAWinner", `non-winner ${who} cannot claim`);
    await close();
  }
  check((await connection.getAccountInfo(t.publicKey)) === null, `ticket of ${who} is closed`);
  check((await connection.getBalance(owner)) > lamportsBefore, `${who} got the ticket rent back, paying nothing`);
  const strangerAfter = (await getAccount(connection, strangerTokens.address, "confirmed", tokenProgram)).amount;
  check(strangerAfter === strangerBefore, "the stranger gained no SKR");
}
const after = await program.account.round.fetch(roundPda(id));
check(after.openTickets === 0, "every ticket of the round is paid or returned");
log(failures === 0 ? "RESULT: PASS" : `RESULT: FAIL (${failures})`);
process.exit(failures === 0 ? 0 : 1);
