package com.example.smsforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.util.Log

/**
 * 常驻前台服务。
 *
 * 职责：
 * 1) 让进程一直活着（低优先级静默通知，用户可在设置里关掉通知但服务仍在）；
 * 2) 维持一个**动态注册**的 SmsReceiver。动态 receiver 优先级高于 Manifest 静态注册，
 *    能在 MIUI 安全中心(priority 999 动态、requiredPermission=BROADCAST_SMS)之后、
 *    或某些 ROM 只往已运行进程投递时，稳定拿到 SMS_RECEIVED；
 * 3) START_STICKY：被系统杀掉后自动重建。
 *
 * 通知 ID 固定为 1，重要性 MIN，不响不震不亮屏，仅用于满足前台服务要求。
 */
class KeepAliveService : Service() {

    private var dynamicReceiver: SmsReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate，注册动态 SmsReceiver")
        registerDynamicReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (dynamicReceiver == null) registerDynamicReceiver()
        // START_STICKY：进程被杀后系统重建服务（intent 为 null 也没关系）
        return START_STICKY
    }

    private fun registerDynamicReceiver() {
        if (dynamicReceiver != null) return
        val r = SmsReceiver()
        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            // 动态注册的 priority 有效且高于 Manifest 静态注册，尽量排前
            priority = 1000
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(r, filter)
            }
            dynamicReceiver = r
            Log.i(TAG, "动态 SmsReceiver 注册成功")
        } catch (t: Throwable) {
            Log.e(TAG, "动态注册失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, APP_NAME, NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle(APP_NAME)
            .setContentText("正在监听短信")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 需显式声明前台服务类型
                startForeground(
                    NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onDestroy() {
        dynamicReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        dynamicReceiver = null
        Log.w(TAG, "onDestroy，服务即将被重建")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "sms_forwarder_alive"
        private const val NOTIF_ID = 1
        private const val APP_NAME = "短信转发"

        /** 幂等启动：无论从开机、Activity 还是外部调用都走这里 */
        fun start(context: Context) {
            val i = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "start 失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}
