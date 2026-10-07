#!/system/bin/sh

SERVICE='com.Night.night/me.weishu.kernelsu.ui.calculator.CalculatorAccessibilityService'
PACKAGE='com.Night.night'
MARKER='/data/adb/night_accessibility_watchdog.enabled'
PIDFILE='/data/adb/night_accessibility_watchdog.pid'
LOCKDIR='/data/adb/night_accessibility_watchdog.lock'
SCRIPT='/data/adb/service.d/99-night-accessibility-watchdog.sh'
PROCESS="$PACKAGE:night_accessibility"
BOOTSTRAP_ACTION='com.Night.night.action.START_ACCESSIBILITY_KEEPALIVE'
BOOTSTRAP_RECEIVER='com.Night.night/me.weishu.kernelsu.ui.calculator.CalculatorAccessibilityBootstrapReceiver'
KEEPALIVE_SERVICE='com.Night.night/me.weishu.kernelsu.ui.calculator.CalculatorAccessibilityKeepAliveService'

contains_service() {
    case ":$1:" in
        *":$SERVICE:"*) return 0 ;;
        *) return 1 ;;
    esac
}

without_night_service() {
    value="$1"
    filtered=''
    old_ifs="$IFS"
    IFS=':'
    for item in $value; do
        [ -z "$item" ] && continue
        [ "$item" = "$SERVICE" ] && continue
        if [ -z "$filtered" ]; then
            filtered="$item"
        else
            filtered="$filtered:$item"
        fi
    done
    IFS="$old_ifs"
    printf '%s' "$filtered"
}

accessibility_process_alive() {
    pidof "$PROCESS" >/dev/null 2>&1 && return 0
    ps -A -o ARGS 2>/dev/null | /system/bin/toybox grep -F "$PROCESS" | /system/bin/toybox grep -Fv grep >/dev/null 2>&1
}

enable_service() {
    user_id="$1"
    current="$(settings --user "$user_id" get secure enabled_accessibility_services 2>/dev/null)"
    [ "$current" = 'null' ] && current=''
    if contains_service "$current"; then
        next="$current"
    elif [ -z "$current" ]; then
        next="$SERVICE"
    else
        next="$current:$SERVICE"
    fi
    if [ "$next" != "$current" ]; then
        settings --user "$user_id" put secure enabled_accessibility_services "$next"
    fi
    # Do not rewrite the secure setting on every poll.  ColorOS emits a
    # settings event for every write, and a tight loop here can flood the
    # system server and make other Night pages (notably Superuser) stall.
    enabled_state="$(settings --user "$user_id" get secure accessibility_enabled 2>/dev/null)"
    [ "$enabled_state" = '1' ] || settings --user "$user_id" put secure accessibility_enabled 1
}

rebind_service() {
    user_id="$1"
    current="$(settings --user "$user_id" get secure enabled_accessibility_services 2>/dev/null)"
    [ "$current" = 'null' ] && current=''
    filtered="$(without_night_service "$current")"
    if [ -n "$filtered" ]; then
        settings --user "$user_id" put secure enabled_accessibility_services "$filtered"
    else
        settings --user "$user_id" delete secure enabled_accessibility_services >/dev/null 2>&1
    fi
    sleep 1
    if [ -n "$filtered" ]; then
        settings --user "$user_id" put secure enabled_accessibility_services "$filtered:$SERVICE"
    else
        settings --user "$user_id" put secure enabled_accessibility_services "$SERVICE"
    fi
    settings --user "$user_id" put secure accessibility_enabled 1
}

start_keepalive_process() {
    user_id="$1"
    cmd package set-stopped-state --user "$user_id" "$PACKAGE" false >/dev/null 2>&1 || true
    am start-foreground-service --user "$user_id" -n "$KEEPALIVE_SERVICE" >/dev/null 2>&1 || \
        am broadcast --user "$user_id" --include-stopped-packages \
            -a "$BOOTSTRAP_ACTION" -n "$BOOTSTRAP_RECEIVER" >/dev/null 2>&1 || true
}

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

# Only one detached watchdog may exist.  A process-level flag is not enough:
# the app and its accessibility process can both configure the watchdog at
# the same time.  mkdir is atomic on Android and works without an extra tool.
if [ -d "$LOCKDIR" ]; then
    old_pid="$(cat "$LOCKDIR/pid" 2>/dev/null)"
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
        exit 0
    fi
    rmdir "$LOCKDIR" 2>/dev/null || rm -rf "$LOCKDIR"
fi
mkdir "$LOCKDIR" 2>/dev/null || exit 0
echo $$ > "$LOCKDIR/pid"
echo $$ > "$PIDFILE"
trap 'rmdir "$LOCKDIR" 2>/dev/null; rm -f "$PIDFILE"' EXIT INT TERM

# A daemon forked by an app-owned root shell can inherit the app's freezer/cpu
# cgroups.  ColorOS freezes or kills those groups when the recent task is
# cleared, even though this process runs as uid 0.  Move the detached watchdog
# into system/thawed groups before entering the loop so it remains independent
# of the Night application lifecycle.
for tasks_file in \
    /sys/fs/cgroup/system/cgroup.procs \
    /dev/freezer/thaw/tasks \
    /dev/cpuset/system-background/tasks \
    /dev/cpuctl/background/tasks; do
    [ -w "$tasks_file" ] && echo $$ > "$tasks_file" 2>/dev/null
done

# Keep the watchdog cheap and avoid it being selected before ordinary apps
# under memory pressure.
renice 10 $$ >/dev/null 2>&1 || true
echo -900 > "/proc/$$/oom_score_adj" 2>/dev/null || true

while [ -f "$MARKER" ]; do
    if ! pm path "$PACKAGE" >/dev/null 2>&1; then
        rm -f "$MARKER"
        break
    fi

    user_id="$(cmd activity get-current-user 2>/dev/null)"
    case "$user_id" in
        ''|*[!0-9]*) user_id=0 ;;
    esac

    dumpsys deviceidle whitelist +"$PACKAGE" >/dev/null 2>&1
    cmd appops set --user "$user_id" "$PACKAGE" RUN_IN_BACKGROUND allow >/dev/null 2>&1
    cmd appops set --user "$user_id" "$PACKAGE" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
    cmd activity set-standby-bucket "$PACKAGE" active >/dev/null 2>&1

    enable_service "$user_id"
    if ! accessibility_process_alive; then
        start_keepalive_process "$user_id"
        sleep 1
        rebind_service "$user_id"
    fi
    sleep 3
done
