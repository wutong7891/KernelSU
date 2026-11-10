package me.weishu.kernelsu.ui.customize

import android.content.Context
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.File

/**
 * 仙鹤隐形模式（对齐 Evil 项目的实现）。
 *
 * 要点：
 * - 配置镜像到 /data/adb/xhovo/stealth.conf，真卸载后配置仍在
 * - 面具真卸载（pm uninstall）
 * - 目标应用内音量键手势唤回，触发后监听自动退出
 * - 手动安装（非手势）时 2 秒轮询发现"从无到有"，恢复显示并清数据
 */
object CustomizeController {

    private const val ADB_DIR = "/data/adb/xhovo"
    private const val WORK_DIR = "$ADB_DIR/work"
    private const val CONF_PATH = "$ADB_DIR/stealth.conf"
    private const val WATCH_PATH = "$ADB_DIR/watch.sh"
    private const val SERVICE_DIR = "/data/adb/service.d"
    private const val SERVICE_PATH = "$SERVICE_DIR/xhovosu-watch.sh"
    private const val APK_PATH = "$ADB_DIR/manager.apk"
    private const val PID_PATH = "$WORK_DIR/watch.pid"
    private const val LOG_PATH = "$WORK_DIR/exec.log"

    /** 部署脚本、镜像配置、启动监听。 */
    fun arm(
        context: Context,
        target: String,
        key: String,
        mode: String,
        count: Int,
        longMs: Int,
        contact: String = "",
    ): Result<String> = runCatching {
        val pkg = context.packageName

        ensureAsset(context, "customize/watch.sh", WATCH_PATH, executable = true)
        ensureAsset(context, "customize/xhovosu-watch.sh", SERVICE_PATH, executable = true)

        val conf = buildString {
            append("target=").append(target).append('\n')
            append("key=").append(key).append('\n')
            append("mode=").append(mode).append('\n')
            append("count=").append(count).append('\n')
            append("long=").append(longMs).append('\n')
            // 通讯录许可：通讯录里没有这个名字就不装回面具
            append("contact=").append(contact.trim()).append('\n')
        }

        val command = buildString {
            append("mkdir -p ").append(q(WORK_DIR)).append(" 2>/dev/null; ")
            append("chmod 0755 ").append(q(ADB_DIR)).append(' ').append(q(WORK_DIR)).append(" 2>/dev/null; ")

            // 镜像配置
            append("cat > ").append(q(CONF_PATH)).append(" <<'XH_CONF'\n").append(conf).append("XH_CONF\n")
            append("chmod 0644 ").append(q(CONF_PATH)).append(" 2>/dev/null; ")

            // 记录包名
            append("printf '%s\\n' ").append(q(pkg)).append(" > ").append(q("$WORK_DIR/pkg")).append("; ")

            // 每次都重新镜像，覆盖旧副本，确保拿到的是当前安装的这个版本
            append("src=$(pm path ").append(q(pkg)).append(" 2>/dev/null | head -1 | sed 's/^package://'); ")
            append("if [ -n \"\$src\" ] && [ -f \"\$src\" ]; then ")
            append("cp -f \"\$src\" ").append(q(APK_PATH)).append(" && chmod 0644 ").append(q(APK_PATH)).append("; ")
            append("fi; ")

            // 停旧监听
            append("[ -f ").append(q(PID_PATH)).append(" ] && { ")
            append("old=$(").append("cat ").append(q(PID_PATH)).append(" 2>/dev/null); ")
            append("[ -n \"\$old\" ] && kill -9 \"\$old\" 2>/dev/null; rm -f ").append(q(PID_PATH)).append("; }; ")

            // 启动监听（su 会话，脱离）
            append("su -c 'setsid sh ").append(WATCH_PATH).append(" >/dev/null 2>&1 &' </dev/null; ")
            append("sleep 2; ")
            append("[ -f ").append(q(PID_PATH)).append(" ] && echo WATCH_STARTED || echo WATCH_FAILED; ")
            append("tail -n 20 ").append(q(LOG_PATH)).append(" 2>/dev/null")
        }

        val out = exec(command)

        // 真卸载
        val remove = exec("su -c 'pm uninstall $pkg' </dev/null 2>&1")
        if (!out.contains("WATCH_STARTED")) {
            error("监听未启动：\n" + out + "\n" + remove)
        }
        "$out\n\n卸载：$remove"
    }

