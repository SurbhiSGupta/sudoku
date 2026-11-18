// Makes Support Bodai codes for friends, family and testers.
//
//   node play-store/tools/make-codes.mjs --count 10 --until 2026-12-31
//   node play-store/tools/make-codes.mjs --count 5 --lifetime --first 100
//
// --until    last day the code works (YYYY-MM-DD); --lifetime never ends
// --first    serial number to start from (1-4095), so batches don't repeat
// The secret is read from index.html, so codes always match the game.
import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const args = process.argv.slice(2);
const flag = (name) => args.includes(`--${name}`);
const value = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : fallback;
};

const html = readFileSync(fileURLToPath(new URL("../../index.html", import.meta.url)), "utf8");
const secret = html.match(/const CODE_SECRET = "([^"]+)"/)[1];
const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const EPOCH = Date.UTC(2026, 0, 1);

const count = Number(value("count", 1));
const first = Number(value("first", 1));
let days = 0;
if (!flag("lifetime")) {
  const until = value("until");
  if (!until) throw new Error("Give --until YYYY-MM-DD or --lifetime");
  days = Math.round((Date.parse(`${until}T00:00:00Z`) - EPOCH) / 864e5);
  if (days < 1 || days > 0xffff) throw new Error("--until must be after 1 Jan 2026");
}
if (first < 1 || first + count - 1 > 0xfff) throw new Error("Serials run from 1 to 4095");

function makeCode(serial) {
  const payload = Buffer.from([days >> 8, days & 255, serial >> 8, serial & 255]);
  const sig = createHmac("sha256", secret).update(payload).digest().readUInt32BE(0);
  let bits = (BigInt(days) << 44n) | (BigInt(serial) << 32n) | BigInt(sig);
  let text = "";
  for (let i = 0; i < 12; i++) {
    text = ALPHABET[Number(bits & 31n)] + text;
    bits >>= 5n;
  }
  return text.match(/.{4}/g).join("-");
}

const label = days ? `until ${value("until")}` : "lifetime";
for (let serial = first; serial < first + count; serial++) {
  console.log(`${makeCode(serial)}  #${serial} ${label}`);
}
