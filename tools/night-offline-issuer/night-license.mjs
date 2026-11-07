// Night offline licence format, shared by the issuer CLI and the tests.
//
// Everything here mirrors NightActivation.kt and the Android issuer exactly:
// same byte layout, same Crockford Base32 alphabet, same signature encoding.
// If you change one of the three, change all three.

import { createHash, createPrivateKey, createPublicKey, randomBytes, sign, verify } from "node:crypto";

export const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
export const VERSION = 0x02;
export const FLAG_DEVICE_BOUND = 0x01;
export const FLAG_PERMANENT = 0x02;
export const PAYLOAD_SIZE = 12;
export const SIGNATURE_SIZE = 64;
export const TOKEN_SIZE = PAYLOAD_SIZE + SIGNATURE_SIZE;
export const PREFIX = "NIGHT";
export const GROUP_SIZE = 5;

export function encodeBase32(data) {
  let buffer = 0;
  let bits = 0;
  let out = "";
  for (const byte of data) {
    buffer = (buffer << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      bits -= 5;
      out += ALPHABET[(buffer >> bits) & 0x1f];
    }
  }
  if (bits > 0) out += ALPHABET[(buffer << (5 - bits)) & 0x1f];
  return out;
}

export function decodeBase32(text) {
  const bytes = [];
  let buffer = 0;
  let bits = 0;
  for (const char of text.toUpperCase()) {
    const value = alphabetIndex(char);
    if (value === null) return null;
    buffer = (buffer << 5) | value;
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((buffer >> bits) & 0xff);
    }
  }
  if (bits > 0 && (buffer & ((1 << bits) - 1)) !== 0) return null;
  return Buffer.from(bytes);
}

function alphabetIndex(char) {
  if (char >= "0" && char <= "9") return char.charCodeAt(0) - 48;
  if (char === "O") return 0;
  if (char === "I" || char === "L") return 1;
  const index = ALPHABET.indexOf(char);
  return index >= 0 ? index : null;
}

export function normalizeCode(input) {
  const upper = String(input ?? "").trim().toUpperCase();
  const body = upper.startsWith(PREFIX) ? upper.slice(PREFIX.length) : upper;
  return body.replace(/[^0-9A-Z]/g, "");
}

export function groupCode(body) {
  const chunks = [];
  for (let i = 0; i < body.length; i += GROUP_SIZE) chunks.push(body.slice(i, i + GROUP_SIZE));
  return `${PREFIX}-${chunks.join("-")}`;
}

/** Three-byte tag derived from the Manager device fingerprint (SHA-256 hex). */
export function deviceTagOf(fingerprint) {
  const clean = String(fingerprint ?? "").trim().toLowerCase().replace(/[^0-9a-f]/g, "");
  if (clean.length < 6) throw new Error("device fingerprint must be at least 6 hex characters");
  return Buffer.from(clean.slice(0, 6), "hex");
}

/** Full fingerprint the Manager shows, so the admin never has to guess. */
export function deviceFingerprintOf(androidId) {
  return createHash("sha256").update(`night-device-v1:${String(androidId).trim().toLowerCase()}`).digest("hex");
}

export function keyFingerprint(publicKeyDer) {
  return createHash("sha256").update(publicKeyDer).digest("hex").slice(0, 16).toUpperCase();
}

export function buildPayload({ serial, notAfter = 0, deviceTag = Buffer.alloc(3), deviceBound = false } = {}) {
  const permanent = !notAfter;
  const value = Number.isInteger(serial) ? serial : randomBytes(3).readUIntBE(0, 3) || 1;
  const payload = Buffer.alloc(PAYLOAD_SIZE);
  payload[0] = VERSION;
  payload[1] = (deviceBound ? FLAG_DEVICE_BOUND : 0) | (permanent ? FLAG_PERMANENT : 0);
  payload.writeUIntBE(value & 0xffffff, 2, 3);
  payload.writeUInt32BE(permanent ? 0 : notAfter >>> 0, 5);
  payload[9] = deviceTag[0] ?? 0;
  payload[10] = deviceTag[1] ?? 0;
  payload[11] = deviceTag[2] ?? 0;
  return payload;
}

