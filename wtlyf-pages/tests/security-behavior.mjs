import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const bundle = await readFile(new URL("../dist/_worker.js", import.meta.url), "utf8");
const moduleUrl = `data:text/javascript;base64,${Buffer.from(bundle).toString("base64")}`;
const worker = (await import(moduleUrl)).default;

class Statement {
  bind() {
    return this;
  }
  async run() {
    return { success: true };
  }
  async first() {
    return { count: 1 };
  }
  async all() {
    return { results: [] };
  }
}

const env = {
  DB: {
    prepare() {
      return new Statement();
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
};

const pending = [];
const context = {
  waitUntil(promise) {
    pending.push(Promise.resolve(promise));
  },
  passThroughOnException() {},
};

async function request(url, init = {}, cf = null) {
  const incoming = new Request(url, init);
  if (cf) Object.defineProperty(incoming, "cf", { value: cf });
  return worker.fetch(incoming, env, context);
}

let response = await request("https://wutong886.pages.dev/admin/accounts");
assert.equal(response.status, 404, "public host must not redirect to the admin host");
assert.equal(response.headers.get("location"), null);

response = await request("https://wutong886.pages.dev/api/admin/session");
assert.equal(response.status, 404, "admin session API must be isolated to admin host");

response = await request("https://wutong886.pages.dev/.env");
assert.equal(response.status, 404, "common secret-file probes must be blocked");

response = await request("https://wutong886.pages.dev/", { method: "PUT" });
assert.equal(response.status, 405, "unexpected HTTP methods must be blocked");

response = await request("https://wtlyf-admin-center.pages.dev/");
assert.equal(response.status, 200, "normal administrator login page must remain available");
assert.match(await response.text(), /管理员登录/);

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

response = await request("https://wutong886.pages.dev/api/v1/night/check", {
  method: "OPTIONS",
  headers: { origin: "https://wutong886.pages.dev" },
});
assert.equal(response.status, 204, "normal Night API preflight must remain available");

await Promise.all(pending);
console.log("WTLYF bundled worker behavior checks passed");
