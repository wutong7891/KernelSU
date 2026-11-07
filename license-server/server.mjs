#!/usr/bin/env node
// Night license server — standalone replacement for the Cloudflare Worker.
// Zero dependencies: node:crypto + a single JSON store with atomic writes.
//
// Wire protocol (must stay byte-identical to manager NightActivation.kt):
//   POST /api/v1/night/session/start  -> { ok, sessionId, sealedPacket, iv, expiresAt }
//   POST /api/v1/night/session/key    -> raw 32-byte key
//   POST /api/v1/night/activate|check -> { ok, message, ... }
//   POST /api/admin/generate          -> { ok, codes: [] }   (Bearer NIGHT_ADMIN_TOKEN)

import { createHash, createHmac, createCipheriv, randomBytes, timingSafeEqual } from "node:crypto";
import { readFileSync, writeFileSync, renameSync, existsSync, mkdirSync } from "node:fs";
import { createServer } from "node:http";
import { dirname, join } from "node:path";

const PORT = Number(process.env.NIGHT_PORT ?? 8080);
const HOST = process.env.NIGHT_HOST ?? "127.0.0.1";
const DATA_FILE = process.env.NIGHT_DATA ?? join(process.cwd(), "data", "night.json");
const ADMIN_TOKEN = process.env.NIGHT_ADMIN_TOKEN ?? "";
const MEMORY_SECRET = process.env.NIGHT_MEMORY_SECRET || ADMIN_TOKEN;
const SESSION_TTL = 45;

if (!ADMIN_TOKEN) {
  console.error("NIGHT_ADMIN_TOKEN is required");
  process.exit(1);
}

const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

// ---------- crypto helpers ----------

const sha256Hex = (value) => createHash("sha256").update(value).digest("hex");
const nightCodeHash = (code) => sha256Hex(`night-license-v1:${code}`);
const sessionHash = (sessionId) => sha256Hex(`night-session-v1:${sessionId}`);

function credentialHmac(value, purpose) {
  return createHmac("sha256", MEMORY_SECRET).update(`${purpose}:${value}`).digest();
}

const sessionKey = (sessionId, codeHash, deviceHash, action, clientNonce) =>
  credentialHmac(`${sessionId}:${codeHash}:${deviceHash}:${action}:${clientNonce}`, "night-memory-key-v1");

const toBase64Url = (buf) => buf.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");

function sealPacket(key, iv, packet) {
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  const body = Buffer.concat([cipher.update(packet), cipher.final()]);
  return Buffer.concat([body, cipher.getAuthTag()]);
}

function safeEqualHex(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  try {
    return timingSafeEqual(Buffer.from(a, "hex"), Buffer.from(b, "hex"));
  } catch {
    return false;
  }
}

// ---------- normalization (mirrors the worker) ----------

const normalize = (value) => {
  const code = String(value ?? "").normalize("NFKC").trim().replace(/\s+/g, "");
  return /^(?:WTLYF|NIGHT)(?:-[A-Z2-9]{5}){4}$/i.test(code) ? code.toUpperCase() : code;
};

const validLicenseCode = (code) =>
  code.length >= 1 && code.length <= 128 && !/[\u0000-\u001f\u007f]/.test(code);

const HEX64 = /^[a-f0-9]{64}$/;
const SESSION_ID = /^[A-Za-z0-9_-]{43}$/;

const generateNightCode = () => {
  const bytes = randomBytes(20);
  const chars = Array.from(bytes, (b) => ALPHABET[b % ALPHABET.length]).join("");
  return `NIGHT-${chars.slice(0, 5)}-${chars.slice(5, 10)}-${chars.slice(10, 15)}-${chars.slice(15, 20)}`;
};

// ---------- storage ----------

const now = () => Math.floor(Date.now() / 1000);
const iso = (epoch) => (epoch ? new Date(epoch * 1000).toISOString().replace("T", " ").slice(0, 19) : null);

let state = { licenses: {}, devices: {}, sessions: {} };

function load() {
  if (!existsSync(DATA_FILE)) return;
  try {
    const parsed = JSON.parse(readFileSync(DATA_FILE, "utf8"));
    state = {
      licenses: parsed.licenses ?? {},
      devices: parsed.devices ?? {},
      sessions: parsed.sessions ?? {},
    };
  } catch (error) {
    console.error("failed to read store, starting empty:", error.message);
  }
}

let flushTimer = null;
function persist() {
  if (flushTimer) return;
  flushTimer = setTimeout(() => {
    flushTimer = null;
    try {
      mkdirSync(dirname(DATA_FILE), { recursive: true });
      const tmp = `${DATA_FILE}.tmp`;
      writeFileSync(tmp, JSON.stringify(state));
      renameSync(tmp, DATA_FILE);
    } catch (error) {
      console.error("persist failed:", error.message);
    }
  }, 50);
}

