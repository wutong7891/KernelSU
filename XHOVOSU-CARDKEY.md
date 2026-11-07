# XhovoSU 卡密系统（离线）

XhovoSU 的卡密不经过任何服务器。签发端用 ECDSA P-256 私钥给卡密签名，安装包内置公钥，运行时在本机验签：没有账号、没有接口、没有可被下线的后端，断网也能用。

## 品牌与构建参数

| 项目 | 值 |
|------|-----|
| 应用名 | XhovoSU |
| 版本 | 1.0（versionCode 1） |
| 包名 | `com.HxovoSU.yydsHx` |
| 图标 | 由 `drawable-nodpi/ic_launcher_sad*.png` 生成的自适应图标与启动图 |

包名同时写进内核模块：`KernelSU` 的内核侧通过 `KSU_PACKAGE_NAME` 找管理器，ksud 通过同一常量默认拉起管理器，三处必须一致，否则装上了也认不出面具。

## 组成

| 路径 | 作用 |
|------|------|
| `manager/app/src/main/java/me/weishu/kernelsu/ui/screen/cardkey/CardKeyApi.kt` | 验签引擎：解析、验签、绑定与到期检查 |
| `.../CardKeyKeys.kt` | 内置验签公钥 |
| `.../CardKeyStore.kt` | 本地记忆卡密与时间戳 |
| `.../CardKeyScreen.kt` | 全屏卡密页与 `CardKeyGate` 组合闸门 |
| `tools/xhovosu-cardkey/` | 密钥生成、批量签发、验签、格式与接线自测 |
| `manager/activation-issuer/` | 手机端签发器（无网络权限） |

## 闸门机制

`CardKeyGate` 包在 `KernelSUTheme` 之内、管理器外壳之外：

```kotlin
KernelSUTheme(...) {
    CardKeyGate(darkMode = darkMode, onContentReady = { contentReady = true }) {
        // 未通过验证时，这里整块都不会进入组合
    }
}
```

未通过时，管理器的 ViewModel、导航栈、各个页面都不会被创建，因此不存在「跳过某页直接进主页」的路径。`passed` 只存在 `rememberSaveable` 里，冷启动必然重新验签；磁盘上只有「记住的卡密」与上次校验时间，改它们只会让校验失败。

## 卡密格式

76 字节令牌，Crockford Base32，`XHOVO-` 前缀，展示时每 5 字符分组：

```
XHOVO-080A2-E8EDC-BBTCG-00005-NT6VE-QT80T-…
```

| 偏移 | 长度 | 含义 |
|------|------|------|
| 0 | 1 | 版本 `0x02` |
| 1 | 1 | bit0 绑定设备，bit1 永久有效 |
| 2 | 3 | 流水号 |
| 5 | 4 | 到期时间（Unix 秒，永久卡为 0） |
| 9 | 3 | 设备指纹前 3 字节 |
| 12 | 64 | ECDSA 签名（覆盖前 12 字节，`r || s`） |

前缀、分隔符、大小写都不参与校验，所以卡密可以折行粘贴。设备指纹 = `SHA-256("xhovo-device-v1:" + ANDROID_ID)`，卡密页可以直接点按复制，交给签发端即可发绑定卡。

## 发卡

```bash
# 30 天通用卡，一次 5 张
node tools/xhovosu-cardkey/issue.mjs --key tools/xhovosu-cardkey/dev/dev_license_private_key.pk8 --days 30 --count 5

# 永久卡
node tools/xhovosu-cardkey/issue.mjs --key <私钥> --days 0

# 绑定某台设备（--device 填卡密页显示的 64 位指纹）
node tools/xhovosu-cardkey/issue.mjs --key <私钥> --days 0 --device <指纹>
```

也可以装 `activation-issuer` APK 在手机上发卡：填设备指纹、天数、数量，直接出卡密。

## 换成自己的密钥

仓库自带一对公开的开发密钥（`tools/xhovosu-cardkey/dev/`），开箱可用，但私钥公开意味着谁都能伪造卡密。正式发版前：

```bash
node tools/xhovosu-cardkey/keygen.mjs --out xhovo-keys
```

把私钥 base64 配到 `XHOVO_LICENSE_PRIVATE_KEY_B64`、公钥 base64 DER 配到 `XHOVO_LICENSE_PUBKEY_DER_B64`，CI 会覆盖源码里的公钥常量并写入签发器资源。卡密页底部显示公钥指纹，与签发器顶部一致才算配对成功。

## KMI 与签名绑定

内核模块把管理器包名与签名证书指纹写死，只有「包名正确 + 签名证书匹配」的 XhovoSU 才能驱动它。

| 参数 | 值 |
|------|-----|
| 包名 | `com.HxovoSU.yydsHx` |
| 证书 DER 大小 | `0x02e9`（745 字节） |
| 证书 SHA-256 | `0535626e3b585bb80a2cf564699b63807253c08efc949e6ddba1c46f8bf238df` |

签名密钥在 `xhovo-keys/`（仓库外，不要提交）：`xhovosu.p12` + 密码，别名 `xhovo`。Gradle 需要 JKS 时用：

```bash
keytool -importkeystore -noprompt \
  -srckeystore xhovosu.p12 -srcstoretype PKCS12 -srcstorepass xhovo2026 \
  -destkeystore xhovosu.jks -deststoretype JKS -deststorepass xhovo2026 \
  -srcalias xhovo -destalias xhovo -destkeypass xhovo2026
```

**换证书就必须重编 KMI**：证书哈希变了，旧模块会拒绝新的管理器。

## 与旧系统的差别

| 能力 | 旧（服务器校验） | 现在（离线签名） |
|------|------------------|------------------|
| 断网使用 | 不行 | 可以 |
| 一卡一机 | 服务器记录 | 卡密内嵌设备标签，本机比对 |
| 吊销已发出的卡 | 后台删除 | 无（离线固有代价），只能换密钥重发版本 |
| 服务器与密钥泄露面 | VPS、内存密钥、数据库 | 只有签发私钥，且不随包分发 |