    /**
     * 只保存配置并重启监听，不卸载面具。
     *
     * 用于「保存配置」按钮：改了目标应用或手势后立即生效，
     * 但不动面具本身（卸载由开关单独触发）。
     */
    fun saveConfig(
        context: Context,
        target: String,
        key: String,
        mode: String,
        count: Int,
        longMs: Int,
        contact: String = "",
    ): Result<String> = runCatching {
        val pkg = context.packageName

        ensureAsset(context, "customize/watch.sh", WATCH_PATH, executable = true)
        ensureAsset(context, "customize/xhovosu-watch.sh", SERVICE_PATH, executable = true)

        val conf = buildString {
            append("target=").append(target).append('\n')
            append("key=").append(key).append('\n')
            append("mode=").append(mode).append('\n')
            append("count=").append(count).append('\n')
            append("long=").append(longMs).append('\n')
            // 通讯录许可：通讯录里没有这个名字就不装回面具
            append("contact=").append(contact.trim()).append('\n')
        }

        val command = buildString {
            append("mkdir -p ").append(q(WORK_DIR)).append(" 2>/dev/null; ")
            append("cat > ").append(q(CONF_PATH)).append(" <<'XH_CONF'\n").append(conf).append("XH_CONF\n")
            append("chmod 0644 ").append(q(CONF_PATH)).append(" 2>/dev/null; ")
            append("printf '%s\\n' ").append(q(pkg)).append(" > ").append(q("$WORK_DIR/pkg")).append("; ")

            // 每次都重新镜像（覆盖），保存配置时也顺手更新一份
            append("src=$(pm path ").append(q(pkg)).append(" 2>/dev/null | head -1 | sed 's/^package://'); ")
            append("if [ -n \"\$src\" ] && [ -f \"\$src\" ]; then ")
            append("cp -f \"\$src\" ").append(q(APK_PATH)).append(" && chmod 0644 ").append(q(APK_PATH)).append("; ")
            append("fi; ")

            // 重启监听以应用新配置
            append("[ -f ").append(q(PID_PATH)).append(" ] && { ")
            append("old=$(cat ").append(q(PID_PATH)).append(" 2>/dev/null); ")
            append("[ -n \"\$old\" ] && kill -9 \"\$old\" 2>/dev/null; rm -f ").append(q(PID_PATH)).append("; }; ")
            append("su -c 'pkill -f xhovo/watch.sh' 2>/dev/null; ")
            append("su -c 'setsid sh ").append(WATCH_PATH).append(" >/dev/null 2>&1 &' </dev/null; ")
            append("sleep 2; ")
            append("[ -f ").append(q(PID_PATH)).append(" ] && echo SAVED || echo SAVE_FAILED")
        }

        val out = exec(command)

        // 只要配置文件落盘就算保存成功。
        // 没配目标应用、也没有规则时，watch.sh 会直接退出不写 pid，
        // 那种情况下不该报「保存失败」——配置本身是存下来的。
        val verify = exec(
            "[ -s " + q(CONF_PATH) + " ] && echo CONFIG_OK || echo CONFIG_MISSING",
        )

        when {
            !verify.contains("CONFIG_OK") -> "保存失败：配置文件未写入\n$out"
            out.contains("SAVED") -> "配置已保存，监听已重启"
            else -> "配置已保存（当前没有可监听的目标，监听未启动）"
        }
    }

