// Compares the countdown the app shows on a USB-connected phone with the one the
// chain clock gives, every few seconds:
//   node timer-check.mjs [samples]
import { execFileSync } from "node:child_process";
import { chainNow, connect, currentRound, loadKeypair, roundTimes } from "./lib.mjs";
import { fileURLToPath } from "node:url";

const ADB = "E:/Android/sdk/platform-tools/adb.exe";
const admin = loadKeypair(fileURLToPath(new URL("../keys/crank.json", import.meta.url)));
const { connection, program } = connect(admin);
const [{ account: config }] = await program.account.config.all();
const samples = Number(process.argv[2] ?? 8);
let worst = 0;

for (let i = 0; i < samples; i++) {
  execFileSync(ADB, ["shell", "uiautomator", "dump", "/sdcard/ui.xml"], { stdio: "ignore" });
  const xml = execFileSync(ADB, ["exec-out", "cat", "/sdcard/ui.xml"]).toString();
  const now = await chainNow(connection);
  const texts = [...xml.matchAll(/text="([^"]*)"/g)].map((m) => m[1]);
  const label = texts.find((t) => /Round \d{4}-\d\d-\d\d · #\d+/.test(t)) ?? "?";
  const timer = texts.find((t) => /^\d\d:\d\d$/.test(t));
  const phase = texts.find((t) => /closes in|Entries close in|Winning numbers in|Next round/.test(t)) ?? "?";
  const id = currentRound(config, now);
  const { close, draw } = roundTimes(config, id);
  const target = now < close ? close : draw;
  const expect = target - now;
  let diff = "";
  if (timer) {
    const [m, s] = timer.split(":").map(Number);
    const shown = m * 60 + s;
    const d = shown - expect;
    worst = Math.max(worst, Math.abs(d));
    diff = `shown ${timer} expected ${String(Math.floor(expect / 60)).padStart(2, "0")}:${String(expect % 60).padStart(2, "0")} diff ${d}s`;
  }
  console.log(new Date(now * 1000).toISOString().slice(11, 19), `round ${id}`, "|", label, "|", phase, "|", diff || "no timer on screen");
  await new Promise((r) => setTimeout(r, 6000));
}
console.log(`worst difference ${worst}s`);
