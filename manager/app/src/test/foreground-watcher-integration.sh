#!/bin/sh
set -eu

TEST_ROOT=$(mktemp -d)
WATCHER_PID=
cleanup() {
  if [ -n "$WATCHER_PID" ]; then
    kill -TERM "$WATCHER_PID" 2>/dev/null || true
    wait "$WATCHER_PID" 2>/dev/null || true
  fi
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT INT TERM

STATE_DIR="$TEST_ROOT/state"
CONFIG_DIR="$STATE_DIR/config"
SCRIPT_DIR="$TEST_ROOT/root-script"
FAKE_BIN="$TEST_ROOT/bin"
RESULT_FILE="$TEST_ROOT/extracted-result.txt"
mkdir -p "$CONFIG_DIR" "$SCRIPT_DIR" "$FAKE_BIN"

cat > "$FAKE_BIN/pm" <<'EOF'
#!/bin/sh
if [ "${1:-}" = path ] && [ "${2:-}" = com.Night.night ]; then
  echo package:/data/app/com.Night.night/base.apk
  exit 0
fi
exit 1
EOF

cat > "$FAKE_BIN/cmd" <<'EOF'
#!/bin/sh
if [ "${1:-}" = activity ]; then
  echo 'mResumedActivity: ActivityRecord{test u0 com.example.target/.MainActivity t1}'
  exit 0
fi
exit 1
EOF

cat > "$FAKE_BIN/dumpsys" <<'EOF'
#!/bin/sh
exit 1
EOF
chmod 0755 "$FAKE_BIN/pm" "$FAKE_BIN/cmd" "$FAKE_BIN/dumpsys"

printf '%s\n' 'payload-from-sibling-zip' > "$SCRIPT_DIR/payload.txt"
(cd "$SCRIPT_DIR" && zip -q payload.zip payload.txt)
rm "$SCRIPT_DIR/payload.txt"

cat > "$SCRIPT_DIR/run.sh" <<EOF
#!/bin/sh
set -eu
SCRIPT_DIR=\${0%/*}
unzip -p "\$SCRIPT_DIR/payload.zip" payload.txt > "$RESULT_FILE"
EOF
chmod 0700 "$SCRIPT_DIR/run.sh"

printf '%s\n' com.example.target > "$CONFIG_DIR/package"
printf '%s\n' com.Night.night > "$CONFIG_DIR/manager_package"
printf '%s\n' "$SCRIPT_DIR/run.sh" > "$CONFIG_DIR/script_path"
: > "$CONFIG_DIR/pre_input"

PATH="$FAKE_BIN:$PATH" \
NIGHT_WATCHER_ROOT="$STATE_DIR" \
NIGHT_WATCHER_SHELL=/bin/sh \
  sh app/src/main/assets/foreground_watcher/watcher.sh &
WATCHER_PID=$!

attempt=0
while [ "$attempt" -lt 20 ] && [ ! -s "$RESULT_FILE" ]; do
  attempt=$((attempt + 1))
  sleep 0.25
done

test -s "$RESULT_FILE"
test "$(cat "$RESULT_FILE")" = payload-from-sibling-zip
grep -F 'App entered foreground: com.example.target' "$STATE_DIR/events.log"
grep -F 'Script finished, exit=0' "$STATE_DIR/events.log"

kill -TERM "$WATCHER_PID"
wait "$WATCHER_PID" 2>/dev/null || true
WATCHER_PID=
echo 'Foreground watcher root-path ZIP extraction simulation passed.'