    /**
     * 确保监听在跑。
     *
     * 三个调用点：打开 App（MainActivity 冷启动）、保存配置、开机广播。
     * 只在已配置目标且有安装包镜像时才启动，避免空跑。
     */
    fun sync(context: Context): Result<String> = runCatching {
        val config = readConfig()
        if (config.target.isBlank()) {
            return@runCatching "未配置目标应用，跳过"
        }
        if (mirrorSize() <= 0) {
            return@runCatching "安装包镜像不存在，跳过"
        }
        if (isWatching()) {
            return@runCatching "监听已在运行"
        }

        ensureAsset(context, "customize/watch.sh", WATCH_PATH, executable = true)

        val command = buildString {
            append("mkdir -p ").append(q(WORK_DIR)).append(" 2>/dev/null; ")
            append("[ -f ").append(q(PID_PATH)).append(" ] && rm -f ").append(q(PID_PATH)).append("; ")
            append("su -c 'setsid sh ").append(WATCH_PATH).append(" >/dev/null 2>&1 &' </dev/null; ")
            append("sleep 2; ")
            append("[ -f ").append(q(PID_PATH)).append(" ] && echo STARTED || echo FAILED")
        }

        val out = exec(command)
        if (out.contains("STARTED")) "监听已启动" else "监听启动失败：\n$out"
    }

    /**
     * 关闭隐形模式：停监听并删掉 stealth.conf。
     *
     * 配置必须删。监听进程靠 stealth.conf 的存在与否判断「是否还在隐形模式」，
     * 留着的话下次 App 冷启动或开机广播的 sync() 会把监听重新拉起来，
     * 用户会觉得开关关不掉。
     */
    fun disarm(): Result<String> = runCatching {
        val command = buildString {
            append("[ -f ").append(q(PID_PATH)).append(" ] && { ")
            append("old=$(cat ").append(q(PID_PATH)).append(" 2>/dev/null); ")
            append("[ -n \"\$old\" ] && kill -9 \"\$old\" 2>/dev/null; ")
            append("rm -f ").append(q(PID_PATH)).append("; }; ")
            append("su -c 'pkill -f xhovo/watch.sh' 2>/dev/null; ")
            append("rm -f ").append(q(CONF_PATH)).append(" 2>/dev/null; ")
            append("rm -f ").append(q("$WORK_DIR/.ksu_install")).append(" 2>/dev/null; ")
            append("echo STOPPED")
        }
        exec(command)
    }

    /** 监听是否在跑。 */
    fun isWatching(): Boolean {
        val out = exec(
            "pid=$(cat " + q(PID_PATH) + " 2>/dev/null); " +
                "[ -n \"\$pid\" ] && [ -r /proc/\$pid/cmdline ] && echo running || echo stopped",
        )
        return out.trim() == "running"
    }

    /** 面具是否安装（对当前用户可见）。 */
    fun isManagerInstalled(context: Context): Boolean {
        val out = exec("pm list packages -e 2>/dev/null | grep -c '^package:${context.packageName}$'")
        return (out.trim().toIntOrNull() ?: 0) > 0
    }

    /** APK 镜像是否存在。 */
    fun mirrorSize(): Long {
        val out = exec("[ -f " + q(APK_PATH) + " ] && stat -c %s " + q(APK_PATH) + " 2>/dev/null || echo 0")
        return out.trim().toLongOrNull() ?: 0L
    }

    /**
     * 从规则里推导「隐形唤回」的目标应用。
     *
     * 定制页不再单独选应用，直接用规则页配好的：
     * 优先取第一条「拉起面具」的启用规则，没有就退到任意启用规则。
     * 这样两个地方不会出现选了两次、还可能选得不一样的问题。
     */
    fun resolveTarget(context: Context): String {
        val rules = runCatching { RulesStore.load(context) }.getOrDefault(emptyList())
        val hit = rules.firstOrNull { it.enabled && it.action == "launch" && it.pkg.isNotBlank() }
            ?: rules.firstOrNull { it.enabled && it.pkg.isNotBlank() }
        return hit?.pkg.orEmpty()
    }

