interface RateLimiter {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}
interface Env {
  DB: D1Database;
  PUSH_FILES: KVNamespace;
  AI?: { run(model: string, input: unknown): Promise<any> };
  WTLYF_ADMIN_TOKEN: string;
  NIGHT_ISSUER_PK8_B64: string;
  GITHUB_TOKEN: string;
  ASSETS?: Fetcher;
  AUTH_RATE_LIMITER?: RateLimiter;
  API_RATE_LIMITER?: RateLimiter;
}

const API_HEADERS = {
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Referrer-Policy": "no-referrer",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
  "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
  "Content-Security-Policy":
    "default-src 'none'; frame-ancestors 'none'; base-uri 'none'",
  "Cross-Origin-Opener-Policy": "same-origin",
  "Cross-Origin-Resource-Policy": "same-origin",
  "X-Permitted-Cross-Domain-Policies": "none",
  "X-Robots-Tag": "noindex, nofollow, nosnippet, noarchive",
  "X-WTLYF-API": "cf-v3",
};
const PAGE_HEADERS = {
  "content-type": "text/html; charset=utf-8",
  "cache-control": "no-store",
  "content-security-policy":
    "default-src 'self'; img-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
  "referrer-policy": "no-referrer",
  "x-content-type-options": "nosniff",
  "x-frame-options": "DENY",
  "permissions-policy": "camera=(), microphone=(), geolocation=()",
  "strict-transport-security": "max-age=31536000; includeSubDomains",
  "cross-origin-opener-policy": "same-origin",
  "cross-origin-resource-policy": "same-origin",
  "x-permitted-cross-domain-policies": "none",
};
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const SESSION_COOKIE = "__Host-wtlyf_admin";
const SESSION_SECONDS = 12 * 60 * 60;
let securityRateLimitReady: Promise<unknown> | null = null;
let adminSessionReady: Promise<unknown> | null = null;
let adminActionTokenReady: Promise<unknown> | null = null;
let adminAccessAuditReady: Promise<unknown> | null = null;
let nightEphemeralSessionReady: Promise<unknown> | null = null;
// Cloudflare Workers WebCrypto caps PBKDF2 at 100,000 iterations.
const PASSWORD_ITERATIONS = 100000;
const ADMIN_SITE_ORIGIN = "https://wtlyf-admin-center.pages.dev";
const LEGACY_ADMIN_HOST = "wtlyf-license-center.wtlyf-night.workers.dev";
const ADMIN_PAGE_PATHS = new Set([
  "/",
  "/admin/accounts",
  "/admin/account-detail",
  "/night-licenses",
  "/push",
]);
const ADMIN_ALLOWED_COUNTRIES = new Set(["CN", "HK", "MO", "TW"]);
const SUSPICIOUS_PATH =
  /(?:^|\/)(?:\.env|\.git|wp-admin|wp-login\.php|phpmyadmin|server-status|actuator|cgi-bin)(?:\/|$)/i;
const PHP_PROBE_PATH =
  /(?:^|\/)(?:[^/]*\.php[^/]*|composer\.(?:json|lock)|\.user\.ini|\.htaccess)(?:\/|$)|(?:^|\/)vendor\/phpunit(?:\/|$)/i;
const isSuspiciousPath = (path: string) => {
  let candidate = path;
  for (let pass = 0; pass < 3; pass++) {
    const normalized = candidate.replace(/\\/g, "/");
    if (SUSPICIOUS_PATH.test(normalized) || PHP_PROBE_PATH.test(normalized))
      return true;
    try {
      const decoded = decodeURIComponent(candidate);
      if (decoded === candidate) return false;
      candidate = decoded;
    } catch {
      return true;
    }
  }
  return true;
};
const GITHUB_REPO = "wutong7891/KernelSU";
const KMI_SET = new Set([
  "android12-5.10",
  "android13-5.10",
  "android13-5.15",
  "android14-5.15",
  "android14-6.1",
  "android15-6.6",
  "android16-6.12",
  "android17-6.18",
]);
const TOOLBOX_MODULES = new Set([
  "always-strong.zip",
  "jailbreak-tolerance.zip",
  "pathmask-android12-5.10.zip",
  "pathmask-android13-5.10.zip",
  "pathmask-android13-5.15.zip",
  "pathmask-android14-5.15.zip",
  "pathmask-android14-6.1.zip",
  "pathmask-android15-6.6.zip",
  "pathmask-android16-6.12.zip",
  "soterkey.zip",
  "tee-simulator-v6.0.1-324.zip",
  "tricky-addon-v4.3.zip",
  "tricky-auto-add-v1.1.zip",
]);
const reply = (body: unknown, status = 200, extra: HeadersInit = {}) =>
  Response.json(body, { status, headers: { ...API_HEADERS, ...extra } });
const sameOriginMutation = (request: Request, url: URL) =>
  request.headers.get("origin") === url.origin &&
  request.headers.get("sec-fetch-site") !== "cross-site";
const requestIp = (request: Request) =>
  (
    request.headers.get("cf-connecting-ip") ??
    request.headers.get("x-forwarded-for")?.split(",")[0] ??
    "unknown"
  )
    .trim()
    .slice(0, 64);
const firewallReply = (
  message: string,
  status: number,
  extra: HeadersInit = {},
) =>
  reply({ ok: false, error: "firewall", message }, status, {
    "Retry-After": "60",
    ...extra,
  });
async function cacheRateLimit(
  request: Request,
  url: URL,
  scope: string,
  limit: number,
  period: number,
) {
  try {
    const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
    const keyHash = hex(
      await crypto.subtle.digest(
        "SHA-256",
        new TextEncoder().encode(`${scope}:${ip}`),
      ),
    );
    const key = new Request(
      `${url.origin}/__wtlyf_firewall/${scope}/${keyHash}`,
    );
    const cached = await caches.default.match(key),
      count = Number(cached?.headers.get("x-wtlyf-count") ?? 0);
    if (count >= limit) return false;
    await caches.default.put(
      key,
      new Response("", {
        headers: {
          "cache-control": `public, max-age=${period}`,
          "x-wtlyf-count": String(count + 1),
        },
      }),
    );
  } catch {}
  return true;
}
async function d1RateLimit(
  env: Env,
  scopeKey: string,
  limit: number,
  period: number,
) {
  const now = Math.floor(Date.now() / 1000),
    expiresAt = now + period;
  try {
    securityRateLimitReady ??= env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS security_rate_limits (scope_key TEXT PRIMARY KEY, count INTEGER NOT NULL, expires_at INTEGER NOT NULL)",
    ).run();
    await securityRateLimitReady;
    const row = await env.DB.prepare(
      "INSERT INTO security_rate_limits(scope_key,count,expires_at) VALUES(?,1,?) ON CONFLICT(scope_key) DO UPDATE SET count=CASE WHEN expires_at<=? THEN 1 ELSE count+1 END,expires_at=CASE WHEN expires_at<=? THEN excluded.expires_at ELSE expires_at END RETURNING count",
    )
      .bind(scopeKey, expiresAt, now, now)
      .first<any>();
    if (crypto.getRandomValues(new Uint8Array(1))[0] < 3)
      await env.DB.prepare(
        "DELETE FROM security_rate_limits WHERE expires_at<?",
      )
        .bind(now - 3600)
        .run();
    return Number(row?.count ?? limit + 1) <= limit;
  } catch (error) {
    console.error("durable rate limit failed", error);
    return null;
  }
}
async function firewall(
  request: Request,
  url: URL,
  env: Env,
  context?: ExecutionContext,
) {
  if (!new Set(["GET", "HEAD", "POST", "OPTIONS"]).has(request.method))
    return firewallReply("请求方法已被防火墙拦截", 405, {
      Allow: "GET, HEAD, POST, OPTIONS",
    });
  if (request.url.length > 4096) return firewallReply("请求地址过长", 414);
  const path = url.pathname.replace(/\/+$/, "") || "/",
    rawLength = request.headers.get("content-length"),
    length = Number(rawLength ?? 0);
  if (isSuspiciousPath(path)) {
    context?.waitUntil(recordAdminAccess(request, env, path, "scanner"));
    return firewallReply("资源不存在", 404);
  }
  if (
    rawLength !== null &&
    (!Number.isSafeInteger(length) || length < 0)
  )
    return firewallReply("请求长度无效", 400);
  const jsonPost =
    request.method === "POST" &&
    (path === "/api/admin/session" ||
      (path.startsWith("/api/admin/") &&
        path !== "/api/admin/push/upload") ||
      path === "/api/night/sign" ||
      path === "/api/night/patch/create" ||
      path === "/api/night/patch/finalize" ||
      path === "/api/night/patch/cleanup");
  if (jsonPost) {
    const contentType = (request.headers.get("content-type") ?? "")
      .split(";", 1)[0]
      .trim()
      .toLowerCase();
    if (contentType !== "application/json" && !contentType.endsWith("+json"))
      return firewallReply("请求格式必须为 JSON", 415);
  }
  // Static downloads stay cacheable. Sensitive API reads and every write are rate limited.
  const sensitiveRead =
    request.method === "GET" &&
    (path.startsWith("/api/admin/") || path.startsWith("/api/night/"));
  if (request.method !== "POST" && !sensitiveRead) return null;
  let max = 2 * 1024 * 1024;
  if (path === "/api/v1/night/calculator-ocr") max = 768 * 1024;
  else if (
    path === "/api/admin/session" ||
    path === "/api/v1/activate" ||
    path === "/api/v1/check" ||
    path.startsWith("/api/v1/night/")
  )
    max = 16 * 1024;
  else if (path === "/api/admin/push/upload") max = 21 * 1024 * 1024;
  else if (path === "/api/night/patch/start") max = Number.POSITIVE_INFINITY;
  else if (path === "/api/night/patch/chunk") max = 17 * 1024 * 1024;
  else if (path.startsWith("/api/admin/") || path.startsWith("/api/night/"))
    max = 256 * 1024;
  if (request.method === "POST" && length > max)
    return firewallReply("请求内容超过安全限制", 413);
  let scope = "",
    limit = 0,
    limiter: RateLimiter | undefined;
  if (path === "/api/admin/session") {
    scope = "admin-login";
    limit = request.method === "POST" ? 5 : 30;
    limiter = env.AUTH_RATE_LIMITER;
  } else if (path === "/api/v1/night/calculator-ocr") {
    scope = "calculator-ocr";
    limit = 60;
    limiter = env.API_RATE_LIMITER;
  } else if (
    path === "/api/v1/activate" ||
    path === "/api/v1/check" ||
    path.startsWith("/api/v1/night/")
  ) {
    scope = "license-api";
    limit = 60;
    limiter = env.API_RATE_LIMITER;
  } else if (path.startsWith("/api/admin/")) {
    scope = "admin-api";
    limit = 30;
    limiter = env.API_RATE_LIMITER;
  } else if (path.startsWith("/api/night/")) {
    scope = "night-tools";
    limit = 120;
    limiter = env.API_RATE_LIMITER;
  }
  if (!scope) return null;
  const ip = request.headers.get("cf-connecting-ip") ?? "unknown",
    ipHash = hex(
      await crypto.subtle.digest(
        "SHA-256",
        new TextEncoder().encode(`${scope}:${ip}`),
      ),
    ),
    scopeKey = `${scope}:${ipHash}`,
    native = limiter ? await limiter.limit({ key: scopeKey }) : null;
  if (native && !native.success)
    return firewallReply("请求过于频繁，请一分钟后再试", 429);
  if (!limiter) {
    const durable = await d1RateLimit(env, scopeKey, limit, 60);
    if (durable === false)
      return firewallReply("请求过于频繁，请一分钟后再试", 429);
    if (
      durable === null &&
      !(await cacheRateLimit(request, url, scope, limit, 60))
    )
      return firewallReply("请求过于频繁，请一分钟后再试", 429);
  }
  return null;
}
const normalize = (value: unknown) => {
  const code = String(value ?? "")
    .normalize("NFKC")
    .trim()
    .replace(/\s+/g, "");
  return /^(?:WTLYF|NIGHT)(?:-[A-Z2-9]{5}){4}$/i.test(code)
    ? code.toUpperCase()
    : code;
};
const validLicenseCode = (code: string) =>
  code.length >= 1 && code.length <= 128 && !/[\u0000-\u001f\u007f]/.test(code);
const hex = (bytes: ArrayBuffer) =>
  Array.from(new Uint8Array(bytes), (b) =>
    b.toString(16).padStart(2, "0"),
  ).join("");
const codeHash = async (code: string) =>
  hex(
    await crypto.subtle.digest(
      "SHA-256",
      new TextEncoder().encode(`wtlyf-license-v1:${code}`),
    ),
  );
const generateCode = () => {
  const bytes = crypto.getRandomValues(new Uint8Array(20));
  const chars = Array.from(bytes, (b) => ALPHABET[b % ALPHABET.length]).join(
    "",
  );
  return `WTLYF-${chars.slice(0, 5)}-${chars.slice(5, 10)}-${chars.slice(10, 15)}-${chars.slice(15, 20)}`;
};
const nightCodeHash = async (code: string) =>
  hex(
    await crypto.subtle.digest(
      "SHA-256",
      new TextEncoder().encode(`night-license-v1:${code}`),
    ),
  );
const generateNightCode = () => {
  const bytes = crypto.getRandomValues(new Uint8Array(20));
  const chars = Array.from(bytes, (b) => ALPHABET[b % ALPHABET.length]).join(
    "",
  );
  return `NIGHT-${chars.slice(0, 5)}-${chars.slice(5, 10)}-${chars.slice(10, 15)}-${chars.slice(15, 20)}`;
};
const safeEqual = (a: string, b: string) => {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
};

async function generateNeverRepeatedCodes(
  env: Env,
  scope: "wtlyf" | "night",
  count: number,
  durationHours: number | null,
  deviceLimit: number | null,
) {
  const codes: string[] = [];
  for (let round = 0; round < 12 && codes.length < count; round++) {
    const needed = count - codes.length,
      candidates: { code: string; hash: string; hint: string }[] = [];
    for (let i = 0; i < needed; i++) {
      const code = scope === "night" ? generateNightCode() : generateCode();
      const hash =
        scope === "night" ? await nightCodeHash(code) : await codeHash(code);
      candidates.push({
        code,
        hash,
        hint: `${code.slice(0, 8)}••••${code.slice(-5)}`,
      });
    }
    const reserved = await env.DB.batch(
      candidates.map((candidate) =>
        env.DB.prepare(
          "INSERT OR IGNORE INTO issued_code_hashes(scope,code_hash) VALUES(?,?)",
        ).bind(scope, candidate.hash),
      ),
    );
    const accepted = candidates.filter(
      (_, index) => reserved[index]?.meta.changes === 1,
    );
    if (!accepted.length) continue;
    const table = scope === "night" ? "night_licenses" : "licenses";
    await env.DB.batch(
      accepted.map((candidate) =>
        env.DB.prepare(
          `INSERT INTO ${table}(code_hash,code_hint,duration_hours,device_limit) VALUES(?,?,?,?)`,
        ).bind(candidate.hash, candidate.hint, durationHours, deviceLimit),
      ),
    );
    codes.push(...accepted.map((candidate) => candidate.code));
  }
  if (codes.length !== count) throw new Error("无法生成新的唯一卡密，请重试");
  return codes;
}

async function issueCustomCode(
  env: Env,
  scope: "wtlyf" | "night",
  code: string,
  durationHours: number | null,
  deviceLimit: number | null,
) {
  const hash =
    scope === "night" ? await nightCodeHash(code) : await codeHash(code);
  const table = scope === "night" ? "night_licenses" : "licenses",
    hint =
      code.length <= 18 ? code : `${code.slice(0, 8)}••••${code.slice(-5)}`;
  const generatedPattern =
    scope === "night"
      ? /^NIGHT(?:-[A-Z2-9]{5}){4}$/
      : /^WTLYF(?:-[A-Z2-9]{5}){4}$/;
  // A deleted custom code may be issued again. A deleted randomly generated
  // code keeps its permanent reservation and cannot be restored through the
  // custom-code field.
  const reserved = await env.DB.prepare(
    "INSERT OR IGNORE INTO issued_code_hashes(scope,code_hash) VALUES(?,?)",
  )
    .bind(scope, hash)
    .run();
  if (reserved.meta.changes !== 1 && generatedPattern.test(code)) return false;
  const issued = await env.DB.prepare(
    `INSERT OR IGNORE INTO ${table}(code_hash,code_hint,duration_hours,device_limit) VALUES(?,?,?,?)`,
  )
    .bind(hash, hint, durationHours, deviceLimit)
    .run();
  return issued.meta.changes === 1;
}

function deviceLimitFrom(body: any): number | null | undefined {
  if (body.unlimited === true || body.deviceLimit === null) return null;
  const value = Number(body.deviceLimit ?? 1);
  return Number.isInteger(value) && value >= 1 && value <= 100000
    ? value
    : undefined;
}

async function signSession(
  username: string,
  expires: string,
  sessionToken: string,
  host: string,
  passwordHash: string,
  env: Env,
) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(env.WTLYF_ADMIN_TOKEN),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return hex(
    await crypto.subtle.sign(
      "HMAC",
      key,
      new TextEncoder().encode(
        `wtlyf-admin-v5:${username}:${expires}:${sessionToken}:${host}:${passwordHash}`,
      ),
    ),
  );
}
async function ensureAdminSessions(env: Env) {
  adminSessionReady ??= env.DB.batch([
    env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS admin_sessions (session_hash TEXT PRIMARY KEY, username TEXT NOT NULL, host TEXT NOT NULL, expires_at INTEGER NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
    ),
    env.DB.prepare(
      "CREATE INDEX IF NOT EXISTS idx_admin_sessions_user ON admin_sessions(username,expires_at)",
    ),
  ]);
  await adminSessionReady;
}
const sessionTokenHash = async (token: string) =>
  hex(
    await crypto.subtle.digest(
      "SHA-256",
      new TextEncoder().encode(`wtlyf-session-v1:${token}`),
    ),
  );
