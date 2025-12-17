package com.honda.pay;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * 短信处理记录数据库 - 简化版
 * 只记录短信处理状态，不存储验证码信息
 * 验证码判断由后台处理
 */
public class SmsDatabase extends SQLiteOpenHelper {

  private static final String TAG = "SmsDatabase";
  private static final String DATABASE_NAME = "sms_tracker.db";
  private static final int DATABASE_VERSION = 2; // ✅ 版本升级

  private static final String TABLE_SMS = "processed_sms";
  private static final String COL_ID = "id";
  private static final String COL_NOTIFICATION_KEY = "notification_key";
  private static final String COL_SENDER = "sender";
  private static final String COL_CONTENT_HASH = "content_hash";
  private static final String COL_POST_TIME = "post_time";
  private static final String COL_PROCESS_TIME = "process_time";
  private static final String COL_APP_START_TIME = "app_start_time";

  private static SmsDatabase instance;
  private static long currentAppStartTime = 0;

  private SmsDatabase(Context context) {
    super(context, DATABASE_NAME, null, DATABASE_VERSION);
  }

  public static synchronized SmsDatabase getInstance(Context context) {
    if (instance == null) {
      instance = new SmsDatabase(context.getApplicationContext());
    }
    return instance;
  }

  /**
   * 设置当前 App 启动时间
   * 应该在插件加载时调用
   */
  public static void setAppStartTime(long startTime) {
    currentAppStartTime = startTime;
    Log.d(TAG, "App start time set to: " + startTime);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    // ✅ 移除了 code 字段
    String createTable = "CREATE TABLE " + TABLE_SMS + " ("
      + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
      + COL_NOTIFICATION_KEY + " TEXT UNIQUE, "
      + COL_SENDER + " TEXT, "
      + COL_CONTENT_HASH + " TEXT, "
      + COL_POST_TIME + " INTEGER, "
      + COL_PROCESS_TIME + " INTEGER, "
      + COL_APP_START_TIME + " INTEGER"
      + ")";
    db.execSQL(createTable);

    // 创建索引加速查询
    db.execSQL("CREATE INDEX idx_notification_key ON " + TABLE_SMS + "(" + COL_NOTIFICATION_KEY + ")");
    db.execSQL("CREATE INDEX idx_content_hash ON " + TABLE_SMS + "(" + COL_CONTENT_HASH + ")");
    db.execSQL("CREATE INDEX idx_post_time ON " + TABLE_SMS + "(" + COL_POST_TIME + ")");

    Log.d(TAG, "Database created");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    // 简单粗暴：删除旧表，创建新表
    db.execSQL("DROP TABLE IF EXISTS " + TABLE_SMS);
    onCreate(db);
    Log.d(TAG, "Database upgraded from " + oldVersion + " to " + newVersion);
  }

  /**
   * 检查短信是否应该被处理 - 简化版
   * 不再检查验证码
   *
   * @return true = 应该处理, false = 应该忽略
   */
  public synchronized boolean shouldProcessSms(
    String notificationKey,
    String sender,
    String content,
    long postTime) {

    long now = System.currentTimeMillis();
    String contentHash = generateHash(sender, content);

    // ====================================
    // 检查 1: 通知 Key 是否已处理过
    // ====================================
    if (hasNotificationKey(notificationKey)) {
      Log.d(TAG, "❌ REJECT: Notification key already processed");
      return false;
    }

    // ====================================
    // 检查 2: 通知发布时间是否在 App 启动前
    // ====================================
    if (currentAppStartTime > 0 && postTime < currentAppStartTime) {
      Log.d(TAG, "❌ REJECT: Historical notification (posted before app start)");
      Log.d(TAG, "   Post time: " + postTime);
      Log.d(TAG, "   App start: " + currentAppStartTime);
      Log.d(TAG, "   Delta: " + (currentAppStartTime - postTime) + "ms");

      // 记录但不处理
      recordSms(notificationKey, sender, contentHash, postTime, now, false);
      return false;
    }

    // ====================================
    // 检查 3: 通知是否太旧 (超过10秒)
    // ====================================
    long age = now - postTime;
    if (age > 10000) {
      Log.d(TAG, "❌ REJECT: Notification too old (age: " + age + "ms)");
      recordSms(notificationKey, sender, contentHash, postTime, now, false);
      return false;
    }

    // ====================================
    // 检查 4: 相同内容是否在最近3秒内处理过
    // ====================================
    if (hasRecentContentHash(contentHash, 3000)) {
      Log.d(TAG, "❌ REJECT: Same content processed recently");
      recordSms(notificationKey, sender, contentHash, postTime, now, false);
      return false;
    }

    // ====================================
    // 全部通过 - 应该处理
    // ====================================
    Log.d(TAG, "✅ ACCEPT: SMS passed all checks");
    Log.d(TAG, "   Age: " + age + "ms");
    Log.d(TAG, "   Since app start: " + (now - currentAppStartTime) + "ms");

    recordSms(notificationKey, sender, contentHash, postTime, now, true);
    return true;
  }

