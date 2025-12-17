package com.honda.pay;


import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

// ✅ 导入你的插件
import com.honda.pay.SmsHybridPlugin;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // ✅ 手动注册插件
    registerPlugin(SmsHybridPlugin.class);
  }
}
