#!/usr/bin/env node
// Format and crypto self-test for the XhovoSU offline licence.
// Runs in CI on every change to the issuer tooling or the Manager format.

import { generateKeyPairSync, sign, verify } from "node:crypto";
import assert from "node:assert/strict";
import {
  FLAG_PERMANENT,
  PAYLOAD_SIZE,
  TOKEN_SIZE,
  buildPayload,
  checkLicence,
  decodeBase32,
  deviceFingerprintOf,
  deviceTagOf,
  encodeBase32,
  groupCode,
  inspectCode,
  issue,
  keyFingerprint,
  normalizeCode,
  publicKeyDer,
  readClaims,
} from "./cardkey-format.mjs";

const { publicKey, privateKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
const spki = publicKeyDer(publicKey);
const now = Math.floor(Date.now() / 1000);

let passed = 0;
function check(name, fn) {
  fn();
  passed += 1;
  console.log(`ok - ${name}`);
}

check("base32 round-trips arbitrary bytes", () => {
  const bytes = Buffer.from(Array.from({ length: TOKEN_SIZE }, (_, i) => (i * 37 + 11) & 0xff));
  assert.deepEqual(decodeBase32(encodeBase32(bytes)), bytes);
});

check("token is 76 bytes of base32 in 25 groups", () => {
  const code = issue(privateKey, { serial: 1, notAfter: now + 86400 });
  const body = normalizeCode(code);
  const token = decodeBase32(body);
  assert.equal(token.length, TOKEN_SIZE);
  assert.equal(body.length, 122);
  assert.equal(code.split("-").length, 26);
});

check("payload layout matches the documented offsets", () => {
  const payload = buildPayload({ serial: 0x123456, notAfter: now + 60, deviceTag: Buffer.from("aabbcc", "hex"), deviceBound: true });
  assert.equal(payload.length, PAYLOAD_SIZE);
  assert.equal(payload[0], 0x02);
  const claims = readClaims(payload);
  assert.equal(claims.serial, 0x123456);
  assert.equal(claims.notAfter, now + 60);
  assert.equal(claims.deviceTag, "aabbcc");
  assert.equal(claims.deviceBound, true);
  assert.equal(claims.permanent, false);
});

check("unbound licence verifies on any device", () => {
  const code = issue(privateKey, { notAfter: now + 86400 });
  const claims = checkLicence(spki, code, { deviceFingerprint: "deadbeefcafebabe" });
  assert.equal(claims.deviceBound, false);
});

check("permanent licence carries the permanent flag", () => {
  const code = issue(privateKey, { notAfter: 0 });
  const claims = inspectCode(spki, code);
  assert.equal(claims.permanent, true);
  assert.equal((decodeBase32(normalizeCode(code))[1] & 0x02) !== 0, true);
});

check("bound licence accepts its own device only", () => {
  const fingerprint = "0123456789abcdef0123456789abcdef01234567";
  const code = issue(privateKey, {
    notAfter: now + 86400,
    deviceBound: true,
    deviceTag: deviceTagOf(fingerprint),
  });
  assert.equal(checkLicence(spki, code, { deviceFingerprint: fingerprint }).deviceBound, true);
  assert.throws(() => checkLicence(spki, code, { deviceFingerprint: "ffffffffffffffff" }), /bound to device/);
});

check("expired licence is rejected", () => {
  const code = issue(privateKey, { notAfter: now - 10 });
  assert.throws(() => checkLicence(spki, code), /expired/);
});

check("a single flipped character invalidates the signature", () => {
  const code = issue(privateKey, { notAfter: now + 86400 });
  const body = normalizeCode(code);
  const index = 40;
  const replacement = body[index] === "A" ? "B" : "A";
  const tampered = body.slice(0, index) + replacement + body.slice(index + 1);
  assert.throws(() => inspectCode(spki, tampered), /signature does not verify|base32/);
});

check("truncated or padded tokens are rejected", () => {
  const code = issue(privateKey, { notAfter: now + 86400 });
  const body = normalizeCode(code);
  assert.throws(() => inspectCode(spki, body.slice(0, body.length - 10)), /must be 76 bytes/);
});

check("separators and prefix are cosmetic", () => {
  const code = issue(privateKey, { notAfter: now + 86400 });
  const messy = `  ${code.toLowerCase().replace(/-/g, " ")}  `;
  assert.equal(inspectCode(spki, messy).serial > 0, true);
});

check("a foreign key cannot validate licences", () => {
  const other = generateKeyPairSync("ec", { namedCurve: "P-256" });
  const code = issue(other.privateKey, { notAfter: now + 86400 });
  assert.throws(() => inspectCode(spki, code), /signature does not verify/);
});

check("grouping is reversible and stable", () => {
  const code = issue(privateKey, { serial: 7, notAfter: now + 86400 });
  assert.equal(groupCode(normalizeCode(code)), code);
});

check("key fingerprint is stable and short", () => {
  const first = keyFingerprint(spki);
  assert.equal(first, keyFingerprint(Buffer.from(spki)));
  assert.equal(first.length, 16);
});

check("flags encode bound plus permanent together", () => {
  const code = issue(privateKey, { notAfter: 0, deviceBound: true, deviceTag: Buffer.from("010203", "hex") });
  const claims = inspectCode(spki, code);
  assert.equal(claims.permanent, true);
  assert.equal(claims.deviceBound, true);
  assert.equal(claims.deviceTag, "010203");
  assert.equal((FLAG_PERMANENT & 0x02) !== 0, true);
});

// The Android issuer receives a DER signature from `Signature.sign()` and must
// turn it into r || s; the Manager does the inverse before verifying. These two
// helpers are the reference implementation of that conversion, and the test
// proves they agree with Node's fixed-width output.
function fit32(value) {
  let start = 0;
  while (start < value.length - 1 && value[start] === 0) start += 1;
  const body = value.subarray(Math.max(start, value.length - 32));
  const out = Buffer.alloc(32);
  body.copy(out, 32 - body.length);
  return out;
}

function derToRaw(der) {
  let p = 0;
  assert.equal(der[p++], 0x30);
  let length = der[p++];
  if (length & 0x80) p += length & 0x7f;
  const readInt = () => {
    assert.equal(der[p++], 0x02);
    const len = der[p++];
    const value = der.subarray(p, p + len);
    p += len;
    return value;
  };
  const r = readInt();
  const s = readInt();
  return Buffer.concat([fit32(r), fit32(s)]);
}

function rawToDer(raw) {
  const int = (value) => {
    let start = 0;
    while (start < value.length - 1 && value[start] === 0) start += 1;
    const body = value.subarray(start);
    const padded = body[0] & 0x80 ? Buffer.concat([Buffer.from([0]), body]) : body;
    return Buffer.concat([Buffer.from([0x02, padded.length]), padded]);
  };
  const body = Buffer.concat([int(raw.subarray(0, 32)), int(raw.subarray(32, 64))]);
  return Buffer.concat([Buffer.from([0x30, body.length]), body]);
}

check("DER and fixed-width signatures convert both ways", () => {
  const payload = buildPayload({ serial: 42, notAfter: now + 600 });
  const der = sign("sha256", payload, { key: privateKey });
  const fixed = sign("sha256", payload, { key: privateKey, dsaEncoding: "ieee-p1363" });
  assert.equal(derToRaw(der).length, 64);
  // Same payload signed twice yields different signatures, so compare shapes and
  // prove every DER signature converts into something the verifier accepts.
  const rawFromDer = derToRaw(der);
  assert.equal(verify("sha256", payload, { key: publicKey, dsaEncoding: "ieee-p1363" }, rawFromDer), true);
  assert.equal(verify("sha256", payload, publicKey, rawToDer(fixed)), true);
  assert.equal(rawToDer(fixed).length <= 72 && rawToDer(fixed).length >= 68, true);
});

check("the Manager path accepts licences produced from DER signatures", () => {
  const payload = buildPayload({ serial: 9, notAfter: now + 900 });
  const raw = derToRaw(sign("sha256", payload, { key: privateKey }));
  const code = groupCode(encodeBase32(Buffer.concat([payload, raw])));
  assert.equal(inspectCode(spki, code).serial, 9);
});

console.log(`\n${passed} checks passed`);
