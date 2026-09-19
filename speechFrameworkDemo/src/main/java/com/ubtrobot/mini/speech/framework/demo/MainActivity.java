package com.ubtrobot.mini.speech.framework.demo;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

  private static final int RC_CAMERA = 0x7101;

  /** Đổi true khi cần hiện lại mã kích hoạt 6 số trên màn hình. */
  private static final boolean SHOW_ACTIVATION_CODE_ON_UI = true;

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(
          this, new String[] {Manifest.permission.CAMERA}, RC_CAMERA);
    }

    TextView activationCodeView = findViewById(R.id.activation_code);
    if (activationCodeView != null) {
      activationCodeView.setVisibility(
          SHOW_ACTIVATION_CODE_ON_UI ? View.VISIBLE : View.GONE);
    }
    ActivationEyeDisplay.setCodeDisplayListener(code ->
        runOnUiThread(() -> {
          if (activationCodeView != null) {
            activationCodeView.setText(code);
          }
        }));

    XiaozhiDeviceIdentityStore.Identity identity =
        XiaozhiDeviceIdentityStore.INSTANCE.getOrCreate(getApplicationContext());
    TextView deviceIdView = findViewById(R.id.device_id);
    if (deviceIdView != null) {
      deviceIdView.setText("token: " + identity.getDeviceId());
    }

    TextView hint = findViewById(R.id.tv_background_hint);
    if (hint != null) {
      hint.setText(R.string.main_background_hint);
    }

    try {
      com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlStore.INSTANCE
          .init(getApplicationContext());
      com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlHttpServer.INSTANCE
          .start(getApplicationContext());
      TextView deviceIdView2 = findViewById(R.id.device_id);
      if (deviceIdView2 != null) {
        String url = com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlHttpServer.INSTANCE
            .configUrl();
        String did = com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlStore.INSTANCE
            .resolveDeviceId();
        deviceIdView2.setText("Self-Control: " + url + "\nDevice-Id: " + did);
      }
    } catch (Exception e) {
      android.util.Log.w("MainActivity", "Self-Control UI: " + e.getMessage());
    }

    setupTransportSelector();
    // Không moveTaskToBack: speech chạy qua KeepAliveService; đẩy UI xuống
    // khiến mỗi lần bấm icon app bị “force” ra launcher.
  }

  private void setupTransportSelector() {
    RadioGroup group = findViewById(R.id.transport_group);
    RadioButton rbWs = findViewById(R.id.rb_transport_websocket);
    RadioButton rbMqtt = findViewById(R.id.rb_transport_mqtt);
    TextView status = findViewById(R.id.transport_status);
    if (group == null || rbWs == null || rbMqtt == null) {
      return;
    }

    XiaozhiTransportType current = XiaozhiTransportPreference.get(getApplicationContext());
    if (current == XiaozhiTransportType.MQTT) {
      rbMqtt.setChecked(true);
    } else {
      rbWs.setChecked(true);
    }
    updateTransportStatus(status);

    group.setOnCheckedChangeListener((g, checkedId) -> {
      XiaozhiTransportType type = (checkedId == R.id.rb_transport_mqtt)
          ? XiaozhiTransportType.MQTT
          : XiaozhiTransportType.WEBSOCKET;
      if (type == XiaozhiTransportType.MQTT && !XiaozhiMqttConfigStore.hasConfig()) {
        Toast.makeText(this, R.string.transport_mqtt_not_ready, Toast.LENGTH_LONG).show();
        rbWs.setChecked(true);
        return;
      }
      XiaozhiTransportPreference.set(getApplicationContext(), type);
      String label = DemoSpeech.INSTANCE.applyTransportFromUi(getApplicationContext());
      if (status != null) {
        status.setText(getString(R.string.transport_applied, label));
      }
      Toast.makeText(this, getString(R.string.transport_applied, label), Toast.LENGTH_SHORT).show();
    });
  }

  private void updateTransportStatus(TextView status) {
    if (status == null) {
      return;
    }
    if (!XiaozhiMqttConfigStore.hasConfig()) {
      status.setText(R.string.transport_mqtt_waiting_ota);
    } else {
      status.setText("");
    }
  }

  @Override protected void onDestroy() {
    ActivationEyeDisplay.setCodeDisplayListener(null);
    super.onDestroy();
  }
}
