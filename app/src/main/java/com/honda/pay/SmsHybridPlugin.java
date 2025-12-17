package com.honda.pay;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@CapacitorPlugin(
  name = "SmsHybrid",
  permissions = {
    @Permission(strings = { Manifest.permission.READ_SMS }, alias = "readSms"),
    @Permission(strings = { Manifest.permission.READ_PHONE_STATE }, alias = "phoneState"),
    @Permission(strings = { Manifest.permission.READ_PHONE_NUMBERS }, alias = "phoneNumber"),
    @Permission(strings = { Manifest.permission.SEND_SMS }, alias = "sendSms")
  }
)
public class SmsHybridPlugin extends Plugin
  implements SmsNotificationListener.SmsNotificationCallback {

  private static final String TAG = "SmsHybridPlugin";

  // 记录插件加载时间
  private static long pluginLoadTime = 0;

  // ✅ 简化：去掉防抖，直接处理
  private final Handler handler = new Handler(Looper.getMainLooper());

  // 短信发送相关
  private static final String ACTION_SMS_SENT = "com.honda.pay.SMS_SENT";
  private static final String ACTION_SMS_DELIVERED = "com.honda.pay.SMS_DELIVERED";
  private SmsSentReceiver smsSentReceiver;
  private SmsDeliveredReceiver smsDeliveredReceiver;
  private Map<String, PendingSmsInfo> pendingSmsMap = new HashMap<>();

  // 待发送短信信息
  private static class PendingSmsInfo {
    String phoneNumber;
    int slotIndex;
    int subId;
    long timestamp;

    PendingSmsInfo(String phoneNumber, int slotIndex, int subId) {
      this.phoneNumber = phoneNumber;
      this.slotIndex = slotIndex;
      this.subId = subId;
      this.timestamp = System.currentTimeMillis();
    }
  }

  @Override
  public void load() {
    super.load();

    // 记录插件加载时间
    pluginLoadTime = System.currentTimeMillis();

    // 初始化数据库并设置 App 启动时间
    SmsDatabase.setAppStartTime(pluginLoadTime);
    SmsDatabase db = SmsDatabase.getInstance(getContext());
    db.cleanup();
    db.logStatistics();

    // ========================================
    // ✅ 关键修复：立即注册 callback
    // ========================================
    // 这是导致"Android Studio 没有日志"的根本原因
    // 必须在 Plugin 加载时就设置，而不是等待回调被调用
    SmsNotificationListener.callback = this;
    Log.d(TAG, "========================================");
    Log.d(TAG, "✅ SmsNotificationListener callback registered");
    Log.d(TAG, "   Callback object: " + (SmsNotificationListener.callback != null ? "✅ NOT NULL" : "❌ NULL"));
    Log.d(TAG, "========================================");

    // 注册短信发送状态接收器
    registerSmsReceivers();

    Log.d(TAG, "========================================");
    Log.d(TAG, "✅ SmsHybridPlugin loaded");
    Log.d(TAG, "   Load time: " + pluginLoadTime);
    Log.d(TAG, "========================================");
  }

  @Override
  protected void handleOnDestroy() {
    super.handleOnDestroy();
    SmsNotificationListener.callback = null;

    // 注销广播接收器
    unregisterSmsReceivers();

    Log.d(TAG, "🗑️ Plugin destroyed");
  }

  // ========================================
  // 短信发送广播接收器
  // ========================================

  private void registerSmsReceivers() {
    Context context = getContext();

    if (smsSentReceiver == null) {
      smsSentReceiver = new SmsSentReceiver();
      IntentFilter sentFilter = new IntentFilter(ACTION_SMS_SENT);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(smsSentReceiver, sentFilter, Context.RECEIVER_NOT_EXPORTED);
      } else {
        context.registerReceiver(smsSentReceiver, sentFilter);
      }
    }

    if (smsDeliveredReceiver == null) {
      smsDeliveredReceiver = new SmsDeliveredReceiver();
      IntentFilter deliveredFilter = new IntentFilter(ACTION_SMS_DELIVERED);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(smsDeliveredReceiver, deliveredFilter, Context.RECEIVER_NOT_EXPORTED);
      } else {
        context.registerReceiver(smsDeliveredReceiver, deliveredFilter);
      }
    }

    Log.d(TAG, "✅ SMS receivers registered");
  }

  private void unregisterSmsReceivers() {
    Context context = getContext();

    if (smsSentReceiver != null) {
      try {
        context.unregisterReceiver(smsSentReceiver);
        smsSentReceiver = null;
        Log.d(TAG, "✅ SmsSentReceiver unregistered");
      } catch (Exception e) {
        Log.w(TAG, "⚠️ Failed to unregister SmsSentReceiver", e);
      }
    }

    if (smsDeliveredReceiver != null) {
      try {
        context.unregisterReceiver(smsDeliveredReceiver);
        smsDeliveredReceiver = null;
        Log.d(TAG, "✅ SmsDeliveredReceiver unregistered");
      } catch (Exception e) {
        Log.w(TAG, "⚠️ Failed to unregister SmsDeliveredReceiver", e);
      }
    }
  }

  /**
   * 短信发送状态接收器
   */
  private class SmsSentReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      String phoneNumber = intent.getStringExtra("phoneNumber");

      PendingSmsInfo info = pendingSmsMap.get(phoneNumber);

      JSObject event = new JSObject();
      event.put("phoneNumber", phoneNumber);
      event.put("timestamp", System.currentTimeMillis());

      if (info != null) {
        event.put("slotIndex", info.slotIndex);
        event.put("subId", info.subId);
      }

      switch (getResultCode()) {
        case Activity.RESULT_OK:
          Log.d(TAG, "✅ SMS sent successfully to: " + phoneNumber);
          event.put("status", "sent");
          notifyListeners("smsSent", event);
          break;

        case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
          Log.e(TAG, "❌ SMS send failed: Generic failure");
          event.put("status", "failed");
          event.put("errorCode", SmsManager.RESULT_ERROR_GENERIC_FAILURE);
          event.put("errorMessage", "Generic failure");
          notifyListeners("smsSent", event);
          pendingSmsMap.remove(phoneNumber);
          break;

        case SmsManager.RESULT_ERROR_NO_SERVICE:
          Log.e(TAG, "❌ SMS send failed: No service");
          event.put("status", "failed");
          event.put("errorCode", SmsManager.RESULT_ERROR_NO_SERVICE);
          event.put("errorMessage", "No service");
          notifyListeners("smsSent", event);
          pendingSmsMap.remove(phoneNumber);
          break;

        case SmsManager.RESULT_ERROR_NULL_PDU:
          Log.e(TAG, "❌ SMS send failed: Null PDU");
          event.put("status", "failed");
          event.put("errorCode", SmsManager.RESULT_ERROR_NULL_PDU);
          event.put("errorMessage", "Null PDU");
          notifyListeners("smsSent", event);
          pendingSmsMap.remove(phoneNumber);
          break;

        case SmsManager.RESULT_ERROR_RADIO_OFF:
          Log.e(TAG, "❌ SMS send failed: Radio off");
          event.put("status", "failed");
          event.put("errorCode", SmsManager.RESULT_ERROR_RADIO_OFF);
          event.put("errorMessage", "Radio off");
          notifyListeners("smsSent", event);
          pendingSmsMap.remove(phoneNumber);
          break;
      }
    }
  }

  /**
   * 短信送达状态接收器
   */
  private class SmsDeliveredReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      String phoneNumber = intent.getStringExtra("phoneNumber");

      PendingSmsInfo info = pendingSmsMap.remove(phoneNumber);

      JSObject event = new JSObject();
      event.put("phoneNumber", phoneNumber);
      event.put("timestamp", System.currentTimeMillis());

      if (info != null) {
        event.put("slotIndex", info.slotIndex);
        event.put("subId", info.subId);
      }

      if (getResultCode() == Activity.RESULT_OK) {
        Log.d(TAG, "✅ SMS delivered successfully to: " + phoneNumber);
        event.put("status", "delivered");
      } else {
        Log.e(TAG, "❌ SMS delivery failed to: " + phoneNumber);
        event.put("status", "failed");
        event.put("errorMessage", "Delivery failed");
      }

      notifyListeners("smsSent", event);
    }
  }

  // ========================================
  // 权限相关方法
  // ========================================

  @PluginMethod
  public void checkPermissions(PluginCall call) {
    boolean notificationGranted = isNotificationAccessGranted();
    boolean readSmsGranted = hasReadSmsPermission();
    boolean phoneStateGranted = hasPhoneStatePermission();
    boolean sendSmsGranted = hasSendSmsPermission();

    JSObject ret = new JSObject();
    ret.put("notificationAccess", notificationGranted);
    ret.put("readSms", readSmsGranted);
    ret.put("phoneState", phoneStateGranted);
    ret.put("sendSms", sendSmsGranted);
    ret.put("ready", notificationGranted);

    Log.d(TAG, "Permissions - Notification: " + notificationGranted
      + ", ReadSMS: " + readSmsGranted
      + ", PhoneState: " + phoneStateGranted
      + ", SendSMS: " + sendSmsGranted);
    call.resolve(ret);
  }

  @PluginMethod
  public void requestNotificationAccess(PluginCall call) {
    if (isNotificationAccessGranted()) {
      JSObject ret = new JSObject();
      ret.put("granted", true);
      call.resolve(ret);
      return;
    }

    try {
      Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      getContext().startActivity(intent);
      JSObject ret = new JSObject();
      ret.put("opened", true);
      call.resolve(ret);
    } catch (Exception e) {
      Log.e(TAG, "❌ Failed to open settings", e);
      call.reject("Failed to open settings", e);
    }
  }

  @PluginMethod
  public void requestReadSms(PluginCall call) {
    if (hasReadSmsPermission()) {
      JSObject ret = new JSObject();
      ret.put("granted", true);
      call.resolve(ret);
      return;
    }
    requestPermissionForAlias("readSms", call, "readSmsCallback");
  }

  @PermissionCallback
  private void readSmsCallback(PluginCall call) {
    boolean granted = hasReadSmsPermission();
    JSObject ret = new JSObject();
    ret.put("granted", granted);
    call.resolve(ret);
  }

  @PluginMethod
  public void requestPhoneState(PluginCall call) {
    if (hasPhoneStatePermission()) {
      JSObject ret = new JSObject();
      ret.put("granted", true);
      call.resolve(ret);
      return;
    }
    requestPermissionForAlias("phoneState", call, "phoneStateCallback");
  }

  @PermissionCallback
  private void phoneStateCallback(PluginCall call) {
    boolean granted = hasPhoneStatePermission();
    JSObject ret = new JSObject();
    ret.put("granted", granted);
    call.resolve(ret);
  }

  @PluginMethod
  public void requestSendSms(PluginCall call) {
    if (hasSendSmsPermission()) {
      JSObject ret = new JSObject();
      ret.put("granted", true);
      call.resolve(ret);
      return;
    }
    requestPermissionForAlias("sendSms", call, "sendSmsCallback");
  }

  @PermissionCallback
  private void sendSmsCallback(PluginCall call) {
    boolean granted = hasSendSmsPermission();
    JSObject ret = new JSObject();
    ret.put("granted", granted);
    call.resolve(ret);
  }

  private boolean isNotificationAccessGranted() {
    String packageName = getContext().getPackageName();
    String flat = Settings.Secure.getString(
      getContext().getContentResolver(),
      "enabled_notification_listeners"
    );
    if (flat != null) {
      String[] names = flat.split(":");
      for (String name : names) {
        ComponentName cn = ComponentName.unflattenFromString(name);
        if (cn != null && packageName.equals(cn.getPackageName())) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean hasReadSmsPermission() {
    return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_SMS)
      == PackageManager.PERMISSION_GRANTED;
  }

  private boolean hasPhoneStatePermission() {
    return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_PHONE_STATE)
      == PackageManager.PERMISSION_GRANTED;
  }

  private boolean hasSendSmsPermission() {
    return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.SEND_SMS)
      == PackageManager.PERMISSION_GRANTED;
  }

  // ========================================
  // ✅ 短信发送方法
  // ========================================

  @PluginMethod
  public void sendSms(PluginCall call) {
    // 1. 检查权限
    if (!hasSendSmsPermission()) {
      call.reject("SEND_SMS permission not granted");
      return;
    }

    // 2. 获取参数
    String phoneNumber = call.getString("phoneNumber");
    String message = call.getString("message");
    Integer simSlotIndex = call.getInt("simSlotIndex");
    Integer subId = call.getInt("subId");

    // 3. 验证参数
    if (phoneNumber == null || phoneNumber.isEmpty()) {
      call.reject("phoneNumber is required");
      return;
    }

    if (message == null || message.isEmpty()) {
      call.reject("message is required");
      return;
    }

    Log.d(TAG, "========================================");
    Log.d(TAG, "📤 Sending SMS");
    Log.d(TAG, "   To: " + phoneNumber);
    Log.d(TAG, "   Message: " + (message.length() > 50 ? message.substring(0, 50) + "..." : message));
    Log.d(TAG, "   Requested SlotIndex: " + simSlotIndex);
    Log.d(TAG, "   Requested SubId: " + subId);
    Log.d(TAG, "========================================");

    try {
      // 4. 获取 SmsManager
      SmsManager smsManager;
      int usedSubId = -1;
      int usedSlotIndex = -1;

      if (subId != null && subId > 0) {
        // 使用指定的 subId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
          smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
          usedSubId = subId;

          // 尝试找到对应的 slotIndex
          SmsHelper.SimSlotInfo[] slots = SmsHelper.getSimSlots(getContext());
          for (SmsHelper.SimSlotInfo slot : slots) {
            if (slot.subId == subId) {
              usedSlotIndex = slot.slotIndex;
              break;
            }
          }

          Log.d(TAG, "✅ Using SmsManager for subId: " + subId);
        } else {
          smsManager = SmsManager.getDefault();
          Log.w(TAG, "⚠️ Android version < 5.1, using default SmsManager");
        }
      } else if (simSlotIndex != null && simSlotIndex >= 0) {
        // 使用指定的 slotIndex
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
          // 查找 slotIndex 对应的 subId
          SmsHelper.SimSlotInfo[] slots = SmsHelper.getSimSlots(getContext());
          Integer foundSubId = null;

          for (SmsHelper.SimSlotInfo slot : slots) {
            if (slot.slotIndex == simSlotIndex && slot.hasSim) {
              foundSubId = slot.subId;
              usedSlotIndex = slot.slotIndex;
              break;
            }
          }

          if (foundSubId != null && foundSubId > 0) {
            smsManager = SmsManager.getSmsManagerForSubscriptionId(foundSubId);
            usedSubId = foundSubId;
            Log.d(TAG, "✅ Using SmsManager for slotIndex " + simSlotIndex + " (subId: " + foundSubId + ")");
          } else {
            Log.w(TAG, "⚠️ No SIM found at slotIndex " + simSlotIndex + ", using default");
            smsManager = SmsManager.getDefault();
          }
        } else {
          smsManager = SmsManager.getDefault();
          Log.w(TAG, "⚠️ Android version < 5.1, using default SmsManager");
        }
      } else {
        // 使用默认 SmsManager
        smsManager = SmsManager.getDefault();
        Log.d(TAG, "✅ Using default SmsManager");
      }

      // 5. 创建 PendingIntent
      Intent sentIntent = new Intent(ACTION_SMS_SENT);
      sentIntent.putExtra("phoneNumber", phoneNumber);

      PendingIntent sentPI = PendingIntent.getBroadcast(
        getContext(),
        0,
        sentIntent,
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
          ? PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
          : PendingIntent.FLAG_UPDATE_CURRENT
      );

      Intent deliveredIntent = new Intent(ACTION_SMS_DELIVERED);
      deliveredIntent.putExtra("phoneNumber", phoneNumber);

      PendingIntent deliveredPI = PendingIntent.getBroadcast(
        getContext(),
        0,
        deliveredIntent,
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
          ? PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
          : PendingIntent.FLAG_UPDATE_CURRENT
      );

      // 6. 记录待发送短信信息
      pendingSmsMap.put(phoneNumber, new PendingSmsInfo(phoneNumber, usedSlotIndex, usedSubId));

      // 7. 发送短信
      ArrayList<String> parts = smsManager.divideMessage(message);

      if (parts.size() == 1) {
        // 单条短信
        smsManager.sendTextMessage(phoneNumber, null, message, sentPI, deliveredPI);
        Log.d(TAG, "✅ Single SMS sent");
      } else {
        // 多条短信
        ArrayList<PendingIntent> sentIntents = new ArrayList<>();
        ArrayList<PendingIntent> deliveredIntents = new ArrayList<>();

        for (int i = 0; i < parts.size(); i++) {
          sentIntents.add(sentPI);
          deliveredIntents.add(deliveredPI);
        }

        smsManager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, deliveredIntents);
        Log.d(TAG, "✅ Multipart SMS sent (" + parts.size() + " parts)");
      }

      // 8. 返回成功结果
      JSObject result = new JSObject();
      result.put("success", true);
      result.put("usedSlotIndex", usedSlotIndex);
      result.put("usedSubId", usedSubId);
      result.put("messageId", String.valueOf(System.currentTimeMillis()));

      Log.d(TAG, "========================================");
      Log.d(TAG, "✅ SMS send initiated successfully");
      Log.d(TAG, "   Used SlotIndex: " + usedSlotIndex);
      Log.d(TAG, "   Used SubId: " + usedSubId);
      Log.d(TAG, "========================================");

      call.resolve(result);

    } catch (Exception e) {
      Log.e(TAG, "❌ Error sending SMS", e);

      JSObject result = new JSObject();
      result.put("success", false);
      result.put("error", e.getMessage());

      call.reject("Failed to send SMS: " + e.getMessage(), e);
    }
  }

  // ========================================
  // 监听控制
  // ========================================

  @PluginMethod
  public void startListening(PluginCall call) {
    boolean notificationGranted = isNotificationAccessGranted();
    boolean readSmsGranted = hasReadSmsPermission();
    boolean phoneStateGranted = hasPhoneStatePermission();

    Log.d(TAG, "========================================");
    Log.d(TAG, "🔍 Checking permissions before starting listener");
    Log.d(TAG, "   Notification: " + notificationGranted);
    Log.d(TAG, "   ReadSms: " + readSmsGranted);
    Log.d(TAG, "   PhoneState: " + phoneStateGranted);
    Log.d(TAG, "========================================");

    if (!notificationGranted) {
      Log.e(TAG, "❌ Notification access not granted");
      call.reject("Notification access not granted");
      return;
    }

    if (!readSmsGranted) {
      Log.e(TAG, "❌ READ_SMS permission not granted");
      call.reject("READ_SMS permission not granted");
      return;
    }

    if (!phoneStateGranted) {
      Log.w(TAG, "⚠️ READ_PHONE_STATE permission not granted");
      Log.w(TAG, "⚠️ Will use fallback strategy (may be less accurate)");
    }

    Log.d(TAG, "========================================");
    Log.d(TAG, "🎧 Starting SMS listener...");
    Log.d(TAG, "========================================");

    SmsNotificationListener.callback = this;
    Log.d(TAG, "✅ Listener started");
    call.resolve();
  }

  @PluginMethod
  public void stopListening(PluginCall call) {
    Log.d(TAG, "🛑 Stopping listener");
    SmsNotificationListener.callback = null;
    call.resolve();
  }

  // ========================================
  // ✅ SMS 通知回调 - 简化版
  // ========================================

  @Override
  public void onSmsNotificationReceived(
    String sender,
    String content,
    String notificationKey,
    long postTime
  ) {
    long now = System.currentTimeMillis();

    Log.d(TAG, "========================================");
    Log.d(TAG, "📬 SMS notification received");
    Log.d(TAG, "   Sender: " + sender);
    Log.d(TAG, "   Content length: " + content.length());
    Log.d(TAG, "   PostTime: " + postTime);
    Log.d(TAG, "   Age: " + (now - postTime) + "ms");
    Log.d(TAG, "========================================");

    // ✅ 数据库检查（不传验证码）
    SmsDatabase db = SmsDatabase.getInstance(getContext());
    boolean shouldProcess = db.shouldProcessSms(
      notificationKey,
      sender,
      content,
      postTime
    );

    if (!shouldProcess) {
      Log.d(TAG, "🚫 SMS rejected by database filter");
      return;
    }

    Log.d(TAG, "========================================");
    Log.d(TAG, "📨 SMS ACCEPTED for processing");
    Log.d(TAG, "   Sender: " + sender);
    Log.d(TAG, "========================================");

    // ✅ 直接处理，不防抖
    fetchFullSmsAndSend(sender, content, postTime);
  }

  @PluginMethod
  public void getSimStatus(PluginCall call) {
    Context ctx = getContext();
    SmsHelper.SimSlotInfo[] slots = SmsHelper.getSimSlots(ctx);

    JSArray arr = new JSArray();
    for (SmsHelper.SimSlotInfo s : slots) {
      JSObject o = new JSObject();
      o.put("slotIndex", s.slotIndex);
      o.put("hasSim", s.hasSim);
      o.put("subId", s.subId);
      o.put("iccId", s.iccId);
      o.put("carrierName", s.carrierName);
      o.put("phoneNumber", s.phoneNumber);
      arr.put(o);
    }

    JSObject ret = new JSObject();
    ret.put("slots", arr);

    call.resolve(ret);
  }

  // ========================================
  // ✅ 获取完整短信并发送 - 简化版
  // ========================================

  private void fetchFullSmsAndSend(String sender, String notificationContent, long postTime) {
    if (!hasReadSmsPermission()) {
      Log.w(TAG, "⚠️ No READ_SMS permission, using notification data only");

      // 直接使用通知内容
      SmsHelper.SmsData fallbackData = createFallbackSmsData(sender, notificationContent);
      sendToFrontend(fallbackData, sender, notificationContent, "notification_no_permission");
      return;
    }

    try {
      Log.d(TAG, "🔍 Searching for full SMS in DB ...");

      // ✅ 使用时间窗口匹配，不用验证码匹配
      SmsHelper.SmsData fullSms = SmsHelper.readLatestSms(getContext(), sender, postTime);

      if (fullSms == null) {
        Log.d(TAG, "   First attempt: not found, retrying after delay...");

        // 重试2次
        for (int retry = 1; retry <= 2; retry++) {
          try {
            Thread.sleep(retry == 1 ? 300 : 500);
          } catch (InterruptedException e) {
            break;
          }

          Log.d(TAG, "   Retry #" + retry + "...");
          fullSms = SmsHelper.readLatestSms(getContext(), sender, postTime);

          if (fullSms != null) {
            Log.d(TAG, "   ✅ Found on retry #" + retry);
            break;
          }
        }
      }

      if (fullSms != null) {
        Log.d(TAG, "✅ Found full SMS in database");
        Log.d(TAG, "   SubId: " + fullSms.subId);
        Log.d(TAG, "   SlotIndex: " + fullSms.slotIndex);
        Log.d(TAG, "   RecipientNumber: " + maskPhone(fullSms.recipientNumber));

        sendToFrontend(fullSms, fullSms.sender, fullSms.message, "database_complete");
        return;
      }

      Log.w(TAG, "⚠️ Full SMS not found in database after retries");
      Log.d(TAG, "📋 Using notification content with SIM detection...");

      SmsHelper.SmsData fallbackData = createFallbackSmsData(sender, notificationContent);

      if (fallbackData != null) {
        Log.d(TAG, "✅ Fallback data created:");
        Log.d(TAG, "   SubId: " + fallbackData.subId);
        Log.d(TAG, "   SlotIndex: " + fallbackData.slotIndex);
        Log.d(TAG, "   RecipientNumber: " + maskPhone(fallbackData.recipientNumber));

        sendToFrontend(fallbackData, sender, notificationContent, "notification_fallback");
      } else {
        Log.w(TAG, "⚠️ Fallback data creation failed (dual SIM or no SIM info)");
        sendToFrontend(null, sender, notificationContent, "notification_no_sim");
      }

    } catch (Exception e) {
      Log.e(TAG, "❌ Error fetching full SMS", e);

      SmsHelper.SmsData fallbackData = createFallbackSmsData(sender, notificationContent);
      sendToFrontend(fallbackData, sender, notificationContent, "notification_error");
    }
  }

  /**
   * ✅ 发送到前端 - 简化版
   * 不包含验证码字段
   */
  private void sendToFrontend(
    SmsHelper.SmsData smsData,
    String sender,
    String content,
    String source
  ) {
    JSObject data = new JSObject();

    // ========================================
    // ✅ 改为大驼峰格式
    // ========================================
    data.put("Sender", sender);                          // sender -> Sender
    data.put("Message", content);                        // message -> Message
    data.put("Timestamp", System.currentTimeMillis());   // timestamp -> Timestamp
    data.put("Source", source);                          // source -> Source

    // SIM 信息
    if (smsData != null) {
      data.put("SubId", smsData.subId);                              // subId -> SubId
      data.put("SimSlotIndex", smsData.slotIndex);                   // ✅ slotIndex -> SimSlotIndex (关键修复)
      data.put("SimNumber", smsData.simNumber);                      // simNumber -> SimNumber
      data.put("RecipientNumber", smsData.recipientNumber);          // recipientNumber -> RecipientNumber
      data.put("DisplayName", smsData.displayName);                  // displayName -> DisplayName
      data.put("IccId", smsData.iccId);                              // iccId -> IccId
      data.put("CarrierName", smsData.carrierName);                  // carrierName -> CarrierName
      data.put("NetworkCountry", smsData.networkCountry);            // networkCountry -> NetworkCountry
    } else {
      data.put("SubId", -1);
      data.put("SimSlotIndex", -1);                                  // ✅ slotIndex -> SimSlotIndex
    }

    Log.d(TAG, "========================================");
    Log.d(TAG, "📤 Sending to frontend");
    Log.d(TAG, "   Sender: " + sender);
    Log.d(TAG, "   Message length: " + content.length());
    Log.d(TAG, "   Source: " + source);
    Log.d(TAG, "   SubId: " + data.getInteger("SubId"));
    Log.d(TAG, "   SimSlotIndex: " + data.getInteger("SimSlotIndex"));  // ✅ 改这里
    Log.d(TAG, "========================================");

    notifyListeners("smsReceived", data);
  }

  /**
   * 创建回退数据
   * 单卡：返回该卡信息
   * 双卡：返回第一张卡作为默认值
   */
  private SmsHelper.SmsData createFallbackSmsData(String sender, String content) {
    if (!hasPhoneStatePermission()) {
      return null;
    }

    try {
      SmsHelper.SimSlotInfo[] slots = SmsHelper.getSimSlots(getContext());

      if (slots == null || slots.length == 0) {
        Log.d(TAG, "   ⚠️ No SIM slots available");
        return null;
      }

      // 收集所有活跃的 SIM 卡
      java.util.List<SmsHelper.SimSlotInfo> activeSlots = new java.util.ArrayList<>();

      for (SmsHelper.SimSlotInfo slot : slots) {
        Log.d(TAG, "   Checking slot: " + slot.toString());
        if (slot.hasSim) {
          activeSlots.add(slot);
        }
      }

      int activeCount = activeSlots.size();
      Log.d(TAG, "   Active SIM count: " + activeCount);

      if (activeCount == 0) {
        Log.d(TAG, "   ⚠️ No active SIM cards");
        return null;
      }

      // ✅ 使用第一张卡作为默认值
      SmsHelper.SimSlotInfo firstSim = activeSlots.get(0);

      SmsHelper.SmsData data = new SmsHelper.SmsData(
        sender,
        content,
        System.currentTimeMillis(),
        firstSim.subId
      );

      // ✅ 关键：正确填充 slotIndex
      data.slotIndex = firstSim.slotIndex;
      data.simNumber = firstSim.phoneNumber;
      data.recipientNumber = firstSim.phoneNumber;
      data.displayName = firstSim.carrierName;
      data.iccId = firstSim.iccId;
      data.carrierName = firstSim.carrierName;

      if (activeCount == 1) {
        Log.d(TAG, "   ✅ Single SIM detected");
        Log.d(TAG, "      Using slot " + data.slotIndex + ": " + maskPhone(data.recipientNumber));
      } else {
        Log.d(TAG, "   ⚠️ Multiple SIMs detected (" + activeCount + " cards)");
        Log.d(TAG, "      Defaulting to slot " + data.slotIndex + ": " + maskPhone(data.recipientNumber));
        Log.d(TAG, "      All possible recipients:");

        for (int i = 0; i < activeSlots.size(); i++) {
          SmsHelper.SimSlotInfo slot = activeSlots.get(i);
          Log.d(TAG, "         [" + i + "] Slot " + slot.slotIndex + ": " +
            maskPhone(slot.phoneNumber) + " (" + slot.carrierName + ")");
        }
      }

      return data;

    } catch (Exception e) {
      Log.e(TAG, "Error creating fallback data", e);
    }

    return null;
  }

  private String maskPhone(String phone) {
    if (phone == null || phone.isEmpty()) return "未知";
    if (phone.length() < 4) return phone;
    int len = phone.length();
    if (len <= 7) {
      return phone.substring(0, 3) + "****";
    } else {
      return phone.substring(0, 3) + "****" + phone.substring(len - 4);
    }
  }
}
