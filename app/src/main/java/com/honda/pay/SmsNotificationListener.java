package com.honda.pay;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 短信通知监听服务 - 简化版
 * 只负责收集和转发短信，不做任何验证码判断
 * 所有业务逻辑（包括验证码提取、长度判断等）都由后台处理
 */
public class SmsNotificationListener extends NotificationListenerService {

  private static final String TAG = "SmsNotificationListener";
  public static SmsNotificationCallback callback = null;

  // 记录服务启动时间
  private static long serviceStartTime = 0;

  private static final String[] SMS_PACKAGES = {
    "com.android.mms",
    "com.google.android.apps.messaging",
    "com.samsung.android.messaging",
    "com.huawei.message",
    "com.oppo.mms",
    "com.vivo.mms",
    "com.xiaomi.mms",
    "com.miui.mms",
    "com.sonyericsson.conversations",
    "com.android.messaging",
  };

  @Override
  public void onCreate() {
    super.onCreate();
    serviceStartTime = System.currentTimeMillis();
    Log.d(TAG, "========================================");
    Log.d(TAG, "✅ NotificationListener onCreate");
    Log.d(TAG, "   Start time: " + serviceStartTime);
    Log.d(TAG, "========================================");
  }

  @Override
  public void onListenerConnected() {
    super.onListenerConnected();
    serviceStartTime = System.currentTimeMillis();
    Log.d(TAG, "========================================");
    Log.d(TAG, "📡 NotificationListener CONNECTED");
    Log.d(TAG, "   Updated start time: " + serviceStartTime);
    Log.d(TAG, "========================================");
  }

  @Override
  public void onNotificationPosted(StatusBarNotification sbn) {
    String packageName = sbn.getPackageName();

    Log.d(TAG, "📢 onNotificationPosted called, package: " + packageName);

    // ✅ 只检查是否是短信应用
    if (!isSmsPackage(packageName)) {
      Log.d(TAG, "   ❌ Not SMS package, ignore");
      return;
    }

    try {
      long notificationTime = sbn.getPostTime();
      long now = System.currentTimeMillis();
      long timeSincePosted = now - notificationTime;

      Log.d(TAG, "========================================");
      Log.d(TAG, "📨 SMS Notification detected");
      Log.d(TAG, "   Package: " + packageName);
      Log.d(TAG, "   Post time: " + notificationTime);
      Log.d(TAG, "   Current time: " + now);
      Log.d(TAG, "   Time since posted: " + timeSincePosted + "ms");
      Log.d(TAG, "   Service start: " + serviceStartTime);

      // ✅ 过滤 1: 历史通知（在服务启动前的）
      if (notificationTime < serviceStartTime) {
        Log.w(TAG, "   ⚠️ IGNORING: Historical (before service start)");
        Log.d(TAG, "========================================");
        return;
      }

      // ✅ 过滤 2: 旧通知（5秒前的）
      if (timeSincePosted > 5000) {
        Log.w(TAG, "   ⚠️ IGNORING: Old notification (> 5s)");
        Log.d(TAG, "========================================");
        return;
      }

      Log.d(TAG, "   ✅ PROCESSING this notification");

      Notification notification = sbn.getNotification();
      if (notification == null) {
        Log.w(TAG, "   ⚠️ Notification is null");
        return;
      }

      Bundle extras = notification.extras;
      if (extras == null) {
        Log.w(TAG, "   ⚠️ Extras is null");
        return;
      }

      // ✅ 提取短信内容
      String title = extras.getString(Notification.EXTRA_TITLE);
      CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
      CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

      String content = bigText != null ? bigText.toString() :
        (text != null ? text.toString() : "");

      // ✅ 只要有内容就转发，不做任何判断
      if (content.isEmpty()) {
        Log.w(TAG, "   ⚠️ Content is empty");
        return;
      }

      Log.d(TAG, "   From: " + title);
      Log.d(TAG, "   Content: " + content);

      // ✅ 生成通知唯一标识
      String notificationKey = sbn.getKey();
      Log.d(TAG, "   Key: " + notificationKey);
      Log.d(TAG, "========================================");

      // ✅ 直接转发，不做任何验证码判断
      if (callback != null) {
        Log.d(TAG, "   📤 Forwarding SMS to plugin...");
        callback.onSmsNotificationReceived(
          title != null ? title : "",
          content,
          notificationKey,
          notificationTime
        );
      } else {
        Log.w(TAG, "   ⚠️ No callback registered!");
      }

    } catch (Exception e) {
      Log.e(TAG, "❌ Error in onNotificationPosted", e);
    }
  }

  /**
   * 判断是否是短信应用
   */
  private boolean isSmsPackage(String packageName) {
    if (packageName == null) return false;

    // 精确匹配已知短信应用
    for (String pkg : SMS_PACKAGES) {
      if (packageName.equals(pkg)) return true;
    }

    // 模糊匹配
    String lower = packageName.toLowerCase();
    return lower.contains("mms") ||
      lower.contains("message") ||
      lower.contains("sms");
  }

  /**
   * 回调接口 - 简化版
   * 移除了 code 和 isComplete 参数
   */
  public interface SmsNotificationCallback {
    void onSmsNotificationReceived(
      String sender,
      String content,
      String notificationKey,
      long postTime
    );
  }
}
