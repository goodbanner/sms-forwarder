package com.example.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 * 收到短信 → 拼接多段 → 关键字匹配 → **趁广播 wakelock 还在，立刻内联发出**。
 *
 * 为什么内联而不是只丢给 WorkManager：
 * 广播派发期间系统替我们持着 WakeLock，CPU 和网络都是醒的，此时发请求最快（通常几百毫秒）；
 * 如果只入队，一旦广播返回、CPU 重新休眠，WorkManager 的 JobScheduler 任务在 Doze 下可能被
 * 推迟到维护窗口才跑（没进电池白名单时可延迟几十分钟）。
 *
 * 内联只给 6 秒预算：超时/失败就降级成 WorkManager 重试，两边都不丢。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        val appContext = context.applicationContext
        val cfg = Config(appContext)
        if (cfg.keywords().isEmpty()) {
            Log.d(TAG, "未配置关键字，跳过")
            return
        }

        // 长短信会被拆成多段，先按发件人分组、按时间排序再拼接，避免关键字被拆到两段里而漏判
        val matched = parts.filterNotNull()
            .groupBy { it.originatingAddress ?: "unknown" }
            .mapNotNull { (sender, segs) ->
                // 框架按到达顺序给出各段，不做重排（SmsMessage 不暴露可靠的段序号）
                val body = segs.joinToString("") { it.displayMessageBody ?: "" }
                val ts = segs.minOf { it.timestampMillis }
                if (cfg.matches(body)) Msg(sender, body, ts) else null
            }

        if (matched.isEmpty()) {
            Log.d(TAG, "未命中关键字")
            return
        }

        // goAsync 保住广播的生命周期（系统给的执行窗口约 10s，这里留在 8s 内收工）
        val pending = goAsync()
        Thread({
            try {
                val deadline = SystemClock.elapsedRealtime() + INLINE_BUDGET_MS
                for (m in matched) {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        enqueueRetry(appContext, m)
                        continue
                    }
                    when (Sender.send(cfg, m.sender, m.body, m.ts, fast = true)) {
                        Sender.Outcome.Ok -> Log.i(TAG, "已实时转发 sender=${m.sender}")
                        Sender.Outcome.PermanentFail -> Log.e(TAG, "配置错误，不再重试 sender=${m.sender}")
                        Sender.Outcome.RetryableFail -> enqueueRetry(appContext, m)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "内联转发异常", t)
                matched.forEach { enqueueRetry(appContext, it) }
            } finally {
                pending.finish()
            }
        }, "sms-inline").start()
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

        /** 内联发送的总预算，留足余量给系统回收广播 */
        private const val INLINE_BUDGET_MS = 8_000L
    }
}
