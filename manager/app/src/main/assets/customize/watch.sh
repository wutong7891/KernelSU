#!/system/bin/sh
# ============================================================
# 仙鹤隐形监听
#
# 监听分三类，生命周期各不相同：
#
#   1. 规则引擎（rules.conf）
#      常驻。规则一直有效，除非手动删除，不会因为触发过就失效。
#
#   2. 隐形唤回（stealth.conf）
#      一次性。音量键命中并装回面具后，只删掉 stealth.conf，
#      监听进程继续跑，规则照常生效。
#
#   3. KSU 生命周期
#      打开 KSU → 装回面具；退出 KSU → 自动从系统卸载。
#      只在隐形模式开启时生效。
#      注意：卸面具不会删规则文件，rules.conf 和 stealth.conf 原样保留，
#      下次 KSU 打开装回面具后规则继续有效。
#
# 启动方式（三处，均由 App 侧调用 sync 触发）：
#   1. 打开 App（MainActivity 冷启动）
#   2. 在「仙鹤定制」里保存配置
#   3. 开机 /data/adb/service.d/xhovosu-watch.sh
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
RULES="$ADB_DIR/rules.conf"
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

# busybox 只用来解决两件事：
#   1. sleep 的小数秒（toybox 的 sleep 0.2 在部分 ROM 上会卡几十秒）
#   2. script 分配伪终端（强制 getevent 按行刷新）
# 其余系统命令（dumpsys / getevent / pm / am）必须用系统路径，
# 因为 KernelSU 的 busybox 没有这些 applet。
have_bb() { [ -x "$BB" ]; }
bb() { if have_bb; then "$BB" "$@"; else "$@"; fi; }

# 基础文本工具用系统自带的，避免 applet 缺失
GETEVENT=/system/bin/getevent
[ -x "$GETEVENT" ] || GETEVENT=getevent

log() {
  printf '[%s] %s\n' "$(date '+%m-%d %H:%M:%S')" "$1" >> "$LOG"
  sz=$(wc -c < "$LOG" 2>/dev/null || echo 0)
  if [ "$sz" -gt 200000 ]; then
    tail -n 400 "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG" 2>/dev/null
  fi
}

sleepf() { bb sleep "$1" 2>/dev/null || sleep 1; }

cfg() { grep -m1 "^$1=" "$CONF" 2>/dev/null | cut -d= -f2-; }

TARGET=$(cfg target)
KEY=$(cfg key); [ -z "$KEY" ] && KEY=voldown
MODE=$(cfg mode); [ -z "$MODE" ] && MODE=long
COUNT=$(cfg count); [ -z "$COUNT" ] && COUNT=3
LONGMS=$(cfg long); [ -z "$LONGMS" ] && LONGMS=1500

# 没有隐形配置不算错：只要还有规则，就继续跑规则引擎
if [ -z "$TARGET" ]; then
  if [ ! -f "$RULES" ] || [ ! -s "$RULES" ]; then
    log "既没有隐形配置也没有规则，退出"
    exit 0
  fi
  log "无隐形配置，只跑规则引擎"
fi

case "$KEY" in
  volup)   KEYCODE=0073 ;;
  voldown) KEYCODE=0072 ;;
  *)       KEYCODE=0072 ;;
esac

log "===== 监听启动 pid=$$ target=$TARGET key=$KEY mode=$MODE count=$COUNT long=$LONGMS bb=$([ -x "$BB" ] && echo yes || echo no) ====="

