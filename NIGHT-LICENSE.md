# Night 卡密系统（离线）

Night 的卡密不再依赖服务器。旧系统让客户端连一台自建许可服务器（或 Cloudflare Worker），用一次性会话密钥换取授权；那套东西已经整体移除，替换为**离线签名卡密**：签发器持私钥签名，Night 安装包内置公钥，运行在本机验签。

## 现在的结构

| 组件 | 路径 | 职责 |
|------|------|------|
| 客户端校验 | `manager/app/src/main/java/me/weishu/kernelsu/ui/activation/NightActivation.kt` | 解析卡密、验签、检查绑定与到期、记录本地状态 |
| 内置公钥 | `manager/app/src/main/java/me/weishu/kernelsu/ui/activation/NightLicenseKeys.kt` | 唯一可信的验签公钥 |
| 手机签发器 | `manager/activation-issuer/` | 持私钥签发卡密，无网络权限 |
| 命令行签发器 | `tools/night-offline-issuer/` | 密钥生成、批量签发、验签、格式自测 |

被删除的部分：`license-server/`（Node 许可服务与其部署脚本）、`.github/workflows/night-license-server.yml`、客户端里的会话协议与全部 HTTP 调用、`tools/night-activation-issuer/`（旧的 RSA 桌面签发器）。

## 卡密长什么样

```
NIGHT-0807J-QW1DC-BBGRR-0000C-Q015Q-9JF47-…
```

152 个字符，76 字节令牌的 Base32 编码，按 5 字符分组。它比旧卡密长，因为里面带着完整的 ECDSA P-256 签名——这是"离线、不可伪造"必须付出的长度代价。输入时前缀、分隔符、大小写都可以忽略。

详细格式见 `tools/night-offline-issuer/README.md`。

## 发卡流程

1. 生成自己的密钥对：`node tools/night-offline-issuer/keygen.mjs --out night-license-keys`
2. 把私钥的 base64 存为 GitHub secret `NIGHT_LICENSE_PRIVATE_KEY_B64`
3. 把公钥的 base64 DER 存为 GitHub secret `NIGHT_LICENSE_PUBKEY_DER_B64`
4. 跑 `Build Manager`（workflow_dispatch）或 `Build Night Offline Issuer`，CI 会把公钥写进 Manager 源码、把私钥写进签发器资源
5. 用签发器发卡：手机 APK，或 `node tools/night-offline-issuer/issue.mjs --key <私钥> --days 30`

两个 secret 必须来自同一对密钥，否则卡密签得出来、验不过去。Night 激活页底部和签发器顶部都会显示公钥指纹（16 位十六进制），两处一致才算配对成功。

## 仓库自带的开发密钥

`tools/night-offline-issuer/dev/` 里有一对公开的开发密钥，用于本地测试和 CI 冒烟，所以 `node tools/night-offline-issuer/issue.mjs --key tools/night-offline-issuer/dev/dev_license_private_key.pk8` 开箱即可签出卡密。**它的私钥是公开的，任何人都能伪造对应卡密**；正式发布必须按上面的流程换成自己的密钥对。

## 能力对照

| 能力 | 旧系统 | 现在 |
|------|--------|------|
| 断网激活 | 不行 | 可以 |
| 一卡一机 | 服务器记录绑定 | 卡密内嵌设备标签，本机比对 |
| 有效期 | 服务器计时 | 签名载荷内嵌到期时间 |
| 永久卡 | 支持 | 支持 |
| 解绑换机 | 后台接口 | 重新签发一张卡 |
| 撤销已发出的卡 | 后台删除 | 离线模式下无撤销，只能换密钥对重发版本 |
| 多设备共用一张卡 | 服务器限制 | 通用卡不限制；绑定卡锁单机 |

撤销能力是离线的固有代价：没有服务器，就没有"让某张已发出的卡失效"的通道。如果确实需要远程吊销，只能保留一个极小的可选校验服务——但那正是本次移除的东西，因此默认不做。
