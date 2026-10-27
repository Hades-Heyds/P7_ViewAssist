package com.xiaopeng.qa.camera;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.graphics.Outline;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前台服务 + 系统悬浮窗（TYPE_APPLICATION_OVERLAY）+ Camera2/TextureView 实时预览。
 * v1.24：后台常驻架构。打灯联动开启（或手动浮窗）即启动本服务为前台服务并保持运行，
 * SignalMonitor 轮询移入服务内，收到转向信号由服务直接 show/hide/切换 overlay，
 * 彻底摆脱对 Activity 前台状态的依赖（Android 12+ 后台 startForegroundService 会被拒绝，
 * 服务提前在前台运行才能在其他应用上方弹窗）。
 */
public class CameraOverlayService extends Service implements SignalMonitor.Listener {

    private static final String TAG = "CameraAssist";
    private static final String CHANNEL_ID = "cameraassist_overlay";
    private static final int NOTIF_ID = 1;
    private static final String PREFS_NAME = "cameraassist";

    // 服务命令 action
    private static final String ACTION_START_LINK = "com.xiaopeng.qa.camera.START_LINK";
    private static final String ACTION_STOP_LINK = "com.xiaopeng.qa.camera.STOP_LINK";
    private static final String ACTION_SHOW_MANUAL = "com.xiaopeng.qa.camera.SHOW_MANUAL";
    private static final String ACTION_HIDE_MANUAL = "com.xiaopeng.qa.camera.HIDE_MANUAL";
    private static final String ACTION_HIDE_ALL = "com.xiaopeng.qa.camera.HIDE_ALL";
    // v1.30：浮窗大小调整与位置记录
    private static final String ACTION_RECORD_POS = "com.xiaopeng.qa.camera.RECORD_POS";
    private static final String ACTION_APPLY_SIZE = "com.xiaopeng.qa.camera.APPLY_SIZE";
    // v1.32：还原左右转向浮窗配置到默认
    private static final String ACTION_RESET_OVERLAY = "com.xiaopeng.qa.camera.RESET_OVERLAY";
    // v1.46：右浮窗对齐左浮窗（位置/大小/形状）
    private static final String ACTION_ALIGN_RIGHT_TO_LEFT = "com.xiaopeng.qa.camera.ALIGN_RIGHT_TO_LEFT";

    // ---- v1.33：画面与窗口解耦模型 ----
    /** 画面基准宽度 = 屏宽*0.4（即默认窗口宽，不随窗口变化），高度按 16:9。 */
    private static final float OVERLAY_DEF_W_RATIO = 0.4f;
    /** 双指画面缩放 cam_scale 范围（默认 1.0）。 */
    private static final float CAM_SCALE_MIN = 0.2f;
    private static final float CAM_SCALE_MAX = 4.0f;
    private static final float CAM_SCALE_DEFAULT = 1.0f;
    /** 窗口边缘拖动最小尺寸 = 屏宽/屏高 * 0.15。 */
    private static final float MIN_SIZE_RATIO = 0.15f;
    // ---- v1.51：位置自动吸附常量 ----
    /** 吸附阈值（dp）：靠得很近才吸，禁止远距离误吸。 */
    private static final int SNAP_THRESHOLD_DP = 20;
    /** 车机左侧竖向快捷边栏右缘吸附线 = 屏宽*0.08。 */
    private static final float LEFT_BAR_RATIO = 0.08f;
    /** 车机底部横向底栏上缘吸附线 = 屏高*0.92。 */
    private static final float BOTTOM_BAR_RATIO = 0.92f;
    /** 预览 buffer 尺寸（16:9，与画面基准同比例）。 */
    private static final int BUFFER_W = 1280;
    private static final int BUFFER_H = 720;
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";

    /** 联动是否开启（服务是否在轮询转向灯），供主界面读取刷新状态。 */
    private static final AtomicBoolean linkActive = new AtomicBoolean(false);
    /** 手动浮窗当前是否显示，供主界面读取刷新状态。 */
    private static final AtomicBoolean manualVisible = new AtomicBoolean(false);
    /** 服务侧运行日志（最近 50 行），供主界面定时拉取显示。 */
    private static final List<String> recentLogs =
            Collections.synchronizedList(new ArrayList<String>());

    private WindowManager wm;
    private View overlayView;
    private TextureView textureView;
    private TextView labelView;
    // ---- v1.38：相机输出 buffer 尺寸固定为 1280x720。注意 onSurfaceTextureAvailable 的 width/height
    // 是 TextureView 视图尺寸（随浮窗窗口变化），不是相机输出 buffer 尺寸，严禁写入 bufferW/H——
    // 若误用作渲染 scale 分母，拖浮窗边框改窗口形状时画面比例会跟着窗口变形。----
    private int bufferW = BUFFER_W;
    private int bufferH = BUFFER_H;

    // ---- v1.33：编辑模式（LEFT/RIGHT 转向浮窗；MANUAL 手动浮窗保持 v1.31 可拖动现状）----
    /** 当前浮窗是否处于编辑模式（金色描边 + 四边边缘手柄 + 单指拖动/双指缩放画面）。 */
    private boolean editMode = false;
    /** 当前方向画面缩放 camScale（0.2~4.0，默认1.0；编辑会话双指修改，退出时写入方向键）。 */
    private float camScale = CAM_SCALE_DEFAULT;
    // ---- v1.34：编辑模式画面平移（panX/panY，默认0；编辑会话单指平移，退出时写入方向键）----
    /** 当前方向画面横向平移量（px，正右负左，0=居中）。 */
    private float panX = 0f;
    /** 当前方向画面纵向平移量（px，正下负上，0=居中）。 */
    private float panY = 0f;
    /** 编辑模式视觉元素：金色描边边框、四边边缘手柄（改窗口形状）、底部角标。 */
    private View borderView;
    private View cropHandleL;
    private View cropHandleR;
    private View cropHandleT;
    private View cropHandleB;
    // ---- v1.51：四角等比例缩放手柄（编辑模式显示，拖动窗口角等比缩放）----
    private View cropHandleTL;
    private View cropHandleTR;
    private View cropHandleBL;
    private View cropHandleBR;
    private TextView editHintView;

    private CameraManager cameraManager;
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private Surface previewSurface;

    private String direction = "LEFT";
    /** 当前已打开摄像头对应的方向（LEFT/RIGHT/MANUAL），用于去抖分支判断是否需要重开摄像头。 */
    private String openedDirection = null;
    /** 当前是否处于 AVM 视角下发模式（转向灯绑定 AVM 视角时打灯经 AVM 下发，不打开物理摄像头）。 */
    private boolean avmModeActive = false;
    /** v1.39：最近一次成功激活浮窗的转向方向。打灯期间同方向重复亮起回调一律忽略，
     * 杜绝 dumpsys 抖动产生的假 0->1 边沿导致周期式重发绑定视角（覆盖倒挡影像、卡顿）。 */
    private String lastActiveDir = null;
    // ---- v1.46：浮窗右上角关闭按钮的“关闭抑制”状态 ----
    /** 手动提前关闭浮窗后处于抑制状态的方向（"LEFT"/"RIGHT"），null=无抑制。
     * 抑制期间同方向 0->1 亮起信号不重开浮窗（防打灯抖动假边沿误重开）；
     * 检测到 1 秒熄灭信号（两灯均 0 持续 1s）或换方向打灯时解除抑制并恢复召唤。 */
    private String closeSuppressedDir = null;

    private SignalMonitor monitor;
    private AdbClient adb;
    private Handler mainHandler;