now_ms() {
  t=$(cat /proc/uptime 2>/dev/null | cut -d' ' -f1)
  sec=${t%.*}
  frac=${t#*.}
  frac=$(printf '%s' "$frac" | cut -c1-2)
  echo $((sec * 1000 + 10#$frac * 10))
}

# ---------- 前台包名后台刷新 ----------
refresh_fg() {
  while true; do
    p=$(dumpsys activity activities 2>/dev/null \
      | grep -m1 -E 'mResumedActivity|topResumedActivity|ResumedActivity' \
      | grep -oE '[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+/[a-zA-Z0-9_.$]+' \
      | head -1 | cut -d/ -f1)
    if [ -z "$p" ]; then
      p=$(dumpsys window 2>/dev/null \
        | grep -m1 -E 'mCurrentFocus|mFocusedApp' \
        | grep -oE '[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+/[a-zA-Z0-9_.$]+' \
        | head -1 | cut -d/ -f1)
    fi
    [ -n "$p" ] && printf '%s' "$p" > "$FG_FILE"
    sleepf 1
  done
}

# ---------- 扫描输入设备 ----------
find_devices() {
  for dev in /dev/input/event*; do
    [ -e "$dev" ] || continue
    caps=$($GETEVENT -p "$dev" 2>/dev/null)
    case "$caps" in
      *0072*) echo "$dev" ;;
      *0073*) echo "$dev" ;;
    esac
  done
}

# ---------- 装回面具 ----------
revive() {
  pkg=$(cat "$PKG_FILE" 2>/dev/null)
  [ -z "$pkg" ] && pkg=com.HxovoSU.yydsHx
  apk=$ADB_DIR/manager.apk
  tmp=/data/local/tmp/xh_revive.apk

  log "开始装回面具"

  [ -f "$apk" ] || { log "FAIL 安装包不存在"; return 1; }

  # /data/adb 是 adb_data_file 标签，system_server 读不了，先落到 tmp
  cp -f "$apk" "$tmp" 2>/dev/null
  chmod 0644 "$tmp" 2>/dev/null
  [ -f "$tmp" ] || { log "FAIL 复制到 tmp 失败"; return 1; }

  out=$(su -c "pm install -r -d -g --user 0 '$tmp'" </dev/null 2>&1)
  log "install: $out"
  rm -f "$tmp" 2>/dev/null

  case "$out" in
    *Success*) log "安装成功" ;;
    *) log "FAIL 安装失败"; return 1 ;;
  esac

  su -c "monkey -p '$pkg' -c android.intent.category.LAUNCHER 1" </dev/null >> "$LOG" 2>&1
  log "已拉起面具"
  return 0
}

# ---------- 规则引擎 ----------
# 每行 11 段，| 分隔：
#   enabled|pkg|key|mode|count|window|long|action|script|inputB64|enter
#
# action=launch  → 拉起面具
# action=script  → 执行脚本，inputB64 解码后按行喂入 stdin

# 执行一条规则的 script 动作
run_script() {
  spath=$1
  sinput=$2
  senter=$3

  [ -f "$spath" ] || { log "规则脚本不存在: $spath"; return 1; }

  if [ -n "$sinput" ]; then
    # base64 解码到临时文件
    in_file="$WORK/.rule_input"
    echo "$sinput" | base64 -d > "$in_file" 2>/dev/null
    [ -s "$in_file" ] || { log "输入解码失败"; return 1; }

    # 逐行喂入，间隔 0.2 秒，模拟逐行回车
    fifo="$WORK/.rule_fifo"
    rm -f "$fifo"
    if mkfifo "$fifo" 2>/dev/null; then
      (
        while IFS= read -r l; do
          bb sleep 0.2 2>/dev/null || sleep 1
          if [ "$senter" = "1" ]; then
            printf '%s\n' "$l"
          else
            printf '%s' "$l"
          fi
        done < "$in_file"
      ) > "$fifo" 2>/dev/null &
      feeder=$!
      (cd / && sh "$spath" < "$fifo") >> "$LOG" 2>&1
      rc=$?
      wait "$feeder" 2>/dev/null
      rm -f "$fifo"
      log "脚本退出码=$rc"
      return 0
    fi
    (cd / && sh "$spath" < "$in_file") >> "$LOG" 2>&1
    log "脚本退出码=$?"
    return 0
  fi

  (cd / && sh "$spath" < /dev/null) >> "$LOG" 2>&1
  log "脚本退出码=$?"
  return 0
}

