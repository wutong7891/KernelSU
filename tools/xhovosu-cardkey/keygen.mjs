#!/usr/bin/env node
// Generates the XhovoSU card-key secret.
//
// A 14-character code can only carry a truncated MAC, so the secret is shared:
// the issuer signs with it, the Manager verifies with it. Treat it like a
// password and rotate it by rebuilding the Manager.

import { randomBytes } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const args = process.argv.slice(2);
const readFlag = (name) => {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : undefined;
};

const outDir = resolve(readFlag("--out") ?? "xhovo-cardkey-keys");
const secret = randomBytes(32);

mkdirSync(outDir, { recursive: true });
const binPath = resolve(outDir, "cardkey_secret.bin");
const b64Path = resolve(outDir, "cardkey_secret.b64");
writeFileSync(binPath, secret);
writeFileSync(b64Path, `${secret.toString("base64")}\n`, "ascii");

console.log(`secret written to ${dirname(binPath)}`);
console.log("");
console.log("Manager constant (CardKeyKeys.SECRET_BASE64):");
console.log(secret.toString("base64"));
console.log("");
console.log("Issuer secret (GitHub secret XHOVO_CARDKEY_SECRET_B64):");
console.log(secret.toString("base64"));
console.log("");
console.log("Keep it private: anyone holding it can print valid card keys.");
