#!/system/bin/sh

ROOT_DIR=/data/adb/night_foreground_watcher
CONFIG_DIR="$ROOT_DIR/config"
LOG_FILE="$ROOT_DIR/events.log"
PID_FILE="$ROOT_DIR/watcher.pid"
LOCK_DIR="$ROOT_DIR/watcher.lock"

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
}
trap cleanup EXIT
trap 'exit 0' INT TERM

foreground_package() {
  pkg=$(cmd activity get-foreground-activities 2>/dev/null \
    | sed -n 's/.*[[:space:]]u[0-9][0-9]*[[:space:]]\([A-Za-z0-9._]*\)\/.*/\1/p' \
    | head -n 1)
  if [ -z "$pkg" ]; then
    pkg=$(dumpsys activity activities 2>/dev/null \
      | sed -n -e '/mResumedActivity/s/.*[[:space:]]u[0-9][0-9]*[[:space:]]\([A-Za-z0-9._]*\)\/.*/\1/p' \
               -e '/topResumedActivity/s/.*[[:space:]]u[0-9][0-9]*[[:space:]]\([A-Za-z0-9._]*\)\/.*/\1/p' \
      | head -n 1)
  fi
  if [ -z "$pkg" ]; then
    pkg=$(dumpsys activity top 2>/dev/null \
      | sed -n 's/^[[:space:]]*ACTIVITY[[:space:]]\([A-Za-z0-9._]*\)\/.*/\1/p' \
      | head -n 1)
  fi
  if [ -z "$pkg" ]; then
    pkg=$(dumpsys window windows 2>/dev/null \
      | sed -n -e '/mCurrentFocus=/s/.*[[:space:]]\([A-Za-z0-9._]*\)\/[^[:space:]]*.*/\1/p' \
               -e '/mFocusedApp=/s/.*[[:space:]]u[0-9][0-9]*[[:space:]]\([A-Za-z0-9._]*\)\/.*/\1/p' \
               -e '/mFocusedWindow=/s/.*[[:space:]]\([A-Za-z0-9._]*\)\/[^[:space:]]*.*/\1/p' \
               -e '/mTopFullscreenOpaqueWindowState=/s/.*[[:space:]]\([A-Za-z0-9._]*\)\/[^[:space:]]*.*/\1/p' \
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

append_log "Detached watcher started, pid=$$"
export PATH="/data/adb/ksu/bin:/system/bin:/system/xbin:$PATH"
last_observed=
target_active=0
away_samples=0

while true; do
  target=$(tr -d '\r\n' < "$CONFIG_DIR/package" 2>/dev/null)
  [ -n "$target" ] || { sleep 0.5; continue; }
  current=$(foreground_package)
  if [ "$current" != "$last_observed" ]; then
    append_log "Foreground observed: ${current:-unknown}"
    last_observed="$current"
  fi
  if [ "$current" = "$target" ]; then
    away_samples=0
    if [ "$target_active" = "0" ]; then
      target_active=1
      append_log "App entered foreground: $target"
      if [ -f "$ROOT_DIR/selected_script.sh" ]; then
        export KSU_TRIGGER_TYPE=app_foreground
        export KSU_TRIGGER_PACKAGE="$target"
        (cd / && /system/bin/sh "$ROOT_DIR/selected_script.sh" < "$CONFIG_DIR/pre_input") >> "$LOG_FILE" 2>&1
        code=$?
        append_log "Script finished, exit=$code"
      else
        append_log "Selected script is missing"
      fi
    fi
  elif [ -n "$current" ] && [ "$target_active" = "1" ]; then
    away_samples=$((away_samples + 1))
    if [ "$away_samples" -ge 2 ]; then
      target_active=0
      away_samples=0
    fi
  fi
  sleep 0.5
done