async function createAdminSession(
  username: string,
  expires: string,
  host: string,
  env: Env,
) {
  await ensureAdminSessions(env);
  const token = toBase64Url(crypto.getRandomValues(new Uint8Array(32))),
    tokenHash = await sessionTokenHash(token),
    now = Math.floor(Date.now() / 1000);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM admin_sessions WHERE expires_at<=?").bind(now),
    env.DB.prepare(
      "INSERT INTO admin_sessions(session_hash,username,host,expires_at) VALUES(?,?,?,?)",
    ).bind(tokenHash, username, host, Number(expires)),
    env.DB.prepare(
      "DELETE FROM admin_sessions WHERE session_hash IN (SELECT session_hash FROM admin_sessions WHERE username=? AND host=? ORDER BY expires_at DESC, created_at DESC LIMIT -1 OFFSET 8)",
    ).bind(username, host),
  ]);
  return token;
}
async function revokeAdminSession(request: Request, env: Env) {
  const cookie = request.headers.get("cookie") ?? "",
    match = cookie.match(new RegExp(`(?:^|;\\s*)${SESSION_COOKIE}=([^;]+)`));
  if (!match) return;
  let decoded = "";
  try {
    decoded = decodeURIComponent(match[1]);
  } catch {
    return;
  }
  const parts = decoded.split(".", 5),
    version = parts[0],
    token = version === "v6" ? parts[2] : version === "v5" ? parts[3] : "";
  if (!token) return;
  await ensureAdminSessions(env);
  await env.DB.prepare("DELETE FROM admin_sessions WHERE session_hash=?")
    .bind(await sessionTokenHash(token))
    .run();
}
async function getAdminSession(request: Request, env: Env) {
  const cookie = request.headers.get("cookie") ?? "";
  const match = cookie.match(
    new RegExp(`(?:^|;\\s*)${SESSION_COOKIE}=([^;]+)`),
  );
  if (!match) return null;
  let decoded = "";
  try {
    decoded = decodeURIComponent(match[1]);
  } catch {
    return null;
  }
  const parts = decoded.split(".", 5),
    version = parts[0];
  if (version !== "v5" && version !== "v6") return null;
  const encodedUsername = version === "v5" ? parts[1] : "",
    expires = version === "v5" ? parts[2] : parts[1],
    sessionToken = version === "v5" ? parts[3] : parts[2],
    signature = version === "v5" ? parts[4] : parts[3];
  if (
    !expires ||
    !sessionToken ||
    !signature ||
    Number(expires) <= Math.floor(Date.now() / 1000)
  )
    return null;
  let username = "";
  if (version === "v5") {
    if (!encodedUsername) return null;
    try {
      username = decodeSessionUsername(encodedUsername);
    } catch {
      return null;
    }
  }
  await ensureAdminSessions(env);
  const host = new URL(request.url).hostname;
  const row = await env.DB.prepare(
    version === "v5"
      ? "SELECT u.username,u.password_hash FROM admin_sessions s JOIN admin_users u ON u.username=s.username WHERE s.session_hash=? AND s.username=? AND s.host=? AND s.expires_at=? AND s.expires_at>? AND u.active=1"
      : "SELECT u.username,u.password_hash FROM admin_sessions s JOIN admin_users u ON u.username=s.username WHERE s.session_hash=? AND s.host=? AND s.expires_at=? AND s.expires_at>? AND u.active=1",
  )
    .bind(
      ...(version === "v5"
        ? [
            await sessionTokenHash(sessionToken),
            username,
            host,
            Number(expires),
            Math.floor(Date.now() / 1000),
          ]
        : [
            await sessionTokenHash(sessionToken),
            host,
            Number(expires),
            Math.floor(Date.now() / 1000),
          ]),
    )
    .first<any>();
  if (
    !row?.username ||
    !safeEqual(
      signature,
      await signSession(
        String(row.username),
        expires,
        sessionToken,
        host,
        String(row.password_hash),
        env,
      ),
    )
  )
    return null;
  return String(row.username);
}

async function ensureAdminActionTokens(env: Env) {
  adminActionTokenReady ??= env.DB.batch([
    env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS admin_action_tokens (token_hash TEXT PRIMARY KEY, username TEXT NOT NULL, action TEXT NOT NULL, expires_at INTEGER NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
    ),
    env.DB.prepare(
      "CREATE INDEX IF NOT EXISTS idx_admin_action_tokens_expiry ON admin_action_tokens(expires_at)",
    ),
  ]);
  await adminActionTokenReady;
}

async function isPrimaryAdmin(username: string, env: Env) {
  const row = await env.DB.prepare(
    "SELECT created_by FROM admin_users WHERE username=? AND active=1",
  )
    .bind(username)
    .first<any>();
  return String(row?.created_by ?? "") === "system";
}

async function adminActionTokenHash(
  token: string,
  username: string,
  action: string,
  host: string,
  env: Env,
) {
  return hex(
    await credentialHmac(
      `${token}:${username}:${action}:${host}`,
      "wtlyf-admin-action-v1",
      env,
    ),
  );
}

async function issueAdminActionToken(
  username: string,
  action: string,
  host: string,
  env: Env,
) {
  await ensureAdminActionTokens(env);
  const token = toBase64Url(crypto.getRandomValues(new Uint8Array(32))),
    tokenHash = await adminActionTokenHash(token, username, action, host, env),
    now = Math.floor(Date.now() / 1000),
    expiresAt = now + 5 * 60;
  await env.DB.batch([
    env.DB.prepare("DELETE FROM admin_action_tokens WHERE expires_at<=?").bind(
      now,
    ),
    env.DB.prepare(
      "DELETE FROM admin_action_tokens WHERE username=? AND action=?",
    ).bind(username, action),
    env.DB.prepare(
      "INSERT INTO admin_action_tokens(token_hash,username,action,expires_at) VALUES(?,?,?,?)",
    ).bind(tokenHash, username, action, expiresAt),
  ]);
  return { token, expiresAt };
}

async function consumeAdminActionToken(
  token: string,
  username: string,
  action: string,
  host: string,
  env: Env,
) {
  if (!token || token.length > 256) return false;
  await ensureAdminActionTokens(env);
  const result = await env.DB.prepare(
    "DELETE FROM admin_action_tokens WHERE token_hash=? AND username=? AND action=? AND expires_at>? RETURNING token_hash",
  )
    .bind(
      await adminActionTokenHash(token, username, action, host, env),
      username,
      action,
      Math.floor(Date.now() / 1000),
    )
    .first<any>();
  return !!result?.token_hash;
}

async function authorizeAccountMutation(
  request: Request,
  env: Env,
  username: string,
  body: any,
) {
  if (!(await isPrimaryAdmin(username, env)))
    return reply(
      { ok: false, message: "仅主账号可以管理管理员账户" },
      403,
    );
  const ipHash = hex(
      await crypto.subtle.digest(
        "SHA-256",
        new TextEncoder().encode(`${username}:${requestIp(request)}`),
      ),
    ),
    allowed = await d1RateLimit(
      env,
      `admin-account-mutation:${ipHash}`,
      5,
      15 * 60,
    );
  if (allowed !== true)
    return reply(
      {
        ok: false,
        message:
          allowed === false
            ? "账户管理验证失败次数过多，请 15 分钟后再试"
            : "账户管理安全验证暂时不可用，请稍后重试",
      },
      allowed === false ? 429 : 503,
      allowed === false ? { "Retry-After": "900" } : {},
    );
  if (
    !(await consumeAdminActionToken(
      String(body.actionToken ?? ""),
      username,
      "account-mutation",
      new URL(request.url).hostname,
      env,
    ))
  )
    return reply(
      { ok: false, message: "安全操作令牌已失效，请刷新页面后重试" },
      403,
    );
  const currentPassword = String(body.currentPassword ?? "");
  if (
    currentPassword.length < 1 ||
    currentPassword.length > 256 ||
    !(await authenticateAdmin(username, currentPassword, env))
  )
    return reply({ ok: false, message: "主账号当前密码错误" }, 403);
  return null;
}

function accountDetailSite(nonce: string) {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#070b18"><title>管理员使用详情</title><style nonce="${nonce}">
*{box-sizing:border-box}body{margin:0;min-height:100vh;color:#eef5ff;font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif;background:radial-gradient(circle at 15% 10%,#23396b 0,transparent 34%),radial-gradient(circle at 85% 15%,#562b78 0,transparent 28%),#070b18}main{width:min(1080px,calc(100% - 28px));margin:auto;padding:30px 0 64px}.card{border:1px solid #ffffff2c;background:#101a31c9;box-shadow:0 22px 70px #0008;backdrop-filter:blur(24px);border-radius:28px;padding:26px;margin-bottom:20px}.top{display:flex;align-items:center;justify-content:space-between;gap:18px}.top h1{margin:0;font-size:clamp(26px,5vw,44px)}.muted,small{color:#aebbd4}.buttons{display:flex;gap:10px;flex-wrap:wrap}button{border-radius:15px;border:1px solid #ffffff2e;padding:12px 16px;font:inherit;font-weight:700;color:#fff;background:linear-gradient(120deg,#2f8cff,#8056e8);cursor:pointer}.logout{background:#8e2948}.row{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;padding:15px 0;border-bottom:1px solid #ffffff1c}.row:last-child{border:0}.row strong,.row small{display:block}.audit{overflow:auto}.audit-row{display:grid;grid-template-columns:150px 1fr 160px 160px 70px;gap:12px;align-items:start;padding:14px 0;border-bottom:1px solid #ffffff1c;min-width:820px}.ok{color:#8fe5b2}.fail{color:#ff9db7}.status{min-height:22px;color:#91d9ff}@media(max-width:720px){main{padding-top:16px}.card{border-radius:21px;padding:20px}.top{align-items:flex-start;flex-direction:column}.buttons{width:100%}.buttons button{flex:1}}
</style></head><body><main><header class="card top"><div><h1 id="title">管理员详情</h1><div class="muted">单独查看所选管理员的使用情况、设备与 IP。</div></div><div class="buttons"><button id="refresh">刷新</button><button id="exit">退出详情</button></div></header><section class="card"><h2>使用情况</h2><div id="summary" class="status">正在加载…</div><h2>最近操作</h2><div id="recent">正在加载…</div></section><section class="card"><h2>登录设备与 IP</h2><div class="muted">最近 200 条登录记录，自动保留 90 天。</div><div id="logins" class="audit">正在加载…</div></section></main><script nonce="${nonce}">
const $=s=>document.querySelector(s),username=new URLSearchParams(location.search).get('username')||'';async function req(path,options={}){const r=await fetch(path,{credentials:'same-origin',headers:{'content-type':'application/json'},...options});let d={};try{d=await r.json()}catch{}if(r.status===401){location.href='/';throw Error('请重新登录')}if(!r.ok)throw Error(d.message||'请求失败');return d}function addRow(parent,left,meta,right){const row=document.createElement('div'),box=document.createElement('div'),name=document.createElement('strong'),small=document.createElement('small'),value=document.createElement('span');row.className='row';name.textContent=left;small.textContent=meta;value.textContent=right;box.append(name,small);row.append(box,value);parent.append(row)}async function load(){if(!username){location.href='/';return}$('#title').textContent=username+' 的详情';const summary=$('#summary'),recent=$('#recent'),logins=$('#logins');summary.textContent='正在加载…';recent.textContent='正在加载…';logins.textContent='正在加载…';try{const [usage,audit]=await Promise.all([req('/api/admin/usage-audit?username='+encodeURIComponent(username)),req('/api/admin/login-audit?username='+encodeURIComponent(username))]);const s=usage.summary[0]||{};summary.textContent='修补 '+Number(s.patch_count||0)+' 次 · 签发 '+Number(s.license_count||0)+' 张（'+Number(s.issue_operations||0)+' 次操作）';recent.textContent='';if(!usage.recent.length)recent.textContent='暂无最近操作';usage.recent.forEach(a=>{const action=a.action==='night_patch'?'网页修补':a.action==='night_license_issue'?'Night 卡密签发':'WTLYF 卡密签发';addRow(recent,action,a.created_at+' · '+a.ip_address+(a.detail?' · '+a.detail:''),a.action==='night_patch'?'1 次':a.quantity+' 张')});logins.textContent='';if(!audit.records.length)logins.textContent='暂无登录记录';audit.records.forEach(a=>{const row=document.createElement('div'),time=document.createElement('div'),account=document.createElement('div'),device=document.createElement('div'),network=document.createElement('div'),state=document.createElement('strong'),ua=document.createElement('small'),name=document.createElement('strong'),place=document.createElement('small'),ip=document.createElement('strong');row.className='audit-row';time.textContent=a.created_at;name.textContent=a.username;ua.textContent=a.user_agent;account.append(name,ua);device.textContent=a.device;ip.textContent=a.ip_address;place.textContent=[a.country,a.colo].filter(Boolean).join(' · ')||'未知位置';network.append(ip,place);state.textContent=a.success?'成功':'失败';state.className=a.success?'ok':'fail';row.append(time,account,device,network,state);logins.append(row)})}catch(e){summary.textContent=e.message;recent.textContent='';logins.textContent=e.message}}$('#refresh').onclick=load;$('#exit').onclick=()=>location.href='/';load();
</script></body></html>`;
}

function accountDetailSiteV2(nonce: string) {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#070b18"><title>管理员详情</title><style nonce="${nonce}">
*{box-sizing:border-box}body{margin:0;min-height:100vh;color:#eef5ff;font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif;background:radial-gradient(circle at 15% 10%,#23396b 0,transparent 34%),radial-gradient(circle at 85% 15%,#562b78 0,transparent 28%),#070b18}main{width:min(1080px,calc(100% - 28px));margin:auto;padding:30px 0 64px}.card{border:1px solid #ffffff2c;background:#101a31c9;box-shadow:0 22px 70px #0008;backdrop-filter:blur(24px);border-radius:28px;padding:26px;margin-bottom:20px}.top,.row{display:flex;align-items:center;justify-content:space-between;gap:18px}.top h1{margin:0;font-size:clamp(26px,5vw,44px)}.muted,small{color:#aebbd4}.buttons{display:flex;gap:10px;flex-wrap:wrap}button,input{border-radius:15px;border:1px solid #ffffff2e;padding:12px 16px;font:inherit;color:#fff;background:#0c162c}button{font-weight:700;background:linear-gradient(120deg,#2f8cff,#8056e8);cursor:pointer}.credential{padding:14px 0;border-bottom:1px solid #ffffff1c}.credential:last-child,.row:last-child{border:0}.credential strong,.credential span{display:block}.password-row{display:grid;grid-template-columns:1fr auto;gap:10px;margin-top:10px}.row{align-items:flex-start;padding:15px 0;border-bottom:1px solid #ffffff1c}.row strong,.row small{display:block}.audit{overflow:auto}.audit-row{display:grid;grid-template-columns:150px 1fr 160px 160px 70px;gap:12px;align-items:start;padding:14px 0;border-bottom:1px solid #ffffff1c;min-width:820px}.ok{color:#8fe5b2}.fail{color:#ff9db7}.status{min-height:22px;color:#91d9ff}@media(max-width:720px){main{padding-top:16px}.card{border-radius:21px;padding:20px}.top{align-items:flex-start;flex-direction:column}.buttons{width:100%}.buttons button{flex:1}.password-row{grid-template-columns:1fr}}
</style></head><body><main><header class="card top"><div><h1 id="title">管理员详情</h1><div class="muted">单独查看所选管理员的账户、使用情况、设备与 IP。</div></div><div class="buttons"><button id="refresh">刷新</button><button id="exit">退出详情</button></div></header><section class="card"><h2>账户凭据</h2><div class="credential"><strong>用户名</strong><span id="accountUsername">正在加载…</span></div><div class="credential"><strong>密码</strong><span>不可查看：服务器只保存加盐哈希，不保存明文密码。</span><div class="password-row"><input id="newPassword" type="password" autocomplete="new-password" maxlength="256" placeholder="输入新密码（1–256 字符）"><button id="resetPassword">重置密码</button></div><div id="passwordStatus" class="status"></div></div></section><section class="card"><h2>使用情况</h2><div id="summary" class="status">正在加载…</div><h2>最近操作</h2><div id="recent">正在加载…</div></section><section class="card"><h2>登录设备与 IP</h2><div class="muted">最近 200 条登录记录，自动保留 90 天。</div><div id="logins" class="audit">正在加载…</div></section></main><script nonce="${nonce}">
const $=s=>document.querySelector(s),username=new URLSearchParams(location.search).get('username')||'';async function req(path,options={}){const r=await fetch(path,{credentials:'same-origin',headers:{'content-type':'application/json'},...options});let d={};try{d=await r.json()}catch{}if(r.status===401){location.href='/';throw Error('请重新登录')}if(!r.ok)throw Error(d.message||'请求失败');return d}function addRow(parent,left,meta,right){const row=document.createElement('div'),box=document.createElement('div'),name=document.createElement('strong'),small=document.createElement('small'),value=document.createElement('span');row.className='row';name.textContent=left;small.textContent=meta;value.textContent=right;box.append(name,small);row.append(box,value);parent.append(row)}async function load(){if(!username){location.href='/';return}$('#title').textContent=username+' 的详情';$('#accountUsername').textContent=username;const summary=$('#summary'),recent=$('#recent'),logins=$('#logins');summary.textContent='正在加载…';recent.textContent='正在加载…';logins.textContent='正在加载…';try{const [usage,audit]=await Promise.all([req('/api/admin/usage-audit?username='+encodeURIComponent(username)),req('/api/admin/login-audit?username='+encodeURIComponent(username))]);const s=usage.summary[0]||{};summary.textContent='修补 '+Number(s.patch_count||0)+' 次 · 签发 '+Number(s.license_count||0)+' 张（'+Number(s.issue_operations||0)+' 次操作）';recent.textContent='';if(!usage.recent.length)recent.textContent='暂无最近操作';usage.recent.forEach(a=>{const action=a.action==='night_patch'?'网页修补':a.action==='night_license_issue'?'Night 卡密签发':'WTLYF 卡密签发';addRow(recent,action,a.created_at+' · '+a.ip_address+(a.detail?' · '+a.detail:''),a.action==='night_patch'?'1 次':a.quantity+' 张')});logins.textContent='';if(!audit.records.length)logins.textContent='暂无登录记录';audit.records.forEach(a=>{const row=document.createElement('div'),time=document.createElement('div'),account=document.createElement('div'),device=document.createElement('div'),network=document.createElement('div'),state=document.createElement('strong'),ua=document.createElement('small'),name=document.createElement('strong'),place=document.createElement('small'),ip=document.createElement('strong');row.className='audit-row';time.textContent=a.created_at;name.textContent=a.username;ua.textContent=a.user_agent;account.append(name,ua);device.textContent=a.device;ip.textContent=a.ip_address;place.textContent=[a.country,a.colo].filter(Boolean).join(' · ')||'未知位置';network.append(ip,place);state.textContent=a.success?'成功':'失败';state.className=a.success?'ok':'fail';row.append(time,account,device,network,state);logins.append(row)})}catch(e){summary.textContent=e.message;recent.textContent='';logins.textContent=e.message}}$('#resetPassword').onclick=async()=>{const password=$('#newPassword').value;if(!password){$('#passwordStatus').textContent='请输入新密码';return}if(!confirm('确认重置 '+username+' 的密码？'))return;try{const result=await req('/api/admin/accounts/reset-password',{method:'POST',body:JSON.stringify({username,password})});$('#newPassword').value='';$('#passwordStatus').textContent=result.message}catch(e){$('#passwordStatus').textContent=e.message}};$('#refresh').onclick=load;$('#exit').onclick=()=>location.href='/';load();
</script></body></html>`;
}

const fromBase64 = (value: string) => {
  const raw = atob(value.replace(/\s+/g, ""));
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
};
const toBase64Url = (value: ArrayBuffer | Uint8Array) => {
  let raw = "";
  for (const byte of new Uint8Array(
    value instanceof Uint8Array ? value.buffer : value,
  ))
    raw += String.fromCharCode(byte);
  return btoa(raw).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
};
const toBase64 = (value: ArrayBuffer | Uint8Array) => {
  let raw = "";
  for (const byte of new Uint8Array(
    value instanceof Uint8Array ? value.buffer : value,
  ))
    raw += String.fromCharCode(byte);
  return btoa(raw);
};
const normalizeUsername = (value: unknown) => String(value ?? "").trim();
const validAdminUsername = (value: string) =>
  value.length >= 1 &&
  value.length <= 64 &&
  !/[\u0000-\u001f\u007f]/u.test(value);
const encodeSessionUsername = (value: string) =>
  toBase64Url(new TextEncoder().encode(value));
const decodeSessionUsername = (value: string) => {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(normalized + "=".repeat((4 - (normalized.length % 4)) % 4));
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return new TextDecoder().decode(bytes);
};
async function credentialHmac(value: string, purpose: string, env: Env) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(env.WTLYF_ADMIN_TOKEN),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(`${purpose}:${value}`),
  );
}
async function passwordHash(
  password: string,
  saltBase64: string,
  iterations: number,
) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const bits = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      hash: "SHA-256",
      salt: fromBase64(saltBase64),
      iterations,
    },
    key,
    256,
  );
  return toBase64(bits);
}
async function protectedPasswordHash(
  password: string,
  saltBase64: string,
  iterations: number,
  env: Env,
) {
  const protectedMaterial = toBase64(
    await credentialHmac(password, "wtlyf-admin-password-v2", env),
  );
  return `v2$${await passwordHash(protectedMaterial, saltBase64, iterations)}`;
}
async function portablePasswordHash(
  password: string,
  saltBase64: string,
  iterations: number,
) {
  // Administrator accounts are shared by several Pages projects through one
  // D1 database. Project secrets are intentionally independent, so a password
  // verifier must not depend on WTLYF_ADMIN_TOKEN or accounts created on the
  // account-center project cannot sign in on the ordinary service projects.
  return `v3$${await passwordHash(password, saltBase64, iterations)}`;
}
const failedUsernameFingerprint = async (username: string, env: Env) =>
  `failed:${hex(await credentialHmac(username, "wtlyf-admin-username-v1", env)).slice(0, 32)}`;
