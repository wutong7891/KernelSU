#!/system/bin/sh
set -eu

CONFIG_DIR="/data/adb/tricky_store"
MODULE_DIR="/data/adb/modules/tricky_store"

[ -d "$MODULE_DIR" ] || {
    echo "AlwaysStrong 模块尚未安装: $MODULE_DIR" >&2
    exit 51
}

mkdir -p "$CONFIG_DIR"

# AlwaysStrong 的四个开关以 no_* 文件是否存在表示状态：文件不存在即开启。
rm -f \
    "$CONFIG_DIR/no_auto_fp" \
    "$CONFIG_DIR/no_auto_keybox" \
    "$CONFIG_DIR/no_auto_indicator" \
    "$CONFIG_DIR/no_rom_spoof_block"

# 图片中的自定义 Keybox 开关为关闭。
rm -f "$CONFIG_DIR/custom_keybox"

# 图片中的检查间隔为 5 分钟；模块内部单位为秒。
printf '%s\n' '300' > "$CONFIG_DIR/hourly_interval_sec"
chown 0:0 "$CONFIG_DIR/hourly_interval_sec" 2>/dev/null || true
chmod 0600 "$CONFIG_DIR/hourly_interval_sec"
command -v restorecon >/dev/null 2>&1 && restorecon -RF "$CONFIG_DIR" 2>/dev/null || true
sync
echo "AlwaysStrong 已配置：指纹、Keybox、状态指示、屏蔽 ROM 伪装均开启，间隔 5 分钟，自定义 Keybox 关闭"

