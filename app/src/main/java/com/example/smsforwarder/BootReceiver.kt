package com.example.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机自启入口。
 *
 * 收到 BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED 后拉起常驻前台服务，
 * 让进程一直活着并维持动态注册的 SmsReceiver。
 *
 * 为什么需要它：
 * 1) 应用本身没有开机接收器时，开机后不会主动运行，只能等 SMS_RECEIVED 把它拉起——
 *    而 MIUI/HyperOS 常把这种"被动拉起"拦掉（尤其转成系统应用后仍在省电名单外）。
 * 2) 动态注册的 receiver 优先级高于静态的，能抢在安全中心 abort 之前拿到短信广播。
 *    动态注册需要一个活着的进程，所以必须有常驻服务兜底。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "收到 $action，拉起常驻服务")

        val svc = Intent(context, KeepAliveService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "启动常驻服务失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"

        /** 需要在 Manifest 的 intent-filter 里与这些 action 对应 */
        val ACTIONS = arrayOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
