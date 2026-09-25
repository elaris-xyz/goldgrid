// Finds a real SGT member mint on mainnet and dumps its raw account data,
// so the on-chain parser can be tested against the real layout.
import { Connection, PublicKey } from "@solana/web3.js";
import fs from "node:fs";
const conn = new Connection("https://api.mainnet-beta.solana.com", "confirmed");
const GROUP = new PublicKey("GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te");
const TOKEN22 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb";
const sigs = await conn.getSignaturesForAddress(GROUP, { limit: 15 });
for (const s of sigs) {
  const tx = await conn.getParsedTransaction(s.signature, { maxSupportedTransactionVersion: 0 });
  for (const key of tx?.transaction.message.accountKeys ?? []) {
    const k = key.pubkey.toBase58();
    if (k === GROUP.toBase58()) continue;
    const info = await conn.getAccountInfo(key.pubkey);
    if (info?.owner.toBase58() !== TOKEN22 || info.data.length <= 165 || info.data[165] !== 1) continue;
    const parsed = await conn.getParsedAccountInfo(key.pubkey);
    const member = parsed.value.data.parsed.info.extensions?.find((e) => e.extension === "tokenGroupMember");
    if (member?.state.group === GROUP.toBase58()) {
      fs.writeFileSync("../../programs/daily_draw/tests/fixtures/sgt-member-mint.json",
        JSON.stringify({ mint: k, data: info.data.toString("base64") }, null, 1));
      console.log("real SGT member mint:", k, "member #", member.state.memberNumber, "bytes:", info.data.length);
      process.exit(0);
    }
  }
}
console.log("no member mint found in the last", sigs.length, "transactions");
