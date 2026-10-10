// Tops up the sponsor budget that feeds every round's pot: mints the devnet test
// SKR to the admin (the mint authority) and funds the program's vault with it.
//   node fund-pot.mjs <SKR>
import anchor from "@coral-xyz/anchor";
import { getOrCreateAssociatedTokenAccount, mintTo, TOKEN_PROGRAM_ID } from "@solana/spl-token";
import { PublicKey } from "@solana/web3.js";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { connect, configPda, loadKeypair, log } from "./lib.mjs";

const { BN } = anchor;
const SKR = 1_000_000;
const amount = Number(process.argv[2]);
if (!(amount > 0)) throw new Error("usage: node fund-pot.mjs <SKR>");
const admin = loadKeypair(fileURLToPath(new URL("../spikes/switchboard-devnet/payer.json", import.meta.url)));
const { connection, program } = connect(admin);
const mint = new PublicKey(JSON.parse(readFileSync(new URL("./devnet-state.json", import.meta.url))).mint);

const before = (await program.account.config.fetch(configPda())).sponsorBudget.toNumber() / SKR;
const tokens = await getOrCreateAssociatedTokenAccount(connection, admin, mint, admin.publicKey);
await mintTo(connection, admin, mint, tokens.address, admin, BigInt(amount * SKR));
log(`minted ${amount} test SKR to the admin`);
await program.methods.fund(new BN(amount * SKR))
  .accountsPartial({ sponsor: admin.publicKey, mint, sponsorTokens: tokens.address, tokenProgram: TOKEN_PROGRAM_ID })
  .rpc();
const after = (await program.account.config.fetch(configPda())).sponsorBudget.toNumber() / SKR;
log(`sponsor budget ${before} -> ${after} SKR`);
