// Wiring contract for the XhovoSU networked card-key gate.
//
// Guards three things:
//   1. the Manager verifies against the server (not a local secret)
//   2. the gate still wraps the whole manager shell
//   3. the endpoint path matches what cardkey-server exposes

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "../..");
const read = (path) => readFileSync(resolve(repo, path), "utf8");

const api = read("manager/app/src/main/java/me/weishu/kernelsu/ui/screen/cardkey/CardKeyApi.kt");
const screen = read("manager/app/src/main/java/me/weishu/kernelsu/ui/screen/cardkey/CardKeyScreen.kt");
const activity = read("manager/app/src/main/java/me/weishu/kernelsu/ui/MainActivity.kt");
const manifest = read("manager/app/src/main/AndroidManifest.xml");

// 1. Verification is networked and points at the expected endpoint.
assert.match(api, /okhttp3|OkHttpClient/, "verification must use HTTP");
assert.match(api, /\/api\/license\/verify/, "must call the verify endpoint");
assert.match(api, /deviceId/, "must send the device id for one-device binding");
assert.doesNotMatch(api, /HmacSHA256/, "the local HMAC path must be gone");
assert.doesNotMatch(api, /CARDKEY_SECRET|SECRET_BASE64/, "no shared secret in the client anymore");

// 2. The gate wraps the whole manager shell and releases the splash.
assert.match(activity, /CardKeyGate\(/, "MainActivity must wrap its content in CardKeyGate");
assert.match(activity, /onContentReady = \{ contentReady = true \}/, "the gate must release the splash screen");
assert.doesNotMatch(activity, /NightActivation/, "the old Night gate must be gone");

// 3. Branding and UI wiring.
assert.match(manifest, /android:label="@string\/app_name"/, "app label comes from the build config");
assert.match(screen, /CardKeyApi\.apiBase\(\)/, "the gate shows the verification server");
assert.doesNotMatch(screen, /Pigeon|pigeon/, "the gate must not carry upstream platform branding");

console.log("XhovoSU networked card-key contract holds: server verification, gate wired");
