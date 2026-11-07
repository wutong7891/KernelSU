// Wiring contract for the XhovoSU card-key gate.
//
// Fails if the Manager reintroduces networking into licence verification, if the
// embedded key drifts away from the key pair the issuer signs with, or if the
// gate stops wrapping the manager shell.

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

// 1. Verification is offline.
assert.doesNotMatch(api, /okhttp3|OkHttpClient/, "card-key verification must not use HTTP");
assert.doesNotMatch(api, /https?:\/\//, "card-key verification must not reference a server");
assert.match(api, /SHA256withECDSA/, "verification must use ECDSA over SHA-256");
assert.match(api, /TOKEN_SIZE = PAYLOAD_SIZE \+ SIGNATURE_SIZE/, "token size must stay explicit");
assert.match(api, /FLAG_DEVICE_BOUND/, "device binding must stay implemented");
assert.match(api, /XHOVO/, "token prefix must be XHOVO");

// 2. The embedded key is a real P-256 SPKI key.
const embedded = keys.match(/PUBLIC_KEY_DER_BASE64: String =\s*\n?\s*"([^"]+)"/)?.[1];
assert.ok(embedded, "CardKeyKeys must embed a verification key");
const der = Buffer.from(embedded, "base64");
assert.equal(der.length, 91, "embedded key must be a P-256 SubjectPublicKeyInfo");
assert.equal(der[0], 0x30);
assert.equal(der[1], 0x59);

const devKey = readFileSync(resolve(here, "dev/dev_public_key.der"));
assert.equal(
  der.equals(devKey),
  true,
  "embedded key must match dev/dev_public_key.der, otherwise the shipped issuer cannot sign usable keys",
);

// 3. The gate wraps the whole manager shell.
assert.match(activity, /CardKeyGate\(/, "MainActivity must wrap its content in CardKeyGate");
assert.match(activity, /onContentReady = \{ contentReady = true \}/, "the gate must release the splash screen");
assert.doesNotMatch(activity, /NightActivation/, "the old Night gate must be gone");

// 4. Branding.
assert.match(manifest, /android:label="@string\/app_name"/, "app label comes from the build config");
assert.doesNotMatch(screen, /Pigeon|pigeon/, "the gate must not carry the upstream platform branding");

console.log("XhovoSU card-key gate contract holds: offline verification, matching keys, gate wired");
