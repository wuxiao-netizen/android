package com.honda.pay;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 短信辅助类 - 简化版
 * 移除验证码提取和匹配逻辑
 * 只负责读取短信和获取 SIM 信息
 */
public class SmsHelper {

  private static final String TAG = "SmsHelper";

  /**
   * 短信存储位置 - 按优先级排序
   */
  private static final String[] SMS_URIS_PRIORITY = {
    "content://sms/inbox",
    "content://sms/",
    "content://sms/conversations",
  };

  // ✅ 时间窗口：向前向后各 10 秒
  private static final long TIME_WINDOW_MS = 10000;

  // ----------------------------
  // ✅ 验证码解析（保留方法但不使用）
  // ----------------------------

  /**
   * 提取验证码
   * ⚠️ 保留此方法以防其他地方调用，但插件不再使用
   */
  public static String extractCode(String text) {
    if (text == null || text.isEmpty()) return null;

    Pattern[] patterns = {
      Pattern.compile("(?:验证码|动态码|校验码|code|CODE|Code)[:\\s:：]*([0-9]{4,8})"),
      Pattern.compile("\\b([0-9]{6})\\b"),
      Pattern.compile("\\b([0-9]{4,8})\\b")
    };

    for (Pattern pattern : patterns) {
      Matcher matcher = pattern.matcher(text);
      if (matcher.find()) {
        return matcher.group(1);
      }
    }
    return null;
  }

  // ----------------------------
  // ✅ 短信读取（按时间窗口匹配）
  // ----------------------------

  /**
   * 从短信数据库读取最新短信 - 简化版
   *
   * @param context 上下文
   * @param sender 发件人
   * @param timestamp 时间戳（用于时间窗口匹配）
   * @return 短信数据
   */
  public static SmsData readLatestSms(Context context, String sender, long timestamp) {
    Log.d(TAG, "========================================");
    Log.d(TAG, "🔍 Starting to search for full SMS");
    Log.d(TAG, "   Sender: " + sender);
    Log.d(TAG, "   Timestamp: " + timestamp);
    Log.d(TAG, "========================================");

    if (context == null) return null;

    ContentResolver resolver = context.getContentResolver();
    if (resolver == null) return null;

    // 按优先级依次尝试不同的 URI
    for (int i = 0; i < SMS_URIS_PRIORITY.length; i++) {
      String uriString = SMS_URIS_PRIORITY[i];
      Log.d(TAG, "📍 Priority " + (i + 1) + ": Trying " + uriString);

      try {
        SmsData sms = readFromUri(resolver, uriString, sender, timestamp);

        if (sms != null) {
          Log.d(TAG, "✅ Found matching SMS!");
          Log.d(TAG, "   Source: " + uriString);
          Log.d(TAG, "   Sender: " + sms.sender);
          Log.d(TAG, "   subId: " + sms.subId);
          Log.d(TAG, "========================================");

          // 尝试填充 SIM 信息
          fillSimInfo(context, sms);

          return sms;
        } else {
          Log.d(TAG, "   ⚠️ No match in " + uriString);
        }

      } catch (Exception e) {
        Log.w(TAG, "   ❌ Failed to read from: " + uriString, e);
      }
    }

    Log.w(TAG, "========================================");
    Log.w(TAG, "❌ No matching SMS found in any location");
    Log.w(TAG, "========================================");
    return null;
  }