async function authenticateAdmin(username: string, password: string, env: Env) {
  const row = await env.DB.prepare(
    "SELECT username,password_hash,password_salt,password_iterations FROM admin_users WHERE username=? AND active=1",
  )
    .bind(username)
    .first<any>();
  const salt =
    row?.password_salt ?? toBase64(crypto.getRandomValues(new Uint8Array(16)));
  const iterations = Number(row?.password_iterations ?? PASSWORD_ITERATIONS);
  const storedHash = String(row?.password_hash ?? ""),
    portableHash = await portablePasswordHash(password, salt, iterations);
  if (!row) return null;
  if (storedHash.startsWith("v3$"))
    return safeEqual(portableHash, storedHash)
      ? { username: String(row.username), passwordHash: storedHash }
      : null;
  const protectedHash = await protectedPasswordHash(
    password,
    salt,
    iterations,
    env,
  );
  if (storedHash.startsWith("v2$"))
    if (!safeEqual(protectedHash, storedHash)) return null;
  if (!storedHash.startsWith("v2$")) {
    const legacyHash = await passwordHash(password, salt, iterations);
    if (!safeEqual(legacyHash, storedHash)) return null;
  }
  const migratedSalt = toBase64(crypto.getRandomValues(new Uint8Array(16))),
    migratedHash = await portablePasswordHash(
      password,
      migratedSalt,
      PASSWORD_ITERATIONS,
    );
  await env.DB.prepare(
    "UPDATE admin_users SET password_hash=?,password_salt=?,password_iterations=? WHERE username=? AND password_hash=?",
  )
    .bind(
      migratedHash,
      migratedSalt,
      PASSWORD_ITERATIONS,
      String(row.username),
      storedHash,
    )
    .run();
  return { username: String(row.username), passwordHash: migratedHash };
}
function loginDevice(userAgent: string) {
  const os = /Android/i.test(userAgent)
    ? "Android"
    : /iPhone|iPad|iPod/i.test(userAgent)
      ? "iOS/iPadOS"
      : /Windows/i.test(userAgent)
        ? "Windows"
        : /Macintosh|Mac OS X/i.test(userAgent)
          ? "macOS"
          : /Linux/i.test(userAgent)
            ? "Linux"
            : "未知系统";
  const browser = /Edg\//i.test(userAgent)
    ? "Edge"
    : /OPR\//i.test(userAgent)
      ? "Opera"
      : /Firefox\//i.test(userAgent)
        ? "Firefox"
        : /Chrome\//i.test(userAgent)
          ? "Chrome"
          : /Safari\//i.test(userAgent)
            ? "Safari"
            : "未知浏览器";
  return `${os} · ${browser}`;
}
async function ensureAdminLoginAudit(env: Env) {
  await env.DB.batch([
    env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS admin_login_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL, ip_address TEXT NOT NULL, device TEXT NOT NULL, user_agent TEXT NOT NULL, country TEXT, colo TEXT, success INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
    ),
    env.DB.prepare(
      "CREATE INDEX IF NOT EXISTS idx_admin_login_audit_created ON admin_login_audit(created_at DESC)",
    ),
    env.DB.prepare(
      "DELETE FROM admin_login_audit WHERE created_at < datetime('now','-90 days')",
    ),
  ]);
}
async function loginBlocked(request: Request, env: Env, username: string) {
  await ensureAdminLoginAudit(env);
  const ip = requestIp(request),
    protectedUsername = await failedUsernameFingerprint(username, env);
  const row = await env.DB.prepare(
    "SELECT SUM(CASE WHEN ip_address=? AND created_at>=datetime('now','-15 minutes') THEN 1 ELSE 0 END) AS ip_count, SUM(CASE WHEN ip_address=? AND username IN (?,?) AND created_at>=datetime('now','-15 minutes') THEN 1 ELSE 0 END) AS pair_count, SUM(CASE WHEN ip_address=? THEN 1 ELSE 0 END) AS ip_hour_count FROM admin_login_audit WHERE success=0 AND created_at>=datetime('now','-1 hour')",
  )
    .bind(ip, ip, username, protectedUsername, ip)
    .first<any>();
  return (
    Number(row?.ip_count ?? 0) >= 12 ||
    Number(row?.pair_count ?? 0) >= 5 ||
    Number(row?.ip_hour_count ?? 0) >= 30
  );
}
async function recordAdminLogin(
  request: Request,
  env: Env,
  username: string,
  success: boolean,
) {
  try {
    await ensureAdminLoginAudit(env);
    const ip = requestIp(request),
      auditUsername = success
        ? username.slice(0, 64) || "未知账户"
        : await failedUsernameFingerprint(username, env);
    const userAgent = (request.headers.get("user-agent") ?? "未知设备")
      .replace(/[\r\n]/g, " ")
      .slice(0, 512);
    const cf = (request as any).cf ?? {};
    await env.DB.prepare(
      "INSERT INTO admin_login_audit(username,ip_address,device,user_agent,country,colo,success) VALUES(?,?,?,?,?,?,?)",
    )
      .bind(
        auditUsername,
        ip,
        loginDevice(userAgent),
        userAgent,
        String(cf.country ?? "").slice(0, 8),
        String(cf.colo ?? "").slice(0, 16),
        success ? 1 : 0,
      )
      .run();
  } catch (error) {
    console.error("admin login audit failed", error);
  }
}
async function ensureAdminAccessAudit(env: Env) {
  if (!adminAccessAuditReady) {
    adminAccessAuditReady = env.DB.batch([
      env.DB.prepare(
        "CREATE TABLE IF NOT EXISTS admin_access_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, method TEXT NOT NULL, category TEXT NOT NULL, ip_address TEXT NOT NULL, user_agent TEXT NOT NULL, country TEXT, colo TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
      ),
      env.DB.prepare(
        "CREATE INDEX IF NOT EXISTS idx_admin_access_audit_created ON admin_access_audit(created_at DESC)",
      ),
      env.DB.prepare(
        "CREATE INDEX IF NOT EXISTS idx_admin_access_audit_ip ON admin_access_audit(ip_address,created_at DESC)",
      ),
      env.DB.prepare(
        "DELETE FROM admin_access_audit WHERE created_at < datetime('now','-90 days')",
      ),
    ]).catch((error) => {
      adminAccessAuditReady = null;
      throw error;
    });
  }
  await adminAccessAuditReady;
}
async function recordAdminAccess(
  request: Request,
  env: Env,
  path: string,
  category: "page" | "scanner" | "geo-block",
) {
  try {
    await ensureAdminAccessAudit(env);
    const userAgent = (request.headers.get("user-agent") ?? "未知设备")
      .replace(/[\r\n]/g, " ")
      .slice(0, 512);
    const cf = (request as any).cf ?? {};
    await env.DB.prepare(
      "INSERT INTO admin_access_audit(path,method,category,ip_address,user_agent,country,colo) VALUES(?,?,?,?,?,?,?)",
    )
      .bind(
        path.slice(0, 256),
        request.method.slice(0, 16),
        category,
        requestIp(request),
        userAgent,
        String(cf.country ?? "").slice(0, 8),
        String(cf.colo ?? "").slice(0, 16),
      )
      .run();
  } catch (error) {
    console.error("admin access audit failed", error);
  }
}
async function ensureAdminUsageAudit(env: Env) {
  await env.DB.batch([
    env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS admin_usage_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL, action TEXT NOT NULL, quantity INTEGER NOT NULL DEFAULT 1, detail TEXT, ip_address TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
    ),
    env.DB.prepare(
      "CREATE INDEX IF NOT EXISTS idx_admin_usage_audit_created ON admin_usage_audit(created_at DESC)",
    ),
    env.DB.prepare(
      "CREATE INDEX IF NOT EXISTS idx_admin_usage_audit_user ON admin_usage_audit(username,action)",
    ),
  ]);
}
async function recordAdminUsage(
  request: Request,
  env: Env,
  username: string,
  action: "night_patch" | "night_license_issue" | "wtlyf_license_issue",
  quantity = 1,
  detail = "",
) {
  try {
    await ensureAdminUsageAudit(env);
    const ip = (
      request.headers.get("cf-connecting-ip") ??
      request.headers.get("x-forwarded-for")?.split(",")[0] ??
      "unknown"
    )
      .trim()
      .slice(0, 64);
    await env.DB.prepare(
      "INSERT INTO admin_usage_audit(username,action,quantity,detail,ip_address) VALUES(?,?,?,?,?)",
    )
      .bind(
        username.slice(0, 64),
        action,
        Math.max(1, Math.min(100, Math.trunc(quantity))),
        detail.slice(0, 160),
        ip,
      )
      .run();
  } catch (error) {
    console.error("admin usage audit failed", error);
  }
}
async function signNight(androidId: string, env: Env) {
  if (!/^[A-Za-z0-9._:-]{4,128}$/.test(androidId))
    throw new Error("Android ID 格式无效");
  if (!env.NIGHT_ISSUER_PK8_B64) throw new Error("Night 签发密钥尚未配置");
  const key = await crypto.subtle.importKey(
    "pkcs8",
    fromBase64(env.NIGHT_ISSUER_PK8_B64),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(`Night|1|${androidId.trim().toLowerCase()}`),
  );
  return `N1.${toBase64Url(signature)}`;
}

const githubHeaders = (env: Env, extra: HeadersInit = {}) => ({
  authorization: `Bearer ${env.GITHUB_TOKEN}`,
  accept: "application/vnd.github+json",
  "x-github-api-version": "2022-11-28",
  "user-agent": "wtlyf-night-web-patcher",
  ...extra,
});
async function githubJson(path: string, env: Env, init: RequestInit = {}) {
  const response = await fetch(
    `https://api.github.com/repos/${GITHUB_REPO}${path}`,
    { ...init, headers: githubHeaders(env, init.headers) },
  );
  if (!response.ok) throw new Error(`GitHub 服务错误 (${response.status})`);
  return response.status === 204 ? null : response.json<any>();
}
async function deletePatchRelease(id: string, env: Env) {
  await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/${id}`, {
    method: "DELETE",
    headers: githubHeaders(env),
  }).catch(() => null);
}
async function ensurePatchJobs(env: Env) {
  await env.DB.prepare(
    "CREATE TABLE IF NOT EXISTS patch_jobs (job_id TEXT PRIMARY KEY, owner TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
  ).run();
}
async function registerPatchJob(env: Env, jobId: string, owner: string) {
  await ensurePatchJobs(env);
  await env.DB.prepare(
    "INSERT OR REPLACE INTO patch_jobs(job_id,owner) VALUES(?,?)",
  )
    .bind(jobId, owner)
    .run();
}
async function ownsPatchJob(env: Env, jobId: string, owner: string) {
  await ensurePatchJobs(env);
  const row = await env.DB.prepare(
    "SELECT 1 AS ok FROM patch_jobs WHERE job_id=? AND owner=? AND created_at>=datetime('now','-24 hours')",
  )
    .bind(jobId, owner)
    .first<any>();
  return Number(row?.ok ?? 0) === 1;
}
async function forgetPatchJob(env: Env, jobId: string) {
  await ensurePatchJobs(env);
  await env.DB.prepare("DELETE FROM patch_jobs WHERE job_id=?")
    .bind(jobId)
    .run();
}

async function startWebPatch(
  request: Request,
  url: URL,
  env: Env,
  owner: string,
) {
  const kmi = String(url.searchParams.get("kmi") ?? "");
  if (!KMI_SET.has(kmi))
    return reply({ ok: false, message: "请选择有效的 KMI" }, 400);
  const length = Number(request.headers.get("content-length") ?? 0);
  if (!Number.isSafeInteger(length) || length < 1)
    return reply({ ok: false, message: "无法读取镜像大小" }, 411);
  if (!env.GITHUB_TOKEN)
    return reply({ ok: false, message: "云端修补凭据尚未配置" }, 503);
  const releases = await githubJson("/releases?per_page=100", env).catch(
    () => [],
  );
  if (
    !Array.isArray(releases) ||
    !releases.some((item: any) => item.name === "Night Web Patch Engine")
  )
    return reply(
      { ok: false, message: "云端修补引擎仍在首次构建，请几分钟后再试" },
      503,
    );
  const suffix = Array.from(crypto.getRandomValues(new Uint8Array(6)), (b) =>
    b.toString(16).padStart(2, "0"),
  ).join("");
  let releaseId = "";
  try {
    const release = await githubJson("/releases", env, {
      method: "POST",
      body: JSON.stringify({
        tag_name: `night-patch-${Date.now()}-${suffix}`,
        target_commitish: "night",
        name: `Night private patch ${suffix}`,
        body: "Private transient web patch job.",
        draft: true,
        prerelease: false,
      }),
    });
    releaseId = String(release.id);
    await registerPatchJob(env, releaseId, owner);
    const upload = await fetch(
      `https://uploads.github.com/repos/${GITHUB_REPO}/releases/${releaseId}/assets?name=input.img`,
      {
        method: "POST",
        headers: githubHeaders(env, {
          "content-type": "application/octet-stream",
          "content-length": String(length),
        }),
        body: request.body,
      },
    );
    if (!upload.ok) throw new Error(`镜像上传失败 (${upload.status})`);
    await githubJson("/actions/workflows/night-web-patch.yml/dispatches", env, {
      method: "POST",
      body: JSON.stringify({
        ref: "night",
        inputs: { release_id: releaseId, kmi },
      }),
    });
    return reply({
      ok: true,
      jobId: releaseId,
      message: "镜像已安全上传，云端正在修补",
    });
  } catch (error) {
    if (releaseId) {
      await deletePatchRelease(releaseId, env);
      await forgetPatchJob(env, releaseId).catch(() => null);
    }
    return reply(
      {
        ok: false,
        message: error instanceof Error ? error.message : "启动修补失败",
      },
      502,
    );
  }
}

async function createWebPatch(request: Request, env: Env, owner: string) {
  const body: any = await request.json().catch(() => ({}));
  const kmi = String(body.kmi ?? "");
  if (!KMI_SET.has(kmi))
    return reply({ ok: false, message: "请选择有效的 KMI" }, 400);
  const filename = String(body.filename ?? "image.img")
    .replace(/[^A-Za-z0-9._-]/g, "_")
    .slice(0, 120);
  const size = Number(body.size ?? 0);
  if (!Number.isSafeInteger(size) || size < 1)
    return reply({ ok: false, message: "镜像大小无效" }, 400);
  const releases = await githubJson("/releases?per_page=100", env).catch(
    () => [],
  );
  if (
    !Array.isArray(releases) ||
    !releases.some((item: any) => item.name === "Night Web Patch Engine")
  )
    return reply(
      { ok: false, message: "云端修补引擎仍在首次构建，请稍后再试" },
      503,
    );
  const suffix = Array.from(crypto.getRandomValues(new Uint8Array(6)), (b) =>
    b.toString(16).padStart(2, "0"),
  ).join("");
  try {
    const release = await githubJson("/releases", env, {
      method: "POST",
      body: JSON.stringify({
        tag_name: `night-patch-${Date.now()}-${suffix}`,
        target_commitish: "night",
        name: `Night private patch ${suffix}`,
        body: `Transient chunked patch job. File: ${filename}; bytes: ${size}; KMI: ${kmi}`,
        draft: true,
        prerelease: false,
      }),
    });
    const jobId = String(release.id);
    await registerPatchJob(env, jobId, owner);
    return reply({ ok: true, jobId, message: "上传任务已建立" });
  } catch (error) {
    return reply(
      {
        ok: false,
        message: error instanceof Error ? error.message : "建立任务失败",
      },
      502,
    );
  }
}

