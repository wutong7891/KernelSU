#!/system/bin/sh

# Enable Night's calculator AccessibilityService without replacing any
# accessibility services that are already enabled on the device. The first
# explicit root run also installs this script as a KernelSU/Magisk boot script.
# Run once from a root shell, for example:
#   su -c sh /sdcard/enable-night-accessibility.sh

SERVICE='com.Night.night/me.weishu.kernelsu.ui.calculator.CalculatorAccessibilityService'
BOOT_SCRIPT='/data/adb/service.d/99-night-accessibility.sh'

if [ "$(id -u)" != '0' ]; then
    echo '错误：请使用 Root 执行此脚本。' >&2
    exit 1
fi

case "$0" in
    /data/adb/service.d/*)
        # Wait for Android's settings provider and the foreground user.
        sleep 15
        ;;
    *)
        mkdir -p /data/adb/service.d || exit 5
        cp -f "$0" "$BOOT_SCRIPT" || exit 5
        chmod 0700 "$BOOT_SCRIPT" || exit 5
        ;;
esac

USER_ID="$(cmd activity get-current-user 2>/dev/null)"
case "$USER_ID" in
    ''|*[!0-9]*) USER_ID="$(am get-current-user 2>/dev/null)" ;;
esac
case "$USER_ID" in
    ''|*[!0-9]*) USER_ID=0 ;;
esac

if ! pm path com.Night.night >/dev/null 2>&1; then
    echo '错误：没有检测到 Night（com.Night.night）。' >&2
    exit 2
fi

CURRENT="$(settings --user "$USER_ID" get secure enabled_accessibility_services 2>/dev/null)"
[ "$CURRENT" = 'null' ] && CURRENT=''

case ":$CURRENT:" in
    *":$SERVICE:"*) NEXT="$CURRENT" ;;
    '') NEXT="$SERVICE" ;;
    *) NEXT="$CURRENT:$SERVICE" ;;
esac

settings --user "$USER_ID" put secure enabled_accessibility_services "$NEXT"
settings --user "$USER_ID" put secure accessibility_enabled 1

SAVED="$(settings --user "$USER_ID" get secure enabled_accessibility_services 2>/dev/null)"
ENABLED="$(settings --user "$USER_ID" get secure accessibility_enabled 2>/dev/null)"

case ":$SAVED:" in
    *":$SERVICE:") : ;;
    *":$SERVICE:"*) : ;;
    *)
        echo '失败：系统拒绝写入无障碍服务列表。' >&2
        exit 3
        ;;
esac

if [ "$ENABLED" != '1' ]; then
    echo '失败：系统无障碍总开关未开启。' >&2
    exit 4
fi

echo "成功：已为用户 $USER_ID 开启 Night 计算器无障碍监听。"
echo '原有无障碍服务已保留。'
echo "已安装开机自动开启脚本：$BOOT_SCRIPT"