  /**
   * 从指定 URI 读取短信 - 简化版
   *
   * ✅ 使用时间窗口 + 发件人匹配
   * ❌ 不再使用验证码匹配
   */
  private static SmsData readFromUri(
    ContentResolver resolver,
    String uriString,
    String targetSender,
    long targetTimestamp
  ) {
    if (targetSender == null || targetSender.isEmpty()) {
      Log.w(TAG, "      targetSender is empty, skip");
      return null;
    }

    Uri uri = Uri.parse(uriString);
    Cursor cursor = null;

    try {
      // ✅ 计算时间范围: 前后各 10 秒
      long minTime = targetTimestamp - 300000;  // ✅ 向前 5 分钟
      long maxTime = targetTimestamp + 10000;   // ✅ 向后 10 秒

      Log.d(TAG, "      Searching in time range:");
      Log.d(TAG, "      minTime: " + minTime);
      Log.d(TAG, "      target:  " + targetTimestamp);
      Log.d(TAG, "      maxTime: " + maxTime);

      // ✅ 查询条件: 接收的短信 + 发件人 + 时间范围
      String selection = "type = 1 AND address LIKE ? AND date >= ? AND date <= ?";
      String[] selectionArgs = new String[]{
        "%" + targetSender + "%",
        String.valueOf(minTime),
        String.valueOf(maxTime)
      };

      cursor = resolver.query(
        uri,
        null,
        selection,
        selectionArgs,
        "date DESC LIMIT 1"  // ✅ 只取最新的一条
      );

      if (cursor == null) {
        Log.w(TAG, "      Cursor is null");
        return null;
      }

      Log.d(TAG, "      SMS columns: " + Arrays.toString(cursor.getColumnNames()));

      int count = cursor.getCount();
      Log.d(TAG, "      Found " + count + " messages in time window");

      if (count == 0) {
        return null;
      }

      // 获取列索引
      int idxAddr = cursor.getColumnIndex("address");
      int idxBody = cursor.getColumnIndex("body");
      int idxDate = cursor.getColumnIndex("date");

      // 可能的订阅 ID 字段
      int idxSubId    = cursor.getColumnIndex("sub_id");
      int idxSubIdAlt = cursor.getColumnIndex("subscription_id");

      // 可能的槽位字段
      int idxSimId    = cursor.getColumnIndex("sim_id");
      int idxPhoneId  = cursor.getColumnIndex("phone_id");

      if (idxBody == -1 || idxAddr == -1 || idxDate == -1) {
        Log.w(TAG, "      Missing base columns");
        return null;
      }

      // ✅ 取第一条（最新的）
      if (cursor.moveToFirst()) {
        String address = cursor.getString(idxAddr);
        String body = cursor.getString(idxBody);
        long ts = cursor.getLong(idxDate);

        if (body == null || body.isEmpty()) {
          Log.w(TAG, "      Body is empty");
          return null;
        }

        // 订阅 ID
        int subId = -1;
        if (idxSubId != -1) {
          subId = cursor.getInt(idxSubId);
        }
        if (subId <= 0 && idxSubIdAlt != -1) {
          subId = cursor.getInt(idxSubIdAlt);
        }

        // 卡槽号
        int slotIndex = -1;
        if (idxPhoneId != -1) {
          slotIndex = cursor.getInt(idxPhoneId);
        } else if (idxSimId != -1) {
          slotIndex = cursor.getInt(idxSimId);
        }

        Log.d(TAG, "         ✅ Found SMS:");
        Log.d(TAG, "            From: " + address);
        Log.d(TAG, "            Time: " + ts);
        Log.d(TAG, "            Delta: " + (ts - targetTimestamp) + "ms");
        Log.d(TAG, "            SubId: " + subId);
        Log.d(TAG, "            SlotIndex: " + slotIndex);

        // 构造数据
        SmsData data = new SmsData(address, body, ts, subId);
        data.slotIndex = slotIndex;
        return data;
      }

    } catch (Exception e) {
      Log.e(TAG, "      Error reading from URI", e);
    } finally {
      if (cursor != null) cursor.close();
    }

    return null;
  }

  // ----------------------------
  // SIM 卡信息获取
  // ----------------------------

  /**
   * SIM 槽位信息
   */
  public static class SimSlotInfo {
    public int slotIndex;      // 槽位 0 / 1
    public boolean hasSim;     // 是否有卡
    public int subId;          // 订阅 ID（如果有卡）
    public String iccId;       // SIM 序列号
    public String carrierName; // 运营商名字
    public String phoneNumber; // 可能为 null

