#!/system/bin/sh
# ============================================================
# 仙鹤隐形模式
#
#   sh xh_guard.sh arm <包名>     开启：固化安装包 → 注入守护 → 静默卸载面具
#   sh xh_guard.sh disarm         停止守护（不动面具）
#   sh xh_guard.sh revive         立刻手动装回面具（调试用）
#   sh xh_guard.sh probe          打印当前探测到的前台应用（调试用）
#   sh xh_guard.sh status         查看状态
# ============================================================

PKG="${PKG:-com.HxovoSU.yydsHx}"
ROOT_DIR=/data/local/xh_guard
GUARD="$ROOT_DIR/guard.sh"
CONF="$ROOT_DIR/target"
APK_SRC=/data/adb/XhovoSU-manager.apk
LOG="$ROOT_DIR/guard.log"
PID_FILE="$ROOT_DIR/guard.pid"

say() {
  echo "$1"
  printf '[%s] %s\n' "$(date '+%m-%d %H:%M:%S')" "$1" >> "$LOG" 2>/dev/null
}

# ---------- 写守护脚本本体 ----------
write_guard() {
  mkdir -p "$ROOT_DIR" 2>/dev/null
  chmod 0755 "$ROOT_DIR" 2>/dev/null

  cat > "$GUARD" <<'GUARD_EOF'
#!/system/bin/sh
trap '' HUP

ROOT_DIR=/data/local/xh_guard
CONF="$ROOT_DIR/target"
APK=/data/adb/XhovoSU-manager.apk
LOG="$ROOT_DIR/guard.log"
PID_FILE="$ROOT_DIR/guard.pid"
PKG_FILE="$ROOT_DIR/pkg"

echo $$ > "$PID_FILE"
chmod 0644 "$PID_FILE" 2>/dev/null

tlog() {
  printf '[%s] %s\n' "$(date '+%m-%d %H:%M:%S')" "$1" >> "$LOG"
  sz=$(wc -c < "$LOG" 2>/dev/null || echo 0)
  if [ "$sz" -gt 200000 ]; then
    tail -n 400 "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG" 2>/dev/null
  fi
}

# ---------- 三路探测前台应用 ----------
fg_activity() {
  dumpsys activity activities 2>/dev/null \
    | grep -m1 -E 'mResumedActivity|topResumedActivity|ResumedActivity' \
    | grep -oE '[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+/[a-zA-Z0-9_.$]+' \
    | head -1 | cut -d/ -f1
}

fg_window() {
  dumpsys window 2>/dev/null \
    | grep -m1 -E 'mCurrentFocus|mFocusedApp' \
    | grep -oE '[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+/[a-zA-Z0-9_.$]+' \
    | head -1 | cut -d/ -f1
}

fg_proc() {
  # 兜底：/proc 里 oom_score_adj == 0 的进程，取名字像包名的那个
  for d in /proc/[0-9]*; do
    adj=$(cat "$d/oom_score_adj" 2>/dev/null)
    [ "$adj" = "0" ] || continue
    name=$(cat "$d/cmdline" 2>/dev/null | tr '\0' ' ' | awk '{print $1}')
    case "$name" in
      *.*) [ -n "$name" ] && { echo "$name"; return; } ;;
    esac
  done
}

foreground() {
  f=$(fg_activity)
  [ -n "$f" ] && { echo "$f"; return; }
  f=$(fg_window)
  [ -n "$f" ] && { echo "$f"; return; }
  fg_proc
}

# ---------- 装回面具 ----------
revive() {
  pkg=$(cat "$PKG_FILE" 2>/dev/null)
  [ -z "$pkg" ] && pkg=com.HxovoSU.yydsHx

  if [ ! -f "$APK" ]; then
    tlog "FAIL 安装包不存在: $APK"
    return 1
  fi

  if pm list packages 2>/dev/null | grep -q "^package:$pkg$"; then
    tlog "面具已存在，只拉起"
  else
    tlog "开始静默安装 $APK"
    out=$(pm install -r -d -g --user 0 "$APK" 2>&1)
    tlog "install 输出: $out"
    sleep 1
    if ! pm list packages 2>/dev/null | grep -q "^package:$pkg$"; then
      tlog "FAIL 安装后仍不在包列表"
      return 1
    fi
    tlog "安装成功"
  fi

  tlog "拉起面具"
  m=$(monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 2>&1)
  tlog "monkey 输出: $m"
  case "$m" in
    *"No activities found"*|*"aborted"*)
      am start -n "$pkg/me.weishu.kernelsu.ui.MainActivity" >> "$LOG" 2>&1
      ;;
  esac
  return 0
}

