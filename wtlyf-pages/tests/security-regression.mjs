import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const source = await readFile(new URL("../src/index.ts", import.meta.url), "utf8");

const mustContain = [
  'path === "/api/admin/session" ||',
  'path === "/api/admin/logout" ||',
  'return reply({ ok: false, message: "接口不存在" }, 404);',
  'return reply({ ok: false, message: "资源不存在" }, 404);',
  'CREATE TABLE IF NOT EXISTS admin_access_audit',
  'path === "/api/admin/access-audit"',
  'context.waitUntil(recordAdminAccess(request, env, path, "page"))',
  'context?.waitUntil(recordAdminAccess(request, env, path, "scanner"))',
  'const ADMIN_ALLOWED_COUNTRIES = new Set(["CN", "HK", "MO", "TW"]);',
  '!ADMIN_ALLOWED_COUNTRIES.has(country)',
  'recordAdminAccess(request, env, path, "geo-block")',
  'limit = request.method === "POST" ? 5 : 30;',
  'scope = "admin-api";',
  'limit = 30;',
  'Number(row?.ip_count ?? 0) >= 12',
  'Number(row?.pair_count ?? 0) >= 5',
  'Number(row?.ip_hour_count ?? 0) >= 30',
  'LIMIT -1 OFFSET 8',
  'SameSite=Strict; Priority=High',
  'request.headers.get("origin") === url.origin',
];

for (const marker of mustContain) {
  assert.ok(source.includes(marker), `missing security control: ${marker}`);
}

assert.ok(
  !source.includes("Response.redirect(ADMIN_SITE_ORIGIN"),
  "the public service must not reveal the administrator origin through redirects",
);

const loginHostGate = source.indexOf('path === "/api/admin/session" ||');
const accountHostReject = source.indexOf("if (accountOnlyApi && !accountHost)");
assert.ok(loginHostGate >= 0 && accountHostReject > loginHostGate);

console.log("WTLYF security regression checks passed");
