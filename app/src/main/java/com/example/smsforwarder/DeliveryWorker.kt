package com.example.smsforwarder

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 兜底通道：广播内联快发失败（超时/断网/服务端 5xx）时由这里重试。
 * 指数退避，最多 5 次。
 */
class DeliveryWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val sender = inputData.getString(K_SENDER) ?: return Result.failure()
        val body = inputData.getString(K_BODY) ?: return Result.failure()
        val ts = inputData.getLong(K_TS, System.currentTimeMillis())

        val cfg = Config(applicationContext)

        return when (Sender.send(cfg, sender, body, ts, fast = false)) {
            Sender.Outcome.Ok -> Result.success()
            Sender.Outcome.PermanentFail -> Result.failure()
            Sender.Outcome.RetryableFail -> {
                if (runAttemptCount >= MAX_RETRY) {
                    Log.e(TAG, "重试次数用尽，放弃 sender=$sender")
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        }
    }

    companion object {
        private const val TAG = "DeliveryWorker"
        private const val MAX_RETRY = 5

        const val K_SENDER = "sender"
        const val K_BODY = "body"
        const val K_TS = "ts"

        /** 指数退避：30s、60s、120s、240s… 避免弱网下反复唤醒 radio */
        val BACKOFF_SECONDS = 30L
        val BACKOFF_TIME_UNIT = TimeUnit.SECONDS
    }
}
