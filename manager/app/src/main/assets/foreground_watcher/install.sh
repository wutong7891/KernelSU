#!/system/bin/sh
# XhovoSU 前台监听 · 安装并启动
# 用法: sh install.sh <暂存目录>
# 暂存目录里需要有: watcher.sh / package / pre_input / selected_script.sh

ROOT_DIR=/data/local/xhovosu_fgwatch
SRC="$1"

if [ -z "$SRC" ]; then
  echo "FAILED: no stage dir"
  exit 1
fi

if [ ! -f "$SRC/watcher.sh" ]; then
  echo "FAILED: watcher.sh missing"
  exit 1
fi

# 1. 准备独立目录
mkdir -p "$ROOT_DIR/config" 2>/dev/null
chmod 0755 "$ROOT_DIR" 2>/dev/null

# 2. 停掉旧实例（按 pid 文件，不误伤执行这条命令的 shell）
if [ -f "$ROOT_DIR/watcher.pid" ]; then
  old=$(cat "$ROOT_DIR/watcher.pid" 2>/dev/null)
  if [ -n "$old" ] && [ -r "/proc/$old/cmdline" ]; then
    kill "$old" 2>/dev/null
    sleep 1
    kill -9 "$old" 2>/dev/null
  fi
  rm -f "$ROOT_DIR/watcher.pid"
fi
rm -rf "$ROOT_DIR/watcher.lock" 2>/dev/null

# 3. 落地文件
cp -f "$SRC/watcher.sh"          "$ROOT_DIR/watcher.sh"          2>/dev/null || { echo "FAILED: cp watcher"; exit 1; }
cp -f "$SRC/package"             "$ROOT_DIR/config/package"      2>/dev/null || { echo "FAILED: cp package"; exit 1; }
cp -f "$SRC/pre_input"           "$ROOT_DIR/config/pre_input"    2>/dev/null
cp -f "$SRC/selected_script.sh"  "$ROOT_DIR/selected_script.sh"  2>/dev/null || { echo "FAILED: cp script"; exit 1; }

chmod 0755 "$ROOT_DIR/watcher.sh" "$ROOT_DIR/selected_script.sh" 2>/dev/null
chmod 0600 "$ROOT_DIR/config/package" "$ROOT_DIR/config/pre_input" 2>/dev/null

# 4. 启动（独立会话，父进程交给 init）
rm -f "$ROOT_DIR/watcher.pid"
if command -v setsid >/dev/null 2>&1; then
  nohup setsid /system/bin/sh "$ROOT_DIR/watcher.sh" </dev/null >/dev/null 2>&1 &
else
  nohup /system/bin/sh "$ROOT_DIR/watcher.sh" </dev/null >/dev/null 2>&1 &
fi

# 5. 确认真的起来了
i=0
while [ $i -lt 10 ]; do
  if [ -f "$ROOT_DIR/watcher.pid" ]; then
    pid=$(cat "$ROOT_DIR/watcher.pid" 2>/dev/null)
    if [ -n "$pid" ] && [ -r "/proc/$pid/cmdline" ]; then
      echo "STARTED pid=$pid"
      exit 0
    fi
  fi
  sleep 0.3
  i=$((i + 1))
done

echo "FAILED: watcher did not come up"
exit 1
