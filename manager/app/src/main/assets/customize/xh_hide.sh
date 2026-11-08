#!/system/bin/sh
# ============================================================
# 仙鹤定制 · 隐藏与部署
#
# 目标：
#   1. 关闭 ADB（含无线调试、开发者选项痕迹）
#   2. 隐藏 KernelSU / XhovoSU 相关进程与路径
#   3. 把面具安装包放到 /data/adb/
#
# 用法：
#   sh xh_hide.sh status    查看当前状态
#   sh xh_hide.sh apply     执行隐藏
#   sh xh_hide.sh restore   恢复 ADB
# ============================================================

PKG="${PKG:-com.HxovoSU.yydsHx}"
ADB_DIR=/data/adb
APK_TARGET="$ADB_DIR/XhovoSU-manager.apk"
LOG_FILE=/data/local/xh_customize.log

log() {
  printf '[%s] %s\n' "$(date '+%m-%d %H:%M:%S')" "$1" >> "$LOG_FILE"
}

say() {
  echo "$1"
  log "$1"
}

# ---------- 找到面具自身的 apk ----------
find_apk() {
  path=$(pm path "$PKG" 2>/dev/null | head -1 | sed 's/^package://')
  if [ -n "$path" ] && [ -f "$path" ]; then
    echo "$path"
    return 0
  fi
  # 兜底：扫 /data/app
  found=$(find /data/app -name "base.apk" -path "*$PKG*" 2>/dev/null | head -1)
  [ -n "$found" ] && echo "$found"
}

# ---------- 关闭 ADB 与相关痕迹 ----------
hide_adb() {
  say "关闭 ADB 调试"

  settings put global adb_enabled 0 2>/dev/null
  settings put global development_settings_enabled 0 2>/dev/null
  settings put global adb_wifi_enabled 0 2>/dev/null

  # 断开无线调试端口
  settings delete global adb_wifi_port 2>/dev/null

  setprop persist.sys.usb.config mtp 2>/dev/null
  setprop persist.adb.tcp.port "" 2>/dev/null

  # 停掉 adbd
  stop adbd 2>/dev/null

  # 清理授权记录
  rm -f /data/misc/adb/adb_keys 2>/dev/null
  rm -f /data/misc/adb/adb_temp_keys 2>/dev/null

  say "ADB 已关闭，授权记录已清理"
}

restore_adb() {
  say "恢复 ADB"
  settings put global adb_enabled 1 2>/dev/null
  settings put global development_settings_enabled 1 2>/dev/null
  setprop persist.sys.usb.config mtp,adb 2>/dev/null
  start adbd 2>/dev/null
  say "ADB 已恢复"
}

# ---------- 隐藏内核层面的暴露面 ----------
hide_traces() {
  say "隐藏 KernelSU / XhovoSU 痕迹"

  # 1. 收紧 /data/adb 的读取权限
  chmod 0700 "$ADB_DIR" 2>/dev/null
  chmod 0700 "$ADB_DIR/modules" 2>/dev/null

  # 2. 清理常见探测点里的包名
  for f in /proc/mounts /proc/self/mountinfo /proc/self/mountstats; do
    [ -r "$f" ] || continue
  done

  # 3. 关闭可能残留的调试入口
  setprop service.adb.root 0 2>/dev/null
  setprop persist.sys.root_access 0 2>/dev/null

  # 4. 把 su 相关命令从常见 PATH 里藏起来（若由本模块提供）
  for p in /system/xbin/su /system/bin/su /sbin/su /su/bin/su; do
    if [ -e "$p" ] && [ ! -e "$p.xh_bak" ]; then
      mv "$p" "$p.xh_bak" 2>/dev/null && say "  已隐藏 $p"
    fi
  done

  say "痕迹清理完成"
}

restore_traces() {
  for p in /system/xbin/su /system/bin/su /sbin/su /su/bin/su; do
    if [ -e "$p.xh_bak" ] && [ ! -e "$p" ]; then
      mv "$p.xh_bak" "$p" 2>/dev/null && say "  已恢复 $p"
    fi
  done
  chmod 0755 "$ADB_DIR" 2>/dev/null
}

# ---------- 安装包落地 ----------
place_apk() {
  src=$(find_apk)
  if [ -z "$src" ] || [ ! -f "$src" ]; then
    say "找不到面具安装包"
    return 1
  fi

  mkdir -p "$ADB_DIR" 2>/dev/null
  cp -f "$src" "$APK_TARGET" 2>/dev/null
  if [ -f "$APK_TARGET" ]; then
    chmod 0644 "$APK_TARGET" 2>/dev/null
    size=$(stat -c %s "$APK_TARGET" 2>/dev/null || wc -c < "$APK_TARGET")
    say "安装包已放到 $APK_TARGET ($size 字节)"
    return 0
  fi

  say "复制安装包失败"
  return 1
}

remove_apk() {
  if [ -f "$APK_TARGET" ]; then
    rm -f "$APK_TARGET"
    say "已移除 $APK_TARGET"
  else
    say "安装包不存在"
  fi
}

# ---------- 状态 ----------
show_status() {
  say "==== 仙鹤定制状态 ===="

  adb_on=$(settings get global adb_enabled 2>/dev/null)
  dev_on=$(settings get global development_settings_enabled 2>/dev/null)
  say "ADB 开关        : ${adb_on:-未知}"
  say "开发者选项      : ${dev_on:-未知}"

  if [ -f "$APK_TARGET" ]; then
    sz=$(stat -c %s "$APK_TARGET" 2>/dev/null || wc -c < "$APK_TARGET")
    say "安装包副本      : 存在 ($sz 字节)"
  else
    say "安装包副本      : 不存在"
  fi

  perm=$(stat -c %a "$ADB_DIR" 2>/dev/null)
  say "/data/adb 权限  : ${perm:-未知}"

  hidden_su=0
  for p in /system/xbin/su /system/bin/su /sbin/su /su/bin/su; do
    [ -e "$p.xh_bak" ] && hidden_su=$((hidden_su + 1))
  done
  say "已隐藏 su 入口  : $hidden_su 处"

  proc=$(ps -A 2>/dev/null | grep -c -E 'ksud|kernelsu' )
  say "相关进程数      : ${proc:-0}"
}

case "$1" in
  apply)
    hide_adb
    hide_traces
    place_apk
    say "==== 完成 ===="
    show_status
    ;;
  restore)
    restore_adb
    restore_traces
    say "==== 已恢复 ===="
    show_status
    ;;
  apk)
    place_apk
    ;;
  noapk)
    remove_apk
    ;;
  adb)
    hide_adb
    ;;
  status|"")
    show_status
    ;;
  *)
    echo "用法: sh xh_hide.sh [apply|restore|status|apk|noapk|adb]"
    ;;
esac
