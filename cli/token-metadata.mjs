// Gives the devnet test mint a name, symbol and logo, so wallets show
// "Test SKR (devnet)" instead of "Unknown token". Wallets read these from the
// Metaplex Token Metadata account of the mint; without one they have nothing to
// show. Only the mint authority (the admin) can create it.
//
//   node token-metadata.mjs            # show what is on chain
//   node token-metadata.mjs --write    # create, or update if it exists
//
// The instruction is built by hand (Borsh, CreateMetadataAccountV3 = 33,
// UpdateMetadataAccountV2 = 15) to avoid pulling in the Metaplex SDK for one call.
import { PublicKey, SystemProgram, TransactionInstruction } from "@solana/web3.js";
import { connect, loadKeypair, send } from "./lib.mjs";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const META = new PublicKey("metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s");
const state = JSON.parse(readFileSync(new URL("./devnet-state.json", import.meta.url)));
const mint = new PublicKey(state.mint);
const ADMIN_PATH = process.env.ADMIN_KEYPAIR ?? fileURLToPath(new URL("../spikes/switchboard-devnet/payer.json", import.meta.url));
const admin = loadKeypair(ADMIN_PATH);
const { connection } = connect(admin);

const NAME = "Test SKR (devnet)";
const SYMBOL = "SKR";
const URI = "https://elaris-xyz.github.io/goldgrid/token/test-skr.json";

const [metadata] = PublicKey.findProgramAddressSync([Buffer.from("metadata"), META.toBuffer(), mint.toBuffer()], META);

const str = (s) => {
  const b = Buffer.from(s, "utf8");
  const len = Buffer.alloc(4);
  len.writeUInt32LE(b.length);
  return Buffer.concat([len, b]);
};
const u16 = (n) => {
  const b = Buffer.alloc(2);
  b.writeUInt16LE(n);
  return b;
};
const NONE = Buffer.from([0]);
const SOME = Buffer.from([1]);
// DataV2: name, symbol, uri, seller_fee_basis_points, creators, collection, uses
const dataV2 = Buffer.concat([str(NAME), str(SYMBOL), str(URI), u16(0), NONE, NONE, NONE]);

const create = () =>
  new TransactionInstruction({
    programId: META,
    keys: [
      { pubkey: metadata, isSigner: false, isWritable: true },
      { pubkey: mint, isSigner: false, isWritable: false },
      { pubkey: admin.publicKey, isSigner: true, isWritable: false }, // mint authority
      { pubkey: admin.publicKey, isSigner: true, isWritable: true }, // payer
      { pubkey: admin.publicKey, isSigner: true, isWritable: false }, // update authority
      { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
    ],
    // is_mutable = true, collection_details = None
    data: Buffer.concat([Buffer.from([33]), dataV2, Buffer.from([1]), NONE]),
  });

const update = () =>
  new TransactionInstruction({
    programId: META,
    keys: [
      { pubkey: metadata, isSigner: false, isWritable: true },
      { pubkey: admin.publicKey, isSigner: true, isWritable: false }, // update authority
    ],
    // data = Some(DataV2), new_update_authority = None, primary_sale_happened = None, is_mutable = None
    data: Buffer.concat([Buffer.from([15]), SOME, dataV2, NONE, NONE, NONE]),
  });

const show = async () => {
  const acc = await connection.getAccountInfo(metadata);
  if (!acc) return console.log("no metadata yet for", mint.toBase58());
  // key(1) update_authority(32) mint(32), then the three Borsh strings
  let at = 65;
  const read = () => {
    const n = acc.data.readUInt32LE(at);
    const s = acc.data.subarray(at + 4, at + 4 + n).toString("utf8").replace(/\0+$/, "");
    at += 4 + n;
    return s;
  };
  console.log({ metadata: metadata.toBase58(), name: read(), symbol: read(), uri: read() });
};

if (process.argv.includes("--write")) {
  const exists = await connection.getAccountInfo(metadata);
  await send(connection, [exists ? update() : create()], [admin], exists ? "update token metadata" : "create token metadata");
}
await show();