const deviceKey = (codeHash, deviceHash) => `${codeHash}:${deviceHash}`;
const deviceCount = (codeHash) =>
  Object.keys(state.devices).filter((k) => k.startsWith(`${codeHash}:`)).length;

function pruneSessions() {
  const t = now();
  for (const [hash, row] of Object.entries(state.sessions)) {
    if (row.expires_at <= t) delete state.sessions[hash];
  }
}

// ---------- replies ----------

const API_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
};

const reply = (body, status = 200, extra = {}) => ({
  status,
  headers: { ...API_HEADERS, ...extra },
  body: JSON.stringify(body),
});

const readJson = (req) =>
  new Promise((resolve) => {
    let raw = "";
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > 256 * 1024) {
        req.destroy();
        return;
      }
      raw += chunk;
    });
    req.on("end", () => {
      try {
        resolve(JSON.parse(raw || "{}"));
      } catch {
        resolve(null);
      }
    });
    req.on("error", () => resolve(null));
  });

// ---------- license core ----------

function activateLicense(code, deviceHash) {
  const hash = nightCodeHash(code);
  const row = state.licenses[hash];
  if (!row) {
    return reply({ ok: false, error: "invalid_code", message: "Night 卡密不存在" }, 404);
  }
  if (row.expires_at !== null && row.expires_at <= now()) {
    row.status = "expired";
    persist();
    return reply({ ok: false, error: "expired_code", message: "Night 卡密已到期" }, 403);
  }
  if (row.status !== "active") {
    return reply({ ok: false, error: "invalid_code", message: "Night 卡密已停用" }, 403);
  }

  const key = deviceKey(hash, deviceHash);
  if (state.devices[key]) {
    state.devices[key].last_seen_at = iso(now());
    persist();
    return reply({ ok: true, message: "Night 验证成功", expiresAt: iso(row.expires_at) });
  }

  // One card per device: drop any other card bound to this fingerprint.
  for (const other of Object.keys(state.devices)) {
    if (other.endsWith(`:${deviceHash}`) && !other.startsWith(`${hash}:`)) {
      delete state.devices[other];
    }
  }

  const limit = row.device_limit;
  if (limit !== null && deviceCount(hash) >= limit) {
    return reply(
      {
        ok: false,
        error: "device_limit_reached",
        message: `Night 卡密设备数量已达上限（${deviceCount(hash)}/${limit}）`,
      },
      409,
    );
  }

  state.devices[key] = { code_hash: hash, device_hash: deviceHash, bound_at: iso(now()), last_seen_at: iso(now()) };
  if (!row.bound_at) row.bound_at = iso(now());
  if (row.expires_at === null && row.duration_hours !== null) {
    row.expires_at = now() + row.duration_hours * 3600;
  }
  persist();
  return reply({
    ok: true,
    message: "Night 激活成功",
    expiresAt: iso(row.expires_at),
    deviceCount: deviceCount(hash),
    deviceLimit: limit,
  });
}

// ---------- session endpoints ----------

async function startSession(body) {
  if (!body) return reply({ ok: false, error: "invalid_request", message: "请求格式无效" }, 400);
  const code = normalize(body.code);
  const deviceHash = String(body.deviceHash ?? "").toLowerCase();
  const action = String(body.action ?? "");
  const clientNonce = String(body.clientNonce ?? "").toLowerCase();

  if (
    !validLicenseCode(code) ||
    !HEX64.test(deviceHash) ||
    !HEX64.test(clientNonce) ||
    !["activate", "check"].includes(action)
  ) {
    return reply({ ok: false, error: "invalid_request", message: "会话参数无效" }, 400);
  }

  const license = activateLicense(code, deviceHash);
  if (license.status !== 200) return license;

  pruneSessions();

  const sessionId = toBase64Url(randomBytes(32));
  const codeHash = nightCodeHash(code);
  const key = sessionKey(sessionId, codeHash, deviceHash, action, clientNonce);
  const packet = randomBytes(32);
  const iv = randomBytes(12);
  const sealed = sealPacket(key, iv, packet);
  const proofHash = sha256Hex(Buffer.concat([packet, Buffer.from(action, "utf8"), Buffer.from(sessionId, "utf8")]));

  state.sessions[sessionHash(sessionId)] = {
    code_hash: codeHash,
    device_hash: deviceHash,
    action,
    client_nonce: clientNonce,
    proof_hash: proofHash,
    key_issued: 0,
    expires_at: now() + SESSION_TTL,
  };
  persist();

  key.fill(0);
  packet.fill(0);

  return reply({
    ok: true,
    sessionId,
    sealedPacket: toBase64Url(sealed),
    iv: toBase64Url(iv),
    expiresAt: now() + SESSION_TTL,
  });
}

