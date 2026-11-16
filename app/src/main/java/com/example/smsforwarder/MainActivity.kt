package com.example.smsforwarder

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/** 极简配置页：webhook、关键字、请求体模板、授权、测试发送 */
class MainActivity : Activity() {

    private lateinit var urlEt: EditText
    private lateinit var kwEt: EditText
    private lateinit var tplEt: EditText
    private lateinit var headersEt: EditText
    private lateinit var regexCb: CheckBox
    private lateinit var escapeCb: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Config(this)
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 24.dp(), 24.dp(), 24.dp())
        }

        fun label(t: String) {
            root.addView(TextView(this).apply {
                text = t
                setPadding(0, 16.dp(), 0, 6.dp())
            })
        }

        label("接收方 Webhook 地址")
        urlEt = EditText(this).apply {
            setText(cfg.webhookUrl)
            hint = "https://open.feishu.cn/open-apis/bot/v2/hook/xxx"
            isSingleLine = true
        }
        root.addView(urlEt, lp())

        label("关键字（每行一个，命中任意一个即转发）")
        kwEt = EditText(this).apply {
            setText(cfg.keywordsText)
            hint = "验证码\n账单\n余额"
            minLines = 3
        }
        root.addView(kwEt, lp())

        regexCb = CheckBox(this).apply {
            text = "按正则匹配"
            isChecked = cfg.regexMode
        }
        root.addView(regexCb)

        label("请求体模板（占位符 {{sender}} {{time}} {{body}}）")
        tplEt = EditText(this).apply {
            setText(cfg.bodyTemplate)
            minLines = 4
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        root.addView(tplEt, lp())

        escapeCb = CheckBox(this).apply {
            text = "占位符做 JSON 转义（模板是 JSON 时保持勾选）"
            isChecked = cfg.jsonEscape
        }
        root.addView(escapeCb)

        label("额外请求头（每行 Key: Value，可留空）")
        headersEt = EditText(this).apply {
            setText(cfg.headersText)
            hint = "Authorization: Bearer xxx"
            minLines = 2
        }
        root.addView(headersEt, lp())

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val saveBtn = Button(this).apply { text = "保存" }
        val testBtn = Button(this).apply { text = "测试发送" }
        val permBtn = Button(this).apply { text = "授予短信权限" }
        btnRow.addView(saveBtn, lp(weight = 1f))
        btnRow.addView(testBtn, lp(weight = 1f))
        btnRow.addView(permBtn, lp(weight = 1f))
        root.addView(btnRow)

        val tip = TextView(this).apply {
            text = "保存后无需重启。若系统杀后台，请把本应用加入电池优化白名单（root 脚本已自动处理）。"
            textSize = 12f
            setPadding(0, 16.dp(), 0, 0)
        }
        root.addView(tip)

        saveBtn.setOnClickListener {
            persist(cfg)
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }

        testBtn.setOnClickListener {
            persist(cfg)
            if (cfg.webhookUrl.isBlank()) {
                Toast.makeText(this, "请先填写 Webhook 地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val req = OneTimeWorkRequestBuilder<DeliveryWorker>()
                .setInputData(
                    workDataOf(
                        DeliveryWorker.K_SENDER to "10086",
                        DeliveryWorker.K_BODY to "这是一条测试短信，关键字：${cfg.keywords().firstOrNull() ?: "验证码"}",
                        DeliveryWorker.K_TS to System.currentTimeMillis()
                    )
                )
                .build()
            WorkManager.getInstance(this).enqueue(req)
            Toast.makeText(this, "已提交测试发送，稍后查看接收端", Toast.LENGTH_SHORT).show()
        }

        permBtn.setOnClickListener { ensurePermission() }

        setContentView(ScrollView(this).apply { addView(root) })
        ensurePermission()
    }

    private fun lp(weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            if (weight > 0f) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            weight
        )

    private fun persist(cfg: Config) {
        cfg.webhookUrl = urlEt.text.toString()
        cfg.keywordsText = kwEt.text.toString()
        cfg.bodyTemplate = tplEt.text.toString()
        cfg.headersText = headersEt.text.toString()
        cfg.regexMode = regexCb.isChecked
        cfg.jsonEscape = escapeCb.isChecked
    }

    private fun ensurePermission() {
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), REQ_SMS)
    }
}

private const val REQ_SMS = 1001
