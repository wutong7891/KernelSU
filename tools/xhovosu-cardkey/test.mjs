#!/usr/bin/env node
// Format and MAC self-test for the 14-character XhovoSU card key.

import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import {
  ALPHABET,
  CHECK_BITS,
  CHECK_CHARS,
  CODE_LENGTH,
  MAX_DAY_CODE,
  PAYLOAD_BITS,
  PAYLOAD_CHARS,
  checkLicence,
  dayCodeFor,
  decodeBase32,
  deviceTagOf,
  encodeBase32,
  groupCode,
  inspectCode,
  issue,
  normalizeCode,
  notAfterFromDayCode,
} from "./cardkey-format.mjs";

const secret = randomBytes(32);
const now = Math.floor(Date.now() / 1000);

let passed = 0;
function check(name, fn) {
  fn();
  passed += 1;
  console.log(`ok - ${name}`);
}

check("base32 round-trips a 40-bit payload", () => {
  for (const value of [0, 1, 32, 123456789, 2 ** 40 - 1]) {
    assert.equal(decodeBase32(encodeBase32(value, PAYLOAD_BITS), PAYLOAD_BITS), value);
  }
});

check("a card key is exactly 14 characters in 4 groups", () => {
  const code = issue(secret, { serial: 1, notAfter: 0 });
  assert.equal(code.length, CODE_LENGTH + 3, "14 chars plus 3 separators");
  assert.equal(normalizeCode(code).length, CODE_LENGTH);
  assert.equal(code.split("-").length, 4);
  assert.deepEqual(code.split("-").map((part) => part.length), [4, 4, 4, 2]);
});

check("every character comes from the Crockford alphabet", () => {
  const code = normalizeCode(issue(secret, { serial: 9, notAfter: 0 }));
  for (const char of code) assert.ok(ALPHABET.includes(char), `unexpected char ${char}`);
});

check("fields survive the round trip", () => {
  const notAfter = now + 86400 * 17;
  const code = issue(secret, { serial: 0xabcd, notAfter, deviceTag: 0x123, deviceBound: true });
  const claims = inspectCode(secret, code);
  assert.equal(claims.serial, 0xabcd);
  assert.equal(claims.deviceTag, "123");
  assert.equal(claims.deviceBound, true);
  assert.equal(claims.permanent, false);
});

check("permanent keys carry dayCode 0", () => {
  const claims = inspectCode(secret, issue(secret, { serial: 3, notAfter: 0 }));
  assert.equal(claims.permanent, true);
  assert.equal(claims.notAfter, 0);
});

check("expiry is day-granular and matches the request", () => {
  const notAfter = now + 86400 * 10;
  const code = issue(secret, { serial: 5, notAfter });
  const claims = inspectCode(secret, code);
  assert.ok(Math.abs(claims.notAfter - notAfter) <= 86400);
});

check("day codes clamp inside 12 bits", () => {
  assert.equal(dayCodeFor(0), 0);
  assert.throws(() => dayCodeFor(2 ** 40), /outside the 12-bit/);
  assert.equal(notAfterFromDayCode(0), 0);
  assert.ok(MAX_DAY_CODE === 0xfff);
});

check("a single changed character breaks the checksum", () => {
  const body = normalizeCode(issue(secret, { serial: 7, notAfter: 0 }));
  const index = 5;
  const replaced = body[index] === "A" ? "B" : "A";
  const tampered = body.slice(0, index) + replaced + body.slice(index + 1);
  assert.throws(() => inspectCode(secret, tampered), /checksum does not match/);
});

check("wrong length or bad letters are rejected", () => {
  const code = normalizeCode(issue(secret, { serial: 1, notAfter: 0 }));
  assert.throws(() => inspectCode(secret, code.slice(0, 13)), /must be 14 characters/);
  assert.throws(() => inspectCode(secret, `${code.slice(0, 13)}!`), /must be 14 characters|invalid characters|checksum/);
  const withBadLetter = `${code.slice(0, 13)}U`;
  assert.throws(() => inspectCode(secret, withBadLetter), /invalid characters|checksum/);
});

check("a different secret cannot validate the key", () => {
  const code = issue(secret, { serial: 11, notAfter: 0 });
  assert.throws(() => inspectCode(randomBytes(32), code), /checksum does not match/);
});

check("separators and the XHOVO prefix are cosmetic", () => {
  const code = issue(secret, { serial: 13, notAfter: 0 });
  const messy = `  xhovo${code.replace(/-/g, " ").toLowerCase()}  `;
  assert.equal(inspectCode(secret, messy).serial, 13);
});

check("device binding accepts only the tagged device", () => {
  const fingerprint = "8f14e45fceea167a5a36dedd4bea2543";
  const code = issue(secret, {
    serial: 17,
    notAfter: now + 86400,
    deviceBound: true,
    deviceTag: deviceTagOf(fingerprint),
  });
  assert.equal(checkLicence(secret, code, { deviceFingerprint: fingerprint }).serial, 17);
  assert.throws(() => checkLicence(secret, code, { deviceFingerprint: "00000000" }), /bound to device/);
});

check("expired keys are refused", () => {
  const code = issue(secret, { serial: 19, notAfter: now - 86400 * 3 });
  assert.throws(() => checkLicence(secret, code), /expired/);
});

check("grouping is reversible", () => {
  const code = issue(secret, { serial: 23, notAfter: 0 });
  assert.equal(groupCode(normalizeCode(code)), code);
});

check("payload and checksum split is 8 + 6 characters", () => {
  assert.equal(PAYLOAD_CHARS, 8);
  assert.equal(CHECK_CHARS, 6);
  assert.equal(PAYLOAD_BITS + CHECK_BITS, CODE_LENGTH * 5);
});

console.log(`\n${passed} checks passed`);
