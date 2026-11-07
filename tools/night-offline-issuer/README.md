# Night 离线卡密签发工具

Night 卡密不再经过任何服务器。签发器用 ECDSA P-256 私钥给卡密签名，Night 安装包内置对应公钥，安装后在本机验签。没有账号、没有接口、没有可被下线的后端。

## 卡密格式

一张卡密是 76 字节的二进制令牌，用 Crockford Base32 编码，展示时按 5 字符分组：

```
NIGHT-0807J-QW1DC-BBGRR-0000C-Q015Q-9JF47-…
```

| 偏移 | 长度 | 含义 |
|------|------|------|
| 0 | 1 | 格式版本，固定 `0x02` |
| 1 | 1 | 标志位：bit0 绑定设备，bit1 永久有效 |
| 2 | 3 | 流水号，大端 |
| 5 | 4 | 到期时间，大端 Unix 秒；永久卡为 0 |
| 9 | 3 | 设备标签：设备指纹（SHA-256 十六进制）的前 3 字节 |
| 12 | 64 | 签名：SHA-256 ECDSA 覆盖前 12 字节，`r || s` 各 32 字节 |

解析时前缀、大小写与分隔符都会被忽略，所以卡密可以被折行、加空格或重新分组而仍然有效。签名只覆盖前 12 字节，因此任何对载荷的修改都会导致验签失败。

## 生成密钥对

```bash
node tools/night-offline-issuer/keygen.mjs --out night-license-keys
```

输出：

- `night_license_private_key.pk8`：签发私钥，只交给签发器或存成 GitHub secret `NIGHT_LICENSE_PRIVATE_KEY_B64`
- 公钥 fingerprint 与 base64：写入 `NIGHT_LICENSE_PUBKEY_DER_B64`，CI 会用它覆盖 Manager 源码里的公钥常量

仓库里已经带了一对**公开的开发密钥**（`dev/`），方便开箱测试。任何人都能拿它伪造卡密，正式发布前必须换成自己生成的密钥对。

## 签发

```bash
# 30 天通用卡，一次签 5 张
node tools/night-offline-issuer/issue.mjs --key night_license_private_key.pk8 --days 30 --count 5

# 永久卡
node tools/night-offline-issuer/issue.mjs --key night_license_private_key.pk8 --days 0

# 绑定设备：--device 传 Night 里显示的 64 位设备指纹
node tools/night-offline-issuer/issue.mjs --key night_license_private_key.pk8 --days 0 \
  --device 8f14e45fceea167a5a36dedd4bea2543a1b2c3d4e5f60718293a4b5c6d7e8f90
```

也可以在手机上用签名器 APK（`manager/activation-issuer`）签发：填设备指纹、有效天数与数量，直接得到可复制的卡密。

## 验签

```bash
node tools/night-offline-issuer/verify.mjs --pub dev/dev_public_key.der --code "NIGHT-…"
node tools/night-offline-issuer/verify.mjs --pub dev/dev_public_key.der --code "NIGHT-…" --device <指纹>
```

## 自测

```bash
node tools/night-offline-issuer/test.mjs
```

覆盖 Base32 往返、载荷布局、绑定/通用、到期拒绝、单字符篡改拒绝、截断拒绝、异密钥拒绝，以及 DER 与定宽签名的互转一致性。

## 与旧系统的区别

| 项目 | 旧卡密 | 新卡密 |
|------|--------|--------|
| 校验位置 | 远程服务器（session + AES-GCM 密封包） | 本机验签 |
| 服务器 | `license-server`（Node）与 Cloudflare Worker | 无 |
| 断网 | 不可用 | 完全可用 |
| 泄露风险 | 服务器密钥、内存密钥、数据库 | 只有签发私钥，在签发端 |
| 卡密长度 | 25 字符 | 152 字符（含分组） |
| 解绑 | 后台接口 | 重新签发一张卡 |
