#!/system/bin/sh
set -eu

SELF_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

usage() {
    cat <<'EOF'
Night Terminal Patcher v3

Usage:
  sh night-patch.sh list
  sh night-patch.sh patch <boot.img|init_boot.img> <KMI> [output.img]
  sh night-patch.sh restore <patched.img> [output.img]

Example:
  sh night-patch.sh patch init_boot.img android14-6.1
EOF
}

list_kmi() {
    printf '%s\n' \
        android12-5.10 android13-5.10 android13-5.15 android14-5.15 \
        android14-6.1 android15-6.6 android16-6.12 android17-6.18
}

fail() {
    echo "错误：$*" >&2
    exit 1
}

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        toybox sha256sum "$1" | awk '{print $1}'
    fi
}

absolute_path() {
    target=$1
    parent=$(dirname -- "$target")
    name=$(basename -- "$target")
    printf '%s/%s\n' "$(CDPATH= cd -- "$parent" && pwd)" "$name"
}

validate_image() {
    [ -f "$1" ] || fail "镜像不存在：$1"
    magic=$(dd if="$1" bs=8 count=1 2>/dev/null || true)
    [ "$magic" = "ANDROID!" ] || fail "不是未压缩的 Android boot/init_boot 镜像（缺少 ANDROID! 文件头）"
}

select_engine() {
    case "$(uname -m)" in
        aarch64|arm64) ENGINE_REL="bin/android/ksud-arm64" ;;
        x86_64|amd64) ENGINE_REL="bin/android/ksud-x86_64" ;;
        *) fail "不支持的终端架构：$(uname -m)" ;;
    esac
    ENGINE_SOURCE="$SELF_DIR/$ENGINE_REL"
    [ -f "$ENGINE_SOURCE" ] || fail "缺少修补引擎：$ENGINE_SOURCE"
}

verify_asset() {
    rel_path=$1
    manifest="$SELF_DIR/SHA256.txt"
    asset="$SELF_DIR/$rel_path"
    [ -f "$manifest" ] || fail "缺少完整性清单：$manifest"
    [ -f "$asset" ] || fail "工具包文件缺失：$asset"
    expected=$(awk -v name="$rel_path" '$2 == name { print $1; exit }' "$manifest")
    [ -n "$expected" ] || fail "SHA-256 清单没有此文件：$rel_path"
    actual=$(sha256_file "$asset")
    [ "$actual" = "$expected" ] || fail "SHA-256 校验失败：$rel_path"
}

prepare_engine() {
    TMP_WORK=${TMPDIR:-/data/local/tmp}/night-terminal-patcher-$$
    mkdir -p "$TMP_WORK"
    ENGINE="$TMP_WORK/ksud"
    cp "$ENGINE_SOURCE" "$ENGINE"
    chmod 700 "$ENGINE"
    trap 'rm -rf "$TMP_WORK"' EXIT INT TERM
}

command=${1:-}
case "$command" in
    list)
        list_kmi
        exit 0
        ;;
    patch)
        [ "$#" -ge 3 ] && [ "$#" -le 4 ] || { usage; exit 2; }
        image=$(absolute_path "$2")
        kmi=$3
        case " $(list_kmi | tr '\n' ' ') " in
            *" $kmi "*) ;;
            *) fail "不支持的 KMI：$kmi。请运行 list 查看支持列表" ;;
        esac
        validate_image "$image"
        module="$SELF_DIR/kmi/${kmi}_kernelsu.ko"
        [ -f "$module" ] || fail "缺少 KMI：$module"
        if [ "$#" -eq 4 ]; then
            mkdir -p "$(dirname -- "$4")"
            output=$(absolute_path "$4")
        else
            dir=$(dirname -- "$image")
            base=$(basename -- "$image")
            stem=${base%.*}
            output="$dir/${stem}_Night_patched.img"
        fi
        ;;
    restore)
        [ "$#" -ge 2 ] && [ "$#" -le 3 ] || { usage; exit 2; }
        image=$(absolute_path "$2")
        validate_image "$image"
        if [ "$#" -eq 3 ]; then
            mkdir -p "$(dirname -- "$3")"
            output=$(absolute_path "$3")
        else
            dir=$(dirname -- "$image")
            base=$(basename -- "$image")
            stem=${base%.*}
            output="$dir/${stem}_Night_restored.img"
        fi
        ;;
    *)
        usage
        exit 2
        ;;
esac

[ ! -e "$output" ] || fail "输出文件已存在，拒绝覆盖：$output"
mkdir -p "$(dirname -- "$output")"
select_engine
verify_asset "$ENGINE_REL"
prepare_engine

echo "输入：$image"
echo "输入 SHA-256：$(sha256_file "$image")"
if [ "$command" = patch ]; then
    echo "KMI：$kmi"
    verify_asset "kmi/${kmi}_kernelsu.ko"
    "$ENGINE" boot-patch --boot "$image" --module "$module" --kmi "$kmi" \
        --out "$(dirname -- "$output")" --out-name "$(basename -- "$output")"
else
    "$ENGINE" boot-restore --boot "$image" \
        --out "$(dirname -- "$output")" --out-name "$(basename -- "$output")"
fi

[ -s "$output" ] || fail "引擎未生成输出镜像"
report="$output.SHA256.txt"
{
    echo "Night Terminal Patcher v3"
    echo "Operation: $command"
    echo "Input: $image"
    echo "Input SHA-256: $(sha256_file "$image")"
    [ "$command" = patch ] && echo "KMI: $kmi"
    echo "Output: $output"
    echo "Output SHA-256: $(sha256_file "$output")"
} > "$report"
echo "完成：$output"
echo "校验报告：$report"