async function uploadPatchChunk(
  request: Request,
  url: URL,
  env: Env,
  owner: string,
) {
  const jobId = String(url.searchParams.get("jobId") ?? ""),
    index = Number(url.searchParams.get("index") ?? -1);
  if (!/^\d{1,20}$/.test(jobId) || !Number.isSafeInteger(index) || index < 0)
    return reply({ ok: false, message: "分片参数无效" }, 400);
  if (!(await ownsPatchJob(env, jobId, owner)))
    return reply({ ok: false, message: "无权访问该修补任务" }, 403);
  const length = Number(request.headers.get("content-length") ?? 0);
  if (!length || length > 16 * 1024 * 1024)
    return reply({ ok: false, message: "分片大小无效" }, 400);
  const release = await githubJson(`/releases/${jobId}`, env).catch(() => null);
  if (
    !release ||
    !String(release.name ?? "").startsWith("Night private patch ")
  )
    return reply({ ok: false, message: "上传任务不存在" }, 404);
  const name = `chunk-${String(index).padStart(16, "0")}.bin`;
  const old = (release.assets ?? []).find((item: any) => item.name === name);
  if (old)
    await githubJson(`/releases/assets/${old.id}`, env, { method: "DELETE" });
  const upload = await fetch(
    `https://uploads.github.com/repos/${GITHUB_REPO}/releases/${jobId}/assets?name=${name}`,
    {
      method: "POST",
      headers: githubHeaders(env, {
        "content-type": "application/octet-stream",
        "content-length": String(length),
      }),
      body: request.body,
    },
  );
  if (!upload.ok)
    return reply({ ok: false, message: `分片 ${index + 1} 上传失败` }, 502);
  return reply({ ok: true, index });
}

async function finalizeWebPatch(request: Request, env: Env, owner: string) {
  const body: any = await request.json().catch(() => ({}));
  const jobId = String(body.jobId ?? ""),
    kmi = String(body.kmi ?? ""),
    chunks = Number(body.chunks ?? 0);
  if (
    !/^\d{1,20}$/.test(jobId) ||
    !KMI_SET.has(kmi) ||
    !Number.isSafeInteger(chunks) ||
    chunks < 1
  )
    return reply({ ok: false, message: "任务参数无效" }, 400);
  if (!(await ownsPatchJob(env, jobId, owner)))
    return reply({ ok: false, message: "无权访问该修补任务" }, 403);
  const release = await githubJson(`/releases/${jobId}`, env).catch(() => null);
  const uploaded = (release?.assets ?? []).filter((item: any) =>
    /^chunk-(?:\d{6}|\d{16})\.bin$/.test(item.name),
  );
  const indices = uploaded
    .map((item: any) => Number(String(item.name).slice(6, -4)))
    .sort((a: number, b: number) => a - b);
  if (
    uploaded.length !== chunks ||
    indices.some((value: number, index: number) => value !== index)
  )
    return reply(
      {
        ok: false,
        message: `分片不完整或顺序异常：${uploaded.length}/${chunks}`,
      },
      409,
    );
  try {
    await githubJson("/actions/workflows/night-web-patch.yml/dispatches", env, {
      method: "POST",
      body: JSON.stringify({
        ref: "night",
        inputs: { release_id: jobId, kmi },
      }),
    });
    return reply({ ok: true, jobId, message: "镜像上传完成，云端正在修补" });
  } catch (error) {
    await deletePatchRelease(jobId, env);
    return reply(
      {
        ok: false,
        message: error instanceof Error ? error.message : "启动修补失败",
      },
      502,
    );
  }
}

async function patchStatus(jobId: string, env: Env, owner: string) {
  if (!/^\d{1,20}$/.test(jobId))
    return reply({ ok: false, message: "任务编号无效" }, 400);
  if (!(await ownsPatchJob(env, jobId, owner)))
    return reply({ ok: false, message: "无权访问该修补任务" }, 403);
  try {
    const release = await githubJson(`/releases/${jobId}`, env);
    const names = (release.assets ?? []).map((asset: any) => asset.name);
    if (names.includes("Night_patched.img"))
      return reply({
        ok: true,
        status: "done",
        message: "修补完成，可以下载新镜像",
      });
    if (names.includes("failure.txt"))
      return reply({
        ok: true,
        status: "failed",
        message: "修补失败，请检查镜像和 KMI 是否匹配",
      });
    return reply({
      ok: true,
      status: "processing",
      message: "云端正在修补，请稍候…",
    });
  } catch {
    return reply({ ok: false, message: "修补任务不存在或已经清理" }, 404);
  }
}

async function downloadPatch(jobId: string, env: Env, owner: string) {
  if (!/^\d{1,20}$/.test(jobId))
    return reply({ ok: false, message: "任务编号无效" }, 400);
  if (!(await ownsPatchJob(env, jobId, owner)))
    return reply({ ok: false, message: "无权访问该修补任务" }, 403);
  try {
    const release = await githubJson(`/releases/${jobId}`, env);
    const asset = (release.assets ?? []).find(
      (item: any) => item.name === "Night_patched.img",
    );
    if (!asset) return reply({ ok: false, message: "修补结果尚未生成" }, 404);
    const response = await fetch(asset.url, {
      headers: githubHeaders(env, { accept: "application/octet-stream" }),
    });
    if (!response.ok) return reply({ ok: false, message: "结果下载失败" }, 502);
    const headers = new Headers(response.headers);
    headers.set("content-type", "application/octet-stream");
    headers.set(
      "content-disposition",
      'attachment; filename="Night_patched.img"',
    );
    headers.set("cache-control", "private, no-store");
    return new Response(response.body, { status: 200, headers });
  } catch {
    return reply({ ok: false, message: "结果下载失败" }, 502);
  }
}

async function activateLicense(
  request: Request,
  env: Env,
  scope: "wtlyf" | "night",
) {
  let body: { code?: unknown; deviceHash?: unknown } = {};
  try {
    body = await request.json();
  } catch {
    return reply(
      { ok: false, error: "invalid_request", message: "请求格式无效" },
      400,
    );
  }
  const code = normalize(body.code),
    device = String(body.deviceHash ?? "").toLowerCase(),
    night = scope === "night";
  if (!validLicenseCode(code) || !/^[a-f0-9]{64}$/.test(device))
    return reply(
      {
        ok: false,
        error: "invalid_request",
        message: `${night ? "Night " : ""}卡密或设备指纹格式无效`,
      },
      400,
    );
  const table = night ? "night_licenses" : "licenses",
    hash = night ? await nightCodeHash(code) : await codeHash(code);
  const row = await env.DB.prepare(
    `SELECT status,duration_hours,expires_at,device_limit,CASE WHEN expires_at IS NOT NULL AND expires_at<=CURRENT_TIMESTAMP THEN 1 ELSE 0 END AS expired FROM ${table} WHERE code_hash=?`,
  )
    .bind(hash)
    .first<any>();
  if (!row)
    return reply(
      {
        ok: false,
        error: "invalid_code",
        message: `${night ? "Night " : ""}卡密不存在`,
      },
      404,
    );
  if (row.expired) {
    await env.DB.prepare(
      `UPDATE ${table} SET status='expired' WHERE code_hash=?`,
    )
      .bind(hash)
      .run();
    return reply(
      {
        ok: false,
        error: "expired_code",
        message: `${night ? "Night " : ""}卡密已到期`,
      },
      403,
    );
  }
  if (row.status !== "active")
    return reply(
      {
        ok: false,
        error: "invalid_code",
        message: `${night ? "Night " : ""}卡密已停用`,
      },
      403,
    );
  const existing = await env.DB.prepare(
    "SELECT 1 FROM license_devices WHERE scope=? AND code_hash=? AND device_hash=?",
  )
    .bind(scope, hash, device)
    .first();
  if (existing) {
    await env.DB.prepare(
      "UPDATE license_devices SET last_seen_at=CURRENT_TIMESTAMP WHERE scope=? AND code_hash=? AND device_hash=?",
    )
      .bind(scope, hash, device)
      .run();
    return reply({
      ok: true,
      message: `${night ? "Night " : ""}验证成功`,
      expiresAt: row.expires_at,
    });
  }
  await env.DB.prepare(
    "DELETE FROM license_devices WHERE scope=? AND device_hash=? AND code_hash<>?",
  )
    .bind(scope, device, hash)
    .run();
  const inserted = await env.DB.prepare(
    `INSERT OR IGNORE INTO license_devices(scope,code_hash,device_hash) SELECT ?,?,? FROM ${table} l WHERE l.code_hash=? AND (l.device_limit IS NULL OR (SELECT COUNT(*) FROM license_devices d WHERE d.scope=? AND d.code_hash=?)<l.device_limit)`,
  )
    .bind(scope, hash, device, hash, scope, hash)
    .run();
  if (inserted.meta.changes === 1) {
    await env.DB.prepare(
      `UPDATE ${table} SET bound_at=COALESCE(bound_at,CURRENT_TIMESTAMP),last_seen_at=CURRENT_TIMESTAMP,expires_at=CASE WHEN expires_at IS NOT NULL THEN expires_at WHEN duration_hours IS NULL THEN NULL ELSE datetime('now','+'||duration_hours||' hours') END WHERE code_hash=?`,
    )
      .bind(hash)
      .run();
    const state = await env.DB.prepare(
      `SELECT expires_at,device_limit,(SELECT COUNT(*) FROM license_devices WHERE scope=? AND code_hash=?) AS device_count FROM ${table} WHERE code_hash=?`,
    )
      .bind(scope, hash, hash)
      .first<any>();
    return reply({
      ok: true,
      message: `${night ? "Night " : ""}激活成功`,
      expiresAt: state?.expires_at ?? null,
      deviceCount: Number(state?.device_count ?? 1),
      deviceLimit: state?.device_limit ?? null,
    });
  }
  const state = await env.DB.prepare(
    `SELECT device_limit,(SELECT COUNT(*) FROM license_devices WHERE scope=? AND code_hash=?) AS device_count FROM ${table} WHERE code_hash=?`,
  )
    .bind(scope, hash, hash)
    .first<any>();
  return reply(
    {
      ok: false,
      error: "device_limit_reached",
      message: `${night ? "Night " : ""}卡密设备数量已达上限（${Number(state?.device_count ?? 0)}/${Number(state?.device_limit ?? 0)}）`,
    },
    409,
  );
}
const activate = (request: Request, env: Env) =>
  activateLicense(request, env, "wtlyf");

async function ensureNightEphemeralSessions(env: Env) {
  nightEphemeralSessionReady ??= env.DB.prepare(
    "CREATE TABLE IF NOT EXISTS night_ephemeral_sessions (session_hash TEXT PRIMARY KEY,code_hash TEXT NOT NULL,device_hash TEXT NOT NULL,action TEXT NOT NULL,client_nonce TEXT NOT NULL,proof_hash TEXT NOT NULL,key_issued INTEGER NOT NULL DEFAULT 0,expires_at INTEGER NOT NULL)",
  ).run();
  await nightEphemeralSessionReady;
}
const nightSessionHash = async (sessionId: string) =>
  hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`night-session-v1:${sessionId}`)));
async function nightSessionKey(sessionId: string, codeHashValue: string, deviceHash: string, action: string, clientNonce: string, env: Env) {
  return new Uint8Array(await credentialHmac(`${sessionId}:${codeHashValue}:${deviceHash}:${action}:${clientNonce}`, "night-memory-key-v1", env));
}
async function startNightEphemeralSession(request: Request, env: Env) {
  const body: any = await request.json().catch(() => ({})), code = normalize(body.code), deviceHash = String(body.deviceHash ?? "").toLowerCase(), action = String(body.action ?? ""), clientNonce = String(body.clientNonce ?? "").toLowerCase();
  if (!validLicenseCode(code) || !/^[a-f0-9]{64}$/.test(deviceHash) || !/^[a-f0-9]{64}$/.test(clientNonce) || !new Set(["activate", "check"]).has(action))
    return reply({ ok: false, error: "invalid_request", message: "会话参数无效" }, 400);
  const licenseResponse = await activateLicense(new Request(request.url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ code, deviceHash }) }), env, "night");
  if (!licenseResponse.ok) return licenseResponse;
  await ensureNightEphemeralSessions(env);
  const sessionId = toBase64Url(crypto.getRandomValues(new Uint8Array(32))), codeHashValue = await nightCodeHash(code), keyBytes = await nightSessionKey(sessionId, codeHashValue, deviceHash, action, clientNonce, env), packet = crypto.getRandomValues(new Uint8Array(32)), iv = crypto.getRandomValues(new Uint8Array(12)), aesKey = await crypto.subtle.importKey("raw", keyBytes, "AES-GCM", false, ["encrypt"]), sealedPacket = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, aesKey, packet)), proofInput = new Uint8Array(packet.length + action.length + sessionId.length), now = Math.floor(Date.now() / 1000), expiresAt = now + 45;
  proofInput.set(packet, 0); proofInput.set(new TextEncoder().encode(action), packet.length); proofInput.set(new TextEncoder().encode(sessionId), packet.length + action.length);
  const proofHash = hex(await crypto.subtle.digest("SHA-256", proofInput));
  await env.DB.batch([env.DB.prepare("DELETE FROM night_ephemeral_sessions WHERE expires_at<=?").bind(now), env.DB.prepare("INSERT INTO night_ephemeral_sessions(session_hash,code_hash,device_hash,action,client_nonce,proof_hash,expires_at) VALUES(?,?,?,?,?,?,?)").bind(await nightSessionHash(sessionId), codeHashValue, deviceHash, action, clientNonce, proofHash, expiresAt)]);
  keyBytes.fill(0); packet.fill(0); proofInput.fill(0);
  return reply({ ok: true, sessionId, sealedPacket: toBase64Url(sealedPacket), iv: toBase64Url(iv), expiresAt });
}
async function revealNightEphemeralKey(request: Request, env: Env) {
  const body: any = await request.json().catch(() => ({})), code = normalize(body.code), deviceHash = String(body.deviceHash ?? "").toLowerCase(), action = String(body.action ?? ""), clientNonce = String(body.clientNonce ?? "").toLowerCase(), sessionId = String(body.sessionId ?? "");
  if (!validLicenseCode(code) || !/^[a-f0-9]{64}$/.test(deviceHash) || !/^[a-f0-9]{64}$/.test(clientNonce) || !/^[A-Za-z0-9_-]{43}$/.test(sessionId)) return reply({ ok: false, error: "invalid_request", message: "会话参数无效" }, 400);
  await ensureNightEphemeralSessions(env);
  const codeHashValue = await nightCodeHash(code), row = await env.DB.prepare("UPDATE night_ephemeral_sessions SET key_issued=1 WHERE session_hash=? AND code_hash=? AND device_hash=? AND action=? AND client_nonce=? AND key_issued=0 AND expires_at>? RETURNING session_hash").bind(await nightSessionHash(sessionId), codeHashValue, deviceHash, action, clientNonce, Math.floor(Date.now() / 1000)).first();
  if (!row) return reply({ ok: false, error: "session_mismatch", message: "会话不一致、已使用或已过期" }, 403);
  const keyBytes = await nightSessionKey(sessionId, codeHashValue, deviceHash, action, clientNonce, env);
  return new Response(keyBytes, { headers: { ...API_HEADERS, "content-type": "application/octet-stream", "cache-control": "no-store", "content-disposition": "attachment; filename=night-session.key" } });
}
async function activateNight(request: Request, env: Env, expectedAction: "activate" | "check") {
  const body: any = await request.json().catch(() => ({})), code = normalize(body.code), deviceHash = String(body.deviceHash ?? "").toLowerCase(), action = String(body.action ?? ""), clientNonce = String(body.clientNonce ?? "").toLowerCase(), sessionId = String(body.sessionId ?? ""), sessionProof = String(body.sessionProof ?? "").toLowerCase();
  if (action !== expectedAction || !validLicenseCode(code) || !/^[a-f0-9]{64}$/.test(deviceHash) || !/^[a-f0-9]{64}$/.test(clientNonce) || !/^[a-f0-9]{64}$/.test(sessionProof) || !/^[A-Za-z0-9_-]{43}$/.test(sessionId)) return reply({ ok: false, error: "session_required", message: "需要有效的一次性内存会话" }, 403);
  await ensureNightEphemeralSessions(env);
  const consumed = await env.DB.prepare("DELETE FROM night_ephemeral_sessions WHERE session_hash=? AND code_hash=? AND device_hash=? AND action=? AND client_nonce=? AND proof_hash=? AND key_issued=1 AND expires_at>? RETURNING session_hash").bind(await nightSessionHash(sessionId), await nightCodeHash(code), deviceHash, action, clientNonce, sessionProof, Math.floor(Date.now() / 1000)).first();
  if (!consumed) return reply({ ok: false, error: "session_mismatch", message: "会话不一致、已使用或已过期" }, 403);
  const licenseResponse = await activateLicense(new Request(request.url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ code, deviceHash }) }), env, "night");
  if (!licenseResponse.ok) return licenseResponse;
  const licenseBody: any = await licenseResponse.json();
  return reply({ ...licenseBody, rootLeaseSeconds: 120 });
}

