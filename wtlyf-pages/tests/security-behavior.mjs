import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const bundle = await readFile(new URL("../dist/_worker.js", import.meta.url), "utf8");
const moduleUrl = `data:text/javascript;base64,${Buffer.from(bundle).toString("base64")}`;
const worker = (await import(moduleUrl)).default;

const passwordSalt = Buffer.alloc(16, 7).toString("base64");
async function portableHash(password) {
  const passwordKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  return `v3$${Buffer.from(
    await crypto.subtle.deriveBits(
      {
        name: "PBKDF2",
        hash: "SHA-256",
        salt: Buffer.from(passwordSalt, "base64"),
        iterations: 100000,
      },
      passwordKey,
      256,
    ),
  ).toString("base64")}`;
}
const adminPasswordHash = await portableHash("correct-password");

class Statement {
  constructor(sql) {
    this.sql = sql;
    this.values = [];
  }
  bind(...values) {
    this.values = values;
    return this;
  }
  async run() {
    return { success: true };
  }
  async first() {
    if (this.sql.includes("SELECT username,password_hash,password_salt"))
      return {
        username: "wutong",
        password_hash: adminPasswordHash,
        password_salt: passwordSalt,
        password_iterations: 100000,
      };
    if (this.sql.includes("SELECT SUM(CASE WHEN ip_address="))
      return { pair_count: 0, account_count: 0, pair_hour_count: 0 };
    if (this.sql.includes("INSERT INTO security_rate_limits")) return { count: 1 };
    return { count: 1 };
  }
  async all() {
    return { results: [] };
  }
}

const env = {
  DB: {
    prepare(sql) {
      return new Statement(sql);
    },
    async batch(statements) {
      return statements.map(() => ({ success: true }));
    },
  },
  PUSH_FILES: {
    async get() {
      return null;
    },
  },
  WTLYF_ADMIN_TOKEN: "test-admin-token",
};

const pending = [];
const context = {
  waitUntil(promise) {
    pending.push(Promise.resolve(promise));
  },
  passThroughOnException() {},
};

async function request(url, init = {}, cf = null, requestEnv = env) {
  const incoming = new Request(url, init);
  if (cf) Object.defineProperty(incoming, "cf", { value: cf });
  return worker.fetch(incoming, requestEnv, context);
}

let response = await request("https://wutong886.pages.dev/admin/accounts");
assert.equal(response.status, 404, "public host must not redirect to the admin host");
assert.equal(response.headers.get("location"), null);

response = await request("https://wutong886.pages.dev/api/admin/session");
assert.equal(
  response.status,
  401,
  "ordinary administrator hosts must retain session checks",
);

response = await request("https://wutong886.pages.dev/api/admin/accounts");
assert.equal(
  response.status,
  404,
  "administrator-account management must remain isolated to the account center",
);

response = await request("https://wutong886.pages.dev/api/admin/access-audit");
assert.equal(
  response.status,
  404,
  "administrator access audits must remain isolated to the account center",
);

response = await request("https://wutong886.pages.dev/api/admin/session/", {
  method: "POST",
  headers: {
    "content-type": "text/plain",
    origin: "https://wutong886.pages.dev",
    "sec-fetch-site": "same-origin",
  },
  body: "{}",
});
assert.equal(
  response.status,
  415,
  "trailing slashes must not bypass login-specific request validation",
);

response = await request("https://wutong886.pages.dev/.env");
assert.equal(response.status, 404, "common secret-file probes must be blocked");

for (const phpProbe of [
  "/shell.php",
  "/INDEX.PHP/anything",
  "/%2569ndex%252ephp",
  "/vendor%2fphpunit/phpunit/src/Util/PHP/eval-stdin.php",
  "/composer.lock",
  "/.user.ini",
]) {
  response = await request(`https://wutong886.pages.dev${phpProbe}`);
  assert.equal(response.status, 404, `${phpProbe} must be blocked`);
  assert.equal(
    (await response.json()).error,
    "firewall",
    `${phpProbe} must be rejected at the shared firewall boundary`,
  );
}

response = await request("https://wutong886.pages.dev/", { method: "PUT" });
assert.equal(response.status, 405, "unexpected HTTP methods must be blocked");

response = await request("https://wtlyf-admin-center.pages.dev/");
assert.equal(response.status, 200, "normal administrator login page must remain available");
const adminPage = await response.text();
assert.match(adminPage, /管理员登录/);
assert.match(
  adminPage,
  /主账号当前密码/,
  "administrator account creation must require primary-account reauthentication",
);
assert.match(
  adminPage,
  /currentPassword,actionToken/,
  "administrator account mutations must send the one-time action token",
);

response = await request(
  "https://wtlyf-admin-center.pages.dev/",
  {},
  { country: "US", colo: "LAX" },
);
assert.equal(response.status, 403, "foreign administrator access must be blocked");

response = await request(
  "https://wtlyf-admin-center.pages.dev/",
  {},
  { country: "CN", colo: "SHA" },
);
assert.equal(response.status, 200, "domestic administrator access must remain available");

response = await request("https://wtlyf-admin-center.pages.dev/api/admin/session", {
  method: "POST",
  headers: {
    "content-type": "application/json",
    origin: "https://attacker.invalid",
    "sec-fetch-site": "cross-site",
  },
  body: JSON.stringify({ username: "probe", password: "probe" }),
});
assert.equal(response.status, 403, "cross-site administrator login must be rejected");

response = await request(
  "https://wtlyf-admin-center.pages.dev/api/admin/session",
  {
    method: "POST",
    headers: {
      "content-type": "application/json",
      origin: "https://wtlyf-admin-center.pages.dev",
      "sec-fetch-site": "same-origin",
      "cf-connecting-ip": "203.0.113.10",
    },
    body: JSON.stringify({ username: "wutong", password: "correct-password" }),
  },
  { country: "CN", colo: "SHA" },
);
assert.equal(response.status, 200, "valid administrator credentials must still work");
const sessionCookie = response.headers.get("set-cookie") || "";
assert.match(sessionCookie, /__Host-wtlyf_admin=v6\./);
assert.ok(
  !sessionCookie.includes(Buffer.from("wutong").toString("base64url")),
  "the session cookie must not expose the administrator username",
);

response = await request(
  "https://wutong886.pages.dev/api/admin/session",
  {
    method: "POST",
    headers: {
      "content-type": "application/json",
      origin: "https://wutong886.pages.dev",
      "sec-fetch-site": "same-origin",
      "cf-connecting-ip": "203.0.113.11",
    },
    body: JSON.stringify({ username: "wutong", password: "correct-password" }),
  },
  { country: "CN", colo: "SHA" },
  { ...env, WTLYF_ADMIN_TOKEN: "different-project-admin-token" },
);
assert.equal(
  response.status,
  200,
  "ordinary administrator login must remain available on the public service host",
);
assert.match(response.headers.get("set-cookie") || "", /__Host-wtlyf_admin=v6\./);

response = await request("https://wutong886.pages.dev/api/v1/night/check", {
  method: "OPTIONS",
  headers: { origin: "https://wutong886.pages.dev" },
});
assert.equal(response.status, 204, "normal Night API preflight must remain available");

response = await request("https://wutong886.pages.dev/api/v1/night/check", {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({ code: "probe", deviceHash: "0".repeat(64) }),
});
assert.equal(response.status, 403, "Night checks must require a one-time memory session");
assert.equal((await response.json()).error, "session_required");

await Promise.all(pending);
console.log("WTLYF bundled worker behavior checks passed");
