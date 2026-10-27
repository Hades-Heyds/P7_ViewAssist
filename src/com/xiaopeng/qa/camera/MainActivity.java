package com.xiaopeng.qa.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v1.24：主界面仅作配置界面，联动轮询与浮窗全部由常驻前台服务 CameraOverlayService 负责，
 * 退后台/退出本界面不影响服务；本界面定时拉取服务状态与日志刷新 UI。
 */
public class MainActivity extends Activity implements View.OnClickListener {

    private static final String TAG = "CameraAssist";
    private static final int REQ_CAMERA = 100;
    // v1.33：日志区滚动窗口，最多保留最近 500 行
    private static final int MAX_LOG_LINES = 500;
    // v1.44：开发页防误触，连续点击 10 次才跳转预览页
    private static final int CAMERA_TEST_CLICK_REQUIRED = 10;
    private static final long CAMERA_TEST_CLICK_WINDOW_MS = 2000L;
    private int cameraTestClickCount = 0;
    private long cameraTestLastClickTime = 0L;

    private Button btnOverlay;
    private Button btnCamera;
    private Button btnCameraTest;
    private EditText etHost;
    private EditText etPort;
    private Button swEnable;
    // v1.41：一键还原左右转向浮窗默认
    private Button btnResetOverlay;
    // v1.46：右浮窗对齐左浮窗（位置/大小/形状）
    private Button btnAlignRightToLeft;
    private Button btnClearLog;
    private TextView tvState;
    private TextView tvLog;
    private ScrollView scrollLog;
    private SharedPreferences prefs;

    // 本地镜像状态（每 1s 从服务同步刷新）
    private boolean linkOn = false;