  /**
   * 检查通知 Key 是否已存在
   */
  private boolean hasNotificationKey(String notificationKey) {
    SQLiteDatabase db = getReadableDatabase();
    Cursor cursor = null;
    try {
      cursor = db.query(
        TABLE_SMS,
        new String[]{COL_ID},
        COL_NOTIFICATION_KEY + " = ?",
        new String[]{notificationKey},
        null, null, null, "1"
      );
      return cursor.getCount() > 0;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  /**
   * 检查内容哈希是否在指定时间窗口内处理过
   */
  private boolean hasRecentContentHash(String contentHash, long windowMs) {
    SQLiteDatabase db = getReadableDatabase();
    Cursor cursor = null;
    try {
      long cutoffTime = System.currentTimeMillis() - windowMs;
      cursor = db.query(
        TABLE_SMS,
        new String[]{COL_ID},
        COL_CONTENT_HASH + " = ? AND " + COL_PROCESS_TIME + " > ?",
        new String[]{contentHash, String.valueOf(cutoffTime)},
        null, null, null, "1"
      );
      return cursor.getCount() > 0;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  /**
   * 记录短信处理信息 - 简化版
   */
  private void recordSms(
    String notificationKey,
    String sender,
    String contentHash,
    long postTime,
    long processTime,
    boolean accepted) {

    SQLiteDatabase db = getWritableDatabase();
    try {
      ContentValues values = new ContentValues();
      values.put(COL_NOTIFICATION_KEY, notificationKey);
      values.put(COL_SENDER, sender);
      values.put(COL_CONTENT_HASH, contentHash);
      values.put(COL_POST_TIME, postTime);
      values.put(COL_PROCESS_TIME, processTime);
      values.put(COL_APP_START_TIME, currentAppStartTime);

      long id = db.insertWithOnConflict(TABLE_SMS, null, values, SQLiteDatabase.CONFLICT_IGNORE);

      if (id > 0) {
        Log.d(TAG, "📝 Recorded: " + (accepted ? "ACCEPTED" : "REJECTED") +
          " - " + sender);
      }
    } catch (Exception e) {
      Log.e(TAG, "Error recording SMS", e);
    }
  }

  /**
   * 清理旧记录 (保留最近24小时)
   */
  public synchronized void cleanup() {
    SQLiteDatabase db = getWritableDatabase();
    try {
      // ✅ 改为 5 分钟（300000ms）
      long cutoffTime = System.currentTimeMillis() - (5 * 60 * 1000);  // 5 分钟前
      int deleted = db.delete(
        TABLE_SMS,
        COL_PROCESS_TIME + " < ?",
        new String[]{String.valueOf(cutoffTime)}
      );

      if (deleted > 0) {
        Log.d(TAG, "🧹 Cleaned up " + deleted + " old records (older than 5 min)");
      }
    } catch (Exception e) {
      Log.e(TAG, "Error cleaning up", e);
    }
  }



  /**
   * 生成内容哈希 - 简化版
   * 只使用发件人和内容
   */
  private String generateHash(String sender, String content) {
    String combined = sender + "|" + content;
    return String.valueOf(combined.hashCode());
  }

  /**
   * 获取统计信息 (用于调试)
   */
  public void logStatistics() {
    SQLiteDatabase db = getReadableDatabase();
    Cursor cursor = null;
    try {
      // 总记录数
      cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_SMS, null);
      if (cursor.moveToFirst()) {
        int total = cursor.getInt(0);
        Log.d(TAG, "📊 Total records: " + total);
      }
      cursor.close();

      // 本次启动的记录数
      cursor = db.query(
        TABLE_SMS,
        new String[]{"COUNT(*)"},
        COL_APP_START_TIME + " = ?",
        new String[]{String.valueOf(currentAppStartTime)},
        null, null, null
      );
      if (cursor.moveToFirst()) {
        int thisSession = cursor.getInt(0);
        Log.d(TAG, "📊 This session: " + thisSession);
      }
    } catch (Exception e) {
      Log.e(TAG, "Error getting statistics", e);
    } finally {
      if (cursor != null) cursor.close();
    }
  }
}
