#!/system/bin/sh
set -eu

MANAGER_PACKAGE="com.Night.night"
NATIVE_DIR="$(dumpsys package "$MANAGER_PACKAGE" 2>/dev/null \
    | sed -n 's/^[[:space:]]*nativeLibraryDir=//p' \
    | head -n 1 \
    | tr -d '\r')"

KSUD="${NATIVE_DIR:+$NATIVE_DIR/libksud.so}"
if [ -z "$KSUD" ] || [ ! -x "$KSUD" ]; then
    KSUD="/data/adb/ksud"
fi

[ -x "$KSUD" ] || {
    echo "找不到 Night 内置的 KernelSU 命令，请确认已安装 com.Night.night" >&2
    exit 61
}

CURRENT_STATE="$("$KSUD" feature get selinux_hide 2>&1 || true)"
echo "$CURRENT_STATE"

if echo "$CURRENT_STATE" | grep -q 'Value:[[:space:]]*1'; then
    echo "隐藏 SELinux 修改已经开启，先关闭后重新开启…"
    if ! "$KSUD" feature set selinux_hide 0; then
        echo "关闭时返回警告，继续执行重新开启"
    fi
    sleep 1
else
    echo "隐藏 SELinux 修改尚未开启，正在开启…"
fi

if ! "$KSUD" feature set selinux_hide 1; then
    echo "开启命令提示需要重启，继续保存目标状态"
fi

"$KSUD" feature save
FINAL_STATE="$("$KSUD" feature get selinux_hide 2>&1 || true)"
echo "$FINAL_STATE"

if ! echo "$FINAL_STATE" | grep -q 'Value:[[:space:]]*1'; then
    echo "隐藏 SELinux 修改未能保持开启，请检查 Night 与内核功能支持" >&2
    exit 62
fi

sync
echo "隐藏 SELinux 修改已开启并保存"

