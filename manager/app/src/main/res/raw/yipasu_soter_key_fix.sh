#!/system/bin/sh

# One-shot YipaSU boot repair. Remove the launcher immediately so it runs on
# the next boot only, then continue in the background after Android is ready.
SELF="$0"
LOG=/data/adb/yipasu_soter_key_fix.log

(
    rm -f "$SELF"
    until [ "$(getprop sys.boot_completed)" = "1" ]; do
        sleep 3
    done
    sleep 15

    tap_text() {
        LABEL="$1"
        UI=/data/local/tmp/yipasu_soter_window.xml
        TRY=0
        while [ "$TRY" -lt 6 ]; do
            uiautomator dump "$UI" >/dev/null 2>&1
            LINE=$(sed 's/></>\n</g' "$UI" 2>/dev/null | grep "text=\"$LABEL\"" | head -n 1)
            BOUNDS=$(echo "$LINE" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
            if [ -n "$BOUNDS" ]; then
                set -- $BOUNDS
                input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
                rm -f "$UI"
                sleep 2
                return 0
            fi
            TRY=$((TRY + 1))
            sleep 2
        done
        return 1
    }

    open_key_status() {
        am start -a android.intent.action.DIAL >/dev/null 2>&1
        sleep 2
        # Enter *#899# using Android key codes: STAR, POUND, 8, 9, 9, POUND.
        input keyevent 17
        input keyevent 18
        input keyevent 15
        input keyevent 16
        input keyevent 16
        input keyevent 18
        sleep 5
        tap_text "手动测试" || tap_text "Manual test"
        tap_text "其他" || tap_text "Other"
        tap_text "Key状态" || tap_text "KEY状态" || tap_text "Key status"
    }

    PASS=1
    while [ "$PASS" -le 2 ]; do
        echo "[$(date)] 第 $PASS 遍：开始修复SOTER Key问题"
        stop vendor.soter
        sleep 3
        pm clear com.tencent.soter.soterserver
        start vendor.soter
        sleep 5
        getprop init.svc.vendor.soter
        echo "修复完成，请开机后在拨号输入*#899#选手动测试查看SOTER Key，若失败则多刷新几次"
        open_key_status
        if [ "$PASS" -eq 1 ]; then
            sleep 10
            input keyevent KEYCODE_HOME
            sleep 3
        fi
        PASS=$((PASS + 1))
    done
    echo "[$(date)] YipaSU SOTER Key 两遍自动修复完成"
) >>"$LOG" 2>&1 &

exit 0