function revealKey(body) {
  if (!body) return reply({ ok: false, error: "invalid_request", message: "请求格式无效" }, 400);
  const code = normalize(body.code);
  const deviceHash = String(body.deviceHash ?? "").toLowerCase();
  const action = String(body.action ?? "");
  const clientNonce = String(body.clientNonce ?? "").toLowerCase();
  const sessionId = String(body.sessionId ?? "");

  if (!validLicenseCode(code) || !HEX64.test(deviceHash) || !HEX64.test(clientNonce) || !SESSION_ID.test(sessionId)) {
    return reply({ ok: false, error: "invalid_request", message: "会话参数无效" }, 400);
  }

  pruneSessions();
  const hash = sessionHash(sessionId);
  const row = state.sessions[hash];
  if (
    !row ||
    row.key_issued !== 0 ||
    row.expires_at <= now() ||
    row.code_hash !== nightCodeHash(code) ||
    row.device_hash !== deviceHash ||
    row.action !== action ||
    row.client_nonce !== clientNonce
  ) {
    return reply({ ok: false, error: "session_mismatch", message: "会话不一致、已使用或已过期" }, 403);
  }
  row.key_issued = 1;
  persist();

  const key = sessionKey(sessionId, row.code_hash, deviceHash, action, clientNonce);
  return {
    status: 200,
    headers: {
      ...API_HEADERS,
      "content-type": "application/octet-stream",
      "content-disposition": "attachment; filename=night-session.key",
    },
    body: key,
  };
}

function finishSession(body, expectedAction) {
  if (!body) return reply({ ok: false, error: "invalid_request", message: "请求格式无效" }, 400);
  const code = normalize(body.code);
  const deviceHash = String(body.deviceHash ?? "").toLowerCase();
  const action = String(body.action ?? "");
  const clientNonce = String(body.clientNonce ?? "").toLowerCase();
  const sessionId = String(body.sessionId ?? "");
  const sessionProof = String(body.sessionProof ?? "").toLowerCase();

  if (
    action !== expectedAction ||
    !validLicenseCode(code) ||
    !HEX64.test(deviceHash) ||
    !HEX64.test(clientNonce) ||
    !HEX64.test(sessionProof) ||
    !SESSION_ID.test(sessionId)
  ) {
    return reply({ ok: false, error: "session_required", message: "需要有效的一次性内存会话" }, 403);
  }

  pruneSessions();
  const hash = sessionHash(sessionId);
  const row = state.sessions[hash];
  if (
    !row ||
    row.key_issued !== 1 ||
    row.expires_at <= now() ||
    row.code_hash !== nightCodeHash(code) ||
    row.device_hash !== deviceHash ||
    row.action !== action ||
    row.client_nonce !== clientNonce ||
    !safeEqualHex(row.proof_hash, sessionProof)
  ) {
    return reply({ ok: false, error: "session_mismatch", message: "会话不一致、已使用或已过期" }, 403);
  }
  delete state.sessions[hash];
  persist();

  const license = activateLicense(code, deviceHash);
  if (license.status !== 200) return license;
  return license;
}

// ---------- admin ----------

function adminAuth(req) {
  const header = String(req.headers.authorization ?? "");
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!token || !ADMIN_TOKEN) return false;
  const a = Buffer.from(token);
  const b = Buffer.from(ADMIN_TOKEN);
  return a.length === b.length && timingSafeEqual(a, b);
}

async function adminGenerate(body) {
  const count = Math.max(1, Math.min(100, Number(body?.count ?? 1) || 1));
  const customCode = normalize(body?.code ?? "");
  const deviceLimitRaw = body?.deviceLimit;
  const deviceLimit =
    deviceLimitRaw === null || deviceLimitRaw === undefined || body?.limitMode === "unlimited"
      ? null
      : Math.max(1, Math.min(100000, Number(deviceLimitRaw) || 1));
  const durationHours =
    body?.durationHours === null || body?.durationHours === undefined ? null : Number(body.durationHours);

  const codes = [];

  if (customCode) {
    if (!validLicenseCode(customCode)) {
      return reply({ ok: false, message: "自定义卡密格式无效" }, 400);
    }
    const hash = nightCodeHash(customCode);
    if (state.licenses[hash]) {
      return reply({ ok: false, message: "该卡密已存在，不能重复生成" }, 409);
    }
    state.licenses[hash] = {
      status: "active",
      duration_hours: durationHours,
      expires_at: null,
      device_limit: deviceLimit,
      bound_at: null,
      created_at: iso(now()),
    };
    codes.push(customCode);
  } else {
    for (let i = 0; i < count; i++) {
      let code;
      do {
        code = generateNightCode();
      } while (state.licenses[nightCodeHash(code)]);
      state.licenses[nightCodeHash(code)] = {
        status: "active",
        duration_hours: durationHours,
        expires_at: null,
        device_limit: deviceLimit,
        bound_at: null,
        created_at: iso(now()),
      };
      codes.push(code);
    }
  }

  persist();
  return reply({ ok: true, message: "生成成功", codes });
}

