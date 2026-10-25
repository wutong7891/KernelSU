import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { webcrypto } from "node:crypto";

const crypto = webcrypto;
const encoder = new TextEncoder();
const secret = "local-night-login-test-secret";
const code = "NIGHT-ABCDE-ABCDE-ABCDE-ABCDE";
const deviceHash = "0".repeat(64);
const action = "activate";
const clientNonce = "1".repeat(64);
const sessions = new Map();

const hex = (bytes) =>
  Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, "0")).join("");
const base64Url = (bytes) => Buffer.from(bytes).toString("base64url");
const fromBase64Url = (value) => new Uint8Array(Buffer.from(value, "base64url"));
const sha256 = async (...values) => {
  const length = values.reduce((total, value) => total + value.length, 0);
  const input = new Uint8Array(length);
  let offset = 0;
  for (const value of values) {
    input.set(value, offset);
    offset += value.length;
  }
  return new Uint8Array(await crypto.subtle.digest("SHA-256", input));
};
const codeHash = async () => hex(await sha256(encoder.encode(`night-license-v1:${code}`)));
const sessionKey = async (sessionId) => {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return new Uint8Array(
    await crypto.subtle.sign(
      "HMAC",
      key,
      encoder.encode(
        `night-memory-key-v1:${sessionId}:${await codeHash()}:${deviceHash}:${action}:${clientNonce}`,
      ),
    ),
  );
};

async function startSession() {
  const sessionId = base64Url(crypto.getRandomValues(new Uint8Array(32)));
  const packet = crypto.getRandomValues(new Uint8Array(32));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const keyBytes = await sessionKey(sessionId);
  const aesKey = await crypto.subtle.importKey("raw", keyBytes, "AES-GCM", false, ["encrypt"]);
  const sealedPacket = new Uint8Array(
    await crypto.subtle.encrypt({ name: "AES-GCM", iv }, aesKey, packet),
  );
  const proof = hex(
    await sha256(packet, encoder.encode(action), encoder.encode(sessionId)),
  );
  sessions.set(sessionId, { proof, keyIssued: false });
  packet.fill(0);
  keyBytes.fill(0);
  return { sessionId, sealedPacket: base64Url(sealedPacket), iv: base64Url(iv) };
}

async function revealKey(sessionId) {
  const session = sessions.get(sessionId);
  assert.ok(session && !session.keyIssued, "session key must be issued exactly once");
  session.keyIssued = true;
  return sessionKey(sessionId);
}

async function finishSession(sessionId, proof) {
  const session = sessions.get(sessionId);
  assert.ok(session?.keyIssued, "key must belong to the same session");
  assert.equal(proof, session.proof, "client proof must match the in-memory packet");
  sessions.delete(sessionId);
  return { ok: true, message: "Night 激活成功" };
}

async function successfulClientLogin() {
  const start = await startSession();
  const keyBytes = await revealKey(start.sessionId);
  const aesKey = await crypto.subtle.importKey("raw", keyBytes, "AES-GCM", false, ["decrypt"]);
  const packet = new Uint8Array(
    await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: fromBase64Url(start.iv) },
      aesKey,
      fromBase64Url(start.sealedPacket),
    ),
  );
  const proof = hex(
    await sha256(packet, encoder.encode(action), encoder.encode(start.sessionId)),
  );
  const result = await finishSession(start.sessionId, proof);
  keyBytes.fill(0);
  packet.fill(0);
  return result;
}

const calls = [];
const endpoints = [
  async () => {
    calls.push("primary");
    return successfulClientLogin();
  },
  async () => {
    calls.push("backup");
    throw new Error("backup must not be called after primary success");
  },
];
let result;
for (const endpoint of endpoints) {
  result = await endpoint();
  if (result.ok) break;
}

assert.deepEqual(calls, ["primary"]);
assert.deepEqual(result, {
  ok: true,
  message: "Night 激活成功",
});
assert.equal(sessions.size, 0, "one-time session must be consumed and cleared");

const managerSource = await readFile(
  new URL("../../manager/app/src/main/java/me/weishu/kernelsu/ui/activation/NightActivation.kt", import.meta.url),
  "utf8",
);
assert.match(managerSource, /for \(endpoint in ENDPOINTS\)/);
assert.doesNotMatch(managerSource, /ENDPOINTS\.map[\s\S]{0,120}async/);
assert.doesNotMatch(managerSource, /rootLeaseSeconds|armOnlineRootLease/);

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

console.log("Night local login simulation passed: session, key and proof");
