#!/system/bin/sh
# 在手机上用 root（如 Termux 的 su、或用 adb shell 后 su）执行：
#   su -c sh grant_root.sh /data/local/tmp/app-debug.apk
#
# 作用：安装 + 免交互授予短信/通知权限 + 后台白名单 + Doze 白名单。
set -e

PKG=com.example.smsforwarder
APK="$1"

if [ -n "$APK" ]; then
  echo ">> 安装 $APK"
  pm install -r -g "$APK" 2>/dev/null || pm install -r "$APK"
fi

echo ">> 授予收短信 / 读短信"
pm grant "$PKG" android.permission.RECEIVE_SMS
pm grant "$PKG" android.permission.READ_SMS

echo ">> 授予通知权限（常驻前台服务需要）"
pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

echo ">> 加入 Doze 白名单"
dumpsys deviceidle whitelist +"$PKG" 2>/dev/null || true

echo ">> 允许后台运行"
cmd appops set "$PKG" RUN_IN_BACKGROUND allow 2>/dev/null || true
cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
cmd appops set "$PKG" RECEIVE_SMS allow 2>/dev/null || true

echo ">> 激活待机桶"
am set-standby-bucket "$PKG" active 2>/dev/null || true

echo ">> 关闭系统休眠回收（Android 12+）"
cmd app_hibernation set-state "$PKG" false 2>/dev/null || true

echo ">> 启动常驻服务"
am start-foreground-service -n "$PKG/.KeepAliveService" 2>/dev/null || \
  am startservice -n "$PKG/.KeepAliveService" 2>/dev/null || true

# 可选：抢占"默认短信应用"角色（Android 10+）。
# 只有成为默认短信应用才能拦截通知栏、并保证广播最先到达；不需要就保持注释。
# cmd role add-role-holder android.app.role.SMS "$PKG"

echo ">> 完成。当前权限："
dumpsys package "$PKG" | grep -E "RECEIVE_SMS|READ_SMS|POST_NOTIFICATIONS" | head -n 6
