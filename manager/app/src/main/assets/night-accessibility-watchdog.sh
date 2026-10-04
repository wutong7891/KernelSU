#!/system/bin/sh

SERVICE='com.Night.night/me.weishu.kernelsu.ui.calculator.CalculatorAccessibilityService'
PACKAGE='com.Night.night'
MARKER='/data/adb/night_accessibility_watchdog.enabled'
PIDFILE='/data/adb/night_accessibility_watchdog.pid'
SCRIPT='/data/adb/service.d/99-night-accessibility-watchdog.sh'

if [ "$1" != '--daemon' ]; then
    [ -f "$MARKER" ] || exit 0
    if [ -f "$PIDFILE" ]; then
        old_pid="$(cat "$PIDFILE" 2>/dev/null)"
        if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
            if /system/bin/toybox tr '\000' ' ' < "/proc/$old_pid/cmdline" 2>/dev/null | /system/bin/toybox grep -Fq "$SCRIPT"; then
                exit 0
            fi
        fi
    fi
    /system/bin/toybox setsid /system/bin/sh "$SCRIPT" --daemon </dev/null >/dev/null 2>&1 &
    exit 0
fi

echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE"' EXIT INT TERM

while [ -f "$MARKER" ]; do
    if ! pm path "$PACKAGE" >/dev/null 2>&1; then
        rm -f "$MARKER"
        break
    fi

    user_id="$(cmd activity get-current-user 2>/dev/null)"
    case "$user_id" in
        ''|*[!0-9]*) user_id=0 ;;
    esac

    current="$(settings --user "$user_id" get secure enabled_accessibility_services 2>/dev/null)"
    [ "$current" = 'null' ] && current=''
    case ":$current:" in
        *":$SERVICE:"*) next="$current" ;;
        '') next="$SERVICE" ;;
        *) next="$current:$SERVICE" ;;
    esac

    if [ "$next" != "$current" ]; then
        settings --user "$user_id" put secure enabled_accessibility_services "$next"
    fi
    if [ "$(settings --user "$user_id" get secure accessibility_enabled 2>/dev/null)" != '1' ]; then
        settings --user "$user_id" put secure accessibility_enabled 1
    fi
    sleep 5
done
