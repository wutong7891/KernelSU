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
echo "PathMask 已恢复其余默认配置，作用范围设为全局，开机等待时间设为 5 秒"

