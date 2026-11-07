#!/usr/bin/env node
// Verifies a 14-character XhovoSU card key the same way the Manager does.
//
//   node verify.mjs --key cardkey_secret.bin --code "XXXX-XXXX-XXXX-XX" [--device <指纹>]

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { checkLicence } from "./cardkey-format.mjs";

const args = process.argv.slice(2);
const flag = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : fallback;
};

const keyPath = flag("--key");
const code = flag("--code") ?? args[args.length - 1];
if (!keyPath || !code) {
  console.error("usage: node verify.mjs --key <secret.bin> --code <XXXX-XXXX-XXXX-XX> [--device <指纹>]");
  process.exit(2);
}

const secret = readFileSync(resolve(keyPath));
try {
  const claims = checkLicence(secret, code, { deviceFingerprint: flag("--device") });
  console.log(
    `valid · serial ${claims.serial} · ${claims.permanent ? "permanent" : `expires ${new Date(claims.notAfter * 1000).toISOString().slice(0, 10)}`} · ${claims.deviceBound ? `bound to ${claims.deviceTag}` : "unbound"}`,
  );
} catch (error) {
  console.error(`invalid: ${error.message}`);
  process.exit(1);
}
