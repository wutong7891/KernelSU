#!/system/bin/sh
# XhovoSU 前台应用监听守护进程
# 双路检测：logcat 系统事件（实时钩子）+ dumpsys 轮询（兜底）
# 放置在 /data/local/xhovosu_fgwatch，不受 KernelSU 卸载影响

ROOT_DIR=/data/local/xhovosu_fgwatch
CONFIG_DIR="$ROOT_DIR/config"
LOG_FILE="$ROOT_DIR/events.log"
PID_FILE="$ROOT_DIR/watcher.pid"
LOCK_DIR="$ROOT_DIR/watcher.lock"
EVENT_FILE="$ROOT_DIR/.am_event"
LOGCAT_PID_FILE="$ROOT_DIR/.logcat.pid"

mkdir -p "$CONFIG_DIR"

pid_is_watcher() {
  candidate="$1"
  [ -n "$candidate" ] && [ -r "/proc/$candidate/cmdline" ] || return 1
  tr '\000' ' ' < "/proc/$candidate/cmdline" 2>/dev/null | grep -F "$ROOT_DIR/watcher.sh" >/dev/null 2>&1
}

if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  owner=$(cat "$LOCK_DIR/pid" 2>/dev/null)
  if pid_is_watcher "$owner" && kill -0 "$owner" 2>/dev/null; then
    exit 0
  fi
  rm -f "$LOCK_DIR/pid"
  rmdir "$LOCK_DIR" 2>/dev/null || exit 1
  mkdir "$LOCK_DIR" 2>/dev/null || exit 1
fi

printf '%s\n' "$$" > "$LOCK_DIR/pid"
printf '%s\n' "$$" > "$PID_FILE"
trap '' HUP
cleanup() {
  owner=$(cat "$PID_FILE" 2>/dev/null)
  [ "$owner" = "$$" ] && rm -f "$PID_FILE"
  owner=$(cat "$LOCK_DIR/pid" 2>/dev/null)
  if [ "$owner" = "$$" ]; then
    rm -f "$LOCK_DIR/pid"
    rmdir "$LOCK_DIR" 2>/dev/null
  fi
  lp=$(cat "$LOGCAT_PID_FILE" 2>/dev/null)
  [ -n "$lp" ] && kill "$lp" 2>/dev/null
  rm -f "$LOGCAT_PID_FILE" "$EVENT_FILE"
}
trap cleanup EXIT
trap 'exit 0' INT TERM

# ---------- 系统事件钩子：监听 ActivityManager 的 resume 事件 ----------
# wm_on_resume_called 在每次 Activity 恢复到前台时由系统发出，
# 比轮询 dumpsys 及时得多，也不用反复拉起 dumpsys 进程。
start_hook() {
  lp=$(cat "$LOGCAT_PID_FILE" 2>/dev/null)
  if [ -n "$lp" ] && kill -0 "$lp" 2>/dev/null; then
    return 0
  fi
  : > "$EVENT_FILE"
  (
    logcat -b events -s wm_on_resume_called:* 2>/dev/null \
      | while IFS= read -r line; do
          pkg=$(printf '%s' "$line" | sed -n 's/.*\[[0-9]*,\([A-Za-z0-9._]*\)\/.*/\1/p')
          if [ -n "$pkg" ]; then
            printf '%s\n' "$pkg" > "$EVENT_FILE"
          fi
        done
  ) &
  printf '%s\n' "$!" > "$LOGCAT_PID_FILE"
}

stop_hook() {
  lp=$(cat "$LOGCAT_PID_FILE" 2>/dev/null)
  [ -n "$lp" ] && kill "$lp" 2>/dev/null
  rm -f "$LOGCAT_PID_FILE"
}

# ---------- 兜底：dumpsys 轮询 ----------
foreground_package() {
  pkg=$(dumpsys activity activities 2>/dev/null \
    | sed -n -e '/mResumedActivity/s/.*[[:space:]]u[0-9][0-9]*[[:space:]]\([A-Za-z0-9._]*\)\/.*/\1/p' \
             -e '/topResumedActivity/s/.*[[:space:]]u[0-9][0-9]*[[:space:]]\([A-Za-z0-9._]*\)\/.*/\1/p' \
    | head -n 1)
  if [ -z "$pkg" ]; then
    pkg=$(dumpsys window windows 2>/dev/null \
      | sed -n -e '/mCurrentFocus=/s/.*[[:space:]]\([A-Za-z0-9._]*\)\/[^[:space:]]*.*/\1/p' \
               -e '/mFocusedApp=/s/.*[[:space:]]u[0-9][0-9]*[[:space:]]\([A-Za-z0-9._]*\)\/.*/\1/p' \
      | head -n 1)
  fi
  printf '%s' "$pkg"
}

append_log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1" >> "$LOG_FILE"
  size=$(wc -c < "$LOG_FILE" 2>/dev/null)
  if [ "${size:-0}" -gt 262144 ]; then
    tail -n 200 "$LOG_FILE" > "$LOG_FILE.tmp"
    mv -f "$LOG_FILE.tmp" "$LOG_FILE"
  fi
}

run_action() {
  target="$1"
  append_log "App entered foreground: $target"
  if [ -f "$ROOT_DIR/selected_script.sh" ]; then
    export KSU_TRIGGER_TYPE=app_foreground
    export KSU_TRIGGER_PACKAGE="$target"
    (cd / && /system/bin/sh "$ROOT_DIR/selected_script.sh" < "$CONFIG_DIR/pre_input") >> "$LOG_FILE" 2>&1
    append_log "Script finished, exit=$?"
  else
    append_log "Selected script is missing"
  fi
}

append_log "Detached watcher started, pid=$$"
start_hook
append_log "AM event hook active"

last_foreground=
tick=0

while true; do
  target=$(cat "$CONFIG_DIR/package" 2>/dev/null)

  # 钩子进程挂了就重启它
  tick=$((tick + 1))
  if [ "$tick" -ge 30 ]; then
    tick=0
    start_hook
  fi

  [ -n "$target" ] || { sleep 1; continue; }

  current=$(cat "$EVENT_FILE" 2>/dev/null)
  rm -f "$EVENT_FILE"

  # 事件没来就每 2 秒轮询一次兜底
  if [ -z "$current" ]; then
    sleep 2
    current=$(foreground_package)
  else
    sleep 0.3
    polled=$(foreground_package)
    [ -n "$polled" ] && current="$polled"
  fi

  if [ "$current" = "$target" ] && [ "$last_foreground" != "$target" ]; then
    run_action "$target"
  fi
  last_foreground="$current"
done
