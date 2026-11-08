package me.weishu.kernelsu.ui.customize

import android.content.Context
import me.weishu.kernelsu.ui.util.getRootShell
import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则引擎。
 *
 * 规则存两处：
 *   - SharedPreferences：App 内编辑用
 *   - /data/adb/xhovo/rules.conf：root 侧 watch.sh 执行用
 *
 * 每行 11 段，| 分隔（照参考项目格式）：
 *   enabled|pkg|key|mode|count|window|long|action|script|inputB64|enter
 *
 *   enabled  1/0
 *   pkg      目标应用包名
 *   key      volup / voldown / 留空（进入应用即触发）
 *   mode     long（长按）/ click（连点）
 *   count    click 模式的连点次数
 *   window   click 模式的统计窗口(ms)
 *   long     long 模式的长按时长(ms)
 *   action   launch（拉起面具）/ script（执行脚本）
 *   script   脚本绝对路径（action=script 时用）
 *   inputB64 预输入内容，base64（stdin 按行喂入）
 *   enter    1/0，每行末尾是否补回车
 */
data class Rule(
    val enabled: Boolean = true,
    val pkg: String = "",
    val key: String = "",
    val mode: String = "long",
    val count: Int = 3,
    val windowMs: Int = 2000,
    val longMs: Int = 1500,
    val action: String = "launch",
    val script: String = "",
    val inputB64: String = "",
    val enter: Boolean = true,
)

object RulesStore {

    private const val PREFS = "xhovo_rules"
    private const val KEY_RULES = "rules_json"
    private const val CONF = "/data/adb/xhovo/rules.conf"
    private const val WORK = "/data/adb/xhovo/work"

    // ---------- 读 ----------
    fun load(context: Context): List<Rule> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RULES, null)
        if (raw.isNullOrBlank()) return emptyList()

        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Rule(
                    enabled = o.optBoolean("enabled", true),
                    pkg = o.optString("pkg", ""),
                    key = o.optString("key", ""),
                    mode = o.optString("mode", "long"),
                    count = o.optInt("count", 3),
                    windowMs = o.optInt("window", 2000),
                    longMs = o.optInt("long", 1500),
                    action = o.optString("action", "launch"),
                    script = o.optString("script", ""),
                    inputB64 = o.optString("inputB64", ""),
                    enter = o.optBoolean("enter", true),
                )
            }
        }.getOrDefault(emptyList())
    }

    // ---------- 写 ----------
    /**
     * 保存到 SharedPreferences 并镜像到 rules.conf。
     *
     * 注意：rules.conf 最后必须带换行，否则 shell 的
     * `while read` 在 EOF 处读不到最后一行，整份规则等于失效。
     */
    fun save(context: Context, rules: List<Rule>): Result<Unit> = runCatching {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("enabled", r.enabled)
                    put("pkg", r.pkg)
                    put("key", r.key)
                    put("mode", r.mode)
                    put("count", r.count)
                    put("window", r.windowMs)
                    put("long", r.longMs)
                    put("action", r.action)
                    put("script", r.script)
                    put("inputB64", r.inputB64)
                    put("enter", r.enter)
                },
            )
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RULES, arr.toString())
            .apply()

        // 生成 shell 侧规则文件
        val lines = rules.joinToString("\n") { r ->
            listOf(
                if (r.enabled) "1" else "0",
                r.pkg,
                r.key,
                r.mode,
                r.count.toString(),
                r.windowMs.toString(),
                r.longMs.toString(),
                r.action,
                r.script,
                r.inputB64,
                if (r.enter) "1" else "0",
            ).joinToString("|")
        }

        val content = if (lines.isBlank()) "" else lines + "\n"

        val stage = java.io.File(context.cacheDir, "rules.conf").apply {
            parentFile?.mkdirs()
            writeText(content)
        }

        val command = buildString {
            append("mkdir -p ").append(q(WORK)).append(" 2>/dev/null; ")
            append("cp -f ").append(q(stage.absolutePath)).append(' ').append(q(CONF)).append(" 2>/dev/null; ")
            append("chmod 0600 ").append(q(CONF)).append(" 2>/dev/null; ")
            // 用 wc 校验行数，确保末尾换行生效
            append("echo -n 'LINES='; wc -l < ").append(q(CONF)).append(" 2>/dev/null || echo 0")
        }

        val out = exec(command)
        stage.delete()

        if (rules.isNotEmpty() && !out.contains("LINES=")) error("规则写入失败")
    }

    fun readConfLines(): String = exec("cat " + q(CONF) + " 2>/dev/null || echo '（无规则文件）'")

    fun logPath(): String = "$WORK/exec.log"

    fun readLog(lines: Int = 60): String =
        exec("tail -n $lines " + q("$WORK/exec.log") + " 2>/dev/null")

    private fun exec(command: String): String {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        getRootShell().newJob().add(command).to(out, err).exec()
        return (out + err).joinToString("\n").trim()
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
