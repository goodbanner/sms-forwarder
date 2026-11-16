package com.example.smsforwarder

import android.content.Context
import java.util.Locale

/**
 * 配置全部放在 SharedPreferences 里，UI 改完立刻生效，广播里读的是同一份。
 */
class Config(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    /** 接收方 webhook 地址，如飞书群机器人 / 企业微信 / Bark / 自建 HTTP 接口 */
    var webhookUrl: String
        get() = sp.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(v) = sp.edit().putString(KEY_URL, v.trim()).apply()

    /** 关键字，每行一个；regexMode=true 时每行是一个正则 */
    var keywordsText: String
        get() = sp.getString(KEY_KEYWORDS, "") ?: ""
        set(v) = sp.edit().putString(KEY_KEYWORDS, v).apply()

    /** 请求体模板，支持占位符，见 README */
    var bodyTemplate: String
        get() = sp.getString(KEY_TPL, DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE
        set(v) = sp.edit().putString(KEY_TPL, v).apply()

    /** 关键字按正则匹配 */
    var regexMode: Boolean
        get() = sp.getBoolean(KEY_REGEX, false)
        set(v) = sp.edit().putBoolean(KEY_REGEX, v).apply()

    /** 占位符替换时做 JSON 转义（模板本身是 JSON 时必须开） */
    var jsonEscape: Boolean
        get() = sp.getBoolean(KEY_ESCAPE, true)
        set(v) = sp.edit().putBoolean(KEY_ESCAPE, v).apply()

    /** 额外请求头，格式：一行一个 `Key: Value` */
    var headersText: String
        get() = sp.getString(KEY_HEADERS, "") ?: ""
        set(v) = sp.edit().putString(KEY_HEADERS, v).apply()

    fun keywords(): List<String> =
        keywordsText.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun headers(): Map<String, String> =
        headersText.lines()
            .map { it.trim() }
            .filter { it.contains(':') }
            .associate {
                val i = it.indexOf(':')
                it.substring(0, i).trim() to it.substring(i + 1).trim()
            }

    /**
     * 关键字命中判断。关键字为空时一律 false（绝不误发全部短信）。
     *
     * 每次收到短信都会走这里，所以把解析结果缓存起来：
     * 只在关键字文本/模式变化时重建一次，平时就是几次字符串比对，耗时微秒级。
     */
    fun matches(body: String): Boolean {
        val m = matcher()
        if (m.isEmpty) return false
        return if (m.regex) {
            m.patterns.any { runCatching { it.containsMatchIn(body) }.getOrDefault(false) }
        } else {
            // 只在非正则模式下做一次 lowercase，避免 N 次 ignoreCase 比较
            val lower = body.lowercase(Locale.ROOT)
            m.literal.any { lower.contains(it) }
        }
    }

    /** 解析结果缓存，进程内跨 Config 实例共享（SmsReceiver 每次都会 new 一个 Config） */
    private fun matcher(): Matcher = synchronized(LOCK) {
        val raw = keywordsText
        val regex = regexMode
        val hit = cache
        if (hit != null && hit.raw == raw && hit.regex == regex) return hit

        val kw = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val built = if (regex) {
            Matcher(raw, true, emptyList(), kw.mapNotNull { p ->
                runCatching { Regex(p, RegexOption.IGNORE_CASE) }.getOrNull()
            })
        } else {
            Matcher(raw, false, kw.map { it.lowercase(Locale.ROOT) }, emptyList())
        }
        cache = built
        built
    }

    private class Matcher(
        val raw: String,
        val regex: Boolean,
        val literal: List<String>,
        val patterns: List<Regex>
    ) {
        val isEmpty: Boolean get() = literal.isEmpty() && patterns.isEmpty()
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_KEYWORDS = "keywords"
        private const val KEY_TPL = "tpl"
        private const val KEY_REGEX = "regex"
        private const val KEY_ESCAPE = "escape"
        private const val KEY_HEADERS = "headers"

        private const val DEFAULT_URL = ""

        /** 关键字解析结果缓存（进程级），避免每条短信都重新 split + 编译正则 */
        private val LOCK = Any()
        private var cache: Matcher? = null

        // 默认按飞书群机器人自定义消息格式；企业微信/Telegram 的模板见 README
        private const val DEFAULT_TEMPLATE =
            "{\"msg_type\":\"text\",\"content\":{\"text\":\"【短信转发】\\n发件人: {{sender}}\\n时间: {{time}}\\n内容: {{body}}\"}}"
    }
}