function lookupLicense(rawCode) {
  const code = normalize(rawCode);
  if (!validLicenseCode(code)) return null;
  const hash = nightCodeHash(code);
  const row = state.licenses[hash];
  return row ? { code, hash, row } : null;
}

function adminQuery(body) {
  const found = lookupLicense(body?.code);
  if (!found) return reply({ ok: false, message: "卡密不存在" }, 404);
  const { code, hash, row } = found;
  const devices = Object.values(state.devices).filter((d) => d.code_hash === hash);
  return reply({
    ok: true,
    code,
    status: row.status,
    expiresAt: iso(row.expires_at),
    deviceLimit: row.device_limit,
    deviceCount: devices.length,
    devices: devices.map((d) => ({ deviceHash: d.device_hash, boundAt: d.bound_at, lastSeenAt: d.last_seen_at })),
  });
}

function adminUnbind(body) {
  const found = lookupLicense(body?.code);
  if (!found) return reply({ ok: false, message: "卡密不存在" }, 404);
  const target = String(body?.deviceHash ?? "").toLowerCase();
  let removed = 0;
  for (const [key, device] of Object.entries(state.devices)) {
    if (device.code_hash !== found.hash) continue;
    if (target && device.device_hash !== target) continue;
    delete state.devices[key];
    removed++;
  }
  persist();
  return reply({ ok: true, message: `已解绑 ${removed} 台设备`, removed });
}

function adminDelete(body) {
  const found = lookupLicense(body?.code);
  if (!found) return reply({ ok: false, message: "卡密不存在" }, 404);
  delete state.licenses[found.hash];
  for (const [key, device] of Object.entries(state.devices)) {
    if (device.code_hash === found.hash) delete state.devices[key];
  }
  persist();
  return reply({ ok: true, message: "已删除卡密" });
}

// ---------- router ----------

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);
  const path = url.pathname.replace(/\/+$/, "") || "/";

  const send = ({ status, headers, body }) => {
    res.writeHead(status, headers);
    res.end(body);
  };

  try {
    if (path === "/api/health") {
      return send(reply({ ok: true, service: "night-license", time: iso(now()), licenses: Object.keys(state.licenses).length }));
    }

    if (path === "/api/v1/night/session/start" && req.method === "POST") {
      return send(await startSession(await readJson(req)));
    }
    if (path === "/api/v1/night/session/key" && req.method === "POST") {
      return send(revealKey(await readJson(req)));
    }
    if (path === "/api/v1/night/activate" && req.method === "POST") {
      return send(finishSession(await readJson(req), "activate"));
    }
    if (path === "/api/v1/night/check" && req.method === "POST") {
      return send(finishSession(await readJson(req), "check"));
    }

    if (path.startsWith("/api/admin/")) {
      if (!adminAuth(req)) {
        return send(reply({ ok: false, message: "管理令牌无效" }, 401));
      }
      const body = await readJson(req);
      if (path === "/api/admin/generate" && req.method === "POST") return send(await adminGenerate(body));
      if (path === "/api/admin/query" && req.method === "POST") return send(adminQuery(body));
      if (path === "/api/admin/unbind" && req.method === "POST") return send(adminUnbind(body));
      if (path === "/api/admin/delete" && req.method === "POST") return send(adminDelete(body));
      return send(reply({ ok: false, message: "未知管理接口" }, 404));
    }

    return send(reply({ ok: false, message: "not found" }, 404));
  } catch (error) {
    console.error("unhandled:", error);
    return send(reply({ ok: false, message: "服务器内部错误" }, 500));
  }
});

load();
setInterval(pruneSessions, 60_000).unref();
server.listen(PORT, HOST, () => {
  console.log(`night-license listening on http://${HOST}:${PORT}`);
  console.log(`store: ${DATA_FILE}`);
});