    // 定时刷新服务状态与日志
    private final Handler logPoller = new Handler(Looper.getMainLooper());
    private boolean pollerRunning = false;
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!pollerRunning) return;
            linkOn = CameraOverlayService.isLinkActive();
            updateLinkButton();
            updateStateText();
            for (String line : CameraOverlayService.drainLogs()) {
                appendLog(line);
            }
            logPoller.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("cameraassist", MODE_PRIVATE);

        btnOverlay = findViewById(R.id.btn_overlay);
        btnCamera = findViewById(R.id.btn_camera);
        btnCameraTest = findViewById(R.id.btn_camera_test);
        etHost = findViewById(R.id.et_host);
        etPort = findViewById(R.id.et_port);
        swEnable = findViewById(R.id.sw_enable);
        btnResetOverlay = findViewById(R.id.btn_reset_overlay);
        btnAlignRightToLeft = findViewById(R.id.btn_align_right_to_left);
        btnClearLog = findViewById(R.id.btn_clear_log);
        tvState = findViewById(R.id.tv_state);
        tvLog = findViewById(R.id.tv_log);
        scrollLog = findViewById(R.id.scroll_log);

        btnOverlay.setOnClickListener(this);
        btnCamera.setOnClickListener(this);
        btnCameraTest.setOnClickListener(this);
        btnResetOverlay.setOnClickListener(this);
        btnAlignRightToLeft.setOnClickListener(this);
        btnClearLog.setOnClickListener(this);

        etHost.setText(prefs.getString("host", "127.0.0.1"));
        etPort.setText(String.valueOf(prefs.getInt("port", 5050)));

        swEnable.setOnClickListener(this);

        // 启动日志显示版本号，便于手机端直接核对安装版本
        appendLog("CameraAssist v1.50 启动（versionCode=50）");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionButtons();
        updateStateText();
        updateLinkButton();
        startLogPoller();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLogPoller();
    }

    @Override
    protected void onDestroy() {
        // v1.24：联动与浮窗全部由常驻前台服务负责，退出主界面不影响服务
        stopLogPoller();
        super.onDestroy();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_camera_test) {
            // v1.44：连续点击 10 次才进入预览页，每次点击提示开发页面无法访问
            long now = SystemClock.elapsedRealtime();
            if (now - cameraTestLastClickTime > CAMERA_TEST_CLICK_WINDOW_MS) {
                cameraTestClickCount = 0;
            }
            cameraTestLastClickTime = now;
            cameraTestClickCount++;
            Toast.makeText(this, "开发页面无法访问", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "btn_camera_test clicked " + cameraTestClickCount + "/" + CAMERA_TEST_CLICK_REQUIRED);
            if (cameraTestClickCount >= CAMERA_TEST_CLICK_REQUIRED) {
                cameraTestClickCount = 0;
                Toast.makeText(this, "正在打开摄像头预览页", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, CameraTestActivity.class));
            }
        } else if (id == R.id.btn_overlay) {
            if (hasOverlay()) {
                Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } else if (id == R.id.btn_camera) {
            if (checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "相机权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
        } else if (id == R.id.sw_enable) {
            if (linkOn) {
                disableLink();
            } else {
                enableLink();
            }
        } else if (id == R.id.btn_reset_overlay) {
            // v1.33：一键还原左右转向浮窗位置/形状/画面缩放到默认，浮窗显示中立即按默认刷新
            CameraOverlayService.resetOverlayDefaults(this);
            appendLog("已请求还原左右浮窗默认（位置/形状/画面）");
            Toast.makeText(this, "已还原浮窗默认", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.btn_align_right_to_left) {
            // v1.46：右浮窗对齐左浮窗（位置/大小/形状），立即生效并落盘
            CameraOverlayService.alignRightToLeft(this);
            appendLog("已请求右浮窗对齐左浮窗（位置/大小/形状）");
            Toast.makeText(this, "右浮窗已对齐左浮窗", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.btn_clear_log) {
            // v1.41：清空日志显示区与内存日志
            tvLog.setText("");
            CameraOverlayService.clearLogs();
            appendLog("日志已清空");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            boolean ok = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            appendLog(ok ? "相机权限已授予" : "相机权限被拒绝");
            refreshPermissionButtons();
        }
    }

    private boolean hasOverlay() {
        return Settings.canDrawOverlays(this);
    }

    private void refreshPermissionButtons() {
        boolean overlay = hasOverlay();
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        btnOverlay.setText(overlay ? "悬浮窗权限：已授予" : "申请悬浮窗权限（未授予）");
        btnCamera.setText(camera ? "相机权限：已授予" : "申请相机权限（未授予）");
    }

    private void updateStateText() {
        boolean link = CameraOverlayService.isLinkActive();
        String st;
        if (link) {
            st = "状态：联动运行中（前台服务常驻，退后台可弹窗）";
        } else {
            st = "状态：未运行";
        }
        tvState.setText(st);
    }

    private void enableLink() {
        String host = etHost.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (Exception e) {
            appendLog("端口格式错误");
            linkOn = false;
            updateLinkButton();
            return;
        }
        prefs.edit().putString("host", host).putInt("port", port).apply();
        // v1.41：开启联动时先经已建立的 ADB 连接自动授予相机与悬浮窗权限（失败不阻塞开关流程）
        appendLog("正在经 ADB 自动授权相机与悬浮窗权限…");
        autoGrant(host, port);
        if (!hasOverlay()) {
            appendLog("悬浮窗权限仍不可用（自动授权未生效或已跳过），浮窗可能无法弹出");
        }
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            appendLog("相机权限仍不可用（自动授权未生效或已跳过），预览可能黑屏");
        }
        // v1.24：轮询由常驻前台服务 CameraOverlayService 持有，Activity 不再直接创建 SignalMonitor
        CameraOverlayService.startLink(this, host, port);
        linkOn = CameraOverlayService.isLinkActive();
        updateLinkButton();
        updateStateText();
    }

    /**
     * v1.41：经 ADB shell 自动授权相机与悬浮窗权限。
     * 使用与 v1.40 相同的 adb_key.pem 建立已认证连接，以 shell 身份执行授权命令；
     * 已授权则跳过对应命令；任一步失败仅写日志，不阻塞打灯联动开关流程。
     */
    private void autoGrant(String host, int port) {
        byte[] pem;
        try {
            pem = readRawAdbKey();
        } catch (Exception e) {
            appendLog("读取 adb_key.pem 失败，跳过自动授权：" + e.getMessage());
            return;
        }
        AdbClient adb = null;
        try {
            adb = new AdbClient(host, port, pem);
            boolean cameraGranted = checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
            if (!cameraGranted) {
                String r = adb.exec("pm grant " + getPackageName() + " android.permission.CAMERA");
                appendLog("ADB 授权相机：" + (r == null || r.trim().isEmpty() ? "已执行（无输出）" : r.trim()));
            } else {
                appendLog("相机权限已授予，跳过授权命令");
            }
            if (!hasOverlay()) {
                String r = adb.exec("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                appendLog("ADB 授权悬浮窗：" + (r == null || r.trim().isEmpty() ? "已执行（无输出）" : r.trim()));
            } else {
                appendLog("悬浮窗权限已授予，跳过授权命令");
            }
        } catch (Exception e) {
            appendLog("ADB 自动授权失败（不影响联动开关）：" + e.getMessage());
        } finally {
            if (adb != null) adb.close();
        }
    }

    /** 读取内置 ADB 私钥（PKCS#8 PEM），供自动授权复用已认证连接。 */
    private byte[] readRawAdbKey() throws Exception {
        InputStream is = getResources().openRawResource(R.raw.adb_key);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            is.close();
        }
    }

    private void disableLink() {
        CameraOverlayService.stopLink(this);
        linkOn = false;
        updateLinkButton();
        updateStateText();
    }

    /** 启动 1s 定时刷新：同步服务状态与日志到主界面。 */
    private void startLogPoller() {
        if (pollerRunning) return;
        pollerRunning = true;
        logPoller.post(refreshRunnable);
    }

    /** 停止定时刷新。 */
    private void stopLogPoller() {
        pollerRunning = false;
        logPoller.removeCallbacks(refreshRunnable);
    }

    /** 刷新打灯联动按钮样式：开启=金色激活态，关闭=普通深色。 */
    private void updateLinkButton() {
        if (swEnable == null) return;
        swEnable.setBackgroundResource(linkOn ? R.drawable.bg_button_active : R.drawable.bg_button);
        swEnable.setTextColor(linkOn ? 0xFFFFD98A : 0xFFFFFFFF);
        swEnable.setText(linkOn ? "打灯联动：开启" : "打灯联动：关闭");
    }

    private void appendLog(String line) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String text = tvLog.getText().toString();
        text = text + "\n" + time + " " + line;
        String[] lines = text.split("\\n");
        if (lines.length > MAX_LOG_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - MAX_LOG_LINES; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
            text = sb.toString().trim();
        }
        tvLog.setText(text);
        // v1.33：新日志默认滚到底部；用户已手动上滑回看（不贴近底部）时不强制拉底
        scrollLog.post(() -> {
            View child = scrollLog.getChildAt(0);
            if (child != null
                    && scrollLog.getScrollY() + scrollLog.getHeight() >= child.getHeight() - 40) {
                scrollLog.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}
