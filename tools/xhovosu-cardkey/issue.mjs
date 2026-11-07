#!/usr/bin/env node
// Signs XhovoSU licences offline.
//
//   node issue.mjs --key xhovo_license_private_key.pk8 --days 30 --count 5
//   node issue.mjs --key key.pk8 --device <manager fingerprint> --days 0
//
// --days 0 issues a permanent licence. Passing --device binds the licence to
// exactly that fingerprint; the Manager rejects it everywhere else.

import { createPublicKey } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { deviceTagOf, issue, keyFingerprint, publicKeyDer } from "./cardkey-format.mjs";

const args = process.argv.slice(2);

function flag(name, fallback) {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : fallback;
}

const keyPath = flag("--key");
if (!keyPath) {
  console.error("usage: node issue.mjs --key <pkcs8|pem> [--days N] [--count N] [--device <fingerprint>] [--out file]");
  process.exit(2);
}

const days = Number(flag("--days", "30"));
const count = Number(flag("--count", "1"));
const device = flag("--device");
if (!Number.isInteger(days) || days < 0) throw new Error("--days must be a non-negative integer");
if (!Number.isInteger(count) || count < 1 || count > 1000) throw new Error("--count must be between 1 and 1000");

const rawKey = readFileSync(resolve(keyPath));
const textKey = rawKey.toString("utf8");
const key = textKey.includes("BEGIN PRIVATE KEY") ? textKey : rawKey;

const notAfter = days === 0 ? 0 : Math.floor(Date.now() / 1000) + days * 86400;
const bound = Boolean(device);
// --device is the 64 hex fingerprint the Manager shows, not the raw Android ID.
const deviceTag = bound ? deviceTagOf(device) : Buffer.alloc(3);
const codes = [];
for (let i = 0; i < count; i += 1) codes.push(issue(key, { notAfter, deviceTag, deviceBound: bound }));

const outPath = flag("--out");
if (outPath) writeFileSync(resolve(outPath), `${codes.join("\n")}\n`);
console.log(codes.join("\n"));

const publicKey = publicKeyDer(
  createPublicKey(Buffer.isBuffer(key) ? { key, format: "der", type: "pkcs8" } : key),
);
const fingerprint = keyFingerprint(publicKey);
console.error(`issued ${count} licence(s) · public key ${fingerprint} · ${bound ? `bound to ${deviceTag.toString("hex")}` : "unbound"} · ${days === 0 ? "permanent" : `${days} day(s)`}`);