target=$(cat "$CONF" 2>/dev/null)
[ -z "$target" ] && { tlog "没有配置目标，退出"; exit 0; }

tlog "===== 守护启动 pid=$$ target=$target ====="
tlog "探测自检: activity=$(fg_activity) window=$(fg_window) proc=$(fg_proc)"

last=""
idle=0

while true; do
  now=$(foreground)

  if [ -n "$now" ]; then
    idle=0
    if [ "$now" = "$target" ] && [ "$now" != "$last" ]; then
      tlog "命中目标 $now"
      if revive; then
        tlog "唤回完成"
      else
        tlog "唤回失败，稍后重试"
        last=""
        sleep 3
        continue
      fi
    fi
    last="$now"
  else
    # 连续探测不到任何前台，说明 dumpsys 这条路可能被封了
    idle=$((idle + 1))
    if [ $((idle % 30)) -eq 0 ]; then
      tlog "WARN 连续 $idle 次探测不到前台，当前可用通道: activity/window/proc 均为空"
    fi
  fi

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

  say "找不到安装包，中止"
  return 1
}

# ---------- 启动守护 ----------
start_guard() {
  if [ -f "$PID_FILE" ]; then
    old=$(cat "$PID_FILE" 2>/dev/null)
    [ -n "$old" ] && kill -9 "$old" 2>/dev/null
    rm -f "$PID_FILE"
  fi

  if command -v setsid >/dev/null 2>&1; then
    nohup setsid /system/bin/sh "$GUARD" </dev/null >/dev/null 2>&1 &
  else
    nohup /system/bin/sh "$GUARD" </dev/null >/dev/null 2>&1 &
  fi

  i=0
  while [ $i -lt 15 ]; do
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
  if ! pm list packages 2>/dev/null | grep -q "^package:$PKG$"; then
    say "面具本来就不在"
    return 0
  fi

  say "静默卸载 $PKG"
  o=$(pm uninstall --user 0 "$PKG" 2>&1)
  say "uninstall 输出: $o"
  sleep 1

  if pm list packages 2>/dev/null | grep -q "^package:$PKG$"; then
    o=$(pm uninstall "$PKG" 2>&1)
    say "uninstall 重试: $o"
    sleep 1
  fi

  if pm list packages 2>/dev/null | grep -q "^package:$PKG$"; then
    say "卸载失败，面具仍在"
    return 1
  fi

  say "面具已卸载"
  return 0
}

# ---------- 探测自检 ----------
probe_now() {
  say "==== 探测自检 ===="
  a=$(dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity|ResumedActivity')
  say "activity 行: ${a:-（空）}"
  w=$(dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp')
  say "window 行: ${w:-（空）}"
  say "目标配置: $(cat "$CONF" 2>/dev/null)"
}

# ---------- 状态 ----------
show_status() {
  say "==== 仙鹤隐形模式 ===="
  say "目标应用    : $(cat "$CONF" 2>/dev/null || echo 未配置)"

  if [ -f "$PID_FILE" ]; then
    pid=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$pid" ] && [ -r "/proc/$pid/cmdline" ]; then
      ppid=$(awk '{print $4}' "/proc/$pid/stat" 2>/dev/null)
      say "守护进程    : 运行中 pid=$pid ppid=$ppid"
    else
      say "守护进程    : 已退出（pid 文件残留）"
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

  if [ -f "$LOG" ]; then
    say "---- 最近 20 行日志 ----"
    tail -n 20 "$LOG"
  fi
}

case "$1" in
  arm)
    target="$2"
    [ -z "$target" ] && { echo "缺少目标应用包名"; exit 1; }

    mkdir -p "$ROOT_DIR" 2>/dev/null
    printf '%s\n' "$target" > "$CONF"
    printf '%s\n' "$PKG" > "$ROOT_DIR/pkg"
    : > "$LOG"

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
      [ -n "$old" ] && kill -9 "$old" 2>/dev/null
      rm -f "$PID_FILE"
    fi
    rm -f "$CONF"
    say "守护已停止"
    ;;
  revive)
    if [ ! -f "$GUARD" ]; then
      say "守护脚本不存在"
      exit 1
    fi
    # 从守护脚本里取出 revive 函数直接调用
    sed -n '/^revive()/,/^}/p' "$GUARD" > "$ROOT_DIR/.revive.sh"
    printf '%s\n' "PKG_FILE=$ROOT_DIR/pkg" >> "$ROOT_DIR/.revive.sh"
    . "$ROOT_DIR/.revive.sh"
    revive
    ;;
  probe)
    probe_now
    ;;
  status|"")
    show_status
    ;;
  *)
    echo "用法: sh xh_guard.sh [arm <包名>|disarm|revive|probe|status]"
    ;;
esac
