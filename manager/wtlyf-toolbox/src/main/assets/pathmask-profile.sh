#!/system/bin/sh
set -eu

PERSIST_DIR="/data/adb/pathmask"
ACTIVE_MODULE_DIR="/data/adb/modules/pathmask"
UPDATE_MODULE_DIR="/data/adb/modules_update/pathmask"

if [ -d "$UPDATE_MODULE_DIR" ]; then
    MODULE_DIR="$UPDATE_MODULE_DIR"
elif [ -d "$ACTIVE_MODULE_DIR" ]; then
    MODULE_DIR="$ACTIVE_MODULE_DIR"
else
    echo "PathMask 模块尚未安装" >&2
    exit 41
fi

mkdir -p "$PERSIST_DIR"
chmod 0700 "$PERSIST_DIR" 2>/dev/null || true

# 移除 wtlyf 旧版写入的整套预设，恢复当前模块包自带的默认配置。
for NAME in target_path.conf scope_mode.conf hide_dirents.conf deny_uids.conf deny_packages.conf; do
    rm -f "$PERSIST_DIR/$NAME"
    if [ -f "$MODULE_DIR/$NAME" ]; then
        cp -f "$MODULE_DIR/$NAME" "$PERSIST_DIR/$NAME"
        chown 0:0 "$PERSIST_DIR/$NAME" 2>/dev/null || true
        chmod 0600 "$PERSIST_DIR/$NAME"
    fi
done

# 定制作用范围为全局，并将默认开机等待时间从 60 秒改为 5 秒。
rm -f "$PERSIST_DIR/target_wait_seconds.conf" "$PERSIST_DIR/package_wait_seconds.conf"
printf '%s\n' 'global' > "$PERSIST_DIR/scope_mode.conf"
printf '%s\n' 'global' > "$MODULE_DIR/scope_mode.conf"
printf '%s\n' '5' > "$PERSIST_DIR/wait_seconds.conf"
printf '%s\n' '5' > "$MODULE_DIR/wait_seconds.conf"
chown 0:0 "$PERSIST_DIR/scope_mode.conf" "$MODULE_DIR/scope_mode.conf" \
    "$PERSIST_DIR/wait_seconds.conf" "$MODULE_DIR/wait_seconds.conf" 2>/dev/null || true
chmod 0600 "$PERSIST_DIR/scope_mode.conf"
chmod 0644 "$MODULE_DIR/scope_mode.conf"
chmod 0600 "$PERSIST_DIR/wait_seconds.conf"
chmod 0644 "$MODULE_DIR/wait_seconds.conf"

command -v restorecon >/dev/null 2>&1 && restorecon -RF "$PERSIST_DIR" "$MODULE_DIR" 2>/dev/null || true
sync

# 与 PathMask WebUI 右下角“保存并热重载”执行相同的重载流程。
rm -f "$PERSIST_DIR/load_fail_count" "$PERSIST_DIR/load_fail_reason" 2>/dev/null || true
if grep -q '^pathmask ' /proc/modules 2>/dev/null; then
    rmmod pathmask
fi
PATHMASK_RESET_FAIL_GUARD=1 \
PATHMASK_IGNORE_FAIL_GUARD=1 \
PATHMASK_WAIT_SECONDS=5 \
    sh "$MODULE_DIR/service.sh"

echo "PathMask 已设为全局、等待时间设为 5 秒，并完成保存及热重载"

