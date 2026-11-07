#!/usr/bin/env node
// Verifies a Night licence exactly the way the Manager does.
//
//   node verify.mjs --pub night_license_public_key.der --code "NIGHT-..." [--device <fingerprint>]

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { assertP256Spki, checkLicence } from "./night-license.mjs";

const args = process.argv.slice(2);

function flag(name, fallback) {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : fallback;
}

const pubPath = flag("--pub");
const code = flag("--code") ?? process.argv[process.argv.length - 1];
if (!pubPath || !code) {
  console.error("usage: node verify.mjs --pub <spki.der> --code <NIGHT-...> [--device <fingerprint>]");
  process.exit(2);
}

const der = readFileSync(resolve(pubPath));
assertP256Spki(der);

try {
  const claims = checkLicence(der, code, { deviceFingerprint: flag("--device") });
  console.log(`valid · serial ${claims.serial} · ${claims.permanent ? "permanent" : `expires ${new Date(claims.notAfter * 1000).toISOString()}`} · ${claims.deviceBound ? `bound to ${claims.deviceTag}` : "unbound"}`);
} catch (error) {
  console.error(`invalid: ${error.message}`);
  process.exit(1);
}
