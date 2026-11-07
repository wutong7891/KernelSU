// XhovoSU 14-character card key format.
//
// 14 Crockford Base32 characters = 70 bits, laid out as:
//
//   [ 8 chars / 40 bits ]  serial(16) | dayCode(12) | deviceTag(12)
//   [ 6 chars / 30 bits ]  truncated HMAC-SHA256 over those 40 bits
//
// Grouped for humans as XXXX-XXXX-XXXX-XX.
//
// Honest limitation: 30 bits of MAC plus a key that ships inside the APK means
// this stops typos and casual guessing, not a determined reverse engineer. A
// signature that cannot be forged needs >= 64 bytes, i.e. a much longer code.

import { createHash, createHmac } from "node:crypto";

export const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
export const CODE_LENGTH = 14;
export const PAYLOAD_CHARS = 8;
export const CHECK_CHARS = 6;
export const PAYLOAD_BITS = PAYLOAD_CHARS * 5;
export const CHECK_BITS = CHECK_CHARS * 5;

/** Day 0 of the expiry calendar: 2024-01-01. dayCode 0 means "never expires". */
export const DAY_EPOCH = Math.floor(Date.UTC(2024, 0, 1) / 1000);
export const MAX_DAY_CODE = 0xfff;

export function encodeBase32(value, bits) {
  const chars = bits / 5;
  let out = "";
  let remaining = value;
  for (let i = chars - 1; i >= 0; i -= 1) {
    const weight = 32 ** i;
    out += ALPHABET[Math.floor(remaining / weight) % 32];
    remaining %= weight;
  }
  return out;
}

function alphabetIndex(char) {
  if (char >= "0" && char <= "9") return char.charCodeAt(0) - 48;
  if (char === "O") return 0;
  if (char === "I" || char === "L") return 1;
  const index = ALPHABET.indexOf(char);
  return index >= 0 ? index : -1;
}

export function decodeBase32(text, bits) {
  const chars = bits / 5;
  if (text.length !== chars) return null;
  let value = 0;
  for (const char of text.toUpperCase()) {
    const digit = alphabetIndex(char);
    if (digit < 0) return null;
    value = value * 32 + digit;
  }
  return value;
}

export function normalizeCode(input) {
  const upper = String(input ?? "").trim().toUpperCase();
  const body = upper.startsWith("XHOVO") ? upper.slice(5) : upper;
  return body.replace(/[^0-9A-Z]/g, "");
}

export function groupCode(body) {
  return `${body.slice(0, 4)}-${body.slice(4, 8)}-${body.slice(8, 12)}-${body.slice(12, 14)}`;
}

/** 12-bit device tag: the first three hex digits of the Manager fingerprint. */
export function deviceTagOf(fingerprint) {
  const clean = String(fingerprint ?? "").trim().toLowerCase().replace(/[^0-9a-f]/g, "");
  if (clean.length < 3) throw new Error("device fingerprint must start with at least 3 hex characters");
  return parseInt(clean.slice(0, 3), 16) & 0xfff;
}

export function deviceFingerprintOf(androidId) {
  return createHash("sha256").update(`xhovo-device-v1:${String(androidId).trim().toLowerCase()}`).digest("hex");
}

export function dayCodeFor(notAfterEpoch) {
  if (!notAfterEpoch) return 0;
  const code = Math.ceil((notAfterEpoch - DAY_EPOCH) / 86400);
  if (code < 1 || code > MAX_DAY_CODE) throw new Error("expiry is outside the 12-bit day range");
  return code;
}

export function notAfterFromDayCode(dayCode) {
  return dayCode === 0 ? 0 : DAY_EPOCH + dayCode * 86400;
}

function macOf(key, payloadValue) {
  const buffer = Buffer.alloc(5);
  buffer.writeUIntBE(payloadValue, 0, 5);
  const digest = createHmac("sha256", key).update(buffer).digest();
  return digest.readUIntBE(0, 4) >>> 2;
}

/** Builds one card key from its fields. `key` is the built-in MAC secret. */
export function issue(key, { serial, notAfter = 0, deviceTag = 0, deviceBound = false } = {}) {
  const tag = deviceBound ? deviceTag & 0xfff : 0;
  const dayCode = dayCodeFor(notAfter);
  const serialValue = Number.isInteger(serial) ? serial : (Math.floor(Math.random() * 0xfffe) + 1);
  const payloadValue =
    ((serialValue & 0xffff) * 2 ** 24) + (dayCode & 0xfff) * 2 ** 12 + (tag & 0xfff);
  const body = encodeBase32(payloadValue, PAYLOAD_BITS) + encodeBase32(macOf(key, payloadValue), CHECK_BITS);
  return `${body.slice(0, 4)}-${body.slice(4, 8)}-${body.slice(8, 12)}-${body.slice(12, 14)}`;
}

export function readClaims(payloadValue) {
  const serial = Math.floor(payloadValue / 2 ** 24) & 0xffff;
  const dayCode = Math.floor(payloadValue / 2 ** 12) & 0xfff;
  const deviceTag = payloadValue & 0xfff;
  return {
    serial,
    dayCode,
    deviceBound: deviceTag !== 0,
    deviceTag: deviceTag.toString(16).padStart(3, "0"),
    notAfter: notAfterFromDayCode(dayCode),
    permanent: dayCode === 0,
  };
}

export function inspectCode(key, code) {
  const body = normalizeCode(code);
  if (body.length !== CODE_LENGTH) {
    throw new Error(`card key must be ${CODE_LENGTH} characters, got ${body.length}`);
  }
  const payloadValue = decodeBase32(body.slice(0, PAYLOAD_CHARS), PAYLOAD_BITS);
  const checksum = decodeBase32(body.slice(PAYLOAD_CHARS), CHECK_BITS);
  if (payloadValue === null || checksum === null) throw new Error("card key contains invalid characters");
  if (macOf(key, payloadValue) !== checksum) throw new Error("card key checksum does not match");
  return readClaims(payloadValue);
}

export function checkLicence(key, code, { deviceFingerprint, now = Math.floor(Date.now() / 1000) } = {}) {
  const claims = inspectCode(key, code);
  if (claims.deviceBound) {
    if (!deviceFingerprint) throw new Error("card key is device bound but no fingerprint was given");
    const expected = deviceTagOf(deviceFingerprint);
    if (expected !== parseInt(claims.deviceTag, 16)) {
      throw new Error(`card key is bound to device ${claims.deviceTag}, not ${expected.toString(16).padStart(3, "0")}`);
    }
  }
  if (!claims.permanent && claims.notAfter <= now) {
    throw new Error(`card key expired on ${new Date(claims.notAfter * 1000).toISOString().slice(0, 10)}`);
  }
  return claims;
}

export function assertKey(key) {
  if (!Buffer.isBuffer(key) || key.length < 16) throw new Error("MAC key must be a buffer of at least 16 bytes");
}
