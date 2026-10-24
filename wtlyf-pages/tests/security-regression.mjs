import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const source = await readFile(new URL("../src/index.ts", import.meta.url), "utf8");

const mustContain = [
  'path === "/api/admin/session" ||',
  'path === "/api/admin/logout" && request.method === "POST"',
  'return reply({ ok: false, message: "接口不存在" }, 404);',
  'return reply({ ok: false, message: "资源不存在" }, 404);',
  'CREATE TABLE IF NOT EXISTS admin_access_audit',
  'path === "/api/admin/access-audit"',
  'context.waitUntil(recordAdminAccess(request, env, path, "page"))',
  'context?.waitUntil(recordAdminAccess(request, env, path, "scanner"))',
  'const PHP_PROBE_PATH =',
  'const isSuspiciousPath = (path: string) =>',
  'const decoded = decodeURIComponent(candidate);',
  'if (isSuspiciousPath(path))',
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
  'wtlyf-admin-password-v2',
  'wtlyf-admin-username-v1',
  'return `v2$${await passwordHash',
  'storedHash.startsWith("v2$")',
  'version !== "v5" && version !== "v6"',
  '${SESSION_COOKIE}=v6.${expires}.${sessionToken}.${signature}',
  'username IN (?,?)',
  'CREATE TABLE IF NOT EXISTS admin_action_tokens',
  '"DELETE FROM admin_action_tokens WHERE token_hash=? AND username=? AND action=? AND expires_at>? RETURNING token_hash"',
  'String(row?.created_by ?? "") === "system"',
  '"account-mutation"',
  '仅主账号可以管理管理员账户',
  '主账号当前密码错误',
  '`admin-account-mutation:${ipHash}`',
  'body:JSON.stringify({username,password,currentPassword,actionToken})',
  'const path = url.pathname.replace(/\\/+$/, "") || "/"',
];

for (const marker of mustContain) {
  assert.ok(source.includes(marker), `missing security control: ${marker}`);
}

assert.ok(
  !source.includes("Response.redirect(ADMIN_SITE_ORIGIN"),
  "the public service must not reveal the administrator origin through redirects",
);
assert.ok(
  !source.includes("${SESSION_COOKIE}=v6.${encodeSessionUsername"),
  "v6 administrator cookies must not expose an encoded username",
);

const loginHostGate = source.indexOf('path === "/api/admin/session" ||');
const accountHostReject = source.indexOf("if (accountOnlyApi && !accountHost)");
assert.ok(loginHostGate >= 0, "administrator login must remain rate limited");
assert.ok(accountHostReject >= 0, "account-center APIs must remain host isolated");
const accountOnlyApi = source.slice(
  source.indexOf("const accountOnlyApi ="),
  accountHostReject,
);
assert.ok(
  !accountOnlyApi.includes('path === "/api/admin/session"'),
  "ordinary administrator hosts must retain their login endpoint",
);
assert.ok(
  !accountOnlyApi.includes('path === "/api/admin/logout"'),
  "ordinary administrator hosts must retain their logout endpoint",
);
assert.ok(
  accountOnlyApi.includes('path === "/api/admin/accounts"'),
  "administrator-account management must remain isolated to the account center",
);
assert.ok(
  accountOnlyApi.includes('path === "/api/admin/access-audit"'),
  "administrator access audits must remain isolated to the account center",
);

console.log("WTLYF security regression checks passed");
