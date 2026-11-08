#!/system/bin/sh
# ============================================================
# 仙鹤隐形模式
#
# 一次性完成三件事：
#   1. 把面具安装包固化到 /data/adb/XhovoSU-manager.apk
#   2. 把守护脚本放进 /data/local 并用 setsid 脱离启动
#   3. 静默卸载面具本体
#
# 之后守护进程一直盯着前台应用：
#   一旦用户打开你指定的那个应用，就从 /data/adb 静默装回面具并拉起，
#   装完继续盯着，下次再触发还会重新装。
#
# 用法：
#   sh xh_guard.sh arm <包名>     开启隐形模式
#   sh xh_guard.sh disarm         关闭隐形模式（停守护，不动面具）
#   sh xh_guard.sh status         查看状态
#   sh xh_guard.sh now            立刻手动恢复一次面具（调试用）
# ============================================================

PKG="${PKG:-com.HxovoSU.yydsHx}"
ROOT_DIR=/data/local/xh_guard
GUARD="$ROOT_DIR/guard.sh"
CONF="$ROOT_DIR/target"
APK_SRC=/data/adb/XhovoSU-manager.apk
LOG="$ROOT_DIR/guard.log"
PID_FILE="$ROOT_DIR/guard.pid"

log() {
  printf '[%s] %s\n' "$(date '+%m-%d %H:%M:%S')" "$1" >> "$LOG"
  [ $(wc -c < "$LOG" 2>/dev/null || echo 0) -gt 200000 ] && : > "$LOG"
}

say() {
  echo "$1"
  log "$1"
}

# ---------- 写守护脚本本体 ----------
write_guard() {
  mkdir -p "$ROOT_DIR" 2>/dev/null
  chmod 0755 "$ROOT_DIR" 2>/dev/null

  cat > "$GUARD" <<'GUARD_EOF'
#!/system/bin/sh
# 仙鹤隐形守护：盯前台应用，命中就静默装回面具
trap '' HUP

ROOT_DIR=/data/local/xh_guard
CONF="$ROOT_DIR/target"
APK=/data/adb/XhovoSU-manager.apk
LOG="$ROOT_DIR/guard.log"
PID_FILE="$ROOT_DIR/guard.pid"
PKG_FILE="$ROOT_DIR/pkg"

echo $$ > "$PID_FILE"
chmod 0644 "$PID_FILE" 2>/dev/null

mkdir -p "$ROOT_DIR" 2>/dev/null
touch "$LOG" 2>/dev/null

pkg_now() {
  cat "$PKG_FILE" 2>/dev/null
}

tlog() {
  printf '[%s] %s\n' "$(date '+%m-%d %H:%M:%S')" "$1" >> "$LOG"
  [ $(wc -c < "$LOG" 2>/dev/null || echo 0) -gt 200000 ] && : > "$LOG"
}

# 当前前台包名：优先系统事件，退到 dumpsys
foreground() {
  local out
  out=$(dumpsys activity activities 2>/dev/null \
    | grep -m1 -E 'mResumedActivity|topResumedActivity' \
    | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.]+' \
    | head -1 | cut -d/ -f1)
  if [ -z "$out" ]; then
    out=$(dumpsys window 2>/dev/null \
      | grep -m1 -E 'mCurrentFocus|mFocusedApp' \
      | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.]+' \
      | head -1 | cut -d/ -f1)
  fi
  echo "$out"
}

# 恢复面具：装回去再拉起
revive() {
  local pkg
  pkg=$(pkg_now)
  [ -z "$pkg" ] && pkg=com.HxovoSU.yydsHx

  if [ ! -f "$APK" ]; then
    tlog "缺少安装包 $APK，跳过"
    return 1
  fi

  if pm list packages 2>/dev/null | grep -q "^package:$pkg$"; then
    tlog "面具已在，直接拉起"
  else
    tlog "静默安装面具"
    pm install -r -d --user 0 "$APK" >> "$LOG" 2>&1
    sleep 1
  fi

  tlog "拉起面具"
  monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >> "$LOG" 2>&1 \
    || am start -n "$pkg/me.weishu.kernelsu.ui.MainActivity" >> "$LOG" 2>&1
}

target=$(cat "$CONF" 2>/dev/null)
[ -z "$target" ] && { tlog "没有配置目标应用，退出"; exit 0; }

tlog "守护启动 pid=$$ 目标=$target"

last=""
while true; do
  now=$(foreground)

  if [ -n "$now" ] && [ "$now" = "$target" ] && [ "$now" != "$last" ]; then
    tlog "命中目标应用: $now"
    revive
  fi

  last="$now"
  sleep 2
done
GUARD_EOF

  chmod 0755 "$GUARD"
}