    @Override
    public String toString() {
      return "SimSlot{" +
        "slotIndex=" + slotIndex +
        ", hasSim=" + hasSim +
        ", subId=" + subId +
        ", iccId=" + (iccId != null ? "***" + iccId.substring(Math.max(0, iccId.length() - 4)) : "null") +
        ", carrier=" + carrierName +
        ", phoneNumber=" + (phoneNumber != null ? phoneNumber.substring(0, Math.min(3, phoneNumber.length())) + "****" : "null") +
        '}';
    }
  }

  /**
   * 获取所有 SIM 卡槽位信息
   */
  public static SimSlotInfo[] getSimSlots(Context context) {
    SimSlotInfo[] result = new SimSlotInfo[2];
    result[0] = new SimSlotInfo();
    result[0].slotIndex = 0;
    result[0].hasSim = false;
    result[0].subId = -1;

    result[1] = new SimSlotInfo();
    result[1].slotIndex = 1;
    result[1].hasSim = false;
    result[1].subId = -1;

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
      Log.d(TAG, "Android version < 5.1, SIM slot info unavailable");
      return result;
    }

    try {
      SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
      if (sm == null) {
        Log.w(TAG, "SubscriptionManager is null");
        return result;
      }

      if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        != PackageManager.PERMISSION_GRANTED) {
        Log.w(TAG, "No READ_PHONE_STATE permission");
        return result;
      }

      List<SubscriptionInfo> subscriptions = sm.getActiveSubscriptionInfoList();
      if (subscriptions == null || subscriptions.isEmpty()) {
        Log.d(TAG, "No active subscriptions");
        return result;
      }

      TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