type PushItem = {
  id: string;
  title: string;
  message: string;
  fileName: string | null;
  contentType: string | null;
  fileSize: number;
  sha256: string | null;
  createdAt: string;
  downloadUrl?: string | null;
  forceUpdate?: boolean;
  minVersionCode?: number;
};
async function getPushList(env: Env) {
  const value = await env.PUSH_FILES.get("push:list", "json").catch(() => null);
  return Array.isArray(value) ? (value as PushItem[]) : [];
}
async function publishPush(request: Request, env: Env) {
  const form = await request.formData();
  const title = String(form.get("title") ?? "通知")
      .trim()
      .slice(0, 80),
    message = String(form.get("message") ?? "")
      .trim()
      .slice(0, 1000),
    candidate = form.get("file");
  const file =
    candidate instanceof File && candidate.size > 0 ? candidate : null;
  if (!message && !file)
    return reply({ ok: false, message: "请填写推送文字或选择文件" }, 400);
  if (file && file.size > 20 * 1024 * 1024)
    return reply({ ok: false, message: "单个推送文件不能超过 20 MB" }, 413);
  const id = `${Date.now()}-${Array.from(crypto.getRandomValues(new Uint8Array(6)), (b) => b.toString(16).padStart(2, "0")).join("")}`;
  let fileName: string | null = null,
    contentType: string | null = null,
    sha256: string | null = null,
    fileSize = 0;
  if (file) {
    fileName =
      file.name.replace(/[\u0000-\u001f\u007f]/g, "").slice(0, 160) ||
      "wtlyf-push.bin";
    contentType = file.type || "application/octet-stream";
    fileSize = file.size;
    const bytes = await file.arrayBuffer();
    sha256 = hex(await crypto.subtle.digest("SHA-256", bytes));
    await env.PUSH_FILES.put(`push:file:${id}`, bytes, {
      metadata: { fileName, contentType, sha256 },
    });
  }
  const item: PushItem = {
    id,
    title: title || "通知",
    message,
    fileName,
    contentType,
    fileSize,
    sha256,
    createdAt: new Date().toISOString(),
  };
  const list = await getPushList(env);
  list.unshift(item);
  const kept = list.slice(0, 30);
  await Promise.all([
    env.PUSH_FILES.put("push:list", JSON.stringify(kept)),
    env.PUSH_FILES.put("push:latest", JSON.stringify(item)),
    env.PUSH_FILES.put(`push:meta:${id}`, JSON.stringify(item)),
  ]);
  return reply({ ok: true, message: "推送已发布", push: item });
}
async function publishDownloadPush(request: Request, env: Env) {
  const body: any = await request.json().catch(() => ({}));
  const title = String(body.title ?? "通知")
      .trim()
      .slice(0, 80),
    message = String(body.message ?? "")
      .trim()
      .slice(0, 1000);
  const fileName = String(body.fileName ?? "")
    .replace(/[\u0000-\u001f\u007f]/g, "")
    .slice(0, 160);
  const downloadUrl = String(body.downloadUrl ?? "").trim(),
    contentType = String(body.contentType ?? "application/octet-stream").slice(
      0,
      120,
    );
  const fileSize = Number(body.fileSize ?? 0),
    sha256 = String(body.sha256 ?? "").toUpperCase();
  const forceUpdate = body.forceUpdate === true,
    minVersionCode = Number(body.minVersionCode ?? 0);
  if (!message || !fileName)
    return reply({ ok: false, message: "请填写推送文字与文件名" }, 400);
  if (!/^\/api\/v1\/[A-Za-z0-9._\/-]+$/.test(downloadUrl))
    return reply({ ok: false, message: "更新下载地址无效" }, 400);
  if (
    !Number.isSafeInteger(fileSize) ||
    fileSize < 1 ||
    fileSize > 200 * 1024 * 1024
  )
    return reply({ ok: false, message: "更新文件大小无效" }, 400);
  if (!/^[A-F0-9]{64}$/.test(sha256))
    return reply({ ok: false, message: "更新文件 SHA-256 无效" }, 400);
  if (
    forceUpdate &&
    (!Number.isSafeInteger(minVersionCode) ||
      minVersionCode < 1 ||
      minVersionCode > 2147483647)
  )
    return reply({ ok: false, message: "强制更新版本号无效" }, 400);
  const id = `${Date.now()}-${Array.from(crypto.getRandomValues(new Uint8Array(6)), (b) => b.toString(16).padStart(2, "0")).join("")}`;
  const item: PushItem = {
    id,
    title: title || "通知",
    message,
    fileName,
    contentType,
    fileSize,
    sha256,
    createdAt: new Date().toISOString(),
    downloadUrl,
    forceUpdate,
    minVersionCode: forceUpdate ? minVersionCode : 0,
  };
  const list = await getPushList(env);
  list.unshift(item);
  const kept = list.slice(0, 30);
  await Promise.all([
    env.PUSH_FILES.put("push:list", JSON.stringify(kept)),
    env.PUSH_FILES.put("push:latest", JSON.stringify(item)),
    env.PUSH_FILES.put(`push:meta:${id}`, JSON.stringify(item)),
  ]);
  return reply({ ok: true, message: "更新推送已发布", push: item });
}
async function deletePush(request: Request, env: Env) {
  const body: any = await request.json().catch(() => ({})),
    id = String(body.id ?? "");
  if (!/^[0-9]{10,20}-[a-f0-9]{12}$/.test(id))
    return reply({ ok: false, message: "推送编号无效" }, 400);
  const list = await getPushList(env),
    next = list.filter((item) => item.id !== id);
  if (next.length === list.length)
    return reply({ ok: false, message: "未找到该推送" }, 404);
  await Promise.all([
    env.PUSH_FILES.delete(`push:file:${id}`),
    env.PUSH_FILES.delete(`push:meta:${id}`),
    env.PUSH_FILES.put("push:list", JSON.stringify(next)),
    next[0]
      ? env.PUSH_FILES.put("push:latest", JSON.stringify(next[0]))
      : env.PUSH_FILES.delete("push:latest"),
  ]);
  return reply({ ok: true, message: "推送已删除" });
}
async function downloadPush(url: URL, env: Env) {
  const id = String(url.searchParams.get("id") ?? "");
  if (!/^[0-9]{10,20}-[a-f0-9]{12}$/.test(id))
    return reply({ ok: false, message: "文件编号无效" }, 400);
  const meta = (await env.PUSH_FILES.get(
    `push:meta:${id}`,
    "json",
  )) as PushItem | null;
  if (!meta?.fileName)
    return reply({ ok: false, message: "推送文件不存在" }, 404);
  const object = await env.PUSH_FILES.get(`push:file:${id}`, "stream");
  if (!object) return reply({ ok: false, message: "推送文件不存在" }, 404);
  const fallback =
    meta.fileName.replace(/[^A-Za-z0-9._-]/g, "_") || "wtlyf-push.bin";
  return new Response(object, {
    headers: {
      "content-type": meta.contentType || "application/octet-stream",
      "content-length": String(meta.fileSize),
      "content-disposition": `attachment; filename="${fallback}"; filename*=UTF-8''${encodeURIComponent(meta.fileName)}`,
      "cache-control": "private, no-store",
      "x-content-sha256": meta.sha256 || "",
      "x-content-type-options": "nosniff",
    },
  });
}

const shell = (title: string, page: string, content: string) => {
  // Product downloads belong to their own feature pages, never the platform home page.
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#0a0b14"><title>${title}</title><link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' rx='18' fill='%2311172a'/%3E%3Cpath d='M14 19h8l5 25 5-17 5 17 5-25h8L42 48h-8l-2-8-2 8h-8z' fill='%23dbe9ff'/%3E%3C/svg%3E"><link rel="stylesheet" href="/assets/site.css"><link rel="stylesheet" href="/assets/overrides.css"></head><body data-page="${page}"><div class="backdrop"></div>${content}<div id="toast" role="status" aria-live="polite"></div><script src="/assets/site.js?v=20261020b" integrity="sha384-2/tiGlPzFizxfkouFBRcjM1fNAySr6tcltZ1bZ+MHoSWMIicBPNi2xvMlTWwhOtn" crossorigin="anonymous" defer></script></body></html>`;
};
const HOME = shell(
  "无痛定制",
  "home",
  `<main class="shell enter"><section class="hero glass"><div class="brand">W</div><div><h1>无痛定制</h1><p>安全签发与 Night 工具中心</p></div><span class="secure">PROTECTED</span></section><section id="loginPanel" class="glass login"><h2>管理员登录</h2><p>登录一次后，本设备 12 小时内无需重复输入。</p><label for="username">管理员账户</label><input id="username" autocomplete="username" placeholder="输入管理员账户"><label for="password">密码</label><input id="password" type="password" autocomplete="current-password" placeholder="输入密码"><button id="loginBtn" class="primary">登录</button><div id="status" class="status">正在检查登录状态…</div></section><section id="sessionPanel" class="glass login session-ok" hidden><h2>已登录</h2><p><span id="currentAdmin"></span>，可直接进入下方功能。</p><button id="logoutBtn">退出登录</button></section><nav class="portal"><button class="portal-btn" data-go="/wtlyf"><span>WTLYF</span><strong>工具箱签发</strong><small>卡密、有效期与工具箱下载</small></button><button class="portal-btn" data-go="/night"><span>NIGHT</span><strong>独立卡密与修补</strong><small>Night 卡密、镜像修补与面具下载</small></button><button class="portal-btn" data-go="/unpack"><span>ROM</span><strong>解包网页</strong><small>本地解析 OTA ZIP / payload.bin 并提取分区</small></button></nav></main>`,
);
const WTLYF = shell(
  "WTLYF 工具箱签发",
  "wtlyf",
  `<main class="shell"><header class="top glass"><a href="/" class="back">←</a><div><h1>WTLYF 工具箱签发</h1><p>每张卡密可设置设备数量上限，也可允许无限设备使用</p></div><a class="download-mini" href="/api/v1/toolbox/latest-apk">下载工具箱</a></header><div class="grid"><section class="glass panel"><h2>生成卡密</h2><label for="customCode">自定义卡密（选填）</label><input id="customCode" maxlength="128" autocomplete="off" placeholder="可输入任意文字；留空则随机生成"><small>自定义时只生成一张，已经签发过的内容不能重复生成。</small><label for="count">随机生成数量</label><input id="count" type="number" min="1" max="100" value="1"><div class="duration"><div><label for="deviceLimit">设备使用上限</label><input id="deviceLimit" type="number" min="1" max="100000" value="1"></div><div><label for="limitMode">限制方式</label><select id="limitMode"><option value="limited">限制设备数量</option><option value="unlimited">无限设备</option></select></div></div><div class="duration"><div><label for="duration">有效时长</label><input id="duration" type="number" min="1" max="3650" value="30"></div><div><label for="unit">单位</label><select id="unit"><option value="24">天</option><option value="1">小时</option><option value="720">月（30天）</option></select></div></div><button id="generateBtn" class="primary">生成卡密</button></section><section class="glass panel"><h2>查询与管理</h2><label for="code">卡密</label><input id="code" placeholder="输入随机卡密或自定义文字"><div class="actions"><button id="queryBtn">查询</button><button id="unbindBtn">解绑</button><button id="deleteBtn" class="danger">删除</button></div></section></div><section class="glass result"><div id="status" class="status">服务器已连接</div><textarea id="output" readonly placeholder="生成或查询结果"></textarea></section></main>`,
);
const NIGHT = shell(
  "Night 卡密与修补",
  "night",
  `<main class="shell"><header class="top glass"><a href="/" class="back" aria-label="返回">←</a><div><h1>Night 独立卡密与修补</h1><p>Night 卡密与 WTLYF 完全分离；一台设备只能绑定一张卡密</p></div></header><div class="grid"><section class="glass panel"><span class="eyebrow">NIGHT LICENSE</span><h2>生成 Night 卡密</h2><label for="nightCustomCode">自定义卡密（选填）</label><input id="nightCustomCode" maxlength="128" autocomplete="off" placeholder="可输入任意文字；留空则随机生成"><small>自定义时只生成一张，已经签发过的内容不能重复生成。</small><label for="nightCount">随机生成数量</label><input id="nightCount" type="number" min="1" max="100" value="1"><div class="duration"><div><label for="nightDeviceLimit">设备使用上限</label><input id="nightDeviceLimit" type="number" min="1" max="100000" value="1"></div><div><label for="nightLimitMode">限制方式</label><select id="nightLimitMode"><option value="limited">限制设备数量</option><option value="unlimited">无限设备</option></select></div></div><p>Night 卡密默认永久有效。</p><button id="nightGenerateBtn" class="primary">生成 Night 卡密</button><label for="nightCodes">生成结果</label><textarea id="nightCodes" class="code-result" readonly placeholder="随机卡密或自定义文字"></textarea></section><section class="glass panel"><span class="eyebrow">NIGHT MANAGER</span><h2>查询、解绑与删除</h2><label for="nightManageCode">Night 卡密</label><input id="nightManageCode" autocomplete="off" placeholder="输入随机卡密或自定义文字"><div class="actions"><button id="nightQueryBtn">查询</button><button id="nightUnbindBtn">解绑</button><button id="nightDeleteBtn" class="danger">删除</button></div><div id="nightLicenseStatus" class="status">独立 Night 卡密服务器已连接</div><textarea id="nightLicenseOutput" class="code-result" readonly placeholder="绑定状态与有效期"></textarea></section><section class="glass panel"><span class="eyebrow">CLOUD PATCH</span><h2>boot / init_boot 网页修补</h2><p>上传原始镜像，选择完全匹配的 KMI。只生成新镜像，不会连接设备或自动刷写。</p><label for="patchKmi">专属 KMI</label><select id="patchKmi"><option>android12-5.10</option><option>android13-5.10</option><option>android13-5.15</option><option>android14-5.15</option><option>android14-6.1</option><option>android15-6.6</option><option>android16-6.12</option><option>android17-6.18</option></select><label for="bootImage">原始 boot / init_boot 镜像</label><input id="bootImage" type="file" accept=".img,application/octet-stream"><button id="patchBtn" class="primary">上传并开始修补</button><div id="patchStatus" class="status">修补引擎已就绪</div><div class="progress"><i id="patchProgress"></i></div><button id="downloadPatchBtn" hidden>下载 Night_patched.img</button><div class="notice">请务必选择匹配的 KMI；刷错镜像可能导致设备无法启动。</div></section></div><section class="glass tool manager-card"><span>NIGHT MANAGER</span><h2>Night 面具</h2><p>下载已构建并签名的 Night 管理器 APK。</p><a href="/api/v1/night/latest-apk">下载 Night APK</a></section><section class="glass tool manager-card"><span>WUTONG TOOL</span><h2>Wutong 越狱工具</h2><p>下载你提供的 Wutong 越狱工具 APK。</p><a href="/downloads/Wutong-jailbreak.apk">下载越狱工具 APK</a></section></main>`,
);
const UNPACK = shell(
  "Payload Dumper",
  "unpack",
  `<main class="shell unpack-shell"><header class="top glass"><a href="/" class="back" aria-label="返回">←</a><div><h1>Payload Dumper</h1><p>网页解包功能已移除，请下载安卓 APK 在手机本地使用。</p></div><a class="download-mini" href="https://roms.lian86.top" target="_blank" rel="noopener noreferrer">ROM 获取</a></header><section class="glass panel"><span class="eyebrow">ANDROID APP</span><h2>下载 Payload Dumper</h2><p>点击下方按钮下载 Payload Dumper APK，安装后可在安卓设备本地选择 OTA ZIP 或 payload.bin 进行解包。</p><a class="primary download-app" href="/api/v1/download/payload-dumper-apk">下载 Payload Dumper APK</a><div class="notice">文件只在你的安卓设备本地处理，不通过网页上传 ROM。</div></section></main>`,
);
const ACCOUNTS = shell(
  "管理员账户",
  "accounts",
  `<main class="shell"><header class="top glass"><a href="/" class="back" aria-label="返回">←</a><div><h1>管理员账户</h1><p>生成和管理可登录本平台的管理员账户</p></div></header><div class="grid"><section class="glass panel"><h2>生成管理员账户</h2><label for="newUsername">账户名</label><input id="newUsername" autocomplete="off" placeholder="3–32 位字母、数字、点、横线或下划线"><label for="newPassword">密码</label><input id="newPassword" type="password" autocomplete="new-password" placeholder="密码内容自由设置（不能为空）"><button id="createAdminBtn" class="primary">生成账户</button><div id="accountStatus" class="status"></div></section><section class="glass panel"><h2>登录地址</h2><p class="account-url">https://wtlyf-night-license.pages.dev/</p><button id="copyLoginUrl">复制登录网址</button><p>新账户创建后可立即登录；密码只在创建时输入，不会明文保存。</p></section></div><section class="glass result"><h2>现有管理员</h2><div id="accountsList" class="accounts-list">正在加载…</div></section></main>`,
);

