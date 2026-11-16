package com.example.smsforwarder

import android.util.Log
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 实际发送逻辑。广播里（内联快发）和 Worker 里（失败重试）共用同一份，避免两套实现跑偏。
 */
object Sender {

    private const val TAG = "Sender"
    private val JSON = "application/json".toMediaType()

    sealed class Outcome {
        /** 2xx，成功 */
        object Ok : Outcome()
        /** 4xx 之类的配置错误，重试无意义 */
        object PermanentFail : Outcome()
        /** 5xx / 超时 / 断网，值得重试 */
        object RetryableFail : Outcome()
    }

    /** 共享 client；timeout 短的给广播内联路径用，长的给 Worker 用 */
    private val fastClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val normalClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * @param fast true = 广播内联路径，总耗时压在 6 秒内，抢在广播 wakelock 释放前发出去
     */
    fun send(
        cfg: Config,
        sender: String,
        body: String,
        ts: Long,
        fast: Boolean
    ): Outcome {
        val url = cfg.webhookUrl
        if (url.isBlank()) {
            Log.e(TAG, "webhook 为空，放弃发送")
            return Outcome.PermanentFail
        }

        val payload = render(cfg.bodyTemplate, sender, body, ts, cfg.jsonEscape)

        val builder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON))
            .header("Content-Type", "application/json; charset=utf-8")
        cfg.headers().forEach { (k, v) -> builder.header(k, v) }

        val client = if (fast) fastClient else normalClient

        return try {
            client.newCall(builder.build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        Log.i(TAG, "转发成功 code=${resp.code} fast=$fast")
                        Outcome.Ok
                    }
                    resp.code in 400..499 && resp.code != 429 -> {
                        Log.e(TAG, "配置类错误 code=${resp.code} ${resp.body?.string().orEmpty().take(200)}")
                        Outcome.PermanentFail
                    }
                    else -> {
                        Log.e(TAG, "服务端错误 code=${resp.code}")
                        Outcome.RetryableFail
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "网络异常 fast=$fast ${e.javaClass.simpleName}: ${e.message}")
            Outcome.RetryableFail
        }
    }

    /** 占位符替换。escape=true 时对插入值做 JSON 字符串转义。 */
    private fun render(tpl: String, sender: String, body: String, ts: Long, escape: Boolean): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
        fun v(s: String) = if (escape) jsonEscape(s) else s

        return tpl
            .replace("{{sender}}", v(sender))
            .replace("{{body}}", v(body))
            .replace("{{time}}", v(time))
            .replace("{{timestamp}}", ts.toString())
            .replace("{{sender_raw}}", sender)
            .replace("{{body_raw}}", body)
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code))
                    else sb.append(c)
            }
        }
        return sb.toString()
    }
}
