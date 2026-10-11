// Watches a USB-connected phone running Goldgrid and checks what it shows against
// the chain, every few seconds: the countdown, the round's name, and any ticket
// stuck on "Draw delayed". Prints one line per sample and a verdict.
//   node timer-check.mjs [samples] [--quiet]
import { execFileSync } from "node:child_process";
import { chainNow, connect, currentRound, loadKeypair, roundTimes } from "./lib.mjs";
import { fileURLToPath } from "node:url";

const ADB = "E:/Android/sdk/platform-tools/adb.exe";
const admin = loadKeypair(fileURLToPath(new URL("../keys/crank.json", import.meta.url)));
const { connection, program } = connect(admin);
const [{ account: config }] = await program.account.config.all();
const samples = Number(process.argv[2] ?? 8);
const quiet = process.argv.includes("--quiet");
const dayNumber = (id) => {
  const draw = roundTimes(config, id).draw;
  const midnight = Math.floor(draw / 86400) * 86400;
  return Math.round((draw - midnight - (config.genesisTs.toNumber() % 300)) / 300);
};
let worst = 0, timed = 0, badLabel = 0, delayed = 0, failures = 0;

for (let i = 0; i < samples; i++) {
  let xml, before;
  try {
    before = await chainNow(connection);
    execFileSync(ADB, ["shell", "uiautomator", "dump", "/sdcard/ui.xml"], { stdio: "ignore" });
    xml = execFileSync(ADB, ["exec-out", "cat", "/sdcard/ui.xml"]).toString();
  } catch {
    failures++;
    continue;
  }
  let now;
  try {
    now = await chainNow(connection);
  } catch {
    failures++;
    continue;
  }
  const texts = [...xml.matchAll(/text="([^"]*)"/g)].map((m) => m[1]);
  const label = texts.find((t) => /Round \d{4}-\d\d-\d\d · #\d+/.test(t));
  const timer = texts.find((t) => /^\d\d:\d\d$/.test(t));
  const phase = texts.find((t) => /closes in|Entries close in|Winning numbers in|draw in$/.test(t));
  const stuck = texts.filter((t) => /Draw delayed/.test(t)).length;
  delayed = Math.max(delayed, stuck);
  const id = currentRound(config, now);
  const { close, draw } = roundTimes(config, id);
  const expect = (now < close ? close : draw) - now;
  let note = "";
  if (timer && phase) {
    const [m, s] = timer.split(":").map(Number);
    // The screen was captured somewhere between `before` and `now`: an answer
    // anywhere in that window is right, so only the distance outside it counts.
    const window = now - before;
    let d = m * 60 + s - expect;
    if (d > 0) d = Math.max(0, d - window);
    // At a boundary the screen is read a second or two before the chain clock, so
    // the round that just drew may still show its last seconds: compare it with
    // that round's draw instead.
    if (m * 60 + s <= 6 && expect >= config.roundSecs.toNumber() - config.entrySecs.toNumber() + 240) d = m * 60 + s - (roundTimes(config, id - 1).draw - now);
    worst = Math.max(worst, Math.abs(d));
    timed++;
    note = `shown ${timer} expected ${expect}s (read window ${window}s) error ${d}s`;
  }
  if (label) {
    const shownNo = Number(label.match(/#(\d+)/)[1]);
    // Around a boundary the screen may still show the round that just closed.
    if (shownNo !== dayNumber(id) && shownNo !== dayNumber(id - 1)) badLabel++;
    note += ` | ${label.replace("Round ", "")} (chain #${dayNumber(id)})`;
  }
  if (stuck) note += ` | ${stuck} DRAW DELAYED`;
  if (!quiet) console.log(new Date(now * 1000).toISOString().slice(11, 19), note || texts.slice(0, 3).join(" / "));
  await new Promise((r) => setTimeout(r, 6000));
}
const ok = worst <= 6 && badLabel === 0 && delayed === 0;
console.log(`${ok ? "PASS" : "FAIL"}: ${timed} timer reads, worst ${worst}s; wrong round names ${badLabel}; delayed tickets ${delayed}; unreadable screens ${failures}`);
process.exitCode = ok ? 0 : 1;