# ---------- 落地安装包 ----------
place_apk() {
  if [ ! -f "$APK_SRC" ]; then
    src=$(pm path "$PKG" 2>/dev/null | head -1 | sed 's/^package://')
    [ -z "$src" ] && src=$(find /data/app -name base.apk -path "*$PKG*" 2>/dev/null | head -1)
    if [ -n "$src" ] && [ -f "$src" ]; then
      mkdir -p /data/adb 2>/dev/null
      cp -f "$src" "$APK_SRC" 2>/dev/null
      chmod 0644 "$APK_SRC" 2>/dev/null
    fi
  fi

  if [ -f "$APK_SRC" ]; then
    sz=$(stat -c %s "$APK_SRC" 2>/dev/null || wc -c < "$APK_SRC")
    say "安装包已固化: $APK_SRC ($sz 字节)"
    return 0
  fi

  say "找不到安装包，无法开启隐形模式"
  return 1
}

# ---------- 启动守护 ----------
start_guard() {
  if [ -f "$PID_FILE" ]; then
    old=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$old" ] && [ -r "/proc/$old/cmdline" ]; then
      kill "$old" 2>/dev/null
      sleep 1
    fi
    rm -f "$PID_FILE"
  fi

  if command -v setsid >/dev/null 2>&1; then
    nohup setsid /system/bin/sh "$GUARD" </dev/null >/dev/null 2>&1 &
  else
    nohup /system/bin/sh "$GUARD" </dev/null >/dev/null 2>&1 &
  fi

  i=0
  while [ $i -lt 12 ]; do
    if [ -f "$PID_FILE" ]; then
      pid=$(cat "$PID_FILE" 2>/dev/null)
      if [ -n "$pid" ] && [ -r "/proc/$pid/cmdline" ]; then
        ppid=$(awk '{print $4}' "/proc/$pid/stat" 2>/dev/null)
        say "守护已启动 pid=$pid ppid=$ppid"
        return 0
      fi
    fi
    sleep 0.3
    i=$((i + 1))
  done

  say "守护启动失败"
  return 1
}

# ---------- 静默卸载面具 ----------
silent_remove() {
  if pm list packages 2>/dev/null | grep -q "^package:$PKG$"; then
    say "静默卸载面具 $PKG"
    pm uninstall --user 0 "$PKG" >> "$LOG" 2>&1
    sleep 1
    if pm list packages 2>/dev/null | grep -q "^package:$PKG$"; then
      pm uninstall "$PKG" >> "$LOG" 2>&1
      sleep 1
    fi
    if pm list packages 2>/dev/null | grep -q "^package:$PKG$"; then
      say "卸载失败，面具仍在"
      return 1
    fi
    say "面具已卸载"
  else
    say "面具本来就不在"
  fi
  return 0
}

# ---------- 状态 ----------
show_status() {
  say "==== 仙鹤隐形模式 ===="

  if [ -f "$CONF" ]; then
    say "目标应用    : $(cat "$CONF" 2>/dev/null)"
  else
    say "目标应用    : 未配置"
  fi

  if [ -f "$PID_FILE" ]; then
    pid=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$pid" ] && [ -r "/proc/$pid/cmdline" ]; then
      ppid=$(awk '{print $4}' "/proc/$pid/stat" 2>/dev/null)
      say "守护进程    : 运行中 pid=$pid ppid=$ppid"
    else
      say "守护进程    : 已停止"
    fi
  else
    say "守护进程    : 未启动"
  fi

  if [ -f "$APK_SRC" ]; then
    sz=$(stat -c %s "$APK_SRC" 2>/dev/null || wc -c < "$APK_SRC")
    say "安装包      : 存在 ($sz 字节)"
  else
    say "安装包      : 不存在"
  fi

  if pm list packages 2>/dev/null | grep -q "^package:$PKG$"; then
    say "面具状态    : 已安装"
  else
    say "面具状态    : 未安装（隐形中）"
  fi
}

case "$1" in
  arm)
    target="$2"
    if [ -z "$target" ]; then
      echo "缺少目标应用包名"
      exit 1
    fi

    mkdir -p "$ROOT_DIR" 2>/dev/null
    printf '%s\n' "$target" > "$CONF"
    printf '%s\n' "$PKG" > "$ROOT_DIR/pkg"

    place_apk || exit 1
    write_guard
    start_guard || exit 1
    silent_remove

    say "==== 隐形模式已开启 ===="
    say "打开 $target 即可唤回面具"
    ;;
  disarm)
    if [ -f "$PID_FILE" ]; then
      old=$(cat "$PID_FILE" 2>/dev/null)
      [ -n "$old" ] && kill "$old" 2>/dev/null
      rm -f "$PID_FILE"
    fi
    rm -f "$CONF"
    say "守护已停止"
    ;;
  now)
    revive_script="$GUARD"
    if [ -f "$GUARD" ]; then
      sh -c ". /dev/null; $(sed -n '/^revive()/,/^}/p' "$GUARD"); revive" 2>/dev/null
    fi
    if [ -f "$APK_SRC" ]; then
      pm install -r -d --user 0 "$APK_SRC" >> "$LOG" 2>&1
      say "已手动恢复面具"
    fi
    ;;
  status|"")
    show_status
    ;;
  *)
    echo "用法: sh xh_guard.sh [arm <包名>|disarm|status|now]"
    ;;
esac