# 在规则文件里查找匹配当前包名的规则
# 命中返回：规则整行的剩余字段（用全局变量传递）
match_rule() {
  cur_pkg=$1
  cur_key=$2
  cur_mode=$3

  [ -f "$RULES" ] || return 1

  # 末尾补读一行，避免最后一行没换行时 read 返回非 0
  while IFS='|' read -r r_en r_pkg r_key r_mode r_cnt r_win r_lng r_act r_scr r_in r_ent || [ -n "$r_en" ]; do
    [ "$r_en" = "1" ] || continue
    [ "$r_pkg" = "$cur_pkg" ] || continue
    [ "$r_key" = "$cur_key" ] || continue
    [ "$r_mode" = "$cur_mode" ] || continue

    R_CNT=$r_cnt
    R_WIN=$r_win
    R_LNG=$r_lng
    R_ACT=$r_act
    R_SCR=$r_scr
    R_IN=$r_in
    R_ENT=$r_ent
    return 0
  done < "$RULES"

  return 1
}

# 进入应用即触发的规则（key 留空）
match_enter_rule() {
  cur_pkg=$1
  [ -f "$RULES" ] || return 1

  while IFS='|' read -r r_en r_pkg r_key r_mode r_cnt r_win r_lng r_act r_scr r_in r_ent || [ -n "$r_en" ]; do
    [ "$r_en" = "1" ] || continue
    [ "$r_pkg" = "$cur_pkg" ] || continue
    [ -z "$r_key" ] || continue

    R_ACT=$r_act
    R_SCR=$r_scr
    R_IN=$r_in
    R_ENT=$r_ent
    return 0
  done < "$RULES"

  return 1
}

# 执行规则动作
exec_rule_action() {
  if [ "$R_ACT" = "script" ]; then
    log "规则动作: script $R_SCR"
    run_script "$R_SCR" "$R_IN" "$R_ENT"
  else
    log "规则动作: launch"
    revive
  fi
}

# ---------- KSU 生命周期 ----------
# 打开 KSU → 确保面具装着；退出 KSU → 自动卸载。
# 只在隐形模式开启（stealth.conf 有 target）时生效。

ksu_running() {
  pkg=$(cat "$PKG_FILE" 2>/dev/null)
  [ -z "$pkg" ] && pkg=com.HxovoSU.yydsHx

  # 用 pidof 判断进程是否存活（比 ps 可靠，不受输出格式影响）
  if command -v pidof >/dev/null 2>&1; then
    pidof "$pkg" >/dev/null 2>&1 && return 0
  fi
  ps -A 2>/dev/null | grep -q "$pkg" && return 0
  return 1
}

pkg_installed() {
  pkg=$(cat "$PKG_FILE" 2>/dev/null)
  [ -z "$pkg" ] && pkg=com.HxovoSU.yydsHx
  pm list packages 2>/dev/null | grep -q "^package:$pkg$"
}

ksu_cycle() {
  # 没配隐形就不管
  [ -f "$CONF" ] || return 0

  was=0
  while true; do
    bb sleep 2 2>/dev/null || sleep 2

    # 隐形模式被关掉就退出这个监控
    [ -f "$CONF" ] || { log "隐形配置已移除，KSU 监控退出"; return 0; }

    if ksu_running; then
      was=1
      if ! pkg_installed; then
        log "KSU 打开，装回面具"
        # 打标记，避免被「手动安装检测」误判成用户自己装的
        printf '1' > "$WORK/.ksu_install" 2>/dev/null
        revive
      fi
    else
      if [ "$was" = "1" ]; then
        log "KSU 已退出"
        was=0
        if pkg_installed; then
          log "自动卸载面具（规则文件保留）"
          pkg=$(cat "$PKG_FILE" 2>/dev/null)
          [ -z "$pkg" ] && pkg=com.HxovoSU.yydsHx
          # 只卸面具，不动 $RULES 和 $CONF——规则要继续有效
          su -c "pm uninstall --user 0 $pkg" </dev/null >> "$LOG" 2>&1
          rm -f "$WORK/.press" "$WORK/.presskey" 2>/dev/null
        fi
      fi
    fi
  done
}