      for (SubscriptionInfo info : subscriptions) {
        int idx = info.getSimSlotIndex();
        if (idx < 0 || idx > 1) continue;

        result[idx].hasSim = true;
        result[idx].subId = info.getSubscriptionId();
        result[idx].iccId = info.getIccId();
        result[idx].carrierName = info.getCarrierName() != null ? info.getCarrierName().toString() : null;

        // 尝试获取手机号
        String number = getPhoneNumberDirectly(info);
        if (TextUtils.isEmpty(number) && tm != null) {
          number = getPhoneNumberFromTelephonyManager(context, tm, info.getSubscriptionId());
        }
        if (TextUtils.isEmpty(number)) {
          number = getPhoneNumberFromSettings(context, info.getSubscriptionId());
        }

        result[idx].phoneNumber = number;
      }

    } catch (SecurityException e) {
      Log.w(TAG, "SecurityException getting SIM info", e);
    } catch (Exception e) {
      Log.e(TAG, "Error getting SIM info", e);
    }

    for (SimSlotInfo s : result) {
      Log.d(TAG, "SIM SLOT: " + s.toString());
    }

    return result;
  }

  /**
   * 填充 SIM 信息到 SmsData
   */
  private static void fillSimInfo(Context context, SmsData sms) {
    if (sms == null) return;

    try {
      if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        != PackageManager.PERMISSION_GRANTED) {
        Log.d(TAG, "⚠️ No READ_PHONE_STATE permission, skipping SIM info");
        return;
      }

      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
        Log.d(TAG, "⚠️ Android < 5.1, skipping SIM info");
        return;
      }

      SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
      TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

      if (sm == null || tm == null) {
        Log.w(TAG, "⚠️ SubscriptionManager or TelephonyManager is null");
        return;
      }

      List<SubscriptionInfo> subscriptions = sm.getActiveSubscriptionInfoList();
      if (subscriptions == null || subscriptions.isEmpty()) {
        Log.d(TAG, "⚠️ No active subscriptions");
        return;
      }

      SubscriptionInfo targetInfo = null;
      for (SubscriptionInfo info : subscriptions) {
        if (info.getSubscriptionId() == sms.subId) {
          targetInfo = info;
          break;
        }
      }

      if (targetInfo != null) {
        sms.displayName = targetInfo.getDisplayName() != null ? targetInfo.getDisplayName().toString() : null;
        sms.iccId = targetInfo.getIccId();
        sms.carrierName = targetInfo.getCarrierName() != null ? targetInfo.getCarrierName().toString() : null;

        String number = getPhoneNumberDirectly(targetInfo);
        if (TextUtils.isEmpty(number)) {
          number = getPhoneNumberFromTelephonyManager(context, tm, sms.subId);
        }
        if (TextUtils.isEmpty(number)) {
          number = getPhoneNumberFromSettings(context, sms.subId);
        }

        if (!TextUtils.isEmpty(number)) {
          sms.simNumber = number;
          sms.recipientNumber = number;
        }

        fillSimIdentifiers(context, tm, sms, sms.subId);
        logSimInfo(sms);
      }

    } catch (Exception e) {
      Log.e(TAG, "Error filling SIM info", e);
    }
  }

  /**
   * 方法1: 直接从 SubscriptionInfo 获取
   */
  private static String getPhoneNumberDirectly(SubscriptionInfo info) {
    try {
      String number = info.getNumber();
      if (number != null && !number.trim().isEmpty()) {
        Log.d(TAG, "✅ Got number from SubscriptionInfo");
        return number.trim();
      }
    } catch (SecurityException e) {
      Log.d(TAG, "⚠️ No READ_PHONE_NUMBERS permission for SubscriptionInfo");
    } catch (Exception e) {
      Log.w(TAG, "Error getting number from SubscriptionInfo", e);
    }
    return null;
  }

  /**
   * 方法2: 通过 TelephonyManager 获取
   */
  private static String getPhoneNumberFromTelephonyManager(Context context, TelephonyManager tm, int subId) {
    try {
      if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS)
        != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
          != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
          != PackageManager.PERMISSION_GRANTED) {
        Log.d(TAG, "⚠️ No phone number permissions for TelephonyManager");
        return null;
      }

      String number = null;

      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        number = tm.getLine1Number();
      } else {
        TelephonyManager specificTm = tm.createForSubscriptionId(subId);
        if (specificTm != null) {
          number = specificTm.getLine1Number();
        }

        if (TextUtils.isEmpty(number)) {
          number = tm.getLine1Number();
        }
      }

      if (!TextUtils.isEmpty(number)) {
        Log.d(TAG, "✅ Got number from TelephonyManager");
        return number.trim();
      }
    } catch (SecurityException e) {
      Log.d(TAG, "⚠️ SecurityException for TelephonyManager.getLine1Number");
    } catch (Exception e) {
      Log.w(TAG, "Error getting number from TelephonyManager", e);
    }
    return null;
  }

  /**
   * 方法3: 从系统设置读取
   */
  private static String getPhoneNumberFromSettings(Context context, int subId) {
    String number = null;

    try {
      String[] baseKeys = {
        "line1_number",
        "line1number",
        "phone_number",
        "sim_number",
        "sim_phone_number",
        "sim_line1_number"
      };

      for (String key : baseKeys) {
        String settingKey = key + "_" + subId;
        number = Settings.System.getString(context.getContentResolver(), settingKey);
        if (!TextUtils.isEmpty(number)) {
          Log.d(TAG, "✅ Got number from settings: " + settingKey);
          break;
        }
      }

      if (TextUtils.isEmpty(number)) {
        for (String key : baseKeys) {
          number = Settings.System.getString(context.getContentResolver(), key);
          if (!TextUtils.isEmpty(number)) {
            Log.d(TAG, "✅ Got number from settings: " + key);
            break;
          }
        }
      }

      if (TextUtils.isEmpty(number) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
        String[] globalKeys = {
          "line1_number",
          "msisdn"
        };

        for (String key : globalKeys) {
          number = Settings.Global.getString(context.getContentResolver(), key);
          if (!TextUtils.isEmpty(number)) {
            Log.d(TAG, "✅ Got number from Global settings: " + key);
            break;
          }
        }
      }

      return TextUtils.isEmpty(number) ? null : number.trim();
    } catch (Exception e) {
      Log.w(TAG, "Error getting number from settings", e);
      return null;
    }
  }

  /**
   * 获取其他 SIM 卡标识符
   */
  private static void fillSimIdentifiers(Context context, TelephonyManager tm, SmsData sms, int subId) {
    try {
      if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        != PackageManager.PERMISSION_GRANTED) {
        return;
      }

      try {
        sms.iccId = tm.getSimSerialNumber();
        sms.imsi = tm.getSubscriberId();
        sms.carrierName = tm.getSimOperatorName();
        sms.networkCountry = tm.getNetworkCountryIso();
      } catch (SecurityException e) {
        Log.d(TAG, "⚠️ No permission for SIM identifiers");
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        try {
          TelephonyManager specificTm = tm.createForSubscriptionId(subId);
          if (specificTm != null) {
            String specificIccId = specificTm.getSimSerialNumber();
            String specificImsi = specificTm.getSubscriberId();

            if (!TextUtils.isEmpty(specificIccId)) {
              sms.iccId = specificIccId;
            }
            if (!TextUtils.isEmpty(specificImsi)) {
              sms.imsi = specificImsi;
            }
          }
        } catch (Exception e) {
          // 忽略
        }
      }
    } catch (Exception e) {
      Log.w(TAG, "Error getting SIM identifiers", e);
    }
  }

  /**
   * 日志输出（脱敏）
   */
  private static void logSimInfo(SmsData sms) {
    Log.d(TAG, "=== SIM Information ===");
    Log.d(TAG, "subId: " + sms.subId);
    Log.d(TAG, "slotIndex: " + sms.slotIndex);
    Log.d(TAG, "simNumber: " + maskPhone(sms.simNumber));
    Log.d(TAG, "recipientNumber: " + maskPhone(sms.recipientNumber));

    if (!TextUtils.isEmpty(sms.displayName)) {
      Log.d(TAG, "displayName: " + sms.displayName);
    }

    if (!TextUtils.isEmpty(sms.iccId)) {
      Log.d(TAG, "ICCID: " + maskString(sms.iccId));
    }
    if (!TextUtils.isEmpty(sms.imsi)) {
      Log.d(TAG, "IMSI: " + maskString(sms.imsi));
    }

    if (!TextUtils.isEmpty(sms.carrierName)) {
      Log.d(TAG, "carrierName: " + sms.carrierName);
    }
    if (!TextUtils.isEmpty(sms.networkCountry)) {
      Log.d(TAG, "networkCountry: " + sms.networkCountry);
    }
    Log.d(TAG, "=======================");
  }

  private static String maskPhone(String phone) {
    if (phone == null || phone.isEmpty()) return "未知";
    if (phone.length() < 4) return phone;
    int len = phone.length();
    if (len <= 7) {
      return phone.substring(0, 3) + "****";
    } else {
      return phone.substring(0, 3) + "****" + phone.substring(len - 4);
    }
  }

  private static String maskString(String input) {
    if (TextUtils.isEmpty(input)) {
      return "null";
    }
    if (input.length() <= 4) {
      return "****";
    }
    return "***" + input.substring(input.length() - 4);
  }

  /**
   * 短信数据类
   */
  public static class SmsData {
    public String sender;
    public String message;
    public long timestamp;
    public int subId;
    public int slotIndex;
    public String simNumber;
    public String recipientNumber;

    // 新增字段
    public String displayName;
    public String iccId;
    public String imsi;
    public String carrierName;
    public String networkCountry;

    public SmsData(String sender, String message, long timestamp, int subId) {
      this.sender = sender;
      this.message = message;
      this.timestamp = timestamp;
      this.subId = subId;
      this.slotIndex = -1;
      this.simNumber = null;
      this.recipientNumber = null;
      this.displayName = null;
      this.iccId = null;
      this.imsi = null;
      this.carrierName = null;
      this.networkCountry = null;
    }
  }
}