    /** 启动前台服务。后台场景可能抛 ForegroundServiceStartNotAllowedException，此处捕获并返回是否
     * 启动成功，由调用方决定延后补启，确保任何生命周期状态下都不会因启动服务导致进程崩溃。 */
    /** 开启打灯联动：启动/复用常驻前台服务并开始转向灯轮询（服务在前台运行，退后台也能弹窗）。 */
    public static void startLink(Context ctx, String host, int port) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_START_LINK);
        i.putExtra(EXTRA_HOST, host);
        i.putExtra(EXTRA_PORT, port);
        safeStart(ctx, i);
    }

    /** 关闭打灯联动：停止轮询并隐藏浮窗；若手动浮窗也未显示则停止前台服务。 */
    public static void stopLink(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_STOP_LINK);
        safeStart(ctx, i);
    }

    /** 显示手动浮窗（调试用）：服务未运行则先启动前台服务。 */
    public static void showManual(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_SHOW_MANUAL);
        safeStart(ctx, i);
    }

    /** 隐藏手动浮窗；若无联动在运行则停止前台服务。 */
    public static void hideManual(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_HIDE_MANUAL);
        safeStart(ctx, i);
    }

    /** 记录当前浮窗位置（v1.30）：服务读取 overlayView 当前 x/y 写入 prefs 并置 recorded=true。 */
    public static void recordCurrentPosition(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_RECORD_POS);
        safeStart(ctx, i);
    }

    /** 按 overlay_scale 应用浮窗大小（v1.30）：浮窗正在显示时实时调整宽高。 */
    public static void applyOverlaySize(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_APPLY_SIZE);
        safeStart(ctx, i);
    }

    /** 还原左右转向浮窗配置到默认（v1.32）：清除 LEFT/RIGHT 两组配置键，浮窗在显示时按默认刷新。 */
    public static void resetOverlayDefaults(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_RESET_OVERLAY);
        safeStart(ctx, i);
    }

    /** 右浮窗对齐左浮窗（v1.46）：将 RIGHT 方向配置（位置/宽高/画面缩放/平移）复制为 LEFT 的当前配置；
     * 浮窗正在显示右转向浮窗时立即应用。 */
    public static void alignRightToLeft(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_ALIGN_RIGHT_TO_LEFT);
        safeStart(ctx, i);
    }

    private static void safeStart(Context ctx, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "启动前台服务被拒绝: " + e.getMessage());
            // 服务已在后台且未提前前台运行时才会走到这里；正常情况下
            // 联动/手动浮窗都在主界面前台点击，或服务已常驻，不会触发。
            serviceLog("启动服务被系统拒绝，请从主界面重新开启");
        }
    }

    public static boolean isLinkActive() {
        return linkActive.get();
    }

    public static boolean isManualVisible() {
        return manualVisible.get();
    }

    /** 拉取并清空服务侧新增日志（主界面定时刷新用）。 */
    public static List<String> drainLogs() {
        List<String> out = new ArrayList<String>();
        synchronized (recentLogs) {
            if (!recentLogs.isEmpty()) {
                out.addAll(recentLogs);
                recentLogs.clear();
            }
        }
        return out;
    }

    /** v1.41：清空服务侧内存日志（主界面“清空日志”按钮调用，与显示区同步清空）。 */
    public static void clearLogs() {
        synchronized (recentLogs) {
            recentLogs.clear();
        }
    }

    private static void serviceLog(String line) {
        Log.i(TAG, line);
        synchronized (recentLogs) {
            recentLogs.add(line);
            if (recentLogs.size() > 50) {
                recentLogs.remove(0);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        bgThread = new HandlerThread("camera-bg");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        createNotificationChannel();
        serviceLog("前台服务已常驻（后台可弹窗）");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }
        String action = intent.getAction();
        if (action == null) action = "";
        if (ACTION_START_LINK.equals(action)) {
            startForegroundCompat();
            String host = intent.getStringExtra(EXTRA_HOST);
            int port = intent.getIntExtra(EXTRA_PORT, 5050);
            startLinkInternal(host, port);
        } else if (ACTION_STOP_LINK.equals(action)) {
            stopLinkInternal();
        } else if (ACTION_SHOW_MANUAL.equals(action)) {
            startForegroundCompat();
            manualVisible.set(true);
            showOverlayForDirection("MANUAL");
        } else if (ACTION_HIDE_MANUAL.equals(action)) {
            manualVisible.set(false);
            hideOverlayAndCamera();
            if (!linkActive.get()) {
                stopSelfInternal();
            }
        } else if (ACTION_HIDE_ALL.equals(action)) {
            stopLinkInternal();
        } else if (ACTION_RECORD_POS.equals(action)) {
            recordPositionInternal();
        } else if (ACTION_APPLY_SIZE.equals(action)) {
            applyOverlaySizeInternal();
        } else if (ACTION_RESET_OVERLAY.equals(action)) {
            resetOverlayDefaultsInternal();
        } else if (ACTION_ALIGN_RIGHT_TO_LEFT.equals(action)) {
            alignRightToLeftInternal();
        }
        return START_STICKY;
    }

    /** 开启联动轮询：创建 ADB 连接并启动 SignalMonitor（300ms 轮询转向灯）。 */
    private void startLinkInternal(String host, int port) {
        if (linkActive.get()) {
            // 已开启：仅更新连接参数（服务常驻，无需重复启动线程）
            serviceLog("联动已在运行，更新 ADB 参数 " + host + ":" + port);
            return;
        }
        byte[] pem = readRawAdbKey();
        if (pem.length == 0) {
            serviceLog("内置密钥读取失败，无法建立 ADB 连接");
            return;
        }
        try {
            adb = new AdbClient(host, port, pem);
            monitor = new SignalMonitor(adb, this, 300);
            if (monitor.start()) {
                linkActive.set(true);
                serviceLog("联动已开启，ADB 轮询启动 (" + host + ":" + port + ")");
                updateNotificationText("联动轮询运行中 · 转向灯弹窗就绪");
            } else {
                serviceLog("旧轮询线程仍在退出（Socket read 最长 15s），请稍后重新开启联动");
                adb = null;
                monitor = null;
            }
        } catch (Exception e) {
            serviceLog("初始化失败: " + e.getMessage());
        }
    }

    /** 关闭联动：停止轮询、隐藏浮窗；无手动浮窗时停止前台服务。 */
    private void stopLinkInternal() {
        linkActive.set(false);
        if (monitor != null) {
            monitor.stop();
            monitor = null;
        }
        if (adb != null) {
            adb = null;
        }
        hideOverlayAndCamera();
        serviceLog("联动已关闭，轮询停止");
        if (!manualVisible.get()) {
            stopSelfInternal();
        }
    }

    /** 停止前台服务：先移除浮窗再 stopForeground/stopSelf，避免 IllegalStateException。 */
    private void stopSelfInternal() {
        hideOverlayAndCamera();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    /** 读取内置 ADB 私钥（res/raw/adb_key），与服务同 APK 直接读取，无需 Activity 传入。 */
    private byte[] readRawAdbKey() {
        try {
            java.io.InputStream is = getResources().openRawResource(R.raw.adb_key);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            is.close();
            return bos.toByteArray();
        } catch (Exception e) {
            serviceLog("读取内置密钥失败: " + e.getMessage());
            return new byte[0];
        }
    }

    private void updateNotificationText(String text) {
        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        nb.setContentTitle("CameraAssist")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true);
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
        nb.setContentIntent(pi);
        startForeground(NOTIF_ID, nb.build());
    }

    private void startForegroundCompat() {
        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        nb.setContentTitle("CameraAssist")
                .setContentText(linkActive.get() ? "联动轮询运行中 · 转向灯弹窗就绪"
                        : "侧边摄像头预览运行中")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true);
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
        nb.setContentIntent(pi);
        Notification n = nb.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "CameraAssist 预览", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }

    // ---- SignalMonitor.Listener：转向信号由服务直接处理，不依赖 Activity 前台状态 ----

    @Override
    public void onLog(final String line) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                serviceLog(line);
            }
        });
    }

    @Override
    public void onSignalChanged(final String direction, final boolean anyOn) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (anyOn && direction != null) {
                    // v1.46：关闭抑制——手动提前关闭浮窗后，同方向 0->1 亮起信号一律不重开
                    //（灯未灭时 dumpsys 抖动会伪造成假边沿，若直接召唤会把刚关掉的浮窗又弹出来）；
                    // 换方向打灯则立即解除抑制并召唤新方向浮窗。
                    if (closeSuppressedDir != null && closeSuppressedDir.equals(direction)) {
                        serviceLog("关闭抑制中（" + direction + "），同方向亮起不重开浮窗");
                        return;
                    }
                    if (closeSuppressedDir != null) {
                        serviceLog("检测到换方向打灯（" + direction + "），解除关闭抑制并重新召唤浮窗");
                        closeSuppressedDir = null;
                    }
                    String label = "LEFT".equals(direction) ? "左转向灯" : "右转向灯";
                    serviceLog(label + " 亮起，弹出侧边摄像头悬浮窗");
                    showOverlayForDirection(direction);
                } else {
                    // v1.46：两灯均 0 持续 1 秒（SignalMonitor 已做 OFF_DEBOUNCE_MS 去抖）即本次
                    // 转向彻底结束，解除关闭抑制，之后任意方向重新打灯都能正常召唤浮窗。
                    if (closeSuppressedDir != null) {
                        serviceLog("检测到1秒熄灭信号，解除关闭抑制");
                        closeSuppressedDir = null;
                    }
                    serviceLog("转向灯熄灭，关闭悬浮窗");
                    hideOverlayAndCamera();
                }
            }
        });
    }

    /**
     * v1.45：ADB 连接成功后自动补授悬浮窗权限。
     * 新车机安装后 appops SYSTEM_ALERT_WINDOW 默认拒绝，浮窗无法直接弹出；
     * 此处检测未授权则经同一已认证 ADB 连接下发 appops set ... allow（失败再尝试 pm grant）。
     * 在 SignalMonitor 轮询线程内执行，任何异常仅记日志，不阻塞联动。
     */
    @Override
    public void onAdbConnected() {
        if (Settings.canDrawOverlays(this)) {
            return;
        }
        AdbClient conn = adb;
        if (conn == null) {
            return;
        }
        try {
            serviceLog("ADB 已连接，检测到悬浮窗未授权，自动申请权限…");
            String r = conn.exec("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            serviceLog("ADB 授权悬浮窗(appops)：" + (r == null || r.trim().isEmpty() ? "已执行（无输出）" : r.trim()));
            if (!Settings.canDrawOverlays(this)) {
                String r2 = conn.exec("pm grant " + getPackageName() + " android.permission.SYSTEM_ALERT_WINDOW");
                serviceLog("ADB 授权悬浮窗(pm grant)：" + (r2 == null || r2.trim().isEmpty() ? "已执行（无输出）" : r2.trim()));
            }
            if (Settings.canDrawOverlays(this)) {
                serviceLog("悬浮窗权限已生效，浮窗可正常弹出");
            } else {
                serviceLog("悬浮窗授权命令已执行，但尚未生效，请确认车机允许");
            }
        } catch (Exception e) {
            serviceLog("ADB 自动授权悬浮窗失败（不影响联动）：" + e.getMessage());
        }
    }

    /** 按方向显示/切换浮窗：首次弹窗创建 overlay，方向变化释放旧摄像头并按新方向重开。 */
    private void showOverlayForDirection(String dir) {
        // v1.39：同方向持续亮起期间若浮窗与相机均已就绪，忽略重复触发，绝不重发 AVM 绑定视角。
        // 打灯后转向灯信号理论上只产生一次 0->1 边沿；若车机 dumpsys 输出抖动出现假边沿，
        // 再次进入本方法会重复 sendAvmMode 抢占 AVM 视角（表现为倒挡影像被覆盖、转向画面卡顿），
        // 此幂等锁 + SignalMonitor 缺失行保持原状态双重兜底，确保打灯期间只下发一次绑定视角。
        if (dir.equals(lastActiveDir) && overlayView != null && cameraDevice != null) {
            serviceLog("同方向信号持续亮起，忽略重复触发，不重发 AVM 视角");
            return;
        }
        lastActiveDir = dir;
        String oldDir = direction;
        // v1.48：方向切换前先保存旧方向当前显示状态（位置/大小/画面缩放平移），
        // 修复编辑模式未退出或普通调整后直接换方向打灯导致旧方向配置丢失的问题。
        if (overlayView != null && !dir.equals(oldDir) && oldDir != null && !"MANUAL".equals(oldDir)) {
            saveEditConfigInternal();
        }
        direction = dir;
        // v1.32：左右转向浮窗配置独立（位置/大小/裁切），方向切换且浮窗已存在时按新方向重新应用；
        // 旧方向若处于编辑模式先自动保存，避免切换丢失未保存配置。
        if (overlayView != null && !dir.equals(oldDir)) {
            applyOverlayConfigForDirection();
        }
        // v1.28：转向灯若已绑定 AVM 视角（left_avm_mode/right_avm_mode），打灯经 AVM 下发该视角，
        // 不再打开物理摄像头（实车验证物理 cameraId 直选无效）；未绑定则回退原物理摄像头浮窗逻辑。
        if (!"MANUAL".equals(dir)) {
            int avmMode = getAvmBinding(dir);
            if (avmMode != -1) {
                avmModeActive = true;
                if (overlayView == null) {
                    startForegroundCompat();
                    ensureOverlay();
                } else {
                    updateLabel();
                }
                if (cameraDevice != null) {
                    closeCameraOnly();
                }
                sendAvmMode(avmMode);
                // v1.33：AVM 绑定路径同样打开物理摄像头（openCamera 默认 cameraId=0，实车可出鱼眼画面），
                // 否则浮窗 TextureView 无预览流导致打灯画面空白。
                openCamera();
                return;
            }
        }
        avmModeActive = false;
        if (overlayView == null) {
            startForegroundCompat();
            ensureOverlay();
            openCamera();
        } else if (cameraDevice == null) {
            // 浮窗在但摄像头未就绪（如刚移除过 TextureView）：重开
            startForegroundCompat();
            openCamera();
        } else if (!dir.equals(openedDirection)) {
            // 方向变化（如左转->右转）：释放旧方向摄像头并按新方向重开，避免画面与标签不一致
            startForegroundCompat();
            closeCameraOnly();
            openCamera();
        } else {
            updateLabel();
        }
    }

    /** 隐藏浮窗并释放摄像头（幂等）。 */
    private void hideOverlayAndCamera() {
        // v1.48：隐藏前无条件保存当前方向浮窗配置（位置/窗口大小/画面缩放平移）。
        // 修复“关闭打灯联动后再次开启，浮窗大小位置重置”：无论用户经哪种路径调整过
        //（普通拖动、编辑模式、拖边、双指缩放），关闭/熄灭那一刻都把当前显示状态落盘，
        // 下次打灯 ensureOverlay 从 prefs 恢复，不再回退默认值。
        if (overlayView != null && direction != null && !"MANUAL".equals(direction)) {
            saveEditConfigInternal();
        }
        // v1.39：灯熄灭即清除方向锁，确保下次重新打灯能再次下发一次绑定视角
        lastActiveDir = null;
        avmModeActive = false;
        removeOverlay();
        releaseCamera();
    }

    /**
     * v1.46：浮窗右上角关闭按钮点击处理——打灯结束前提前关闭浮窗。
     * 转向浮窗：关闭后进入“关闭抑制”，同方向 0->1 亮起信号不再重开（防打灯抖动假边沿误重开），
     * 直到检测到 1 秒熄灭信号或换方向打灯才恢复召唤（见 onSignalChanged）。
     * 手动浮窗：直接隐藏，无联动运行时停止前台服务。
     */
    private void closeOverlayFromButton() {
        if (overlayView == null) return;
        if ("MANUAL".equals(direction)) {
            manualVisible.set(false);
            hideOverlayAndCamera();
            if (!linkActive.get()) {
                stopSelfInternal();
            }
            serviceLog("手动浮窗已通过关闭按钮关闭");
            return;
        }
        // 转向浮窗：记录当前方向进入抑制，随后关闭浮窗与摄像头
        closeSuppressedDir = direction;
        hideOverlayAndCamera();
        serviceLog("浮窗已手动关闭（" + direction + "），进入关闭抑制：同方向亮起不重开，等1秒熄灭或换方向");
    }

    private void ensureOverlay() {
        if (overlayView != null) return;
        LayoutInflater li = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = li.inflate(R.layout.overlay_window, null);
        textureView = overlayView.findViewById(R.id.overlay_texture);
        // v1.47：TextureView 按 24dp 圆角裁剪，防止矩形预览画面覆盖浮窗背景 R 角呈直角
        if (textureView != null) {
            textureView.setClipToOutline(true);
            textureView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
                }
            });
        }
        labelView = overlayView.findViewById(R.id.overlay_label);
        updateLabel();
        // v1.32：编辑模式视觉元素（金色描边边框、四边裁切手柄、底部角标）
        borderView = overlayView.findViewById(R.id.overlay_border);
        cropHandleL = overlayView.findViewById(R.id.handle_left);
        cropHandleR = overlayView.findViewById(R.id.handle_right);
        cropHandleT = overlayView.findViewById(R.id.handle_top);
        cropHandleB = overlayView.findViewById(R.id.handle_bottom);
        cropHandleTL = overlayView.findViewById(R.id.handle_tl);
        cropHandleTR = overlayView.findViewById(R.id.handle_tr);
        cropHandleBL = overlayView.findViewById(R.id.handle_bl);
        cropHandleBR = overlayView.findViewById(R.id.handle_br);
        editHintView = overlayView.findViewById(R.id.overlay_edit_hint);
        android.graphics.drawable.GradientDrawable borderBg = new android.graphics.drawable.GradientDrawable();
        borderBg.setStroke(dp(2), 0xFFFFD98A);
        borderBg.setColor(0x00000000);
        if (borderView != null) borderView.setBackground(borderBg);
        if (editHintView != null) editHintView.setText("可拖动");
        // v1.46：右上角关闭按钮——打灯结束前可提前关闭浮窗（转向浮窗进入关闭抑制，防同方向误重开）
        View closeBtn = overlayView.findViewById(R.id.overlay_close);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeOverlayFromButton();
                }
            });
        }
        // v1.33：四边边缘手柄改窗口形状（窗口变化不影响画面，画面按基准尺寸居中裁剪）
        setupWindowEdgeHandle(cropHandleL, "left");
        setupWindowEdgeHandle(cropHandleR, "right");
        setupWindowEdgeHandle(cropHandleT, "top");
        setupWindowEdgeHandle(cropHandleB, "bottom");
        // v1.51：四角等比例缩放手柄（tl/tr/bl/br）
        setupWindowCornerHandle(cropHandleTL, "tl");
        setupWindowCornerHandle(cropHandleTR, "tr");
        setupWindowCornerHandle(cropHandleBL, "bl");
        setupWindowCornerHandle(cropHandleBR, "br");

        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        // v1.33：转向浮窗按方向配置键读取窗口宽高（left/right_overlay_w/h，含旧 overlay_scale 迁移），
        // 默认 w=屏宽*0.4、h=w*9/16；画面缩放 camScale 按方向键读取。MANUAL 沿用通用 overlay_scale。
        boolean manual = "MANUAL".equals(direction);
        int w;
        int h;
        if (manual) {
            float scale = readOverlayScale();
            w = (int) (dm.widthPixels * scale);
            h = (int) (w * 9f / 16f);
            if (h > dm.heightPixels) h = dm.heightPixels;
            camScale = CAM_SCALE_DEFAULT;
        } else {
            migrateLegacyOverlayScale(dm.widthPixels, dm.heightPixels);
            w = readOverlayW(dm.widthPixels);
            h = readOverlayH(dm.heightPixels);
            camScale = readCamScale();
            panX = readPanX();
            panY = readPanY();
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean recorded;
        int defX;
        int defY;
        if (manual) {
            // 手动浮窗沿用 v1.30 通用键，默认置顶便于拖放
            recorded = prefs.getBoolean("overlay_pos_recorded", false);
            defX = 16;
            defY = (int) (dm.heightPixels * 0.25f);
        } else {
            // v1.32：转向浮窗按方向独立配置（left_/right_ 前缀）；未记录时 LEFT 贴左下、RIGHT 贴右下
            String p = "LEFT".equals(direction) ? "left_" : "right_";
            recorded = prefs.getBoolean(p + "overlay_pos_recorded", false);
            defX = "LEFT".equals(direction) ? 120 : (dm.widthPixels - w - 120);
            defY = dm.heightPixels - h - 120;
        }
        // v1.30：所有浮窗统一 TOP|START 坐标系；已记录位置则显示在记录点，否则按方向默认贴边。
        lp.gravity = Gravity.TOP | Gravity.START;
        if (recorded) {
            lp.x = prefs.getInt(manual ? "overlay_pos_x" : ("LEFT".equals(direction) ? "left_overlay_pos_x" : "right_overlay_pos_x"), defX);
            lp.y = prefs.getInt(manual ? "overlay_pos_y" : ("LEFT".equals(direction) ? "left_overlay_pos_y" : "right_overlay_pos_y"), defY);
        } else {
            lp.x = defX;
            lp.y = defY;
        }
        try {
            wm.addView(overlayView, lp);
        } catch (Exception e) {
            Log.e(TAG, "addView overlay failed", e);
            overlayView = null;
        }
        makeDraggable(overlayView);
        textureView.setSurfaceTextureListener(surfaceListener);
        // v1.33：按解耦模型渲染画面（surface 未就绪时 setTransform 也会保存，available 后重放）
        applyRenderTransform();
    }

    /** 读取 MANUAL 手动浮窗缩放比例（0.2~1.0，默认 0.4），越界自动收敛。
     * v1.33：仅 MANUAL 使用（通用 overlay_scale）；转向浮窗改用 left/right_overlay_w/h 与 cam_scale。 */
    private float readOverlayScale() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        float s = prefs.getFloat("overlay_scale", 0.4f);
        if (s < 0.2f) s = 0.2f;
        if (s > 1.0f) s = 1.0f;
        return s;
    }

    /** 读取当前方向窗口宽度（像素）：优先 left/right_overlay_w，默认屏宽*0.4；越界收敛到 [屏宽*0.15, 屏宽]。 */
    private int readOverlayW(int screenW) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String p = "LEFT".equals(direction) ? "left_" : "right_";
        int defW = (int) (screenW * OVERLAY_DEF_W_RATIO);
        int w = prefs.getInt(p + "overlay_w", defW);
        int minW = (int) (screenW * MIN_SIZE_RATIO);
        if (w < minW) w = minW;
        if (w > screenW) w = screenW;
        return w;
    }

    /** 读取当前方向窗口高度（像素）：优先 left/right_overlay_h，默认 基准宽*9/16；越界收敛到 [屏高*0.15, 屏高]。 */
    private int readOverlayH(int screenH) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String p = "LEFT".equals(direction) ? "left_" : "right_";
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int defW = (int) (dm.widthPixels * OVERLAY_DEF_W_RATIO);
        int defH = (int) (defW * 9f / 16f);
        int h = prefs.getInt(p + "overlay_h", defH);
        int minH = (int) (screenH * MIN_SIZE_RATIO);
        if (h < minH) h = minH;
        if (h > screenH) h = screenH;
        return h;
    }

    /** 读取当前方向画面缩放（cam_scale，默认 1.0），越界收敛到 0.2~4.0。 */
    private float readCamScale() {
        if ("MANUAL".equals(direction)) return CAM_SCALE_DEFAULT;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String p = "LEFT".equals(direction) ? "left_" : "right_";
        float s = prefs.getFloat(p + "cam_scale", CAM_SCALE_DEFAULT);
        if (s < CAM_SCALE_MIN) s = CAM_SCALE_MIN;
        if (s > CAM_SCALE_MAX) s = CAM_SCALE_MAX;
        return s;
    }

    /** v1.34 读取当前方向画面横向平移（pan_x，默认 0）。MANUAL 无平移概念返回 0；
     * 实际收敛由 applyRenderTransform 依据窗口尺寸实时完成。 */
    private float readPanX() {
        if ("MANUAL".equals(direction)) return 0f;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String p = "LEFT".equals(direction) ? "left_" : "right_";
        return prefs.getFloat(p + "pan_x", 0f);
    }

    /** v1.34 读取当前方向画面纵向平移（pan_y，默认 0），见 readPanX。 */
    private float readPanY() {
        if ("MANUAL".equals(direction)) return 0f;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String p = "LEFT".equals(direction) ? "left_" : "right_";
        return prefs.getFloat(p + "pan_y", 0f);
    }

    /** 迁移 v1.32 旧配置：存在 left/right_overlay_scale 且未写新键时换算为 overlay_w/h 后删除；
     * 废弃的 left/right_crop_* 裁切键一律删除（读取时忽略）。 */
    private void migrateLegacyOverlayScale(int screenW, int screenH) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        for (String p : new String[]{"left_", "right_"}) {
            if (prefs.contains(p + "overlay_scale") && !prefs.contains(p + "overlay_w")) {
                float s = prefs.getFloat(p + "overlay_scale", 0.4f);
                if (s < 0.2f) s = 0.2f;
                if (s > 1.0f) s = 1.0f;
                int w = (int) (screenW * s);
                int h = (int) (w * 9f / 16f);
                if (h > screenH) h = screenH;
                ed.putInt(p + "overlay_w", w);
                ed.putInt(p + "overlay_h", h);
            }
            ed.remove(p + "overlay_scale");
            ed.remove(p + "crop_l");
            ed.remove(p + "crop_t");
            ed.remove(p + "crop_r");
            ed.remove(p + "crop_b");
        }
        ed.apply();
    }

    /** 记录当前位置（v1.30）：浮窗未显示则提示先打开手动浮窗，否则按当前方向写位置键并置 recorded。
     * v1.32：转向浮窗写入方向独立键（left_overlay_pos_x/y、right_overlay_pos_x/y），手动沿用通用键。 */
    private void recordPositionInternal() {
        if (overlayView == null) {
            Toast.makeText(this, "请先打开浮窗并拖动到位", Toast.LENGTH_SHORT).show();
            serviceLog("记录位置失败：浮窗未显示");
            return;
        }
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
        String p = "MANUAL".equals(direction) ? "" : ("LEFT".equals(direction) ? "left_" : "right_");
        SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        ed.putInt(p + "overlay_pos_x", lp.x);
        ed.putInt(p + "overlay_pos_y", lp.y);
        ed.putBoolean(p + "overlay_pos_recorded", true);
        ed.apply();
        Toast.makeText(this, "位置已记录", Toast.LENGTH_SHORT).show();
        serviceLog("浮窗位置已记录 x=" + lp.x + " y=" + lp.y + " dir=" + direction);
    }

    /** 按当前配置实时调整浮窗大小；浮窗未显示时仅更新配置，下次弹出生效。
     * v1.33：MANUAL 沿用通用 overlay_scale；转向浮窗按方向 left/right_overlay_w/h 应用
     * （未记录位置时 LEFT 贴左下、RIGHT 贴右下），窗口变化后重放解耦渲染矩阵。 */
    private void applyOverlaySizeInternal() {
        if (overlayView == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
        int w;
        int h;
        if ("MANUAL".equals(direction)) {
            float scale = readOverlayScale();
            w = (int) (dm.widthPixels * scale);
            h = (int) (w * 9f / 16f);
            if (h > dm.heightPixels) h = dm.heightPixels;
            camScale = CAM_SCALE_DEFAULT;
        } else {
            migrateLegacyOverlayScale(dm.widthPixels, dm.heightPixels);
            w = readOverlayW(dm.widthPixels);
            h = readOverlayH(dm.heightPixels);
            camScale = readCamScale();
            panX = readPanX();
            panY = readPanY();
        }
        lp.width = w;
        lp.height = h;
        // 转向浮窗未记录位置时贴边（高度变化后重新贴底；v1.32 左右分开）
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!"MANUAL".equals(direction)) {
            String p = "LEFT".equals(direction) ? "left_" : "right_";
            boolean recorded = prefs.getBoolean(p + "overlay_pos_recorded", false);
            if (!recorded) {
                lp.x = "LEFT".equals(direction) ? 120 : (dm.widthPixels - w - 120);
                lp.y = dm.heightPixels - h - 120;
            }
        }
        try {
            wm.updateViewLayout(overlayView, lp);
        } catch (Exception e) {
            Log.e(TAG, "applyOverlaySize update failed", e);
        }
        applyRenderTransform();
    }

    /** 更新浮窗左上角方向/模式标签。 */
    private void updateLabel() {
        if (labelView == null) return;
        if ("MANUAL".equals(direction)) {
            labelView.setText("手动预览 · 可拖动");
        } else if (avmModeActive) {
            boolean isLeft = "LEFT".equals(direction);
            labelView.setText(isLeft ? "左转向 · AVM 视角" : "右转向 · AVM 视角");
        } else {
            boolean isLeft = "LEFT".equals(direction);
            labelView.setText(isLeft ? "左转向 · 摄像头预览" : "右转向 · 摄像头预览");
        }
    }

    /** 浮窗触摸处理（v1.34）：
     * - MANUAL 手动浮窗保持 v1.31 现状：始终单指可拖动，不进入编辑模式；
     * - LEFT/RIGHT 转向浮窗：普通模式单指滑动 = 拖动整个浮窗位置，松手自动保存到方向配置；
     *   双击进入编辑模式；编辑模式单指滑动 = 平移框内画面（panX/panY，窗口不动）、
     *   双指固定 16:9 比例缩放（非 ScaleGestureDetector，避免与拖动冲突）、
     *   双击退出并保存当前 位置/大小/缩放/平移。 */
    private void makeDraggable(final View view) {
        if (view == null) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            private int downX;
            private int downY;
            private int initX;
            private int initY;
            // v1.34：编辑模式平移画面：起始 pan 值
            private float initPanX;
            private float initPanY;
            private boolean dragging = false;
            // 双击检测：记录上次单击抬起时间（ms）
            private long lastTapTime = 0L;
            private boolean doubleTapConsumed = false;
            // 双指画面缩放：起始两指间距与起始 camScale
            private float startSpan = 0f;
            private float startCamScale = 0f;
            private boolean pinchActive = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (overlayView == null) return false;
                WindowManager.LayoutParams lp =
                        (WindowManager.LayoutParams) overlayView.getLayoutParams();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = (int) event.getRawX();
                        downY = (int) event.getRawY();
                        initX = lp.x;
                        initY = lp.y;
                        initPanX = panX;
                        initPanY = panY;
                        dragging = false;
                        pinchActive = false;
                        // 转向浮窗：双击切换编辑模式（普通模式→进入；编辑模式→退出保存）
                        if (!"MANUAL".equals(direction)) {
                            long now = System.currentTimeMillis();
                            if (now - lastTapTime < 400L) {
                                lastTapTime = 0L;
                                doubleTapConsumed = true;
                                toggleEditMode();
                            } else {
                                lastTapTime = 0L;
                                doubleTapConsumed = false;
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        // 双指画面缩放仅编辑模式生效（手动浮窗保持 v1.31 拖动现状）
                        if (editMode && event.getPointerCount() >= 2) {
                            pinchActive = true;
                            dragging = false;
                            startSpan = computeSpan(event);
                            startCamScale = camScale;
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (pinchActive && event.getPointerCount() >= 2) {
                            float span = computeSpan(event);
                            if (startSpan > 0f && span > 0f) {
                                float newScale = startCamScale * span / startSpan;
                                applyCamScale(newScale);
                            }
                            return true;
                        }
                        if (editMode) {
                            // v1.34 编辑模式单指滑动 = 平移框内画面（panX/panY），窗口位置不变；applyRenderTransform 内部实时收敛
                            int dx = (int) event.getRawX() - downX;
                            int dy = (int) event.getRawY() - downY;
                            if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
                                dragging = true;
                            }
                            if (dragging) {
                                panX = initPanX + dx;
                                panY = initPanY + dy;
                                applyRenderTransform();
                            }
                            return true;
                        }
                        if ("MANUAL".equals(direction)) {
                            // v1.31 现状：手动浮窗始终可拖动
                            int dx = (int) event.getRawX() - downX;
                            int dy = (int) event.getRawY() - downY;
                            if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
                                dragging = true;
                            }
                            if (dragging) {
                                lp.x = initX + dx;
                                lp.y = initY + dy;
                                try {
                                    wm.updateViewLayout(overlayView, lp);
                                } catch (Exception ignored) {
                                }
                            }
                            return true;
                        }
                        // v1.34 非编辑模式转向浮窗单指滑动 = 拖动整个浮窗位置（松手 ACTION_UP 自动保存）
                        {
                            int dx = (int) event.getRawX() - downX;
                            int dy = (int) event.getRawY() - downY;
                            if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
                                dragging = true;
                            }
                            if (dragging) {
                                lp.x = initX + dx;
                                lp.y = initY + dy;
                                try {
                                    wm.updateViewLayout(overlayView, lp);
                                } catch (Exception ignored) {
                                }
                            }
                            return true;
                        }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (pinchActive) {
                            pinchActive = false;
                            return true;
                        }
                        boolean wasDragging = dragging;
                        dragging = false;
                        // v1.51：手动浮窗与转向浮窗非编辑模式拖动结束后，先自动吸附再保存（编辑模式平移画面不触发）
                        if (wasDragging && !editMode) {
                            int oldX = lp.x;
                            int oldY = lp.y;
                            snapToNearestEdge(lp);
                            if (lp.x != oldX || lp.y != oldY) {
                                try {
                                    wm.updateViewLayout(overlayView, lp);
                                } catch (Exception ignored) {
                                }
                            }
                            if (!"MANUAL".equals(direction)) {
                                String p = "LEFT".equals(direction) ? "left_" : "right_";
                                SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                                ed.putInt(p + "overlay_pos_x", lp.x);
                                ed.putInt(p + "overlay_pos_y", lp.y);
                                ed.putBoolean(p + "overlay_pos_recorded", true);
                                ed.apply();
                                serviceLog("浮窗位置拖动自动保存 dir=" + direction + " pos=(" + lp.x + "," + lp.y + ")");
                            } else {
                                // v1.51：手动浮窗拖动结束同样吸附并落盘（通用键）
                                SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                                ed.putInt("overlay_pos_x", lp.x);
                                ed.putInt("overlay_pos_y", lp.y);
                                ed.putBoolean("overlay_pos_recorded", true);
                                ed.apply();
                                serviceLog("手动浮窗位置拖动自动保存 pos=(" + lp.x + "," + lp.y + ")");
                            }
                        }
                        // 转向浮窗非拖动抬起视为一次单击，供下次双击检测（双击已消费则不再计）
                        if (!wasDragging && !"MANUAL".equals(direction) && !doubleTapConsumed) {
                            lastTapTime = System.currentTimeMillis();
                        }
                        doubleTapConsumed = false;
                        return wasDragging;
                }
                return false;
            }
        });
    }

    /** v1.51：位置自动吸附——贴近屏幕右/上边缘、左/下侧边栏且阈值很近才吸附。
     * 左侧竖向快捷边栏右缘吸附线 = 屏宽*LEFT_BAR_RATIO，底部横向底栏上缘吸附线 = 屏高*BOTTOM_BAR_RATIO，
     * 右侧与上方为纯屏幕边缘。水平/垂直独立判断，允许同时吸附（如右上角）。
     * 编辑模式（平移画面）不触发。 */
    private void snapToNearestEdge(WindowManager.LayoutParams lp) {
        if (lp == null || wm == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int sw = dm.widthPixels;
        int sh = dm.heightPixels;
        int th = dp(SNAP_THRESHOLD_DP);
        // 水平方向：左吸附线 = 屏宽*0.08（左边栏右缘），右吸附线 = 屏宽（屏幕右缘）
        int leftLine = (int) (sw * LEFT_BAR_RATIO);
        int leftDist = Math.abs(lp.x - leftLine);
        int rightDist = Math.abs((lp.x + lp.width) - sw);
        if (leftDist <= th && rightDist <= th) {
            if (leftDist <= rightDist) lp.x = leftLine;
            else lp.x = sw - lp.width;
        } else if (leftDist <= th) {
            lp.x = leftLine;
        } else if (rightDist <= th) {
            lp.x = sw - lp.width;
        }
        // 垂直方向：上吸附线 = 屏顶（y=0），下吸附线 = 底栏上缘 = 屏高*0.92
        int bottomLine = (int) (sh * BOTTOM_BAR_RATIO);
        int topDist = Math.abs(lp.y);
        int bottomDist = Math.abs((lp.y + lp.height) - bottomLine);
        if (topDist <= th && bottomDist <= th) {
            if (topDist <= bottomDist) lp.y = 0;
            else lp.y = bottomLine - lp.height;
        } else if (topDist <= th) {
            lp.y = 0;
        } else if (bottomDist <= th) {
            lp.y = bottomLine - lp.height;
        }
    }

    /** 计算两点间距（欧氏距离）。 */
    private float computeSpan(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** dp 转像素（编辑模式描边宽度等）。 */
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 双击切换编辑模式（LEFT/RIGHT 转向浮窗）：进入时加载方向配置（含平移）作为编辑基准，退出时保存。 */
    private void toggleEditMode() {
        if ("MANUAL".equals(direction) || overlayView == null) return;
        if (editMode) {
            saveEditConfigInternal();
            setEditMode(false);
            serviceLog("编辑模式退出，配置已保存");
        } else {
            camScale = readCamScale();
            panX = readPanX();
            panY = readPanY();
            // v1.42：从未编辑过（无 cam_scale 键）时，以默认左下/右下 1/4 裁切区域作为编辑初始值
            String p = "LEFT".equals(direction) ? "left_" : "right_";
            if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).contains(p + "cam_scale")) {
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                float defW = dm.widthPixels * OVERLAY_DEF_W_RATIO;
                float defH = defW * 9f / 16f;
                camScale = 2f;
                panX = "LEFT".equals(direction) ? defW / 2f : -defW / 2f;
                panY = -defH / 2f;
            }
            setEditMode(true);
            serviceLog("编辑模式进入（" + direction + "），双击保存退出");
        }
    }

    /** 设置编辑模式视觉状态：金色描边 + 四边边缘手柄 + 角标提示。 */
    private void setEditMode(boolean on) {
        editMode = on;
        if (borderView != null) borderView.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleL != null) cropHandleL.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleR != null) cropHandleR.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleT != null) cropHandleT.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleB != null) cropHandleB.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleTL != null) cropHandleTL.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleTR != null) cropHandleTR.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleBL != null) cropHandleBL.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleBR != null) cropHandleBR.setVisibility(on ? View.VISIBLE : View.GONE);
        if (editHintView != null) {
            editHintView.setText(on ? "编辑模式 · 单指平移画面，双指缩放，拖边改形状" : "可拖动");
        }
        if (on) applyRenderTransform();
    }

    /** 保存当前编辑配置到方向键（位置/窗口宽高/画面缩放/画面平移），供双击退出与浮窗关闭时调用；
     * v1.33 不再写入废弃 crop_* 与 overlay_scale 键；v1.34 新增 pan_x/pan_y。 */
    private void saveEditConfigInternal() {
        if (overlayView == null || "MANUAL".equals(direction)) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
        String p = "LEFT".equals(direction) ? "left_" : "right_";
        SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        ed.putInt(p + "overlay_w", lp.width);
        ed.putInt(p + "overlay_h", lp.height);
        ed.putFloat(p + "cam_scale", camScale);
        ed.putFloat(p + "pan_x", panX);
        ed.putFloat(p + "pan_y", panY);
        ed.putInt(p + "overlay_pos_x", lp.x);
        ed.putInt(p + "overlay_pos_y", lp.y);
        ed.putBoolean(p + "overlay_pos_recorded", true);
        ed.apply();
        serviceLog("编辑配置已保存 dir=" + direction + " w=" + lp.width + " h=" + lp.height
                + " camScale=" + camScale + " pan=(" + panX + "," + panY + ") pos=(" + lp.x + "," + lp.y + ")");
    }

    /** 编辑模式双指缩放：仅改变窗口内画面缩放 camScale（窗口尺寸不变，画面保持 16:9 固定比例），
     * 范围 0.2~4.0（默认 1.0），越界收敛；渲染矩阵同步更新。 */
    private void applyCamScale(float newScale) {
        if (newScale < CAM_SCALE_MIN) newScale = CAM_SCALE_MIN;
        if (newScale > CAM_SCALE_MAX) newScale = CAM_SCALE_MAX;
        camScale = newScale;
        applyRenderTransform();
    }

    /** v1.35 画面与窗口解耦渲染：画面基准尺寸固定为默认窗口尺寸（w_def=屏宽*0.4，h_def=w_def*9/16），
     * 逻辑显示尺寸 = 基准*camScale（16:9 固定比例）；scale = Wv / 真实bufferW（x/y 严格同一因子，等比不拉伸；
     * 显式 m.reset() 后 postScale(Wv/bufferW, Wv/bufferW) 保证 x/y 缩放因子绝对一致，杜绝叠加矩阵不对称变形）；
     * 窗口尺寸直接取 WindowManager.LayoutParams（updateViewLayout 后立即有效，避免 textureView 布局未刷新
     * 导致偏移错位像变形）；translate = 居中 + panX/panY。
     * v1.35 pan 完全自由：不设任何上下/左右边界收敛（用户明确要求自由拖动，允许露出深色底）。
     * 窗口 resize / camScale / pan / surface 变化后重放。 */
    /** v1.50：取消 4:3 固定显示区域。
     * v1.49 把 TextureView 和渲染区域都锁成 4:3，但画面逻辑尺寸是 16:9，
     * 导致默认 16:9 浮窗内画面被横向裁切，区域外露出深色底形成"遮罩"；
     * v1.50 恢复 TextureView 铺满窗口，画面按 16:9 逻辑尺寸等比渲染居中，
     * 不再限制显示区域，避免画面被裁切/遮挡。 */
    private void relayoutTextureView() {
        // v1.50：不再改动布局——TextureView 保持 match_parent 铺满浮窗，
        // 渲染区域与画面逻辑尺寸直接匹配，防止画面被裁切。
    }

    private void applyRenderTransform() {
        if (overlayView == null || textureView == null) return;
        // v1.50：渲染区域 = 窗口实际尺寸（TextureView 铺满窗口），不再锁定 4:3
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
        int winW = lp.width;
        int winH = lp.height;
        if (winW <= 0 || winH <= 0) {
            winW = textureView.getWidth();
            winH = textureView.getHeight();
        }
        int vw = winW;
        int vh = winH;
        if (vw <= 0) vw = 1;
        if (vh <= 0) vh = 1;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        float defW = dm.widthPixels * OVERLAY_DEF_W_RATIO;
        float defH = defW * 9f / 16f;
        float cs = camScale;
        if (cs < CAM_SCALE_MIN) cs = CAM_SCALE_MIN;
        if (cs > CAM_SCALE_MAX) cs = CAM_SCALE_MAX;
        float Wv = defW * cs;
        float Hv = defH * cs;
        // v1.35：panX/panY 不做任何收敛，自由平移（允许窗口露出深色底）
        // v1.38：scale 分母固定用相机输出 buffer 宽度（1280），与浮窗窗口尺寸完全解耦；
        // 窗口 resize 只影响居中 translate 与裁剪/留空，画面本身恒为 Wv*Hv 等比 16:9，不随浮框变形
        float scale = Wv / BUFFER_W;
        float dx;
        float dy;
        // v1.42：取消 v1.41 固定裁切锁定。未编辑过（方向键无 cam_scale）且非编辑模式时，
        // LEFT/RIGHT 仍以四宫格左下/右下 1/4 作为默认初始裁切显示；
        // 一旦进入编辑模式或已有编辑保存值（cam_scale 键存在），缩放/平移完全自由，
        // 渲染以用户调整后的画面为准（居中 + 自由 pan），不再回锁裁切区域。
        boolean isDir = "LEFT".equals(direction) || "RIGHT".equals(direction);
        boolean defaultCrop = false;
        if (isDir && !editMode) {
            String pk = "LEFT".equals(direction) ? "left_" : "right_";
            defaultCrop = !getSharedPreferences(PREFS_NAME, MODE_PRIVATE).contains(pk + "cam_scale");
        }
        if (defaultCrop) {
            // v1.50：默认完整显示——画面等比适配窗口（fitCenter），完整可见不裁切不遮挡
            float fit = Math.min(vw / Wv, vh / Hv);
            scale = (Wv / BUFFER_W) * fit;
            dx = (vw - Wv * fit) / 2f;
            dy = (vh - Hv * fit) / 2f;
        } else {
            // v1.42 自由渲染（编辑模式 / 已编辑 / MANUAL）：居中 + 自由平移
            dx = (vw - Wv) / 2f + panX;
            dy = (vh - Hv) / 2f + panY;
        }
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.reset();
        // x/y 缩放因子绝对一致（等比），translate 用 lp 尺寸居中并叠加自由 pan
        m.postScale(scale, scale);
        m.postTranslate(dx, dy);
        textureView.setTransform(m);
        // v1.35 日志佐证：窗口/实际view/buffer/画面逻辑尺寸/pan/scale 关键值打印
        serviceLog("RT vw=" + vw + " vh=" + vh + " tv=" + textureView.getWidth() + "x" + textureView.getHeight()
                + " buf=" + bufferW + "x" + bufferH + " Wv=" + (int) Wv + " Hv=" + (int) Hv
                + " pan=(" + (int) panX + "," + (int) panY + ") scale=" + String.format("%.4f", scale));
    }

    /** v1.33 设置窗口边缘手柄触摸：左右沿 X、上下沿 Y 拖动，改变整个窗口形状（宽高独立变化），
     * 最小尺寸 = 屏宽/屏高*0.15，最大不超屏宽/屏高，越界收敛；
     * left/top 拖动时 x/y 同步以保持对边固定，窗口变化后重放解耦渲染矩阵。 */
    private void setupWindowEdgeHandle(final View handle, final String side) {
        if (handle == null) return;
        handle.setOnTouchListener(new View.OnTouchListener() {
            private int startRaw = 0;
            private int startW = 0;
            private int startH = 0;
            private int startX = 0;
            private int startY = 0;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (overlayView == null || !editMode) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        WindowManager.LayoutParams lp =
                                (WindowManager.LayoutParams) overlayView.getLayoutParams();
                        startW = lp.width;
                        startH = lp.height;
                        startX = lp.x;
                        startY = lp.y;
                        boolean horizontal = "left".equals(side) || "right".equals(side);
                        startRaw = horizontal ? (int) event.getRawX() : (int) event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        boolean h = "left".equals(side) || "right".equals(side);
                        int cur = h ? (int) event.getRawX() : (int) event.getRawY();
                        int d = cur - startRaw;
                        DisplayMetrics dm = new DisplayMetrics();
                        wm.getDefaultDisplay().getRealMetrics(dm);
                        int minW = (int) (dm.widthPixels * MIN_SIZE_RATIO);
                        int minH = (int) (dm.heightPixels * MIN_SIZE_RATIO);
                        WindowManager.LayoutParams lp2 =
                                (WindowManager.LayoutParams) overlayView.getLayoutParams();
                        if ("left".equals(side)) {
                            int newW = startW - d;
                            if (newW < minW) newW = minW;
                            if (newW > dm.widthPixels) newW = dm.widthPixels;
                            // 左边缘拖动：宽变化且 x 同步，保持右边缘固定
                            lp2.width = newW;
                            lp2.x = startX + (startW - newW);
                            if (lp2.x < 0) {
                                lp2.x = 0;
                                lp2.width = startX + startW;
                            }
                        } else if ("right".equals(side)) {
                            int newW = startW + d;
                            if (newW < minW) newW = minW;
                            if (newW > dm.widthPixels) newW = dm.widthPixels;
                            lp2.width = newW;
                        } else if ("top".equals(side)) {
                            int newH = startH - d;
                            if (newH < minH) newH = minH;
                            if (newH > dm.heightPixels) newH = dm.heightPixels;
                            // 上边缘拖动：高变化且 y 同步，保持下边缘固定
                            lp2.height = newH;
                            lp2.y = startY + (startH - newH);
                            if (lp2.y < 0) {
                                lp2.y = 0;
                                lp2.height = startY + startH;
                            }
                        } else {
                            int newH = startH + d;
                            if (newH < minH) newH = minH;
                            if (newH > dm.heightPixels) newH = dm.heightPixels;
                            lp2.height = newH;
                        }
                        try {
                            wm.updateViewLayout(overlayView, lp2);
                        } catch (Exception ignored) {
                        }
                        applyRenderTransform();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });
    }

    /** v1.51：四角等比例缩放手柄——仅编辑模式生效（与 setupWindowEdgeHandle 一致，非编辑 return false）。
     * 拖动逻辑：按下记录 startRawX/startRawY/startW/startH/startX/startY；
     * 移动时按横向/纵向主导方向（|dx|*startH >= |dy|*startW 则横向主导）以当前窗口宽高比 ratio=startW/startH
     * 等比计算 newW=newH*ratio（横向主导）或 newH=newW/ratio（纵向主导）。
     * 锚点规则：tl 固定右下角(startX+startW,startY+startH)，tr 固定左下角(startX,startY+startH)，
     * bl 固定右上角(startX+startW,startY)，br 固定左上角(startX,startY)；
     * 拖动方向含右则 newW=startW+dx，含左则 startW-dx，含下则 newH=startH+dy，含上则 startH-dy。
     * 尺寸 clamp：newW 下限 max(屏宽*0.15, 屏高*0.15*ratio)，上限 min(屏宽, 屏高*ratio)，超出后按 ratio 重算另一边；
     * 锚点计算后 lp.x<0 则贴屏（x=0，width=原右缘X，height=width/ratio 并同步 y 保持对边固定），y<0 同理。 */
    private void setupWindowCornerHandle(final View handle, final String corner) {
        if (handle == null) return;
        handle.setOnTouchListener(new View.OnTouchListener() {
            private int startRawX = 0;
            private int startRawY = 0;
            private int startW = 0;
            private int startH = 0;
            private int startX = 0;
            private int startY = 0;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (overlayView == null || !editMode) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        WindowManager.LayoutParams lp0 =
                                (WindowManager.LayoutParams) overlayView.getLayoutParams();
                        startRawX = (int) event.getRawX();
                        startRawY = (int) event.getRawY();
                        startW = lp0.width;
                        startH = lp0.height;
                        startX = lp0.x;
                        startY = lp0.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) event.getRawX() - startRawX;
                        int dy = (int) event.getRawY() - startRawY;
                        DisplayMetrics dm = new DisplayMetrics();
                        wm.getDefaultDisplay().getRealMetrics(dm);
                        float ratio = (float) startW / startH;
                        int newW = startW;
                        int newH = startH;
                        boolean horizontalDominant = Math.abs(dx) * startH >= Math.abs(dy) * startW;
                        if (horizontalDominant) {
                            // 横向主导：拖动方向含右则 newW=startW+dx，含左则 startW-dx
                            if (corner.contains("r")) newW = startW + dx;
                            else newW = startW - dx;
                            newH = (int) (newW / ratio);
                        } else {
                            // 纵向主导：含下则 newH=startH+dy，含上则 startH-dy
                            if (corner.contains("b")) newH = startH + dy;
                            else newH = startH - dy;
                            newW = (int) (newH * ratio);
                        }
                        if (newW < 1) newW = 1;
                        if (newH < 1) newH = 1;
                        // 尺寸 clamp：宽下限 max(屏宽*0.15, 屏高*0.15*ratio)，上限 min(屏宽, 屏高*ratio)
                        int minW = (int) Math.max(dm.widthPixels * MIN_SIZE_RATIO,
                                dm.heightPixels * MIN_SIZE_RATIO * ratio);
                        int maxW = (int) Math.min(dm.widthPixels, dm.heightPixels * ratio);
                        if (newW < minW) newW = minW;
                        if (newW > maxW) newW = maxW;
                        newH = (int) (newW / ratio);
                        if (newH < 1) newH = 1;
                        WindowManager.LayoutParams lp =
                                (WindowManager.LayoutParams) overlayView.getLayoutParams();
                        lp.width = newW;
                        lp.height = newH;
                        // 锚点规则：tl 固定右下角、tr 固定左下角、bl 固定右上角、br 固定左上角
                        if ("tl".equals(corner)) {
                            lp.x = startX + startW - newW;
                            lp.y = startY + startH - newH;
                        } else if ("tr".equals(corner)) {
                            lp.x = startX;
                            lp.y = startY + startH - newH;
                        } else if ("bl".equals(corner)) {
                            lp.x = startX + startW - newW;
                            lp.y = startY;
                        } else { // br
                            lp.x = startX;
                            lp.y = startY;
                        }
                        // 越界贴屏：x<0 时贴左屏（x=0，width=原右缘X，height=width/ratio 并同步 y 保持对边固定）
                        if (lp.x < 0) {
                            int rightEdge = startX + startW;
                            lp.x = 0;
                            lp.width = rightEdge;
                            lp.height = (int) (rightEdge / ratio);
                            // tl/tr 锚点在下方（底边固定），bl/br 锚点在上方（顶边固定）
                            lp.y = ("tl".equals(corner) || "tr".equals(corner))
                                    ? (startY + startH - lp.height) : startY;
                        }
                        // y<0 同理：贴顶屏（y=0，height=原底缘Y，width=height*ratio，x 保持对边固定）
                        if (lp.y < 0) {
                            int bottomEdge = startY + startH;
                            lp.y = 0;
                            lp.height = bottomEdge;
                            lp.width = (int) (bottomEdge * ratio);
                            // tl/bl 锚点在右缘（右缘固定），tr/br 锚点在左缘（左缘固定）
                            lp.x = ("tl".equals(corner) || "bl".equals(corner))
                                    ? (startX + startW - lp.width) : startX;
                        }
                        try {
                            wm.updateViewLayout(overlayView, lp);
                        } catch (Exception ignored) {
                        }
                        applyRenderTransform();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });
    }

    /** v1.33：浮窗已存在且方向切换时按新方向重新应用位置/窗口形状/画面缩放（旧方向编辑中先自动保存）。 */
    private void applyOverlayConfigForDirection() {
        if (overlayView == null) return;
        if (editMode && !"MANUAL".equals(direction)) {
            saveEditConfigInternal();
        }
        editMode = false;
        if (borderView != null) borderView.setVisibility(View.GONE);
        if (cropHandleL != null) cropHandleL.setVisibility(View.GONE);
        if (cropHandleR != null) cropHandleR.setVisibility(View.GONE);
        if (cropHandleT != null) cropHandleT.setVisibility(View.GONE);
        if (cropHandleB != null) cropHandleB.setVisibility(View.GONE);
        if (cropHandleTL != null) cropHandleTL.setVisibility(View.GONE);
        if (cropHandleTR != null) cropHandleTR.setVisibility(View.GONE);
        if (cropHandleBL != null) cropHandleBL.setVisibility(View.GONE);
        if (cropHandleBR != null) cropHandleBR.setVisibility(View.GONE);
        if (editHintView != null) editHintView.setText("可拖动");
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String p = "LEFT".equals(direction) ? "left_" : "right_";
        int w = readOverlayW(dm.widthPixels);
        int h = readOverlayH(dm.heightPixels);
        camScale = readCamScale();
        panX = readPanX();
        panY = readPanY();
        boolean recorded = prefs.getBoolean(p + "overlay_pos_recorded", false);
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
        lp.width = w;
        lp.height = h;
        if (recorded) {
            lp.x = prefs.getInt(p + "overlay_pos_x", "LEFT".equals(direction) ? 120 : (dm.widthPixels - w - 120));
            lp.y = prefs.getInt(p + "overlay_pos_y", dm.heightPixels - h - 120);
        } else {
            lp.x = "LEFT".equals(direction) ? 120 : (dm.widthPixels - w - 120);
            lp.y = dm.heightPixels - h - 120;
        }
        try {
            wm.updateViewLayout(overlayView, lp);
        } catch (Exception ignored) {
        }
        applyRenderTransform();
        serviceLog("方向切换，已应用 " + direction + " 浮窗配置");
    }

    /** v1.33：主界面"还原浮窗默认"：清除 LEFT/RIGHT 两组全部配置键（位置/窗口形状/画面缩放/recorded/旧 crop/旧 scale）；
     * 浮窗在显示时立即按默认值刷新。 */
    private void resetOverlayDefaultsInternal() {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        ed.remove("left_overlay_w");
        ed.remove("left_overlay_h");
        ed.remove("left_cam_scale");
        ed.remove("left_overlay_scale");
        ed.remove("left_overlay_pos_recorded");
        ed.remove("left_overlay_pos_x");
        ed.remove("left_overlay_pos_y");
        ed.remove("left_crop_l");
        ed.remove("left_crop_t");
        ed.remove("left_crop_r");
        ed.remove("left_crop_b");
        ed.remove("left_pan_x");
        ed.remove("left_pan_y");
        ed.remove("right_overlay_w");
        ed.remove("right_overlay_h");
        ed.remove("right_cam_scale");
        ed.remove("right_overlay_scale");
        ed.remove("right_overlay_pos_recorded");
        ed.remove("right_overlay_pos_x");
        ed.remove("right_overlay_pos_y");
        ed.remove("right_crop_l");
        ed.remove("right_crop_t");
        ed.remove("right_crop_r");
        ed.remove("right_crop_b");
        ed.remove("right_pan_x");
        ed.remove("right_pan_y");
        // v1.43：绑定视角归位 display-mode=8（清除历史绑定残留键）
        ed.remove("left_avm_mode");
        ed.remove("right_avm_mode");
        ed.apply();
        serviceLog("浮窗配置已还原默认（LEFT/RIGHT 位置/形状/画面）");
        // v1.43：经 AVM 下发一次 display-mode=8，将绑定视角归位为四路鱼眼四宫格
        bgHandler.post(new Runnable() {
            @Override
            public void run() {
                sendAvmMode(8);
            }
        });
        serviceLog("绑定视角已归位 display-mode=8");
        if (overlayView != null) {
            if (editMode && !"MANUAL".equals(direction)) {
                editMode = false;
                if (borderView != null) borderView.setVisibility(View.GONE);
                if (cropHandleL != null) cropHandleL.setVisibility(View.GONE);
                if (cropHandleR != null) cropHandleR.setVisibility(View.GONE);
                if (cropHandleT != null) cropHandleT.setVisibility(View.GONE);
                if (cropHandleB != null) cropHandleB.setVisibility(View.GONE);
                if (editHintView != null) editHintView.setText("可拖动");
            }
            camScale = CAM_SCALE_DEFAULT;
            panX = 0f;
            panY = 0f;
            applyOverlaySizeInternal();
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
            if (!"MANUAL".equals(direction)) {
                lp.x = "LEFT".equals(direction) ? 120 : (dm.widthPixels - lp.width - 120);
                lp.y = dm.heightPixels - lp.height - 120;
            }
            try {
                wm.updateViewLayout(overlayView, lp);
            } catch (Exception ignored) {
            }
            applyRenderTransform();
        }
        Toast.makeText(this, "浮窗已还原默认位置/形状/画面", Toast.LENGTH_SHORT).show();
    }

    /**
     * v1.46：右浮窗对齐左浮窗——将 RIGHT 方向配置复制为 LEFT 的当前生效配置（位置/大小/形状）。
     * 位置：LEFT 有记录则复制记录坐标，未记录则复制其默认贴边显示坐标（保证实际可见位置一致）；
     * 大小：LEFT 有 left_overlay_w/h 键则复制，未编辑过则移除 RIGHT 对应键（两者回到同一默认值）；
     * 形状（画面）：LEFT 有 left_cam_scale 键则连缩放/平移一起复制，未编辑过则移除 RIGHT 画面键
     * （保持与 LEFT 一致的默认裁切显示）。浮窗正在显示右转向浮窗时立即应用。
     */
    private void alignRightToLeftInternal() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int defW = (int) (dm.widthPixels * OVERLAY_DEF_W_RATIO);
        int defH = (int) (defW * 9f / 16f);
        if (defH > dm.heightPixels) defH = dm.heightPixels;

        // 大小：LEFT 未编辑则删除 RIGHT 键回默认（与 LEFT 同一默认值），已编辑则复制 LEFT 键
        if (prefs.contains("left_overlay_w")) {
            ed.putInt("right_overlay_w", prefs.getInt("left_overlay_w", defW));
        } else {
            ed.remove("right_overlay_w");
        }
        if (prefs.contains("left_overlay_h")) {
            ed.putInt("right_overlay_h", prefs.getInt("left_overlay_h", defH));
        } else {
            ed.remove("right_overlay_h");
        }

        // 形状（画面缩放/平移）：LEFT 未编辑则删除 RIGHT 画面键（保持默认裁切），已编辑则整体复制
        if (prefs.contains("left_cam_scale")) {
            ed.putFloat("right_cam_scale", prefs.getFloat("left_cam_scale", CAM_SCALE_DEFAULT));
            ed.putFloat("right_pan_x", prefs.getFloat("left_pan_x", 0f));
            ed.putFloat("right_pan_y", prefs.getFloat("left_pan_y", 0f));
        } else {
            ed.remove("right_cam_scale");
            ed.remove("right_pan_x");
            ed.remove("right_pan_y");
        }

        // 位置：LEFT 有记录复制记录坐标；未记录则复制其默认贴边显示坐标（保证右浮窗与左浮窗实际位置一致）
        int leftW = prefs.getInt("left_overlay_w", defW);
        int leftH = prefs.getInt("left_overlay_h", defH);
        boolean leftRecorded = prefs.getBoolean("left_overlay_pos_recorded", false);
        int leftX = prefs.getInt("left_overlay_pos_x", 120);
        int leftY = prefs.getInt("left_overlay_pos_y", dm.heightPixels - leftH - 120);
        ed.putInt("right_overlay_pos_x", leftX);
        ed.putInt("right_overlay_pos_y", leftY);
        // 无论 LEFT 是否记录过，都按左浮窗实际显示坐标落盘，确保右浮窗完全对齐
        ed.putBoolean("right_overlay_pos_recorded", true);
        ed.apply();

        serviceLog("右浮窗已对齐左浮窗：pos=(" + leftX + "," + leftY + ") size=" + leftW + "x" + leftH
                + (prefs.contains("left_cam_scale") ? " 画面已同步" : " 画面保持默认"));
        // 浮窗正在显示右转向浮窗时立即应用新配置
        if ("RIGHT".equals(direction) && overlayView != null) {
            applyOverlayConfigForDirection();
        }
        Toast.makeText(this, "右浮窗已对齐左浮窗（位置/大小/形状）", Toast.LENGTH_SHORT).show();
    }

    private void removeOverlay() {
        if (overlayView != null) {
            // v1.33：浮窗关闭时若仍在编辑模式，先自动保存当前 位置/窗口形状/画面缩放，防丢失
            if (editMode && !"MANUAL".equals(direction)) {
                saveEditConfigInternal();
                editMode = false;
            }
            try {
                wm.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
    }

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            // v1.38：width/height 是 TextureView 视图尺寸（=浮窗窗口尺寸），不是相机输出 buffer 尺寸，
            // 不得写入 bufferW/H；这里强制固定输出 buffer 为 1280x720，保证渲染 scale 与窗口彻底解耦
            st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
            serviceLog("RT view=" + width + "x" + height + " buffer fixed=" + BUFFER_W + "x" + BUFFER_H);
            // v1.33：surface 就绪后重放解耦渲染矩阵（画面居中缩放，超出窗口裁剪）
            applyRenderTransform();
            if (cameraDevice != null) createPreviewSession();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            // v1.38：窗口 resize（拖浮窗边框改形状/主界面调大小）可能触发 surface 重建/尺寸变更，
            // 这里再次固定输出 buffer 为 1280x720，防止 TextureView 把 buffer 跟随为窗口尺寸、
            // 导致画面按窗口比例拉伸；随后重放解耦渲染矩阵
            st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
            applyRenderTransform();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            // TextureView 销毁（浮窗移除/关闭）时同步释放摄像头通道，防止占用
            releaseCamera();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }
    };

    private void openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "no CAMERA permission");
            return;
        }
        try {
            String[] ids = cameraManager.getCameraIdList();
            if (ids.length == 0) {
                Log.e(TAG, "no camera device");
                return;
            }
            cameraId = pickCameraId(ids);
            openedDirection = direction;
            cameraManager.openCamera(cameraId, deviceCallback, bgHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "openCamera failed", e);
        }
    }

    /**
     * 按当前方向选择摄像头 ID：优先使用测试页配置的左/右镜头 ID，
     * 未配置时统一回退 ids[0]（video51 鱼眼广角）。
     * v1.28 起仅作为未绑定 AVM 视角时的兜底；v1.36 右转向未配置时与左转向一致用 ids[0]，
     * 避免右转向落到 ids[1]（video52）导致画面缺一块（实车确认左转向 video51 画面完整）。 */
    private String pickCameraId(String[] ids) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String key = "LEFT".equals(direction) ? "left_camera_id" : "right_camera_id";
        String saved = prefs.getString(key, null);
        if (saved != null) {
            for (String id : ids) {
                if (id.equals(saved)) return id;
            }
        }
        return ids[0];
    }

    /**
     * v1.41：左右转向灯固定绑定 display-mode=8（四路鱼眼四宫格画面），不再依赖/暴露用户绑定配置；
     * 返回值恒为 8，忽略 prefs 中 left_avm_mode/right_avm_mode 的残留值。
     */
    private int getAvmBinding(String dir) {
        return 8;
    }

    // ---- v1.28 AVM 视角下发（复用 CameraTestActivity 已验证链路：先取后改再送）----

    /** 转向灯打灯时经 AVM 下发绑定视角（display-mode），不打开物理摄像头。 */
    private void sendAvmMode(int displayMode) {
        Log.i(TAG, "AVM: 转向灯下发视角 mode=" + displayMode);
        Object params = callGetCmdControlParameters();
        if (params == null) {
            Log.e(TAG, "AVM: getCmdControlParameters 返回 null/调用失败，转向灯 AVM 下发失败");
            return;
        }
        boolean written = writeAvmDisplayMode(params, displayMode);
        if (!written) {
            Log.e(TAG, "AVM: 三优先级写入均失败，无法下发 display-mode=" + displayMode);
            return;
        }
        if (!callSetCmdControlParameters(params)) {
            Log.e(TAG, "AVM: setCmdControlParameters 调用失败，转向灯 AVM 下发失败");
        }
    }

    /** 反射调用 CameraManager.getCmdControlParameters()，返回参数对象或 null。 */
    private Object callGetCmdControlParameters() {
        try {
            for (Method m : cameraManager.getClass().getMethods()) {
                if ("getCmdControlParameters".equals(m.getName()) && m.getParameterTypes().length == 0) {
                    Object ret = m.invoke(cameraManager);
                    Log.i(TAG, "AVM: getCmdControlParameters 调用成功 -> " + (ret == null ? "null" : ret.getClass().getName()));
                    return ret;
                }
            }
            Log.e(TAG, "AVM: CameraManager 无 getCmdControlParameters() 方法（手机无此接口属预期）");
        } catch (Exception e) {
            Log.e(TAG, "AVM: getCmdControlParameters 调用异常: " + e);
        }
        return null;
    }

    /** 反射调用 CameraManager.setCmdControlParameters：收集全部重载，按优先级依次尝试。 */
    private boolean callSetCmdControlParameters(Object params) {
        String flatten = null;
        try {
            flatten = (String) params.getClass().getMethod("flatten").invoke(params);
        } catch (Exception e) {
            Log.e(TAG, "AVM: flatten() 调用失败: " + e);
        }
        try {
            List<Method> overloads = new ArrayList<>();
            for (Method m : cameraManager.getClass().getMethods()) {
                if ("setCmdControlParameters".equals(m.getName())) {
                    overloads.add(m);
                }
            }
            if (overloads.isEmpty()) {
                Log.e(TAG, "AVM: CameraManager 无 setCmdControlParameters 方法（手机无此接口属预期）");
                return false;
            }
            for (Method m : overloads) {
                if (m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0].isInstance(params)) {
                    return tryInvokeSet(m, params);
                }
            }
            if (flatten != null) {
                for (Method m : overloads) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 2 && pts[0] == int.class && pts[1] == String.class) {
                        return tryInvokeSet(m, 0, flatten);
                    }
                }
                for (Method m : overloads) {
                    if (m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == String.class) {
                        return tryInvokeSet(m, flatten);
                    }
                }
            }
            for (Method m : overloads) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 2 && pts[0] == int.class && pts[1].isInstance(params)) {
                    return tryInvokeSet(m, 0, params);
                }
            }
            Log.e(TAG, "AVM: 所有 setCmdControlParameters 重载均尝试失败或参数不匹配");
        } catch (Exception e) {
            Log.e(TAG, "AVM: setCmdControlParameters 调用异常: " + e);
        }
        return false;
    }

    /** 调用一次 setCmdControlParameters 重载并记录命中/失败签名（含参数类型）。 */
    private boolean tryInvokeSet(Method m, Object... args) {
        try {
            m.invoke(cameraManager, args);
            Log.i(TAG, "AVM: setCmdControlParameters 命中重载 " + buildMethodSig(m));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "AVM: setCmdControlParameters 重载 " + buildMethodSig(m) + " 调用失败: " + e);
        }
        return false;
    }

    /** 生成方法完整签名：返回类型 方法名(参数类型列表)。 */
    private static String buildMethodSig(Method m) {
        StringBuilder ps = new StringBuilder();
        for (Class<?> pt : m.getParameterTypes()) {
            if (ps.length() > 0) ps.append(", ");
            ps.append(pt.getName());
        }
        return m.getReturnType().getName() + " " + m.getName() + "(" + ps + ")";
    }

    /** 三优先级反射写入 display-mode（依据实车 dump 确认的 CameraManager$Parameters 真实结构）。 */
    private boolean writeAvmDisplayMode(Object params, int value) {
        Class<?> cls = params.getClass();
        try {
            Method m = cls.getMethod("setAvmDisplayMode", int.class);
            m.invoke(params, value);
            Log.i(TAG, "AVM: 优先级1 setAvmDisplayMode(int) 调用成功 = " + value);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "AVM: 优先级1 setAvmDisplayMode(int) 失败: " + e);
        }
        try {
            Method m = cls.getMethod("set", String.class, int.class);
            m.invoke(params, "display-mode", value);
            Log.i(TAG, "AVM: 优先级2 set(String,int) 调用成功 display-mode=" + value);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "AVM: 优先级2 set(String,int) 失败: " + e);
        }
        try {
            Method m = cls.getDeclaredMethod("put", String.class, String.class);
            m.setAccessible(true);
            m.invoke(params, "display-mode", String.valueOf(value));
            Log.i(TAG, "AVM: 优先级3 私有 put(String,String) 调用成功 display-mode=" + value);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "AVM: 优先级3 私有 put(String,String) 失败: " + e);
        }
        return false;
    }

    /** 仅关闭摄像头通道（保留 TextureView/SurfaceTexture），用于方向切换后按新方向重开。 */
    private void closeCameraOnly() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice device) {
            cameraDevice = device;
            if (textureView != null && textureView.isAvailable()) {
                createPreviewSession();
            }
        }

        @Override
        public void onDisconnected(CameraDevice device) {
            device.close();
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice device, int error) {
            device.close();
            cameraDevice = null;
        }
    };

    private void createPreviewSession() {
        if (cameraDevice == null || textureView == null) return;
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
            bufferW = BUFFER_W;
            bufferH = BUFFER_H;
            if (previewSurface != null) {
                previewSurface.release();
                previewSurface = null;
            }
            previewSurface = new Surface(st);
            final CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            cameraDevice.createCaptureSession(Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, bgHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "setRepeatingRequest failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "camera configure failed");
                        }
                    }, bgHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createPreviewSession failed", e);
        }
    }

    /**
     * 完整释放摄像头通道：关闭 CaptureSession / CameraDevice，
     * 释放预览 Surface 与 TextureView 的 SurfaceTexture，断开监听。
     * 幂等，可被 onDestroy 与 onSurfaceTextureDestroyed 重复调用。
     */
    private void releaseCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        if (textureView != null) {
            textureView.setSurfaceTextureListener(null);
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st != null) {
                st.release();
            }
            textureView = null;
        }
    }

    @Override
    public void onDestroy() {
        if (monitor != null) {
            monitor.stop();
            monitor = null;
        }
        linkActive.set(false);
        manualVisible.set(false);
        releaseCamera();
        removeOverlay();
        if (bgThread != null) bgThread.quitSafely();
        serviceLog("前台服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
