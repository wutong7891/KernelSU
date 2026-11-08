#!/system/bin/sh
# ============================================================
# 仙鹤隐形监听
#
# 在目标应用内监听音量键手势，命中后装回面具并拉起，然后自己退出。
#
# 配置：/data/adb/xhovo/stealth.conf
#   target=com.xxx        目标应用包名
#   key=voldown|volup     监听的音量键
#   mode=long|click       长按 / 连点
#   count=3               click 模式的连点次数
#   long=1500             long 模式的长按时长(ms)
#
# 实现要点（每一条都是踩过的坑）：
#   1. getevent -l 不带设备参数不流式输出 → 先用 getevent -p 扫 /dev/input/event*，
#      找出 caps 里含 0072/0073 的设备再显式监听
#   2. 重定向到文件是 4KB 块缓冲，事件稀疏时看不到 → busybox script -q -c 分配伪终端
#   3. getevent 子进程会掉线 → 外层 while 无限重启
#   4. 事件按字节偏移消费，避免重复处理历史事件
#   5. 前台包名由后台循环每秒刷新到文件，事件循环只读文件（dumpsys 会阻塞）
#   6. toybox 的 sleep 0.2 在部分 ROM 上卡几十秒 → 用 busybox sleep
#   7. 所有 pm/am/monkey 调用包 su -c，避免非 su 会话下 Failed transaction
# ============================================================

trap '' HUP

ADB_DIR=/data/adb/xhovo
WORK="$ADB_DIR/work"
CONF="$ADB_DIR/stealth.conf"
PID_FILE="$WORK/watch.pid"
LOG="$WORK/exec.log"
FG_FILE="$WORK/fg"
PKG_FILE="$WORK/pkg"
BB=/data/adb/ksu/bin/busybox

mkdir -p "$WORK" 2>/dev/null
chmod 0755 "$ADB_DIR" "$WORK" 2>/dev/null
: >> "$LOG" 2>/dev/null
echo $$ > "$PID_FILE"
chmod 0644 "$PID_FILE" 2>/dev/null

# 没有 busybox 时回退到系统命令
have_bb() { [ -x "$BB" ]; }
bb() { if have_bb; then "$BB" "$@"; else "$@"; fi; }

log() {
  printf '[%s] %s\n' "$(date '+%m-%d %H:%M:%S')" "$1" >> "$LOG"
  sz=$(bb wc -c < "$LOG" 2>/dev/null || echo 0)
  if [ "$sz" -gt 200000 ]; then
    bb tail -n 400 "$LOG" > "$LOG.tmp" 2>/dev/null && bb mv -f "$LOG.tmp" "$LOG" 2>/dev/null
  fi
}

sleepf() { bb sleep "$1" 2>/dev/null || sleep 1; }

cfg() { bb grep -m1 "^$1=" "$CONF" 2>/dev/null | bb cut -d= -f2-; }

TARGET=$(cfg target)
KEY=$(cfg key); [ -z "$KEY" ] && KEY=voldown
MODE=$(cfg mode); [ -z "$MODE" ] && MODE=long
COUNT=$(cfg count); [ -z "$COUNT" ] && COUNT=3
LONGMS=$(cfg long); [ -z "$LONGMS" ] && LONGMS=1500

[ -z "$TARGET" ] && { log "配置缺 target，退出"; exit 0; }

case "$KEY" in
  volup)   KEYCODE=0073 ;;
  voldown) KEYCODE=0072 ;;
  *)       KEYCODE=0072 ;;
esac

log "===== 监听启动 pid=$$ target=$TARGET key=$KEY mode=$MODE count=$COUNT long=$LONGMS bb=$([ -x "$BB" ] && echo yes || echo no) ====="