function accountSite(nonce: string) {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#070b18"><title>WTLYF 管理员账户中心</title><style nonce="${nonce}">
*{box-sizing:border-box}body{margin:0;min-height:100vh;color:#eef5ff;font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif;background:radial-gradient(circle at 15% 10%,#23396b 0,transparent 34%),radial-gradient(circle at 85% 15%,#562b78 0,transparent 28%),#070b18}main{width:min(1080px,calc(100% - 28px));margin:auto;padding:38px 0 64px}.hero,.card{border:1px solid #ffffff2c;background:#101a31c9;box-shadow:0 22px 70px #0008;backdrop-filter:blur(24px);border-radius:28px}.hero{padding:28px 30px;margin-bottom:20px}.hero h1{margin:0;font-size:clamp(30px,6vw,54px)}.hero p,.muted{color:#aebbd4}.grid{display:grid;grid-template-columns:1fr 1.25fr;gap:20px}.card{padding:26px}h2{margin:0 0 18px}label{display:block;margin:13px 0 7px;color:#c5d2e8}input,button{width:100%;border-radius:15px;border:1px solid #ffffff2e;padding:14px 16px;font:inherit;color:#fff;background:#071022b8}button{cursor:pointer;margin-top:14px;font-weight:700;background:linear-gradient(120deg,#2f8cff,#8056e8);transition:.2s transform,.2s filter}button:hover{transform:translateY(-2px);filter:brightness(1.12)}button.danger{width:auto;margin:0;background:#8e2948}.status{min-height:24px;margin-top:14px;color:#91d9ff}.row{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:15px 0;border-bottom:1px solid #ffffff1c}.row:last-child{border:0}.row strong,.row small{display:block}.row small{color:#9cabc4;margin-top:3px}.logout{background:#25304a;width:auto}.hidden{display:none!important}.audit-card{margin-top:20px}.audit-head{display:flex;align-items:center;justify-content:space-between;gap:16px}.audit-head button{width:auto;margin:0}.audit-list{overflow:auto}.audit-row{display:grid;grid-template-columns:150px 1fr 160px 150px 82px;gap:12px;align-items:start;padding:14px 0;border-bottom:1px solid #ffffff1c;min-width:800px}.audit-row:last-child{border:0}.audit-row strong,.audit-row small{display:block}.audit-row small{color:#9cabc4;margin-top:2px}.audit-ok{color:#8fe5b2}.audit-fail{color:#ff9db7}@media(max-width:720px){main{padding-top:18px}.grid{grid-template-columns:1fr}.hero,.card{border-radius:22px;padding:21px}.row{align-items:flex-start;flex-direction:column}.row button{width:100%}.audit-head{align-items:flex-start}.audit-head button{width:auto}}
.row>div:last-child{display:flex;gap:8px;align-items:center}.row>div:last-child button{width:auto;margin:0;white-space:nowrap}
</style></head><body><main><header class="hero"><h1>WTLYF 管理员账户中心</h1><p>独立管理员登录、账户生成与安全审计站点。</p></header><section id="loginCard" class="card"><h2>管理员登录</h2><label>账户</label><input id="username" autocomplete="username"><label>密码</label><input id="password" type="password" autocomplete="current-password"><button id="login">登录账户中心</button><div id="loginStatus" class="status"></div></section><div id="manager" class="hidden"><div class="grid"><section id="createPanel" class="card"><h2>生成管理员账户</h2><p class="muted">仅主账号可操作；每次必须重新验证主账号当前密码。</p><label>新账户名</label><input id="newUsername" maxlength="64" placeholder="任意名称（1–64 字符）"><label>新密码</label><input id="newPassword" type="password" autocomplete="new-password" maxlength="256" placeholder="设置新账户密码"><label>主账号当前密码</label><input id="ownerPassword" type="password" autocomplete="current-password" maxlength="256" placeholder="用于本次高风险操作验证"><button id="create">验证并生成账户</button><button id="nightLicenses">打开 Night 独立卡密中心</button><button id="pushCenter">打开文件推送中心</button><button id="logout" class="logout">退出登录</button><div id="status" class="status"></div></section><section class="card"><h2>现有管理员</h2><p class="muted">选择一个管理员查看他的使用情况、登录设备和 IP。</p><div id="list">正在加载…</div></section></div><section id="detailsPanel" class="card audit-card hidden"><div class="audit-head"><div><h2 id="detailsTitle">管理员详情</h2><div class="muted">仅显示当前选择管理员的数据。</div></div><button id="refreshDetails">刷新详情</button></div><h2>使用情况</h2><div id="usageSummary" class="audit-list">正在加载…</div><h2>最近操作</h2><div id="usageRecent" class="audit-list">正在加载…</div><h2>登录设备与 IP</h2><div class="muted">最近 200 条登录记录，自动保留 90 天。</div><div id="auditList" class="audit-list">正在加载…</div></section></div></main><script nonce="${nonce}">
const $=s=>document.querySelector(s);let selectedUser='';async function req(path,options={}){const r=await fetch(path,{credentials:'same-origin',headers:{'content-type':'application/json'},...options});let d={};try{d=await r.json()}catch{}if(!r.ok)throw new Error(d.message||'请求失败');return d}function text(id,v){$(id).textContent=v}async function load(){const d=await req('/api/admin/accounts');const list=$('#list');list.textContent='';d.accounts.forEach(a=>{const row=document.createElement('div');row.className='row';const box=document.createElement('div'),name=document.createElement('strong'),meta=document.createElement('small'),actions=document.createElement('div'),view=document.createElement('button');name.textContent=a.username+(a.username===d.current?'（当前账户）':'');meta.textContent='创建：'+a.created_at+(a.last_login_at?' · 最近登录：'+a.last_login_at:'');box.append(name,meta);view.textContent='查看详情';view.onclick=()=>openDetails(a.username);actions.append(view);if(a.username!==d.current){const del=document.createElement('button');del.className='danger';del.textContent='删除账户';del.onclick=async()=>{if(!confirm('确认删除管理员 '+a.username+'？'))return;try{await req('/api/admin/accounts/delete',{method:'POST',body:JSON.stringify({username:a.username})});text('#status','账户已删除');if(selectedUser===a.username){selectedUser='';$('#detailsPanel').classList.add('hidden')}load()}catch(e){text('#status',e.message)}};actions.append(del)}row.append(box,actions);list.append(row)})}async function loadAudit(username){const list=$('#auditList');list.textContent='正在加载…';try{const d=await req('/api/admin/login-audit?username='+encodeURIComponent(username));list.textContent='';if(!d.records.length){list.textContent='暂无登录记录';return}d.records.forEach(a=>{const row=document.createElement('div');row.className='audit-row';const time=document.createElement('div'),account=document.createElement('div'),device=document.createElement('div'),network=document.createElement('div'),state=document.createElement('strong');time.textContent=a.created_at;const user=document.createElement('strong'),ua=document.createElement('small');user.textContent=a.username;ua.textContent=a.user_agent;account.append(user,ua);device.textContent=a.device;const ip=document.createElement('strong'),place=document.createElement('small');ip.textContent=a.ip_address;place.textContent=[a.country,a.colo].filter(Boolean).join(' · ')||'未知位置';network.append(ip,place);state.textContent=a.success?'成功':'失败';state.className=a.success?'audit-ok':'audit-fail';row.append(time,account,device,network,state);list.append(row)})}catch(e){list.textContent=e.message}}async function loadUsage(username){const summary=$('#usageSummary'),recent=$('#usageRecent');summary.textContent='正在加载…';recent.textContent='正在加载…';try{const d=await req('/api/admin/usage-audit?username='+encodeURIComponent(username));summary.textContent='';recent.textContent='';const a=d.summary[0]||{};const row=document.createElement('div'),name=document.createElement('strong'),value=document.createElement('span');row.className='row';name.textContent=username;value.textContent='修补 '+Number(a.patch_count||0)+' 次 · 签发 '+Number(a.license_count||0)+' 张（'+Number(a.issue_operations||0)+' 次操作）';row.append(name,value);summary.append(row);if(!d.recent.length)recent.textContent='暂无最近操作';d.recent.forEach(a=>{const row=document.createElement('div'),left=document.createElement('div'),name=document.createElement('strong'),meta=document.createElement('small'),value=document.createElement('span');row.className='row';name.textContent=a.username;const action=a.action==='night_patch'?'网页修补':a.action==='night_license_issue'?'Night 卡密签发':'WTLYF 卡密签发';meta.textContent=a.created_at+' · '+a.ip_address+(a.detail?' · '+a.detail:'');left.append(name,meta);value.textContent=action+(a.action==='night_patch'?'':' × '+a.quantity);row.append(left,value);recent.append(row)})}catch(e){summary.textContent=e.message;recent.textContent=''}}async function openDetails(username){location.href='/admin/account-detail?username='+encodeURIComponent(username)}async function session(){try{await req('/api/admin/session');$('#loginCard').classList.add('hidden');$('#manager').classList.remove('hidden');await load()}catch{$('#loginCard').classList.remove('hidden');$('#manager').classList.add('hidden')}}$('#login').onclick=async()=>{try{await req('/api/admin/session',{method:'POST',body:JSON.stringify({username:$('#username').value,password:$('#password').value})});text('#loginStatus','登录成功');session()}catch(e){text('#loginStatus',e.message)}};$('#create').onclick=async()=>{const username=$('#newUsername').value,password=$('#newPassword').value;try{await req('/api/admin/accounts',{method:'POST',body:JSON.stringify({username,password})});$('#newPassword').value='';text('#status','管理员账户创建成功｜账户：'+username);load()}catch(e){text('#status',e.message)}};$('#refreshDetails').onclick=()=>selectedUser&&openDetails(selectedUser);$('#nightLicenses').onclick=()=>location.href='/night-licenses';$('#pushCenter').onclick=()=>location.href='/push';$('#logout').onclick=async()=>{await req('/api/admin/logout',{method:'POST',body:'{}'});location.reload()};session();
</script></body></html>`;
}

function pushSite(nonce: string) {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#070b18"><title>WTLYF 文件推送中心</title><style nonce="${nonce}">
*{box-sizing:border-box}body{margin:0;min-height:100vh;color:#eef5ff;font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif;background:radial-gradient(circle at 10% 10%,#244170 0,transparent 35%),radial-gradient(circle at 90% 5%,#68387c 0,transparent 30%),#070b18}main{width:min(900px,calc(100% - 28px));margin:auto;padding:24px 0 60px}.card{border:1px solid #ffffff2c;background:#101a31d6;box-shadow:0 22px 70px #0008;backdrop-filter:blur(24px);border-radius:26px;padding:24px;margin-bottom:18px}h1,h2{margin:0 0 14px}.back{display:inline-block;color:#dce9ff;text-decoration:none;margin-bottom:14px}label{display:block;margin:12px 0 6px;color:#c5d2e8}input,button{width:100%;border-radius:14px;border:1px solid #ffffff30;padding:13px 15px;font:inherit;color:#fff;background:#071022c7}button{cursor:pointer;margin-top:14px;font-weight:700;background:linear-gradient(120deg,#2f8cff,#8056e8)}button.danger{width:auto;margin:0;background:#8e2948}.status{min-height:24px;margin-top:12px;color:#91d9ff;word-break:break-all}.push{padding:15px 0;border-bottom:1px solid #ffffff1c}.push:last-child{border:0}.push strong,.push small{display:block}.push small{color:#9cabc4;margin:5px 0}.push-actions{display:flex;gap:9px;align-items:center}.push-actions a{color:#9fd2ff}.muted{color:#9cabc4}@media(max-width:600px){.card{padding:20px;border-radius:21px}.push-actions{align-items:stretch;flex-direction:column}.push-actions button{width:100%}}
</style></head><body><main><a class="back" href="/">← 返回账户中心</a><section class="card"><h1>WTLYF 文件推送中心</h1><p class="muted">发布文字通知或文件；工具箱启动时会显示最新推送，文件由用户确认后下载。</p></section><section class="card"><h2>发布推送</h2><label>标题</label><input id="title" value="WTLYF 通知" maxlength="80"><label>显示文字</label><input id="message" maxlength="1000" placeholder="输入要向工具箱用户显示的文字"><label>文件（可选，最大 20 MB）</label><input id="file" type="file"><button id="publish">发布到 WTLYF 工具箱</button><div id="status" class="status"></div></section><section class="card"><h2>推送记录</h2><div id="list">正在加载…</div></section></main><script nonce="${nonce}">
const $=s=>document.querySelector(s);async function json(path,options={}){const r=await fetch(path,{credentials:'same-origin',...options});let d={};try{d=await r.json()}catch{}if(!r.ok)throw new Error(d.message||'请求失败');return d}async function load(){try{const d=await json('/api/admin/push/list');const list=$('#list');list.textContent='';if(!d.pushes.length){list.textContent='暂无推送';return}d.pushes.forEach(p=>{const row=document.createElement('div');row.className='push';const title=document.createElement('strong');title.textContent=p.title;const msg=document.createElement('div');msg.textContent=p.message||'（无文字）';const meta=document.createElement('small');meta.textContent=p.createdAt+(p.fileName?' · '+p.fileName+' · '+p.fileSize+' 字节':' · 仅文字');const actions=document.createElement('div');actions.className='push-actions';if(p.fileName){const link=document.createElement('a');link.href='/api/v1/push/download?id='+encodeURIComponent(p.id);link.textContent='下载文件';actions.append(link)}const del=document.createElement('button');del.className='danger';del.textContent='删除推送';del.onclick=async()=>{if(!confirm('确认删除这条推送？'))return;try{await json('/api/admin/push/delete',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({id:p.id})});$('#status').textContent='推送已删除';load()}catch(e){$('#status').textContent=e.message}};actions.append(del);row.append(title,msg,meta,actions);list.append(row)})}catch(e){location.href='/'}}$('#publish').onclick=async()=>{const data=new FormData();data.append('title',$('#title').value);data.append('message',$('#message').value);if($('#file').files[0])data.append('file',$('#file').files[0]);$('#publish').disabled=true;$('#status').textContent='正在上传并发布…';try{const d=await json('/api/admin/push/upload',{method:'POST',body:data});$('#status').textContent=d.message;$('#file').value='';load()}catch(e){$('#status').textContent=e.message}finally{$('#publish').disabled=false}};load();
</script></body></html>`;
}

function nightLicenseSite(nonce: string) {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#070b18"><title>Night 独立卡密中心</title><style nonce="${nonce}">
*{box-sizing:border-box}body{margin:0;min-height:100vh;color:#eef5ff;font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif;background:radial-gradient(circle at 12% 8%,#174d75 0,transparent 34%),radial-gradient(circle at 88% 12%,#552b7e 0,transparent 30%),#070b18}main{width:min(960px,calc(100% - 28px));margin:auto;padding:24px 0 60px}.card{border:1px solid #ffffff2c;background:#101a31d8;box-shadow:0 22px 70px #0008;backdrop-filter:blur(24px);border-radius:26px;padding:24px;margin-bottom:18px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:18px}h1,h2{margin:0 0 14px}.back{display:inline-block;color:#dce9ff;text-decoration:none;margin-bottom:14px}label{display:block;margin:12px 0 6px;color:#c5d2e8}input,select,button,textarea{width:100%;border-radius:14px;border:1px solid #ffffff30;padding:13px 15px;font:inherit;color:#fff;background:#071022c7}textarea{min-height:145px;resize:vertical}button{cursor:pointer;margin-top:13px;font-weight:700;background:linear-gradient(120deg,#2f8cff,#8056e8)}button.danger{background:linear-gradient(120deg,#ad3157,#771d48)}.actions{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.status{min-height:24px;margin-top:12px;color:#91d9ff;word-break:break-all}.muted{color:#9cabc4}.duration{display:grid;grid-template-columns:1fr 1fr;gap:10px}@media(max-width:700px){.grid{grid-template-columns:1fr}.card{padding:20px;border-radius:21px}.actions{grid-template-columns:1fr}}
</style></head><body><main><a class="back" href="/">← 返回账户中心</a><section class="card"><h1>Night 独立卡密中心</h1><p class="muted">与 WTLYF 卡密完全分离。一台设备只能绑定一张 Night 卡密；支持查询、解绑和永久删除。</p></section><div class="grid"><section class="card"><h2>自定义生成</h2><label>自定义卡密（选填）</label><input id="custom" maxlength="128" placeholder="可输入任意文字；留空则随机生成"><label>随机生成数量</label><input id="count" type="number" min="1" max="100" value="1"><div class="duration"><div><label>设备使用上限</label><input id="deviceLimit" type="number" min="1" max="100000" value="1"></div><div><label>限制方式</label><select id="limitMode"><option value="limited">限制设备数量</option><option value="unlimited">无限设备</option></select></div></div><p class="muted">自定义时只生成一张；Night 卡密默认永久有效。</p><button id="generate">生成 Night 卡密</button><label>结果</label><textarea id="output" readonly></textarea></section><section class="card"><h2>管理卡密</h2><label>Night 卡密</label><input id="code" placeholder="输入随机卡密或自定义文字"><div class="actions"><button id="query">查询</button><button id="unbind">解绑</button><button id="delete" class="danger">删除</button></div><div id="status" class="status">独立 Night 卡密服务已连接</div><textarea id="details" readonly placeholder="查询结果"></textarea></section></div></main><script nonce="${nonce}">
const $=s=>document.querySelector(s);async function api(path,body){const r=await fetch(path,{method:'POST',credentials:'same-origin',headers:{'content-type':'application/json'},body:JSON.stringify(body)});let d={};try{d=await r.json()}catch{}if(!r.ok)throw new Error(d.message||'请求失败');return d}function code(){return $('#code').value.trim().toUpperCase()}$('#generate').onclick=async()=>{try{$('#status').textContent='正在生成…';const unlimited=$('#limitMode').value==='unlimited',deviceLimit=unlimited?null:Number($('#deviceLimit').value);const d=await api('/api/admin/night/generate',{count:Number($('#count').value),customCode:$('#custom').value,deviceLimit,unlimited});$('#output').value=d.codes.join('\\n');$('#status').textContent=d.custom?'Night 自定义卡密签发成功':'Night 卡密生成成功'}catch(e){$('#status').textContent=e.message}};$('#query').onclick=async()=>{try{const d=await api('/api/admin/night/status',{code:code()});$('#details').value=JSON.stringify(d.license,null,2);$('#status').textContent='查询成功'}catch(e){$('#status').textContent=e.message}};$('#unbind').onclick=async()=>{if(!confirm('确认解绑这张 Night 卡密？解绑后可绑定新设备。'))return;try{const d=await api('/api/admin/night/unbind',{code:code()});$('#status').textContent=d.message;$('#details').value=''}catch(e){$('#status').textContent=e.message}};$('#delete').onclick=async()=>{if(!confirm('确认永久删除这张 Night 卡密？'))return;try{const d=await api('/api/admin/night/delete',{code:code()});$('#status').textContent=d.message;$('#code').value='';$('#details').value=''}catch(e){$('#status').textContent=e.message}};
</script></body></html>`;
}

async function serveAsset(
  request: Request,
  env: Env,
  download = false,
  cacheControl = "private, no-store",
) {
  if (!env.ASSETS)
    return reply({ ok: false, message: "静态文件仅在 Pages 主站提供" }, 404);
  const response = await env.ASSETS.fetch(request);
  if (!download || !response.ok) return response;
  const headers = new Headers(response.headers);
  headers.delete("Access-Control-Allow-Origin");
  headers.set("Cache-Control", cacheControl);
  headers.set(
    "Content-Disposition",
    `attachment; filename="${new URL(request.url).pathname.split("/").pop()}"`,
  );
  headers.set("X-Content-Type-Options", "nosniff");
  return new Response(response.body, { status: response.status, headers });
}

function bytesToBase64(bytes: Uint8Array) {
  let binary = "";
  const chunk = 8192;
  for (let offset = 0; offset < bytes.length; offset += chunk)
    binary += String.fromCharCode(
      ...bytes.subarray(offset, Math.min(offset + chunk, bytes.length)),
    );
  return btoa(binary);
}

async function calculatorOcr(request: Request, env: Env) {
  if (!env.AI) return reply({ ok: false, message: "服务器 OCR 暂不可用" }, 503);
  const type = String(request.headers.get("content-type") || "")
    .split(";", 1)[0]
    .trim()
    .toLowerCase();
  if (type !== "image/jpeg" && type !== "image/png" && type !== "image/webp")
    return reply({ ok: false, message: "仅支持 JPEG、PNG 或 WebP 图片" }, 415);
  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.length < 32 || bytes.length > 768 * 1024)
    return reply({ ok: false, message: "截图大小无效" }, 413);
  try {
    const result = await env.AI.run("@cf/moondream/moondream3.1-9B-A2B", {
      task: "query",
      image: `data:${type};base64,${bytesToBase64(bytes)}`,
      question:
        "Read only the calculator expression and result visible in this cropped calculator display. Return the visible mathematical text exactly, one item per line. Do not explain, infer, or add labels. If no calculator expression or result is visible, return an empty answer.",
      reasoning: false,
      temperature: 0,
      max_tokens: 96,
      stream: false,
    });
    const text = String(result?.answer ?? result?.response ?? "")
      .trim()
      .slice(0, 1024);
    return reply({ ok: true, text });
  } catch (error) {
    console.error("calculator OCR failed", error);
    return reply({ ok: false, message: "服务器识别暂时失败，请稍后重试" }, 502);
  }
}

export default {
  async fetch(
    request: Request,
    env: Env,
    context: ExecutionContext,
  ): Promise<Response> {
    const firewallUrl = new URL(request.url),
      blocked = await firewall(request, firewallUrl, env, context);
    if (blocked) return blocked;
    const url = new URL(request.url),
      path = url.pathname.replace(/\/+$/, "") || "/",
      accountHost = url.hostname === new URL(ADMIN_SITE_ORIGIN).hostname,
      legacyAccountHost = url.hostname === LEGACY_ADMIN_HOST;
    const country = String((request as any).cf?.country ?? "").toUpperCase();
    if (
      (accountHost || path.startsWith("/api/admin/")) &&
      country &&
      !ADMIN_ALLOWED_COUNTRIES.has(country)
    ) {
      context.waitUntil(recordAdminAccess(request, env, path, "geo-block"));
      return reply({ ok: false, message: "该地区暂不允许访问管理服务" }, 403);
    }
    const accountOnlyApi =
      path === "/api/admin/accounts" ||
      path.startsWith("/api/admin/accounts/") ||
      path === "/api/admin/access-audit" ||
      path === "/api/admin/login-audit" ||
      path === "/api/admin/usage-audit" ||
      path.startsWith("/api/admin/push/");
    if (accountOnlyApi && !accountHost)
      return reply({ ok: false, message: "接口不存在" }, 404);
    if (
      accountHost &&
      (request.method === "GET" || request.method === "HEAD") &&
      ADMIN_PAGE_PATHS.has(path)
    )
      context.waitUntil(recordAdminAccess(request, env, path, "page"));
    if (request.method === "OPTIONS")
      return request.headers.get("origin") === url.origin
        ? new Response(null, {
            status: 204,
            headers: {
              ...API_HEADERS,
              "Access-Control-Allow-Origin": url.origin,
              "Access-Control-Allow-Headers": "content-type",
              "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
              Vary: "Origin",
            },
          })
        : reply({ ok: false, message: "跨站请求已拒绝" }, 403);
    if (
      request.headers.get("sec-fetch-site") === "cross-site" &&
      (path.startsWith("/api/admin/") || path.startsWith("/api/night/"))
    )
      return reply({ ok: false, message: "跨站请求已拒绝" }, 403);
    if (
      request.method === "POST" &&
      (path.startsWith("/api/admin/") || path.startsWith("/api/night/")) &&
      !sameOriginMutation(request, url)
    )
      return reply({ ok: false, message: "跨站写入请求已拒绝" }, 403);
    if (path.startsWith("/assets/")) return serveAsset(request, env);
    if (
      legacyAccountHost &&
      request.method === "GET" &&
      path === "/admin/accounts"
    )
      return reply({ ok: false, message: "资源不存在" }, 404);
    if (
      accountHost &&
      request.method === "GET" &&
      (path === "/" || path === "/admin/accounts")
    ) {
      const nonce = Array.from(
        crypto.getRandomValues(new Uint8Array(18)),
        (b) => b.toString(16).padStart(2, "0"),
      ).join("");
      const page = accountSite(nonce)
        .replaceAll(
          'placeholder="3–32 位字母、数字、点、横线或下划线"',
          'placeholder="任意名称（1–64 字符，可用中文、空格和符号）" maxlength="64"',
        )
        .replace(
          "const $=s=>document.querySelector(s);let selectedUser='';",
          "const $=s=>document.querySelector(s);let selectedUser='',actionToken='',canManageAccounts=false;",
        )
        .replace(
          "async function load(){const d=await req('/api/admin/accounts');const list=",
          "async function load(){const d=await req('/api/admin/accounts');actionToken=d.actionToken||'';canManageAccounts=!!d.canManageAccounts;$('#createPanel').style.display=canManageAccounts?'':'none';const list=",
        )
        .replace(
          "if(a.username!==d.current){const del=",
          "if(canManageAccounts&&a.username!==d.current){const del=",
        )
        .replace(
          "body:JSON.stringify({username:a.username})",
          "body:JSON.stringify({username:a.username,currentPassword:$('#ownerPassword').value,actionToken})",
        )
        .replace(
          "load()}catch(e){text('#status',e.message)}};actions.append(del)",
          "load()}catch(e){text('#status',e.message);load().catch(()=>{})}};actions.append(del)",
        )
        .replace(
          "await req('/api/admin/accounts',{method:'POST',body:JSON.stringify({username,password})});$('#newPassword').value='';text('#status','管理员账户创建成功｜账户：'+username);load()",
          "if(!canManageAccounts)throw Error('仅主账号可以创建管理员账户');const currentPassword=$('#ownerPassword').value;if(!currentPassword)throw Error('请输入主账号当前密码');await req('/api/admin/accounts',{method:'POST',body:JSON.stringify({username,password,currentPassword,actionToken})});$('#newPassword').value='';$('#ownerPassword').value='';text('#status','管理员账户创建成功｜账户：'+username+'｜密码：'+password+'（仅显示一次，请立即保存）');load()",
        )
        .replace(
          "load()}catch(e){text('#status',e.message)}};$('#refreshDetails')",
          "load()}catch(e){text('#status',e.message);load().catch(()=>{})}};$('#refreshDetails')",
        );
      return new Response(page, {
        headers: {
          ...PAGE_HEADERS,
          "content-security-policy": `default-src 'none'; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'`,
        },
      });
    }
    if (
      accountHost &&
      request.method === "GET" &&
      path === "/admin/account-detail"
    ) {
      if (!(await getAdminSession(request, env)))
        return Response.redirect(`${url.origin}/`, 302);
      const username = normalizeUsername(url.searchParams.get("username"));
      if (!username || username.length > 64)
        return Response.redirect(`${url.origin}/`, 302);
      const nonce = Array.from(
        crypto.getRandomValues(new Uint8Array(18)),
        (b) => b.toString(16).padStart(2, "0"),
      ).join("");
      const page = accountDetailSiteV2(nonce)
        .replace(
          '<section class="card"><h2>账户凭据</h2>',
          '<section id="credentialSection" class="card"><h2>账户凭据</h2><p class="muted">仅主账号可重置；每次必须重新验证主账号当前密码。</p>',
        )
        .replace(
          '<div class="password-row"><input id="newPassword"',
          '<label for="ownerPassword">主账号当前密码</label><input id="ownerPassword" type="password" autocomplete="current-password" maxlength="256" placeholder="用于本次高风险操作验证"><div class="password-row"><input id="newPassword"',
        )
        .replace(
          "const $=s=>document.querySelector(s),username=new URLSearchParams(location.search).get('username')||'';",
          "const $=s=>document.querySelector(s),username=new URLSearchParams(location.search).get('username')||'';let actionToken='';",
        )
        .replace(
          "async function load(){if(!username)",
          "async function load(){try{const token=await req('/api/admin/accounts/security-token');actionToken=token.actionToken||'';$('#credentialSection').style.display=''}catch{$('#credentialSection').style.display='none'}if(!username)",
        )
        .replace(
          "body:JSON.stringify({username,password})",
          "body:JSON.stringify({username,password,currentPassword:$('#ownerPassword').value,actionToken})",
        )
        .replace(
          "const password=$('#newPassword').value;if(!password)",
          "const password=$('#newPassword').value;if(!$('#ownerPassword').value){$('#passwordStatus').textContent='请输入主账号当前密码';return}if(!password)",
        )
        .replace(
          "$('#passwordStatus').textContent=e.message}};$('#refresh')",
          "$('#passwordStatus').textContent=e.message;load().catch(()=>{})}};$('#refresh')",
        );
      return new Response(page, {
        headers: {
          ...PAGE_HEADERS,
          "content-security-policy": `default-src 'none'; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'`,
        },
      });
    }
    if (accountHost && request.method === "GET" && path === "/push") {
      const nonce = Array.from(
        crypto.getRandomValues(new Uint8Array(18)),
        (b) => b.toString(16).padStart(2, "0"),
      ).join("");
      return new Response(pushSite(nonce), {
        headers: {
          ...PAGE_HEADERS,
          "content-security-policy": `default-src 'none'; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'`,
        },
      });
    }
    if (accountHost && request.method === "GET" && path === "/night-licenses") {
      const nonce = Array.from(
        crypto.getRandomValues(new Uint8Array(18)),
        (b) => b.toString(16).padStart(2, "0"),
      ).join("");
      return new Response(nightLicenseSite(nonce), {
        headers: {
          ...PAGE_HEADERS,
          "content-security-policy": `default-src 'none'; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'`,
        },
      });
    }
    if (path === "/api/admin/session" && request.method === "POST") {
      try {
        const body: any = await request.json().catch(() => ({}));
        const username = normalizeUsername(body.username),
          password = String(body.password ?? "");
        if (
          username.length < 1 ||
          username.length > 64 ||
          password.length < 1 ||
          password.length > 256
        )
          return reply({ ok: false, message: "账户或密码错误" }, 401);
        if (await loginBlocked(request, env, username)) {
          await recordAdminLogin(request, env, username, false);
          return reply(
            { ok: false, message: "登录失败次数过多，请 15 分钟后再试" },
            429,
            { "Retry-After": "900" },
          );
        }
        const matched = await authenticateAdmin(username, password, env);
        await recordAdminLogin(request, env, username, !!matched);
        if (!matched)
          return reply({ ok: false, message: "账户或密码错误" }, 401);
        await env.DB.prepare(
          "UPDATE admin_users SET last_login_at=CURRENT_TIMESTAMP WHERE username=?",
        )
          .bind(matched.username)
          .run();
        const expires = String(Math.floor(Date.now() / 1000) + SESSION_SECONDS),
          sessionToken = await createAdminSession(
            matched.username,
            expires,
            url.hostname,
            env,
          ),
          signature = await signSession(
            matched.username,
            expires,
            sessionToken,
            url.hostname,
            matched.passwordHash,
            env,
          );
        return reply(
          { ok: true, message: "登录成功", username: matched.username },
          200,
          {
            "Set-Cookie": `${SESSION_COOKIE}=v6.${expires}.${sessionToken}.${signature}; Path=/; Max-Age=${SESSION_SECONDS}; HttpOnly; Secure; SameSite=Strict; Priority=High`,
          },
        );
      } catch (error) {
        console.error("admin login failed", error);
        return reply(
          { ok: false, message: "登录服务暂时不可用，请稍后重试" },
          500,
        );
      }
    }
    const adminUser = await getAdminSession(request, env),
      authorized = !!adminUser;
    if (path === "/api/admin/access-audit" && request.method === "GET") {
      if (!authorized) return reply({ ok: false, message: "请先登录" }, 401);
      await ensureAdminAccessAudit(env);
      const rows = await env.DB.prepare(
        "SELECT path,method,category,ip_address,user_agent,country,colo,created_at FROM admin_access_audit ORDER BY id DESC LIMIT 500",
      ).all();
      return reply({ ok: true, records: rows.results ?? [] });
    }
    if (path === "/api/v1/push/latest" && request.method === "GET") {
      const item = (await env.PUSH_FILES.get(
        "push:latest",
        "json",
      )) as PushItem | null;
      return reply({
        ok: true,
        push: item
          ? {
              ...item,
              downloadUrl:
                item.downloadUrl ||
                (item.fileName
                  ? `/api/v1/push/download?id=${encodeURIComponent(item.id)}`
                  : null),
            }
          : null,
      });
    }
    if (path === "/api/v1/push/download" && request.method === "GET")
      return downloadPush(url, env);
    if (
      path === "/api/v1/toolbox/latest-apk" &&
      (request.method === "GET" || request.method === "HEAD")
    ) {
      const assetUrl = new URL(request.url);
      assetUrl.pathname = "/downloads/wtlyf-toolbox.apk";
      assetUrl.search = "";
      return serveAsset(new Request(assetUrl.toString(), request), env, true);
    }
    if (
      path === "/api/v1/night/latest-apk" &&
      (request.method === "GET" || request.method === "HEAD")
    ) {
      const assetUrl = new URL(request.url);
      assetUrl.pathname = "/downloads/Night-manager.apk";
      assetUrl.search = "";
      return serveAsset(new Request(assetUrl.toString(), request), env, true);
    }
    if (path === "/api/v1/night/calculator-ocr" && request.method === "POST")
      return calculatorOcr(request, env);
    if (
      path === "/api/v1/download/payload-dumper-apk" &&
      (request.method === "GET" || request.method === "HEAD")
    ) {
      const assetUrl = new URL(request.url);
      assetUrl.pathname = "/downloads/Payload-Dumper.apk";
      assetUrl.search = "";
      return serveAsset(new Request(assetUrl.toString(), request), env, true);
    }
    if (
      path.startsWith("/api/v1/toolbox/modules/") &&
      request.method === "GET"
    ) {
      const name = decodeURIComponent(
        path.slice("/api/v1/toolbox/modules/".length),
      );
      if (!TOOLBOX_MODULES.has(name))
        return reply({ ok: false, message: "模块文件不存在" }, 404);
      const assetUrl = new URL(request.url);
      assetUrl.pathname = `/modules/${name}`;
      assetUrl.search = "";
      return serveAsset(
        new Request(assetUrl.toString(), request),
        env,
        true,
        "public, max-age=300, s-maxage=86400, stale-while-revalidate=604800",
      );
    }
    if (path === "/api/admin/session" && request.method === "GET")
      return authorized
        ? reply({ ok: true, authenticated: true, username: adminUser })
        : reply({ ok: false, authenticated: false }, 401);
    if (path === "/api/admin/logout" && request.method === "POST") {
      await revokeAdminSession(request, env).catch((error) =>
        console.error("admin session revoke failed", error),
      );
      return reply({ ok: true, message: "已退出登录" }, 200, {
        "Set-Cookie": `${SESSION_COOKIE}=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict`,
        "Clear-Site-Data": '"cache", "cookies", "storage"',
      });
    }
    if (path === "/") return new Response(HOME, { headers: PAGE_HEADERS });
    if (path === "/admin/accounts")
      return reply({ ok: false, message: "资源不存在" }, 404);
    if (
      (path === "/wtlyf" || path === "/night" || path === "/unpack") &&
      !authorized
    )
      return Response.redirect(`${url.origin}/`, 302);
    if (path === "/wtlyf")
      return new Response(WTLYF, { headers: PAGE_HEADERS });
    if (path === "/night")
      return new Response(NIGHT, { headers: PAGE_HEADERS });
    if (path === "/unpack")
      return new Response(UNPACK, { headers: PAGE_HEADERS });
    if (path.startsWith("/downloads/")) {
      if (!authorized)
        return reply({ ok: false, message: "请先完成管理员验证" }, 401);
      return serveAsset(request, env, true);
    }
    if (path === "/api/v1/health")
      return reply({ ok: true, service: "wtlyf-license", api: "cf-v3" });
    if (
      (path === "/api/v1/activate" || path === "/api/v1/check") &&
      request.method === "POST"
    )
      return activate(request, env);
    if (
      (path === "/api/v1/night/activate" || path === "/api/v1/night/check") &&
      request.method === "POST"
    )
      return activateNight(
        request,
        env,
        path.endsWith("/activate") ? "activate" : "check",
      );
    if (path === "/api/v1/night/session/start" && request.method === "POST")
      return startNightEphemeralSession(request, env);
    if (path === "/api/v1/night/session/key" && request.method === "POST")
      return revealNightEphemeralKey(request, env);
    if (path === "/api/night/sign" && request.method === "POST") {
      if (!authorized)
        return reply(
          { ok: false, message: "管理员验证已失效，请返回首页重新登录" },
          401,
        );
      const body: any = await request.json().catch(() => ({}));
      const androidId = String(body.androidId ?? "").trim();
      try {
        return reply({
          ok: true,
          activationCode: await signNight(androidId, env),
        });
      } catch (error) {
        return reply(
          {
            ok: false,
            message: error instanceof Error ? error.message : "签发失败",
          },
          400,
        );
      }
    }
    if (path === "/api/night/patch/start" && request.method === "POST") {
      if (!authorized)
        return reply(
          { ok: false, message: "管理员验证已失效，请返回首页重新登录" },
          401,
        );
      const response = await startWebPatch(request, url, env, adminUser!);
      if (response.ok)
        await recordAdminUsage(
          request,
          env,
          adminUser!,
          "night_patch",
          1,
          "单次上传修补",
        );
      return response;
    }
    if (path === "/api/night/patch/create" && request.method === "POST") {
      if (!authorized)
        return reply(
          { ok: false, message: "管理员验证已失效，请返回首页重新登录" },
          401,
        );
      return createWebPatch(request, env, adminUser!);
    }
    if (path === "/api/night/patch/chunk" && request.method === "POST") {
      if (!authorized)
        return reply(
          { ok: false, message: "管理员验证已失效，请返回首页重新登录" },
          401,
        );
      return uploadPatchChunk(request, url, env, adminUser!);
    }
    if (path === "/api/night/patch/finalize" && request.method === "POST") {
      if (!authorized)
        return reply(
          { ok: false, message: "管理员验证已失效，请返回首页重新登录" },
          401,
        );
      const response = await finalizeWebPatch(request, env, adminUser!);
      if (response.ok)
        await recordAdminUsage(
          request,
          env,
          adminUser!,
          "night_patch",
          1,
          "分块上传修补",
        );
      return response;
    }
    if (path === "/api/night/patch/status" && request.method === "GET") {
      if (!authorized)
        return reply(
          { ok: false, message: "管理员验证已失效，请返回首页重新登录" },
          401,
        );
      return patchStatus(
        String(url.searchParams.get("jobId") ?? ""),
        env,
        adminUser!,
      );
    }
    if (path === "/api/night/patch/download" && request.method === "GET") {
      if (!authorized)
        return reply(
          { ok: false, message: "管理员验证已失效，请返回首页重新登录" },
          401,
        );
      return downloadPatch(
        String(url.searchParams.get("jobId") ?? ""),
        env,
        adminUser!,
      );
    }
    if (path === "/api/unpack/proxy")
      return reply(
        {
          ok: false,
          message: "远程 ROM 代理已停用，请使用本地 Payload Dumper",
        },
        410,
      );
    if (path === "/api/night/patch/cleanup" && request.method === "POST") {
      if (!authorized)
        return reply(
          { ok: false, message: "管理员验证已失效，请返回首页重新登录" },
          401,
        );
      const body: any = await request.json().catch(() => ({}));
      const jobId = String(body.jobId ?? "");
      if (!/^\d{1,20}$/.test(jobId))
        return reply({ ok: false, message: "任务编号无效" }, 400);
      if (!(await ownsPatchJob(env, jobId, adminUser!)))
        return reply({ ok: false, message: "无权访问该修补任务" }, 403);
      await deletePatchRelease(jobId, env);
      await forgetPatchJob(env, jobId);
      return reply({ ok: true, message: "临时镜像已清理" });
    }
    if (path === "/api/admin/accounts" && request.method === "GET") {
      if (!authorized) return reply({ ok: false, message: "请先登录" }, 401);
      const [result, canManageAccounts] = await Promise.all([
        env.DB.prepare(
          "SELECT username,active,created_at,last_login_at,created_by FROM admin_users ORDER BY created_at ASC",
        ).all<any>(),
        isPrimaryAdmin(adminUser!, env),
      ]);
      const action = canManageAccounts
        ? await issueAdminActionToken(
            adminUser!,
            "account-mutation",
            url.hostname,
            env,
          )
        : null;
      return reply({
        ok: true,
        accounts: result.results,
        current: adminUser,
        canManageAccounts,
        actionToken: action?.token ?? null,
        actionTokenExpiresAt: action?.expiresAt ?? null,
      });
    }
    if (
      path === "/api/admin/accounts/security-token" &&
      request.method === "GET"
    ) {
      if (!authorized) return reply({ ok: false, message: "请先登录" }, 401);
      if (!(await isPrimaryAdmin(adminUser!, env)))
        return reply(
          { ok: false, message: "仅主账号可以管理管理员账户" },
          403,
        );
      const action = await issueAdminActionToken(
        adminUser!,
        "account-mutation",
        url.hostname,
        env,
      );
      return reply({
        ok: true,
        actionToken: action.token,
        actionTokenExpiresAt: action.expiresAt,
      });
    }
    if (path === "/api/admin/login-audit" && request.method === "GET") {
      if (!authorized) return reply({ ok: false, message: "请先登录" }, 401);
      const username = normalizeUsername(url.searchParams.get("username"));
      if (!username || username.length > 64)
        return reply({ ok: false, message: "请选择要查看的管理员" }, 400);
      await ensureAdminLoginAudit(env);
      const protectedUsername = await failedUsernameFingerprint(username, env);
      const result = await env.DB.prepare(
        "SELECT ? AS username,ip_address,device,user_agent,country,colo,success,created_at FROM admin_login_audit WHERE username IN (?,?) ORDER BY id DESC LIMIT 200",
      )
        .bind(username, username, protectedUsername)
        .all<any>();
      return reply({
        ok: true,
        records: result.results.map((row) => ({
          ...row,
          success: Number(row.success) === 1,
        })),
      });
    }
    if (path === "/api/admin/usage-audit" && request.method === "GET") {
      if (!authorized) return reply({ ok: false, message: "请先登录" }, 401);
      const username = normalizeUsername(url.searchParams.get("username"));
      if (!username || username.length > 64)
        return reply({ ok: false, message: "请选择要查看的管理员" }, 400);
      await ensureAdminUsageAudit(env);
      const [summary, recent] = await Promise.all([
        env.DB.prepare(
          "SELECT ? AS username, SUM(CASE WHEN action='night_patch' THEN quantity ELSE 0 END) AS patch_count, SUM(CASE WHEN action IN ('night_license_issue','wtlyf_license_issue') THEN quantity ELSE 0 END) AS license_count, SUM(CASE WHEN action IN ('night_license_issue','wtlyf_license_issue') THEN 1 ELSE 0 END) AS issue_operations FROM admin_usage_audit WHERE username=?",
        )
          .bind(username, username)
          .all<any>(),
        env.DB.prepare(
          "SELECT username,action,quantity,detail,ip_address,created_at FROM admin_usage_audit WHERE username=? ORDER BY id DESC LIMIT 200",
        )
          .bind(username)
          .all<any>(),
      ]);
      return reply({
        ok: true,
        summary: summary.results,
        recent: recent.results,
      });
    }
    if (path === "/api/admin/accounts" && request.method === "POST") {
      if (!authorized) return reply({ ok: false, message: "请先登录" }, 401);
      const body: any = await request.json().catch(() => ({})),
        username = normalizeUsername(body.username),
        password = String(body.password ?? "");
      if (!validAdminUsername(username))
        return reply(
          {
            ok: false,
            message: "账户名不能为空、不能超过 64 个字符或包含控制字符",
          },
          400,
        );
      if (password.length < 1 || password.length > 256)
        return reply(
          { ok: false, message: "密码不能为空，且不能超过 256 个字符" },
          400,
        );
      const denied = await authorizeAccountMutation(
        request,
        env,
        adminUser!,
        body,
      );
      if (denied) {
        context.waitUntil(
          recordAdminAccess(request, env, path, "account-create-denied"),
        );
        return denied;
      }
      const salt = toBase64(crypto.getRandomValues(new Uint8Array(16))),
        hash = await portablePasswordHash(
          password,
          salt,
          PASSWORD_ITERATIONS,
        );
      try {
        await env.DB.prepare(
          "INSERT INTO admin_users(username,password_hash,password_salt,password_iterations,created_by) VALUES(?,?,?,?,?)",
        )
          .bind(username, hash, salt, PASSWORD_ITERATIONS, adminUser)
          .run();
        context.waitUntil(
          recordAdminAccess(request, env, path, "account-created"),
        );
        return reply({ ok: true, message: "管理员账户已生成", username });
      } catch {
        return reply({ ok: false, message: "该管理员账户已经存在" }, 409);
      }
    }
    if (
      path === "/api/admin/accounts/reset-password" &&
      request.method === "POST"
    ) {
      if (!authorized) return reply({ ok: false, message: "请先登录" }, 401);
      const body: any = await request.json().catch(() => ({})),
        username = normalizeUsername(body.username),
        password = String(body.password ?? "");
      if (!validAdminUsername(username))
        return reply({ ok: false, message: "请选择有效的管理员账户" }, 400);
      if (password.length < 1 || password.length > 256)
        return reply(
          { ok: false, message: "新密码不能为空，且不能超过 256 个字符" },
          400,
        );
      const denied = await authorizeAccountMutation(
        request,
        env,
        adminUser!,
        body,
      );
      if (denied) {
        context.waitUntil(
          recordAdminAccess(request, env, path, "password-reset-denied"),
        );
        return denied;
      }
      const salt = toBase64(crypto.getRandomValues(new Uint8Array(16))),
        hash = await portablePasswordHash(
          password,
          salt,
          PASSWORD_ITERATIONS,
        ),
        result = await env.DB.prepare(
          "UPDATE admin_users SET password_hash=?,password_salt=?,password_iterations=? WHERE username=? AND active=1",
        )
          .bind(hash, salt, PASSWORD_ITERATIONS, username)
          .run();
      if (result.meta.changes === 1) {
        await ensureAdminSessions(env);
        await env.DB.prepare("DELETE FROM admin_sessions WHERE username=?")
          .bind(username)
          .run();
        context.waitUntil(
          recordAdminAccess(request, env, path, "password-reset"),
        );
      }
      return result.meta.changes === 1
        ? reply({ ok: true, message: "密码已重置，新密码立即生效" })
        : reply({ ok: false, message: "未找到该管理员账户" }, 404);
    }
    if (path === "/api/admin/accounts/delete" && request.method === "POST") {
      if (!authorized) return reply({ ok: false, message: "请先登录" }, 401);
      const body: any = await request.json().catch(() => ({})),
        username = normalizeUsername(body.username);
      if (username === adminUser)
        return reply({ ok: false, message: "不能删除当前登录账户" }, 400);
      if (!validAdminUsername(username))
        return reply({ ok: false, message: "请选择有效的管理员账户" }, 400);
      const denied = await authorizeAccountMutation(
        request,
        env,
        adminUser!,
        body,
      );
      if (denied) {
        context.waitUntil(
          recordAdminAccess(request, env, path, "account-delete-denied"),
        );
        return denied;
      }
      const count = await env.DB.prepare(
        "SELECT COUNT(*) AS count FROM admin_users WHERE active=1",
      ).first<any>();
      if (Number(count?.count ?? 0) <= 1)
        return reply({ ok: false, message: "必须保留至少一个管理员账户" }, 400);
      const result = await env.DB.prepare(
        "DELETE FROM admin_users WHERE username=?",
      )
        .bind(username)
        .run();
      if (result.meta.changes === 1) {
        await ensureAdminSessions(env);
        await env.DB.prepare("DELETE FROM admin_sessions WHERE username=?")
          .bind(username)
          .run();
        context.waitUntil(
          recordAdminAccess(request, env, path, "account-deleted"),
        );
      }
      return result.meta.changes === 1
        ? reply({ ok: true, message: "管理员账户已删除" })
        : reply({ ok: false, message: "未找到该账户" }, 404);
    }
    if (path === "/api/admin/push/list" && request.method === "GET") {
      if (!authorized) return reply({ ok: false, message: "请先登录" }, 401);
      return reply({ ok: true, pushes: await getPushList(env) });
    }
    if (path === "/api/admin/push/upload" && request.method === "POST") {
      if (!authorized) return reply({ ok: false, message: "请先登录" }, 401);
      return publishPush(request, env);
    }
    if (
      path === "/api/admin/push/publish-download" &&
      request.method === "POST"
    ) {
      if (!authorized) return reply({ ok: false, message: "请先登录" }, 401);
      return publishDownloadPush(request, env);
    }
    if (path === "/api/admin/push/delete" && request.method === "POST") {
      if (!authorized) return reply({ ok: false, message: "请先登录" }, 401);
      return deletePush(request, env);
    }
    if (path.startsWith("/api/admin/night/") && request.method === "POST") {
      if (!authorized)
        return reply(
          { ok: false, message: "管理员验证已失效，请重新登录" },
          401,
        );
      const body: any = await request.json().catch(() => ({}));
      if (path === "/api/admin/night/generate") {
        const count = Number(body.count ?? 1),
          durationHours = null,
          customCode = normalize(body.customCode),
          deviceLimit = deviceLimitFrom(body);
        if (!Number.isInteger(count) || count < 1 || count > 100)
          return reply({ ok: false, message: "生成数量必须为 1 到 100" }, 400);
        if (deviceLimit === undefined)
          return reply(
            {
              ok: false,
              message: "设备上限必须为 1 到 100000，或选择无限设备",
            },
            400,
          );
        if (customCode) {
          if (!validLicenseCode(customCode))
            return reply(
              { ok: false, message: "自定义卡密必须为 1 到 128 个可见字符" },
              400,
            );
          if (
            !(await issueCustomCode(
              env,
              "night",
              customCode,
              durationHours,
              deviceLimit,
            ))
          )
            return reply(
              { ok: false, message: "该自定义卡密已经签发过，不能重复生成" },
              409,
            );
          await recordAdminUsage(
            request,
            env,
            adminUser!,
            "night_license_issue",
            1,
            "Night 自定义卡密",
          );
          return reply({
            ok: true,
            codes: [customCode],
            permanent: true,
            custom: true,
            deviceLimit,
          });
        }
        const codes = await generateNeverRepeatedCodes(
          env,
          "night",
          count,
          durationHours,
          deviceLimit,
        );
        await recordAdminUsage(
          request,
          env,
          adminUser!,
          "night_license_issue",
          codes.length,
          "Night 随机卡密",
        );
        return reply({ ok: true, codes, permanent: true, deviceLimit });
      }
      const code = normalize(body.code);
      if (!validLicenseCode(code))
        return reply({ ok: false, message: "请输入有效的 Night 卡密" }, 400);
      const hash = await nightCodeHash(code);
      if (path === "/api/admin/night/unbind") {
        const result = await env.DB.batch([
          env.DB.prepare(
            "DELETE FROM license_devices WHERE scope='night' AND code_hash=?",
          ).bind(hash),
          env.DB.prepare(
            "UPDATE night_licenses SET device_hash=NULL,bound_at=NULL,last_seen_at=NULL,expires_at=NULL,status='active' WHERE code_hash=?",
          ).bind(hash),
        ]);
        return result[1]?.meta.changes === 1
          ? reply({ ok: true, message: "Night 卡密的全部设备已解绑" })
          : reply({ ok: false, message: "未找到 Night 卡密" }, 404);
      }
      if (path === "/api/admin/night/delete") {
        await env.DB.prepare(
          "DELETE FROM license_devices WHERE scope='night' AND code_hash=?",
        )
          .bind(hash)
          .run();
        const result = await env.DB.prepare(
          "DELETE FROM night_licenses WHERE code_hash=?",
        )
          .bind(hash)
          .run();
        return result.meta.changes === 1
          ? reply({ ok: true, message: "Night 卡密已永久删除" })
          : reply({ ok: false, message: "未找到 Night 卡密" }, 404);
      }
      if (path === "/api/admin/night/status") {
        await env.DB.prepare(
          "UPDATE night_licenses SET status='expired' WHERE code_hash=? AND expires_at IS NOT NULL AND expires_at<=CURRENT_TIMESTAMP",
        )
          .bind(hash)
          .run();
        const row = await env.DB.prepare(
          "SELECT code_hint,status,duration_hours,device_limit,created_at,bound_at,last_seen_at,expires_at,(SELECT COUNT(*) FROM license_devices WHERE scope='night' AND code_hash=?) AS device_count FROM night_licenses WHERE code_hash=?",
        )
          .bind(hash, hash)
          .first<any>();
        if (!row)
          return reply({ ok: false, message: "未找到 Night 卡密" }, 404);
        row.device_limit_text =
          row.device_limit == null ? "无限设备" : String(row.device_limit);
        return reply({ ok: true, license: row });
      }
    }
    if (path.startsWith("/api/admin/") && request.method === "POST") {
      if (!authorized)
        return reply(
          { ok: false, message: "管理员验证已失效，请重新登录" },
          401,
        );
      const body: any = await request.json().catch(() => ({})),
        code = normalize(body.code),
        hash = await codeHash(code);
      if (path === "/api/admin/generate") {
        const count = Number(body.count ?? 1),
          durationHours =
            body.durationHours == null ? null : Number(body.durationHours),
          customCode = normalize(body.customCode),
          deviceLimit = deviceLimitFrom(body);
        if (!Number.isInteger(count) || count < 1 || count > 100)
          return reply({ ok: false, message: "生成数量必须为 1 到 100" }, 400);
        if (
          !Number.isInteger(durationHours) ||
          durationHours < 1 ||
          durationHours > 87600
        )
          return reply(
            { ok: false, message: "有效时长必须为 1 小时到 10 年" },
            400,
          );
        if (deviceLimit === undefined)
          return reply(
            {
              ok: false,
              message: "设备上限必须为 1 到 100000，或选择无限设备",
            },
            400,
          );
        if (customCode) {
          if (!validLicenseCode(customCode))
            return reply(
              { ok: false, message: "自定义卡密必须为 1 到 128 个可见字符" },
              400,
            );
          if (
            !(await issueCustomCode(
              env,
              "wtlyf",
              customCode,
              durationHours,
              deviceLimit,
            ))
          )
            return reply(
              { ok: false, message: "该自定义卡密已经签发过，不能重复生成" },
              409,
            );
          await recordAdminUsage(
            request,
            env,
            adminUser!,
            "wtlyf_license_issue",
            1,
            "WTLYF 自定义卡密",
          );
          return reply({
            ok: true,
            codes: [customCode],
            durationHours,
            custom: true,
            deviceLimit,
          });
        }
        const codes = await generateNeverRepeatedCodes(
          env,
          "wtlyf",
          count,
          durationHours,
          deviceLimit,
        );
        await recordAdminUsage(
          request,
          env,
          adminUser!,
          "wtlyf_license_issue",
          codes.length,
          "WTLYF 随机卡密",
        );
        return reply({ ok: true, codes, durationHours, deviceLimit });
      }
      if (path === "/api/admin/unbind") {
        const result = await env.DB.batch([
          env.DB.prepare(
            "DELETE FROM license_devices WHERE scope='wtlyf' AND code_hash=?",
          ).bind(hash),
          env.DB.prepare(
            "UPDATE licenses SET device_hash=NULL,bound_at=NULL,last_seen_at=NULL,expires_at=NULL,status='active' WHERE code_hash=?",
          ).bind(hash),
        ]);
        return result[1]?.meta.changes === 1
          ? reply({
              ok: true,
              message: "卡密的全部设备已解绑，有效期将在下次激活时重新开始",
            })
          : reply({ ok: false, message: "未找到该卡密" }, 404);
      }
      if (path === "/api/admin/delete") {
        await env.DB.prepare(
          "DELETE FROM license_devices WHERE scope='wtlyf' AND code_hash=?",
        )
          .bind(hash)
          .run();
        const result = await env.DB.prepare(
          "DELETE FROM licenses WHERE code_hash=?",
        )
          .bind(hash)
          .run();
        return result.meta.changes === 1
          ? reply({ ok: true, message: "卡密已永久删除" })
          : reply({ ok: false, message: "未找到该卡密" }, 404);
      }
      if (path === "/api/admin/status") {
        await env.DB.prepare(
          "UPDATE licenses SET status='expired' WHERE code_hash=? AND expires_at IS NOT NULL AND expires_at<=CURRENT_TIMESTAMP",
        )
          .bind(hash)
          .run();
        const row = await env.DB.prepare(
          "SELECT code_hint,status,duration_hours,device_limit,created_at,bound_at,last_seen_at,expires_at,(SELECT COUNT(*) FROM license_devices WHERE scope='wtlyf' AND code_hash=?) AS device_count FROM licenses WHERE code_hash=?",
        )
          .bind(hash, hash)
          .first<any>();
        if (!row) return reply({ ok: false, message: "未找到该卡密" }, 404);
        row.device_limit_text =
          row.device_limit == null ? "无限设备" : String(row.device_limit);
        return reply({ ok: true, license: row });
      }
    }
    return reply(
      { ok: false, error: "not_found", message: "页面或接口不存在" },
      404,
    );
  },
};
