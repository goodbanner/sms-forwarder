# 短信关键字转发（SmsForwarder）

收到含指定关键字的短信 → 自动 POST 到你指定的接收端（飞书群机器人 / 企业微信 / Bark / Telegram / 自建接口）。

不用 root 也能跑（普通 App + 授予"接收短信"权限）；**有 root 的好处**是：免交互授权、后台不被杀、可选接管默认短信应用、可选做系统级安装。

---

## 一、它是怎么工作的

```
系统短信到达
   └─ SmsReceiver（android.provider.Telephony.SMS_RECEIVED 广播，priority 999）
        ├─ 多段长短信按发件人拼接
        ├─ Config.matches(body)  关键字 / 正则匹配
        └─ WorkManager 入队（唯一任务名 = sender+时间戳，天然去重）
             └─ DeliveryWorker → OkHttp POST webhook
                   ├─ 2xx            → 成功
                   ├─ 4xx(429除外)   → 配置错，放弃
                   └─ 5xx/超时/断网 → 指数退避重试，最多 5 次
```

要点：
- `onReceive` 里不干网络活，立刻返回，避免被系统判定超时杀进程（比 `goAsync()` 更稳）。
- 断网时 WorkManager 会等网络恢复再发，不会丢。
- Manifest 里给 Receiver 加了 `android:permission="android.permission.BROADCAST_SMS"`，只有系统进程能给这个广播，防止别的 App 伪造短信触发你的转发。

## 二、编译

```bash
cd sms-forwarder
./gradlew :app:assembleDebug     # 产物 app/build/outputs/apk/debug/app-debug.apk
```

没有 Gradle Wrapper 时，用 Android Studio 直接打开本目录即可（它会自动生成 wrapper）。

## 三、安装 + 授权

**有 root（推荐）**

```bash
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/
adb shell
su
sh /data/local/tmp/grant_root.sh /data/local/tmp/app-debug.apk
```

脚本做了 4 件事：`pm install -g`、`pm grant RECEIVE_SMS`、`dumpsys deviceidle whitelist +包名`、`appops set RUN_IN_BACKGROUND allow`。

**没 root**：直接安装，打开 App 点"授予短信权限"，同意即可。

## 四、配置

打开 App 填三项：

| 项 | 说明 |
|---|---|
| Webhook 地址 | 接收端接口 URL |
| 关键字 | 每行一个，命中任意一个即转发；留空 = 不转发任何短信（安全默认） |
| 请求体模板 | 支持占位符，见下 |
| 额外请求头 | 一行一个 `Key: Value`，用于鉴权 |

占位符：`{{sender}}` 发件人、`{{time}}` 时间、`{{body}}` 短信正文、`{{timestamp}}` 毫秒时间戳、`{{body_raw}}` / `{{sender_raw}}` 不做转义的原值。

### 接收端模板示例

飞书群自定义机器人（默认模板就是它）：
```json
{"msg_type":"text","content":{"text":"【短信转发】\n发件人: {{sender}}\n时间: {{time}}\n内容: {{body}}"}}
```

企业微信群机器人：
```json
{"msgtype":"text","text":{"content":"【短信转发】{{sender}} | {{time}}\n{{body}}"}}
```

Bark（iOS 推送）——地址直接写在 Webhook 里，模板留空即可：
```
https://api.day.app/你的KEY/短信_{{sender}}_{{time}}_{{body_raw}}
```
（注意此时把"JSON 转义"取消勾选，否则正文里的换行会变成 `\n` 字面量）

Telegram：Webhook 填 `https://api.telegram.org/bot<TOKEN>/sendMessage`，模板：
```json
{"chat_id":"<CHAT_ID>","text":"{{sender}}\n{{time}}\n{{body}}"}
```

自建 HTTP 接口：
```json
{"from":"{{sender}}","at":"{{timestamp}}","text":"{{body}}"}
```

## 五、root 还能多做两件事

1. **接管默认短信应用**（`cmd role add-role-holder android.app.role.SMS com.example.smsforwarder`）
   变成默认短信 App 后可以：静默化通知、保证广播最先到达、必要时直接中断广播（`abortBroadcast()`），做到"只转发、不打扰"。
2. **装成系统应用**：把 apk 放进 `/system/priv-app/SmsForwarder/`，`chmod 644`，重启。适合做长期设备级服务；但危险权限仍需 `pm grant`，除非用平台签名。

如果哪天想读历史短信（比如 App 装晚了要补发），root 下可以直接查：
```sql
-- sqlite3 /data/data/com.android.providers.telephony/databases/mmssms.db
select address, date, body from sms where body like '%关键字%' order by date desc limit 20;
```
不过这只是补数据的手段，实时转发还是走广播最可靠。

## 六、提醒（重要）

- **别把验证码短信转发到不可信的第三方通道**，等于把账号口令交给对方；用自己的私有机器人/自建服务，且全程 HTTPS。
- 请求头里如果要放 token，别把 App 配置截图外传。
- 国产 ROM（MIUI/EMUI/ColorOS）即使有 root，也建议在系统设置里打开"自启动"和"后台运行"，否则重启后可能收不到广播。
- Google Play 对 `RECEIVE_SMS` 权限有严格审核，这个 App 只适合自用/侧载，不适合上架。