# ---------- 手动安装检测 ----------
# 用户不走手势、自己手动装了面具，说明想正常用。
# 2 秒轮询发现「从无到有」→ 恢复显示 + 清数据 + 停止监听。
watch_manual_install() {
  pkg=$(cat "$PKG_FILE" 2>/dev/null)
  [ -z "$pkg" ] && pkg=com.HxovoSU.yydsHx

  # 启动瞬间记下基线：隐形模式下应为「不在」
  if pm list packages 2>/dev/null | grep -q "^package:$pkg$"; then
    baseline=1
  else
    baseline=0
  fi
  log "手动安装检测启动，基线=$baseline（0=不在，1=在）"

  while true; do
    bb sleep 2 2>/dev/null || sleep 2

    if pm list packages 2>/dev/null | grep -q "^package:$pkg$"; then
      present=1
    else
      present=0
    fi

    # 只有基线是「不在」时才认为是「从无到有」
    if [ "$baseline" = "0" ] && [ "$present" = "1" ]; then
      # 这次出现如果是我们自己装回来的，不算手动安装
      if [ -f "$WORK/.ksu_install" ]; then
        rm -f "$WORK/.ksu_install" 2>/dev/null
        log "面具由 KSU 生命周期装回，跳过手动安装判定"
        baseline=$present
        continue
      fi

      log "检测到面具被手动安装，恢复显示并清理配置"

      # 恢复显示：解除 hide / disable
      su -c "pm unhide --user 0 $pkg" </dev/null >> "$LOG" 2>&1
      su -c "pm enable --user 0 $pkg" </dev/null >> "$LOG" 2>&1

      # 清数据前先把规则备份到私密目录，pm clear 之后能还原
      if [ -f "$RULES" ]; then
        cp -f "$RULES" "$ADB_DIR/rules.conf.bak" 2>/dev/null
        log "规则已备份到 rules.conf.bak"
      fi

      # 清数据：让 App 恢复到未配置状态
      su -c "pm clear $pkg" </dev/null >> "$LOG" 2>&1

      # 还原规则
      if [ -f "$ADB_DIR/rules.conf.bak" ]; then
        cp -f "$ADB_DIR/rules.conf.bak" "$RULES" 2>/dev/null
        log "规则已还原"
      fi

      # 移除隐形配置，避免下次又被隐藏；规则文件保持不动
      rm -f "$CONF" 2>/dev/null
      log "隐形配置已清理，规则继续监听"
      return 0
    fi

    baseline=$present
  done
}

# ---------- 收集设备 ----------
DEVICES=$(find_devices)
if [ -z "$DEVICES" ]; then
  log "WARN 没有找到带音量键的输入设备，手势功能不可用（免按键规则仍生效）"
  DEVICES=""
fi
log "监听设备: $(echo $DEVICES | tr '\n' ' ')"

# ---------- 后台刷新前台 ----------
refresh_fg &
FG_PID=$!

# ---------- 后台手动安装检测 ----------
watch_manual_install &
MANUAL_PID=$!

# ---------- 后台 KSU 生命周期监控 ----------
ksu_cycle &
KSU_PID=$!

# ---------- 每设备一个常驻 getevent ----------
for dev in $DEVICES; do
  name=$(basename "$dev")
  evlog="$WORK/ev.$name.log"
  : > "$evlog" 2>/dev/null
  (
    while true; do
      if have_bb; then
        $BB script -q -c "$GETEVENT -l $dev" /dev/null >> "$evlog" 2>/dev/null
      else
        getevent -l "$dev" >> "$evlog" 2>/dev/null
      fi
      sleepf 1
    done
  ) &
done

cleanup() {
  kill $FG_PID 2>/dev/null
  kill $MANUAL_PID 2>/dev/null
  kill $KSU_PID 2>/dev/null
  for p in $(ps -A 2>/dev/null | grep -E 'getevent|script' | awk '{print $2}'); do
    kill "$p" 2>/dev/null
  done
}

# ---------- 主循环：字节偏移消费 ----------
press_start=0
click_count=0
click_window_start=0
WINDOW_MS=2000
last_fg=""

for dev in $DEVICES; do
  name=$(basename "$dev")
  eval "off_$name=0"
