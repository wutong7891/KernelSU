// End-to-end protocol test: behaves exactly like manager NightActivation.kt.
import { createHash, createDecipheriv } from "node:crypto";

const BASE = process.env.BASE ?? "http://127.0.0.1:8080";
const ADMIN = process.env.NIGHT_ADMIN_TOKEN ?? "test-admin-token";

const sha256Hex = (v) => createHash("sha256").update(v).digest("hex");
const b64url = (s) => Buffer.from(s, "base64url");

const deviceHash = sha256Hex("night-device-v1:" + "9774d56d682e549c");
const nonce = () => sha256Hex(String(Math.random()));

async function post(path, body, token) {
  const res = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status: res.status, json, text };
}

function check(label, cond, extra = "") {
  console.log(`${cond ? "PASS" : "FAIL"}  ${label}${extra ? "  " + extra : ""}`);
  if (!cond) process.exitCode = 1;
}

async function clientFlow(action, code) {
  const payload = { code, deviceHash };
  const clientNonce = nonce();

  const start = await post("/api/v1/night/session/start", { ...payload, action, clientNonce });
  if (!start.json?.ok) return { stage: "start", ...start };

  const { sessionId, sealedPacket, iv } = start.json;
  const keyRes = await fetch(`${BASE}/api/v1/night/session/key`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ...payload, action, clientNonce, sessionId }),
  });
  const key = Buffer.from(await keyRes.arrayBuffer());
  if (key.length !== 32) return { stage: "key", status: keyRes.status, key };

  const sealed = b64url(sealedPacket);
  const decipher = createDecipheriv("aes-256-gcm", key, b64url(iv));
  decipher.setAuthTag(sealed.subarray(sealed.length - 16));
  const packet = Buffer.concat([decipher.update(sealed.subarray(0, sealed.length - 16)), decipher.final()]);

  const proof = sha256Hex(Buffer.concat([packet, Buffer.from(action, "utf8"), Buffer.from(sessionId, "utf8")]));

  const final = await post(`/api/v1/night/${action}`, { ...payload, action, clientNonce, sessionId, sessionProof: proof });
  return { stage: "final", ...final };
}

const gen = await post("/api/admin/generate", { count: 2, deviceLimit: 1 }, ADMIN);
check("admin generate", gen.status === 200 && gen.json?.ok, `codes=${gen.json?.codes?.length}`);
const [codeA, codeB] = gen.json.codes;

const bad = await post("/api/admin/generate", {}, "wrong-token");
check("admin rejects bad token", bad.status === 401);

const activate = await clientFlow("activate", codeA);
check("activate first device", activate.json?.ok === true, activate.json?.message ?? activate.text);

const again = await clientFlow("check", codeA);
check("check same device", again.json?.ok === true, again.json?.message ?? again.text);

const unknown = await clientFlow("activate", "NIGHT-ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ");
check("unknown code rejected", unknown.json?.ok === false, unknown.json?.message ?? "");

// A second, different device must hit the device_limit=1 wall.
const otherDeviceHash = sha256Hex("night-device-v1:" + "ffffffffffffffff");
const startB = await post("/api/v1/night/session/start", {
  code: codeB, deviceHash: otherDeviceHash, action: "activate", clientNonce: nonce(),
});
check("second card issues session", startB.json?.ok === true);

const startA2 = await post("/api/v1/night/session/start", {
  code: codeA, deviceHash: otherDeviceHash, action: "activate", clientNonce: nonce(),
});
check("device limit enforced", startA2.status === 409, startA2.json?.message ?? "");

// Replay: a used session key must not be handed out twice.
const replayBody = { code: codeB, deviceHash: otherDeviceHash, action: "activate", clientNonce: nonce() };
const s1 = await post("/api/v1/night/session/start", replayBody);
const k1 = await post("/api/v1/night/session/key", { ...replayBody, sessionId: s1.json.sessionId });
const k2 = await post("/api/v1/night/session/key", { ...replayBody, sessionId: s1.json.sessionId });
check("session key issued once", k1.status === 200 && k2.status === 403);

const query = await post("/api/admin/query", { code: codeA }, ADMIN);
check("admin query", query.json?.ok === true && query.json?.deviceCount === 1);

const unbind = await post("/api/admin/unbind", { code: codeA }, ADMIN);
check("admin unbind", unbind.json?.ok === true, unbind.json?.message ?? "");

const afterUnbind = await clientFlow("activate", codeA);
check("reactivate after unbind", afterUnbind.json?.ok === true, afterUnbind.json?.message ?? "");
