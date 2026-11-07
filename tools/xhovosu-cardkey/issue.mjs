#!/usr/bin/env node
// Issues XhovoSU 14-character card keys.
//
//   node issue.mjs --key cardkey_secret.bin --days 30 --count 5
//   node issue.mjs --key cardkey_secret.bin --days 0                 # permanent
//   node issue.mjs --key cardkey_secret.bin --days 0 --device <指纹>  # bound

import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { deviceTagOf, issue } from "./cardkey-format.mjs";

const args = process.argv.slice(2);
const flag = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : fallback;
};

const keyPath = flag("--key");
if (!keyPath) {
  console.error("usage: node issue.mjs --key <secret.bin> [--days N] [--count N] [--device <指纹>] [--out file]");
  process.exit(2);
}

const days = Number(flag("--days", "30"));
const count = Number(flag("--count", "1"));
const device = flag("--device");
if (!Number.isInteger(days) || days < 0) throw new Error("--days must be a non-negative integer");
if (!Number.isInteger(count) || count < 1 || count > 1000) throw new Error("--count must be between 1 and 1000");

const secret = readFileSync(resolve(keyPath));
if (secret.length < 16) throw new Error("secret must be at least 16 bytes");

const notAfter = days === 0 ? 0 : Math.floor(Date.now() / 1000) + days * 86400;
const bound = Boolean(device);
const deviceTag = bound ? deviceTagOf(device) : 0;

const codes = [];
for (let i = 0; i < count; i += 1) {
  codes.push(issue(secret, { notAfter, deviceTag, deviceBound: bound }));
}

const outPath = flag("--out");
if (outPath) writeFileSync(resolve(outPath), `${codes.join("\n")}\n`);
console.log(codes.join("\n"));
console.error(
  `issued ${count} key(s) · ${bound ? `bound to ${deviceTag.toString(16).padStart(3, "0")}` : "unbound"} · ${days === 0 ? "permanent" : `${days} day(s)`}`,
);
