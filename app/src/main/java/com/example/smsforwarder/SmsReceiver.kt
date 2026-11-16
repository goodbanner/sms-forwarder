package com.example.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.provider.Telephony
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * 收到短信 → 读取正文 → 关键字匹配 → 趁广播 wakelock 还在，立刻内联发出。
 *
 * 为什么不直接用 Telephony.Sms.Intents.getMessagesFromIntent(intent)：
 * 在 MIUI/HyperOS(Android 16/17) 上，系统把 SMS_RECEIVED 广播里的 "pdus" extra
 * 改成了 Supplier(延迟反序列化对象) 而不是 Object[] of byte[]，
 * getMessagesFromIntent 内部强转 (byte[]) 会抛 ClassCastException，
 * 导致 onReceive 一进入就崩溃，转发永远不发生。
 * 改为从 content://sms 读取刚落库的短信，绕开 pdus 格式差异。
 *
 * 正文来源优先级：
 * 1) extras["msg_uri"]（系统广播带的具体 URI，如 content://sms/775）
 * 2) content://sms/inbox 按 date 倒序取最新一条
 *
 * 广播到达时短信通常已落库；极少数机型有延迟，做几次短重试。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val appContext = context.applicationContext
        val cfg = Config(appContext)
        if (cfg.keywords().isEmpty()) {
            Log.d(TAG, "未配置关键字，跳过")
            return
        }

        val msgUri = intent.getStringExtra("msg_uri")?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val pending = goAsync()
        Thread({
            try {
                // 短信落库可能比广播稍晚，最多重试几次
                var msg: Msg? = null
                for (attempt in 0 until DB_RETRY_TIMES) {
                    msg = readLatestSms(appContext, msgUri)
                    if (msg != null) break
                    SystemClock.sleep(DB_RETRY_DELAY_MS)
                }
                if (msg == null) {
                    Log.w(TAG, "未能从短信库读取正文（可能缺少 READ_SMS 权限或尚未落库）")
                    return@Thread
                }
                Log.d(TAG, "读取短信 sender=${msg.sender} body=${msg.body.take(40)}")

                if (!cfg.matches(msg.body)) {
                    Log.d(TAG, "未命中关键字")
                    return@Thread
                }

                val deadline = SystemClock.elapsedRealtime() + INLINE_BUDGET_MS
                if (SystemClock.elapsedRealtime() >= deadline) {
                    enqueueRetry(appContext, msg)
                } else {
                    when (Sender.send(cfg, msg.sender, msg.body, msg.ts, fast = true)) {
                        Sender.Outcome.Ok -> Log.i(TAG, "已实时转发 sender=${msg.sender}")
                        Sender.Outcome.PermanentFail -> Log.e(TAG, "配置错误，不再重试 sender=${msg.sender}")
                        Sender.Outcome.RetryableFail -> enqueueRetry(appContext, msg)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "内联转发异常", t)
            } finally {
                pending.finish()
            }
        }, "sms-inline").start()
    }

    /**
     * 从短信库读取正文。优先用广播带的具体 msg_uri，否则取收件箱最新一条。
     */
    private fun readLatestSms(context: Context, msgUri: Uri?): Msg? {
        val projection = arrayOf(COL_ADDR, COL_BODY, COL_DATE)
        var cursor: Cursor? = null
        try {
            if (msgUri != null) {
                cursor = context.contentResolver.query(msgUri, projection, null, null, null)
            }
            if (cursor == null || !cursor.moveToFirst()) {
                cursor?.close()
                cursor = context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI, projection, null, null, "$COL_DATE DESC"
                )
                if (cursor == null || !cursor.moveToFirst()) return null
            }
            val addr = cursor.getString(cursor.getColumnIndexOrThrow(COL_ADDR)) ?: "unknown"
            val body = cursor.getString(cursor.getColumnIndexOrThrow(COL_BODY)) ?: ""
            val date = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DATE))
            if (body.isEmpty()) return null
            return Msg(addr, body, date)
        } catch (e: Throwable) {
            Log.w(TAG, "读取短信库失败: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            cursor?.close()
        }
    }

    /** 降级通道：等网络可用后重试，指数退避 */
    private fun enqueueRetry(context: Context, m: Msg) {
        val uniqueName = "sms:${m.sender}:${m.ts}"
        val request: OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<DeliveryWorker>()
                .setInputData(
                    workDataOf(
                        DeliveryWorker.K_SENDER to m.sender,
                        DeliveryWorker.K_BODY to m.body,
                        DeliveryWorker.K_TS to m.ts
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    DeliveryWorker.BACKOFF_SECONDS,
                    DeliveryWorker.BACKOFF_TIME_UNIT
                )
                .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
    }

    private data class Msg(val sender: String, val body: String, val ts: Long)

    companion object {
        private const val TAG = "SmsReceiver"

        private const val COL_ADDR = "address"
        private const val COL_BODY = "body"
        private const val COL_DATE = "date"

        /** 内联发送的总预算 */
        private const val INLINE_BUDGET_MS = 8_000L

        /** 短信落库重试 */
        private const val DB_RETRY_TIMES = 5
        private const val DB_RETRY_DELAY_MS = 300L
    }
}
