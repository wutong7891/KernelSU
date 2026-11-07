# Night License Server

Night 卡密系统的自托管服务端。替代原本跑在 Cloudflare Pages/Workers 上的 `wtlyf-pages`，协议与 `NightActivation.kt` 逐字节兼容。

## 组件

| 文件 | 作用 |
| --- | --- |
| `server.mjs` | 服务端本体。零依赖，Node 20+，`node:crypto` + JSON 原子持久化 |
| `test-e2e.mjs` | 端到端协议测试，模拟客户端完整三段式握手 |
| `install.sh` | 一键部署到 Ubuntu/Debian：Node、systemd、nginx、Let's Encrypt |

## 协议

客户端按顺序调用三个接口，任何一个环节对不上都会失败：

```
POST /api/v1/night/session/start   { code, deviceHash, action, clientNonce }
  <- { ok, sessionId, sealedPacket, iv, expiresAt }

POST /api/v1/night/session/key     { code, deviceHash, action, clientNonce, sessionId }
  <- 32 字节原始密钥（AES-256-GCM）

POST /api/v1/night/{activate|check} { ..., sessionId, sessionProof }
  <- { ok, message, expiresAt? }
```

密码学约束（改动任何一处都会让已发布客户端失效）：

- `nightCodeHash = sha256hex("night-license-v1:" + code)`
- 会话密钥 `= HMAC-SHA256(NIGHT_MEMORY_SECRET, "night-memory-key-v1:" + sessionId:codeHash:deviceHash:action:clientNonce)`
- `proof = sha256hex(packet || action || sessionId)`，其中 `packet` 是服务端 32 字节随机数的明文
- 会话 45 秒过期、密钥只能取一次、`sessionProof` 校验后一次性消费

## 管理接口

全部需要 `Authorization: Bearer $NIGHT_ADMIN_TOKEN`：

| 接口 | 说明 |
| --- | --- |
| `POST /api/admin/generate` | `{ count, code?, deviceLimit?, limitMode?, durationHours? }` → `{ ok, codes }` |
| `POST /api/admin/query` | `{ code }` → 状态、有效期、绑定设备数 |
| `POST /api/admin/unbind` | `{ code, deviceHash? }` 解绑全部或指定设备 |
| `POST /api/admin/delete` | `{ code }` 删除卡密及其绑定 |

## 部署

```bash
scp server.mjs install.sh root@YOUR_VPS:/tmp/
ssh root@YOUR_VPS 'bash /tmp/install.sh night.103.45.132.14.nip.io'
```

没有域名时 `install.sh` 会自动用 `<ip>.nip.io` 生成一个能签 Let's Encrypt 的主机名。

## 本地测试

```bash
NIGHT_ADMIN_TOKEN=test-admin-token NIGHT_DATA=./.test/night.json NIGHT_PORT=8080 node server.mjs &
NIGHT_ADMIN_TOKEN=test-admin-token node test-e2e.mjs
```

## 数据

`NIGHT_DATA` 指向单个 JSON 文件，结构：

```json
{ "licenses": {}, "devices": {}, "sessions": {} }
```

写入走临时文件 + `rename`，进程被杀不会写出半个文件。会话表每分钟清理过期项。