    /** 规则里所有「拉起面具」的目标包名，去重后返回。 */
    fun launchTargets(context: Context): List<String> {
        val rules = runCatching { RulesStore.load(context) }.getOrDefault(emptyList())
        return rules.filter { it.enabled && it.action == "launch" && it.pkg.isNotBlank() }
            .map { it.pkg }
            .distinct()
    }

    fun readConfig(): StealthConfig {
        val out = exec("cat " + q(CONF_PATH) + " 2>/dev/null")
        val map = out.lines()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toMap()
        return StealthConfig(
            target = map["target"].orEmpty(),
            key = map["key"] ?: "voldown",
            mode = map["mode"] ?: "long",
            count = map["count"]?.toIntOrNull() ?: 3,
            longMs = map["long"]?.toIntOrNull() ?: 1500,
            contact = map["contact"].orEmpty(),
        )
    }

    /**
     * 查通讯录里是否存在指定名字的联系人。
     *
     * 必须完整相等：设 123456 只认 123456，
     * 1234567 或 abc123456 这类包含关系不算命中。
     * 空名字视为不校验。
     */
    fun contactExists(name: String): Boolean {
        val target = name.trim()
        if (target.isEmpty()) return true

        // 逐行取出 display_name= 后面的部分，做整串相等比较
        fun anyLineMatches(output: String): Boolean = output.lines().any { line ->
            val marker = "display_name="
            val idx = line.indexOf(marker)
            idx >= 0 && line.substring(idx + marker.length).trim() == target
        }

        // 主路径：--where 是 SQL 等值查询
        val escaped = target.replace("'", "''")
        val hit = exec(
            "content query --uri content://com.android.contacts/data " +
                "--projection display_name --where " + q("display_name='$escaped'") + " 2>/dev/null",
        )
        if (anyLineMatches(hit)) return true

        // 兜底：某些 ROM 忽略 --where，全量扫一遍自己比对
        val all = exec(
            "content query --uri content://com.android.contacts/data " +
                "--projection display_name 2>/dev/null",
        )
        return anyLineMatches(all)
    }

    fun readLog(): String = exec("tail -n 40 " + q(LOG_PATH) + " 2>/dev/null")

    fun apkPath(): String = APK_PATH

    /** 手动安装检测：装回面具后恢复显示并清数据。 */
    fun onManualInstall(context: Context): Result<String> = runCatching {
        val pkg = context.packageName
        exec("su -c 'pm clear $pkg' </dev/null 2>&1")
    }

    private fun ensureAsset(context: Context, asset: String, target: String, executable: Boolean) {
        val stage = File(context.cacheDir, "xh_stealth_stage").apply {
            deleteRecursively()
            mkdirs()
        }
        val local = File(stage, asset.substringAfterLast('/'))

        // 必须转成 LF，Android 的 sh 不认 \r
        val text = context.assets.open(asset)
            .bufferedReader()
            .use { it.readText() }
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        local.writeText(text)

        val command = buildString {
            append("mkdir -p ").append(q(ADB_DIR)).append(" 2>/dev/null; ")
            append("cp -f ").append(q(local.absolutePath)).append(' ')
            append(q(target)).append(" 2>/dev/null; ")
            if (executable) append("chmod 0755 ").append(q(target)).append(" 2>/dev/null")
        }
        exec(command)
        stage.deleteRecursively()
    }

    private fun exec(command: String): String {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        getRootShell().newJob().add(command).to(out, err).exec()
        return (out + err).joinToString("\n").trim()
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

data class StealthConfig(
    val target: String = "",
    val key: String = "voldown",
    val mode: String = "long",
    val count: Int = 3,
    val longMs: Int = 1500,
    /** 通讯录许可：通讯录里必须存在这个名字才会装回面具，留空表示不校验。 */
    val contact: String = "",
)