done

while true; do
  # DEVICES 为空时跳过事件消费，只保留前台变化检测
  for dev in $DEVICES; do
    name=$(basename "$dev")
    evlog="$WORK/ev.$name.log"
    [ -f "$evlog" ] || continue

    size=$(wc -c < "$evlog" 2>/dev/null || echo 0)
    eval "off=\$off_$name"
    [ "$size" -le "$off" ] && continue

    chunk=$(tail -c +$((off + 1)) "$evlog" 2>/dev/null)
    eval "off_$name=$size"

    # 必须用文件重定向。管道会开子 shell，exit/continue 和计数变量都不生效
    printf '%s\n' "$chunk" > "$WORK/.chunk"
    while IFS= read -r line; do
      # 只关心音量键
      case "$line" in
        *KEY_VOLUMEDOWN*|*KEY_VOLUMEUP*) ;;
        *) continue ;;
      esac

      # 从事件行解析出实际按的是哪个键（规则引擎要按规则区分）
      case "$line" in
        *KEY_VOLUMEDOWN*) cur_key=voldown ;;
        *KEY_VOLUMEUP*)   cur_key=volup ;;
        *) continue ;;
      esac

      case "$line" in
        *DOWN*)
          printf '%s' "$(now_ms)" > "$WORK/.press"
          printf '%s' "$cur_key" > "$WORK/.presskey"
          ;;
        *UP*)
          start=$(cat "$WORK/.press" 2>/dev/null)
          [ -z "$start" ] && continue
          pressed=$(cat "$WORK/.presskey" 2>/dev/null)
          [ -z "$pressed" ] && pressed=$KEY
          rm -f "$WORK/.press" "$WORK/.presskey" 2>/dev/null
          dur=$(( $(now_ms) - start ))
          KEY="$pressed"

          fg=$(cat "$FG_FILE" 2>/dev/null)
          [ -z "$fg" ] && continue

          log "前台 $fg 手势 键=$KEY 时长=${dur}ms"

          # ---------- 1. 先查规则引擎 ----------
          if match_rule "$fg" "$KEY" "$MODE"; then
            hit=0
            if [ "$MODE" = "long" ]; then
              [ "$dur" -ge "$R_LNG" ] && hit=1
            else
              click_now=$(now_ms)
              if [ "$click_window_start" = "0" ] || \
                 [ $((click_now - click_window_start)) -gt "$R_WIN" ]; then
                click_window_start=$click_now
                click_count=1
              else
                click_count=$((click_count + 1))
              fi
              log "规则连点 $click_count/$R_CNT"
              [ "$click_count" -ge "$R_CNT" ] && hit=1
            fi

            if [ "$hit" = "1" ]; then
              log "规则命中: $fg -> $R_ACT"
              click_count=0
              click_window_start=0
              exec_rule_action
            fi
            continue
          fi

          # ---------- 2. 再回退到隐形唤回配置 ----------
          [ "$fg" != "$TARGET" ] && continue

          log "隐形唤回手势 时长=${dur}ms"

          if [ "$MODE" = "long" ]; then
            if [ "$dur" -ge "$LONGMS" ]; then
              log "长按命中"
              if revive; then
                log "唤回完成，本次隐形配置已用掉，规则继续监听"
                rm -f "$CONF" 2>/dev/null
                TARGET=""
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
              click_count=0
              click_window_start=0
              if revive; then
                log "唤回完成，本次隐形配置已用掉，规则继续监听"
                rm -f "$CONF" 2>/dev/null
                TARGET=""
              fi
            fi
          fi
          ;;
      esac
    done < "$WORK/.chunk"
  done

  # 前台变化 → 检查免按键规则
  cur_fg=$(cat "$FG_FILE" 2>/dev/null)
  if [ -n "$cur_fg" ] && [ "$cur_fg" != "$last_fg" ]; then
    last_fg="$cur_fg"
    if match_enter_rule "$cur_fg"; then
      log "进入 $cur_fg，命中免按键规则 -> $R_ACT"
      exec_rule_action
    fi
  fi

  sleepf 0.2
done
