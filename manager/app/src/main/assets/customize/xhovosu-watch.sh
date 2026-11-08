#!/system/bin/sh
# ============================================================
# 仙鹤监听 · 开机自启
#
# 放在 /data/adb/service.d/ 下，由 KernelSU 在 post-fs-data 之后执行。
# 这里只负责把监听拉起来，本体在 /data/adb/xhovo/watch.sh。
#
# 启动条件：
#   1. 已配置目标应用（stealth.conf 存在且 target 非空）
#   2. 监听本体存在
#   3. 当前没有在跑（避免重复启动）
# ============================================================

ADB_DIR=/data/adb/xhovo
WORK="$ADB_DIR/work"
CONF="$ADB_DIR/stealth.conf"
WATCH="$ADB_DIR/watch.sh"
PID_FILE="$WORK/watch.pid"
LOG="$WORK/exec.log"

mkdir -p "$WORK" 2>/dev/null
chmod 0755 "$ADB_DIR" "$WORK" 2>/dev/null

log() {
  printf '[%s] [boot] %s\n' "$(date '+%m-%d %H:%M:%S')" "$1" >> "$LOG" 2>/dev/null
}

# 没配置就不启动
[ -f "$CONF" ] || exit 0
target=$(grep -m1 '^target=' "$CONF" 2>/dev/null | cut -d= -f2-)
[ -z "$target" ] && exit 0

# 本体不在就退出
[ -f "$WATCH" ] || { log "watch.sh 不存在，跳过"; exit 0; }

# 已经在跑就不重复启动
if [ -f "$PID_FILE" ]; then
  old=$(cat "$PID_FILE" 2>/dev/null)
  if [ -n "$old" ] && [ -r "/proc/$old/cmdline" ]; then
    log "监听已在运行 pid=$old"
    exit 0
  fi
  rm -f "$PID_FILE"
fi

log "开机启动监听 target=$target"

if command -v setsid >/dev/null 2>&1; then
  nohup setsid /system/bin/sh "$WATCH" </dev/null >/dev/null 2>&1 &
else
  nohup /system/bin/sh "$WATCH" </dev/null >/dev/null 2>&1 &
fi

# 等它写出 pid 再确认一次
i=0
while [ $i -lt 15 ]; do
  if [ -f "$PID_FILE" ]; then
    pid=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$pid" ] && [ -r "/proc/$pid/cmdline" ]; then
      log "启动成功 pid=$pid"
      exit 0
    fi
  fi
  sleep 0.3
  i=$((i + 1))
done

log "启动失败，pid 文件未出现"
exit 1
