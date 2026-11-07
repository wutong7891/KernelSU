// Contract test for the offline Night licence.
//
// Night no longer has a licence server: the Manager verifies an ECDSA P-256
// token locally, and the issuer holds the only private key. This test fails if
// somebody reintroduces networking, drops the embedded key, or lets the token
// format drift away from the issuer tooling.

import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { generateKeyPairSync } from "node:crypto";
import {
  PAYLOAD_SIZE,
  SIGNATURE_SIZE,
  TOKEN_SIZE,
  decodeBase32,
  inspectCode,
  issue,
  normalizeCode,
  publicKeyDer,
} from "../../tools/night-offline-issuer/night-license.mjs";

const activation = await readFile(
  new URL("../../manager/app/src/main/java/me/weishu/kernelsu/ui/activation/NightActivation.kt", import.meta.url),
  "utf8",
);
const keys = await readFile(
  new URL("../../manager/app/src/main/java/me/weishu/kernelsu/ui/activation/NightLicenseKeys.kt", import.meta.url),
  "utf8",
);

const embedded = keys.match(/PUBLIC_KEY_DER_BASE64: String =\s*"([^"]+)"/)?.[1];
assert.ok(embedded, "the Manager must embed a verification key");
const embeddedDer = Buffer.from(embedded, "base64");
assert.equal(embeddedDer.length, 91, "embedded key must be a P-256 SubjectPublicKeyInfo");
assert.equal(embeddedDer[0], 0x30, "embedded key must be DER");
assert.equal(embeddedDer[1], 0x59, "embedded key must be a 89 byte DER sequence");

assert.doesNotMatch(activation, /okhttp3|OkHttpClient/, "licence verification must not use HTTP");
assert.doesNotMatch(activation, /https?:\/\//, "licence verification must not reference a server");
assert.doesNotMatch(activation, /ENDPOINTS|session\/start|sessionProof|sealedPacket/, "the old session protocol must be gone");
assert.match(activation, /SHA256withECDSA/, "verification must use ECDSA over SHA-256");
assert.match(activation, /TOKEN_SIZE = PAYLOAD_SIZE \+ SIGNATURE_SIZE/, "token size must stay explicit");
assert.match(activation, /FLAG_DEVICE_BOUND/, "device binding must stay implemented");

// The shipped key parses, and it is not the throwaway key below.
const { publicKey, privateKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
const now = Math.floor(Date.now() / 1000);
const licence = issue(privateKey, { notAfter: now + 3600 });

const token = decodeBase32(normalizeCode(licence));
assert.equal(token.length, TOKEN_SIZE);
assert.equal(TOKEN_SIZE, PAYLOAD_SIZE + SIGNATURE_SIZE);
assert.equal(inspectCode(publicKeyDer(publicKey), licence).permanent, false);
assert.throws(() => inspectCode(embeddedDer, licence), /signature does not verify/);

const permanent = issue(privateKey, { notAfter: 0, serial: 3 });
assert.equal(inspectCode(publicKeyDer(publicKey), permanent).permanent, true);

const tampered = normalizeCode(licence);
const flipped = (tampered[30] === "A" ? "B" : "A") + tampered.slice(31);
assert.throws(
  () => inspectCode(publicKeyDer(publicKey), tampered.slice(0, 30) + flipped),
  /signature does not verify|base32/,
);

const sucompatSource = await readFile(
  new URL("../../kernel/feature/sucompat.c", import.meta.url),
  "utf8",
);
assert.match(sucompatSource, /ksu_is_allow_uid_for_current\(current_uid\(\)\.val\)/);
assert.doesNotMatch(sucompatSource, /online_root_lease/);

const supercallPermSource = await readFile(
  new URL("../../kernel/supercall/perm.c", import.meta.url),
  "utf8",
);
assert.match(supercallPermSource, /return ksu_is_allow_uid_for_current\(current_uid\(\)\.val\);/);
assert.doesNotMatch(supercallPermSource, /online_root_lease/);

console.log("Night offline licence contract holds: local ECDSA verification, no server");
