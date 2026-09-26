#!/system/bin/sh
set -eu

SOURCE_KEYBOX="$1"
TARGET_DIR="/data/adb/tricky_store"
TARGET_KEYBOX="$TARGET_DIR/keybox.xml"
TEMP_KEYBOX="$TARGET_DIR/.keybox.xml.wtlyf.tmp"

[ -f "$SOURCE_KEYBOX" ] || {
    echo "找不到内置 keybox.xml: $SOURCE_KEYBOX" >&2
    exit 31
}

mkdir -p "$TARGET_DIR"
cp -f "$SOURCE_KEYBOX" "$TEMP_KEYBOX"
chown 0:0 "$TEMP_KEYBOX" 2>/dev/null || true
chmod 0600 "$TEMP_KEYBOX"
mv -f "$TEMP_KEYBOX" "$TARGET_KEYBOX"
command -v restorecon >/dev/null 2>&1 && restorecon "$TARGET_KEYBOX" 2>/dev/null || true
sync
echo "已替换 $TARGET_KEYBOX"
