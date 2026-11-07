#!/usr/bin/env node
// Generates the XhovoSU licence key pair.
//
// The private key signs licences and belongs to the issuer only; the public key
// is embedded in the Manager. Run this once, keep the private key in a password
// manager or the XHOVO_LICENSE_PRIVATE_KEY_B64 secret, and never commit it.

import { generateKeyPairSync } from "node:crypto";
import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { keyFingerprint, publicKeyDer } from "./cardkey-format.mjs";

const args = process.argv.slice(2);
const outDir = resolve(readFlag(args, "--out") ?? "xhovo-cardkey-keys");

function readFlag(argv, name) {
  const index = argv.indexOf(name);
  return index >= 0 ? argv[index + 1] : undefined;
}

const { publicKey, privateKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
const spki = publicKeyDer(publicKey);
const pkcs8 = privateKey.export({ format: "der", type: "pkcs8" });
const pkcs8Base64 = pkcs8.toString("base64");
const spkiBase64 = spki.toString("base64");

mkdirSync(outDir, { recursive: true });
const privatePath = resolve(outDir, "xhovo_license_private_key.pk8");
const publicPath = resolve(outDir, "xhovo_public_key.der");
writeFileSync(privatePath, pkcs8);
writeFileSync(publicPath, spki);

const fingerprint = keyFingerprint(spki);
console.log(`key pair written to ${dirname(privatePath)}`);
console.log(`public key fingerprint: ${fingerprint}`);
console.log("");
console.log("Manager constant (CardKeyKeys.PUBLIC_KEY_DER_BASE64):");
console.log(spkiBase64);
console.log("");
console.log("Issuer secret (GitHub secret XHOVO_LICENSE_PRIVATE_KEY_B64):");
console.log(pkcs8Base64);
console.log("");
console.log("Keep the private key secret. Anyone holding it can mint licences.");