export function readClaims(payload) {
  const flags = payload[1];
  return {
    version: payload[0],
    serial: payload.readUIntBE(2, 3),
    permanent: (flags & FLAG_PERMANENT) !== 0,
    deviceBound: (flags & FLAG_DEVICE_BOUND) !== 0,
    deviceTag: payload.subarray(9, 12).toString("hex"),
    notAfter: payload.readUInt32BE(5),
  };
}

/**
 * Signs one licence. `privateKey` is a PEM string, PKCS#8 DER Buffer, or a
 * KeyObject; `notAfter` is unix seconds, 0 meaning permanent.
 */
export function issue(privateKey, { serial, notAfter = 0, deviceTag, deviceBound = false } = {}) {
  const key = toPrivateKey(privateKey);
  const payload = buildPayload({ serial, notAfter, deviceTag, deviceBound });
  const raw = sign("sha256", payload, { key, dsaEncoding: "ieee-p1363" });
  if (raw.length !== SIGNATURE_SIZE) throw new Error(`unexpected signature size ${raw.length}`);
  return groupCode(encodeBase32(Buffer.concat([payload, raw])));
}

/** Verifies a licence; returns the claims or a reason string. */
export function inspectCode(publicKeyDer, code) {
  const key = toPublicKey(publicKeyDer);
  const body = normalizeCode(code);
  if (!body) throw new Error("licence is empty");
  const token = decodeBase32(body);
  if (!token) throw new Error("licence is not valid base32");
  if (token.length !== TOKEN_SIZE) throw new Error(`licence must be ${TOKEN_SIZE} bytes, got ${token.length}`);
  const payload = token.subarray(0, PAYLOAD_SIZE);
  const signature = token.subarray(PAYLOAD_SIZE);
  if (payload[0] !== VERSION) throw new Error(`unsupported licence version ${payload[0]}`);
  const ok = verify("sha256", payload, { key, dsaEncoding: "ieee-p1363" }, signature);
  if (!ok) throw new Error("licence signature does not verify");
  return readClaims(payload);
}

/** Resolves whether a licence is usable on a device right now. */
export function checkLicence(publicKeyDer, code, { deviceFingerprint, now = Math.floor(Date.now() / 1000) } = {}) {
  const claims = inspectCode(publicKeyDer, code);
  if (claims.deviceBound) {
    if (!deviceFingerprint) throw new Error("licence is device bound but no fingerprint was given");
    const expected = deviceTagOf(deviceFingerprint);
    if (expected.toString("hex") !== claims.deviceTag) {
      throw new Error(`licence is bound to device ${claims.deviceTag}, not ${expected.toString("hex")}`);
    }
  }
  if (!claims.permanent && claims.notAfter <= now) {
    throw new Error(`licence expired at ${new Date(claims.notAfter * 1000).toISOString()}`);
  }
  return claims;
}

function toPrivateKey(input) {
  if (input && input.type === "private") return input;
  if (Buffer.isBuffer(input)) return createPrivateKey({ key: input, format: "der", type: "pkcs8" });
  return createPrivateKey(input);
}

function toPublicKey(input) {
  if (input && input.type === "public") return input;
  if (Buffer.isBuffer(input)) return createPublicKey({ key: input, format: "der", type: "spki" });
  return createPublicKey(input);
}

export function publicKeyDer(key) {
  return toPublicKey(key).export({ format: "der", type: "spki" });
}

export function privateKeyDer(key) {
  return toPrivateKey(key).export({ format: "der", type: "pkcs8" });
}

/** Public key shape check, so a stale or truncated copy fails loudly. */
export function assertP256Spki(der) {
  if (der.length !== 91) throw new Error(`expected a 91 byte P-256 SPKI key, got ${der.length}`);
  if (der[0] !== 0x30 || der[1] !== 0x59) throw new Error("not a DER sequence of the expected size");
}
