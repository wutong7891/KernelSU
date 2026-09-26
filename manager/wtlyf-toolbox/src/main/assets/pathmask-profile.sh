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

cat > "$PERSIST_DIR/target_path.conf" <<'EOF'
dir:/dev/cpuset/scene-daemon
dir:/dev/???/scene_mode_category
dir:/system_ext/app/SoterService
EOF
printf '%s\n' 'global' > "$PERSIST_DIR/scope_mode.conf"
printf '%s\n' '1' > "$PERSIST_DIR/hide_dirents.conf"
printf '%s\n' '5' > "$PERSIST_DIR/wait_seconds.conf"
: > "$PERSIST_DIR/deny_uids.conf"
: > "$PERSIST_DIR/deny_packages.conf"

for NAME in target_path.conf scope_mode.conf hide_dirents.conf wait_seconds.conf deny_uids.conf deny_packages.conf; do
    cp -f "$PERSIST_DIR/$NAME" "$MODULE_DIR/$NAME"
    chown 0:0 "$PERSIST_DIR/$NAME" "$MODULE_DIR/$NAME" 2>/dev/null || true
    chmod 0600 "$PERSIST_DIR/$NAME"
    chmod 0644 "$MODULE_DIR/$NAME"
done

command -v restorecon >/dev/null 2>&1 && restorecon -RF "$PERSIST_DIR" "$MODULE_DIR" 2>/dev/null || true
sync
echo "PathMask 已按 wtlyf 预设完成配置：三条路径父级全部启用、全局、隐藏上级目录列表项、空黑名单、等待 5 秒"
