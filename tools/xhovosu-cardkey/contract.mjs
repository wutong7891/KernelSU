// Wiring contract for the XhovoSU 14-character card-key gate.
//
// Fails if the Manager reintroduces networking, if the embedded secret drifts
// away from the one the issuer prints with, or if the gate stops wrapping the
// manager shell.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "../..");
const read = (path) => readFileSync(resolve(repo, path), "utf8");

const api = read("manager/app/src/main/java/me/weishu/kernelsu/ui/screen/cardkey/CardKeyApi.kt");
const keys = read("manager/app/src/main/java/me/weishu/kernelsu/ui/screen/cardkey/CardKeyKeys.kt");
const screen = read("manager/app/src/main/java/me/weishu/kernelsu/ui/screen/cardkey/CardKeyScreen.kt");
const activity = read("manager/app/src/main/java/me/weishu/kernelsu/ui/MainActivity.kt");
const manifest = read("manager/app/src/main/AndroidManifest.xml");

// 1. Verification is offline and uses the 14-character format.
assert.doesNotMatch(api, /okhttp3|OkHttpClient/, "card-key verification must not use HTTP");
assert.doesNotMatch(api, /https?:\/\//, "card-key verification must not reference a server");
assert.match(api, /CODE_LENGTH = 14/, "the code format must stay 14 characters");
assert.match(api, /HmacSHA256/, "verification must use HMAC-SHA256");
assert.match(api, /PAYLOAD_CHARS = 8/, "payload must stay 8 characters");
assert.match(api, /CHECK_CHARS = 6/, "checksum must stay 6 characters");
assert.match(api, /XHOVO/, "token prefix must be XHOVO");

// 2. The embedded secret is the one the issuer signs with.
const embedded = keys.match(/SECRET_BASE64: String =\s*\n?\s*"([^"]+)"/)?.[1];
assert.ok(embedded, "CardKeyKeys must embed the MAC secret");
const devSecret = readFileSync(resolve(here, "dev/cardkey_secret.bin")).toString("base64");
assert.equal(
  embedded,
  devSecret,
  "embedded secret must match dev/cardkey_secret.bin, otherwise the shipped issuer cannot print usable keys",
);

// 3. The gate wraps the whole manager shell.
assert.match(activity, /CardKeyGate\(/, "MainActivity must wrap its content in CardKeyGate");
assert.match(activity, /onContentReady = \{ contentReady = true \}/, "the gate must release the splash screen");
assert.doesNotMatch(activity, /NightActivation/, "the old Night gate must be gone");

// 4. Branding.
assert.match(manifest, /android:label="@string\/app_name"/, "app label comes from the build config");
assert.doesNotMatch(screen, /Pigeon|pigeon/, "the gate must not carry the upstream platform branding");

console.log("XhovoSU 14-character card-key contract holds: offline HMAC, matching secret, gate wired");
