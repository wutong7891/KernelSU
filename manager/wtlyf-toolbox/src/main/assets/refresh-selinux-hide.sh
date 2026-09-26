#!/system/bin/sh
set -eu

KSUD="/data/adb/ksud"
[ -x "$KSUD" ] || {
    echo "找不到 KernelSU 命令: $KSUD" >&2
    exit 61
}

echo "正在关闭隐藏 SELinux 修改…"
if ! "$KSUD" feature set selinux_hide 0; then
    echo "关闭隐藏 SELinux 修改时返回警告，继续刷新状态"
fi

sleep 1

echo "正在重新开启隐藏 SELinux 修改…"
if ! "$KSUD" feature set selinux_hide 1; then
    echo "内核要求重启后启用隐藏 SELinux 修改，继续保存开启状态"
fi

"$KSUD" feature save
"$KSUD" feature get selinux_hide 2>/dev/null || true
sync
echo "隐藏 SELinux 修改已重新开启并保存"

