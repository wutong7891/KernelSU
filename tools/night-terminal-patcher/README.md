# Night Terminal Patcher v3

命令行修补工具，支持未压缩的 Android `boot.img` 和 `init_boot.img`。工具只生成新镜像，不连接设备、不自动刷写，也不会覆盖输入镜像。

## Android / Termux

将整个目录解压到 Termux 的 `$HOME`（不要直接放在 `/sdcard` 执行二进制）：

```sh
chmod 700 night-patch.sh
sh night-patch.sh list
sh night-patch.sh patch init_boot.img android14-6.1
sh night-patch.sh restore init_boot_Night_patched.img
```

## Windows 终端 EXE

```powershell
.\NightPatcher.exe list
.\NightPatcher.exe patch .\boot.img android14-6.1
.\NightPatcher.exe patch .\init_boot.img android14-6.1
.\NightPatcher.exe restore .\init_boot_Night_patched.img
```

也可以把输出路径作为最后一个参数：

```powershell
.\NightPatcher.exe patch .\init_boot.img android14-6.1 .\out\init_boot.img
```

`NightPatcher.exe` 是 GitHub Actions 构建的 Windows x64 独立终端程序，不要求预装 .NET。工具包同时保留 `night-patch.cmd` / `night-patch.ps1` 作为备用入口。

必须选择与设备 Android 和内核版本匹配的 KMI。刷写前请自行备份原始镜像；工具不会执行 `fastboot flash`。