now_ms() {
  t=$(bb cat /proc/uptime 2>/dev/null | bb cut -d' ' -f1)
  sec=${t%.*}
  frac=${t#*.}
  frac=$(printf '%s' "$frac" | bb cut -c1-2)
  echo $((sec * 1000 + 10#$frac * 10))
}

# ---------- 前台包名后台刷新 ----------
refresh_fg() {
  while true; do
    p=$(bb dumpsys activity activities 2>/dev/null \
      | bb grep -m1 -E 'mResumedActivity|topResumedActivity|ResumedActivity' \
      | bb grep -oE '[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+/[a-zA-Z0-9_.$]+' \
      | bb head -1 | bb cut -d/ -f1)
    if [ -z "$p" ]; then
      p=$(bb dumpsys window 2>/dev/null \
        | bb grep -m1 -E 'mCurrentFocus|mFocusedApp' \
        | bb grep -oE '[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+/[a-zA-Z0-9_.$]+' \
        | bb head -1 | bb cut -d/ -f1)
    fi
    [ -n "$p" ] && printf '%s' "$p" > "$FG_FILE"
    sleepf 1
  done
}

# ---------- 扫描输入设备 ----------
find_devices() {
  for dev in /dev/input/event*; do
    [ -e "$dev" ] || continue
    caps=$(bb getevent -p "$dev" 2>/dev/null)
    case "$caps" in
      *0072*) echo "$dev" ;;
      *0073*) echo "$dev" ;;
    esac
  done
}

# ---------- 装回面具 ----------
revive() {
  pkg=$(bb cat "$PKG_FILE" 2>/dev/null)
  [ -z "$pkg" ] && pkg=com.HxovoSU.yydsHx
  apk=$ADB_DIR/manager.apk
  tmp=/data/local/tmp/xh_revive.apk

  log "开始装回面具"

  [ -f "$apk" ] || { log "FAIL 安装包不存在"; return 1; }

  # /data/adb 是 adb_data_file 标签，system_server 读不了，先落到 tmp
  bb cp -f "$apk" "$tmp" 2>/dev/null
  bb chmod 0644 "$tmp" 2>/dev/null
  [ -f "$tmp" ] || { log "FAIL 复制到 tmp 失败"; return 1; }

  out=$(su -c "pm install -r -d -g --user 0 '$tmp'" </dev/null 2>&1)
  log "install: $out"
  bb rm -f "$tmp" 2>/dev/null

  case "$out" in
    *Success*) log "安装成功" ;;
    *) log "FAIL 安装失败"; return 1 ;;
  esac

  su -c "monkey -p '$pkg' -c android.intent.category.LAUNCHER 1" </dev/null >> "$LOG" 2>&1
  log "已拉起面具"
  return 0
}

# ---------- 收集设备 ----------
DEVICES=$(find_devices)
if [ -z "$DEVICES" ]; then
  log "FAIL 没有找到带音量键的输入设备"
  exit 1
fi
log "监听设备: $(echo $DEVICES | tr '\n' ' ')"

# ---------- 后台刷新前台 ----------
refresh_fg &
FG_PID=$!

# ---------- 每设备一个常驻 getevent ----------
for dev in $DEVICES; do
  name=$(basename "$dev")
  evlog="$WORK/ev.$name.log"
  : > "$evlog" 2>/dev/null
  (
    while true; do
      if have_bb; then
        $BB script -q -c "$BB getevent -l $dev" /dev/null >> "$evlog" 2>/dev/null
      else
        getevent -l "$dev" >> "$evlog" 2>/dev/null
      fi
      sleepf 1
    done
  ) &
done

cleanup() {
  kill $FG_PID 2>/dev/null
  for p in $(bb ps -A 2>/dev/null | bb grep -E 'getevent|script' | bb awk '{print $2}'); do
    kill "$p" 2>/dev/null
  done
}

# ---------- 主循环：字节偏移消费 ----------
press_start=0
click_count=0
click_window_start=0
WINDOW_MS=2000

for dev in $DEVICES; do
  name=$(basename "$dev")
  eval "off_$name=0"
done

while true; do
  for dev in $DEVICES; do
    name=$(basename "$dev")
    evlog="$WORK/ev.$name.log"
    [ -f "$evlog" ] || continue

    size=$(bb wc -c < "$evlog" 2>/dev/null || echo 0)
    eval "off=\$off_$name"
    [ "$size" -le "$off" ] && continue

    chunk=$(bb tail -c +$((off + 1)) "$evlog" 2>/dev/null)
    eval "off_$name=$size"

    # 必须用文件重定向。管道会开子 shell，exit/continue 和计数变量都不生效
    printf '%s\n' "$chunk" > "$WORK/.chunk"
    while IFS= read -r line; do
      case "$line" in
        *KEY_VOL*) ;;
        *) continue ;;
      esac
      case "$line" in
        *"$KEYCODE"*) ;;
        *) continue ;;
      esac

      case "$line" in
        *DOWN*)
          printf '%s' "$(now_ms)" > "$WORK/.press"
          ;;
        *UP*)
          start=$(bb cat "$WORK/.press" 2>/dev/null)
          [ -z "$start" ] && continue
          bb rm -f "$WORK/.press" 2>/dev/null
          dur=$(( $(now_ms) - start ))

          fg=$(bb cat "$FG_FILE" 2>/dev/null)
          [ "$fg" != "$TARGET" ] && continue

          log "目标应用内手势 时长=${dur}ms"

          if [ "$MODE" = "long" ]; then
            if [ "$dur" -ge "$LONGMS" ]; then
              log "长按命中"
              if revive; then
                log "唤回完成，监听退出"
                cleanup
                exit 0
              fi
            fi
          else
            click_now=$(now_ms)
            if [ "$click_window_start" = "0" ] || \
               [ $((click_now - click_window_start)) -gt "$WINDOW_MS" ]; then
              click_window_start=$click_now
              click_count=1
            else
              click_count=$((click_count + 1))
            fi
            log "连点 $click_count/$COUNT"
            if [ "$click_count" -ge "$COUNT" ]; then
              log "连点命中"
              if revive; then
                log "唤回完成，监听退出"
                cleanup
                exit 0
              fi
            fi
          fi
          ;;
      esac
    done < "$WORK/.chunk"
  done

  sleepf 0.2
done
