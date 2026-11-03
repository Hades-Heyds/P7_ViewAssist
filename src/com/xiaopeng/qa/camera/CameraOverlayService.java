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
public class CameraOverlayService extends Service implements SignalMonitor.Listener, SignalGate.Listener {

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
    // v1.52：应用配置（BSD 提醒开关/焦点抑制开关/原厂相机包名），主界面开关改动后推送
    private static final String ACTION_APPLY_CONFIG = "com.xiaopeng.qa.camera.APPLY_CONFIG";
    // v1.54：BSD 常显窄条位置一键复位
    private static final String ACTION_RESET_BSD_POS = "com.xiaopeng.qa.camera.RESET_BSD_POS";
    // v1.54 beta4：BSD 预警浮窗自定义编辑 / 预警浮窗同步转向灯浮窗 / 单独关闭某侧预警浮窗
    private static final String ACTION_EDIT_BSD_WARNING = "com.xiaopeng.qa.camera.EDIT_BSD_WARNING";
    private static final String ACTION_SYNC_BSD_TO_TURN = "com.xiaopeng.qa.camera.SYNC_BSD_TO_TURN";
    private static final String ACTION_CLOSE_BSD_WINDOW = "com.xiaopeng.qa.camera.CLOSE_BSD_WINDOW";
    public static final String EXTRA_BSD_SIDE = "bsd_side";

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

    // ---- v1.57 beta3：仪表 H.264 投影（ICLinkProjector）----
    private final ICLinkProjector icProjector = new ICLinkProjector();
    private boolean iclProjectionEnabled = true;
    private String iclIp = ICLinkProjector.DEFAULT_HOST;
    private int iclPort = ICLinkProjector.DEFAULT_PORT;

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

    // ---- v1.52：SignalGate 慢变量门控 / BSD 旁路 / Camera1 回退 / R 挡退出延时抑制 ----
    private SignalGate signalGate;
    private SideWarning sideWarning;
    private Camera1Preview camera1Preview;
    /** BSD 提醒开关（偏好 bsd_warning_enabled，默认 false）。 */
    private boolean bsdWarningEnabled = false;
    /** R 挡退出延时抑制窗口时长（ms，偏好 r_exit_suppress_ms，默认 2000）。 */
    private long rExitSuppressMs = 2000L;
    /** R 挡退出延时抑制窗口截止时间戳（ms）；0=无窗口。 */
    private long rExitSuppressUntilMs = 0L;
    /** 用于 R 挡 3->非3 边沿检测的上一轮 gearLevel（仅当解析到 GEARLEV 才更新）。 */
    private int lastGearForRExit = -1;
    /** v1.53 beta2：BSD 无车常显开关（偏好 bsd_show_always，默认 false），
     * 开启时 SideWarning 无车也显示调试样式，便于拖动调位置。 */
    private boolean bsdShowAlways = false;
    // ---- v1.54：BSD 预警自动弹窗（最低优先级） ----
    /** BSD 预警自动弹窗开关（偏好 bsd_auto_overlay，默认 true）：开启后检测
     * SCU_BSD_LEFT_WARNING/RIGHT_WARNING 0->1 边沿自动弹对应侧摄像头浮窗，
     * 预警熄灭后自动关闭对应侧；优先级最低，不干扰打灯/倒车/窄路辅助。 */
    private boolean bsdAutoOverlay = true;
    /** v1.54 beta3：BSD 预警浮窗红色遮罩开关（偏好 bsd_red_overlay，默认 true）：
     * 开启时预警弹窗边界加一小圈向内的半透明红色遮罩（示意预警状态）；
     * 仅 BSD 预警弹窗显示，打灯浮窗/手动浮窗不显示。 */
    private boolean bsdRedOverlay = true;
    /** 上一轮 BSD 预警状态（左/右），用于 0->1 边沿检测与日志去抖。 */
    private boolean lastBsdLeft = false;
    private boolean lastBsdRight = false;
    /** v1.56 beta1：打灯浮窗联动 BSD 红框的边沿跟踪（上一轮左右 BSD 状态），
     * 由 onSignalTick 实时调用 onBsdEdgeForTurn 叠加/移除红框使用；
     * 打灯浮窗不在显示或红框开关关闭时复位，避免重入浮窗时误叠加。 */
    private boolean lastTurnBsdLeft = false;
    private boolean lastTurnBsdRight = false;
    /** 当前由 BSD 自动弹窗激活的浮窗方向（"LEFT"/"RIGHT"/null）。打灯/手动等更高优先级
     * 接管时置 null 让位；BSD 预警全灭时关闭对应侧浮窗。 */
    private String bsdAutoActiveDir = null;
    /** 以 BSD 自动弹窗身份调用 showOverlayForDirection 的临时标记：跳过打灯幂等锁与
     * lastActiveDir 写入，避免 BSD 弹窗污染打灯方向锁。 */
    private boolean bsdAutoCalling = false;
    // ---- v1.54：BSD 双窗避让（左右同时预警时右窗紧挨左窗右侧，不重叠） ----
    /** BSD 双窗时右窗与左窗的间距（dp）。 */
    private static final int BSD_DUAL_GAP_DP = 8;
    /** BSD 双窗场景的右侧浮窗（复用 overlay_window 布局，只读展示，不参与拖动/编辑）。 */
    private View bsdRightOverlayView = null;
    private TextureView bsdRightTextureView = null;
    private TextView bsdRightLabelView = null;
    private Surface bsdRightSurface = null;
    /** BSD 右窗当前是否显示。 */
    private boolean bsdRightOverlayShowing = false;
    /** 当前预览会话是否为“双 surface”模式（主窗 + BSD 右窗），true 时 createPreviewSession 同时输出两路。 */
    private boolean dualSessionTargets = false;
    // ---- v1.54 beta4：BSD 预警浮窗自定义编辑（独立 bsd_ 前缀键，左右各自独立） ----
    /** BSD 预警编辑态是否激活（主界面「编辑预警浮窗」按钮召唤；再次点击同时关闭并保存）。 */
    private boolean bsdEditActive = false;
    /** v1.54 beta6：BSD 编辑态是否由本服务独立启动摄像头预览（无打灯/BSD/手动浮窗归属）。 */
    private boolean bsdEditPreviewActive = false;
    /** v1.54 beta6：进入编辑态独立启动预览前的原 direction，退出编辑时恢复。 */
    private String savedDirectionBeforeEdit = null;
    /** BSD 左/右预警编辑浮窗（复用 overlay_window 布局，独立可拖动/双击编辑/右上角关闭）。 */
    private BsdEditWindow bsdEditLeft = null;
    private BsdEditWindow bsdEditRight = null;
    // ---- v1.53：AVM 画面守护 / 焦点解析日志 ----
    /** AVM 绑定视角守护重发间隔（ms）：打灯浮窗存活期间节流重发抵抗外部抢占。 */
    private static final long AVM_REFRESH_INTERVAL_MS = 1500L;
    /** 最近一次 AVM 守护重发时间戳（ms），0=未重发过。 */
    private long lastAvmRefreshMs = 0L;
    /** 焦点解析日志节流：记录上次上屏的包名与命中，仅在变化时上屏防刷屏。 */
    private String lastFocusLogPkg = null;
    private boolean lastFocusLogHit = false;
    // ---- v1.56 beta3：窄路辅助主动收起 / 退出恢复 ----
    /** 上一轮窄路辅助窗口在屏状态（裸包名窗口 com.xiaopeng.xmart.camera），用于 0->1/1->0 边沿检测。 */
    private boolean lastNarrowAssistActive = false;
    /** 窄路辅助激活时正在显示的打灯方向快照（"LEFT"/"RIGHT"/null），窄路消失后据此恢复打灯浮窗。 */
    private String narrowTurnDirSnapshot = null;
    /** 窄路辅助期间打灯转向信号是否仍亮（onSignalChanged 熄灭回调置 false，亮起回调置 true）。 */
    private boolean narrowTurnStillOn = false;
    /** 窄路辅助退出恢复后的防抖抑制窗口截止时间戳（ms），窗口内窄路再现不重复收起，防抖动循环。 */
    private long narrowExitSuppressUntilMs = 0L;
    /** 窄路辅助退出恢复防抖窗口时长（ms，参考 R 挡退出 2000ms 抑制机制）。 */
    private static final long NARROW_EXIT_SUPPRESS_MS = 2000L;

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

    /**
     * v1.52/v1.53 beta2/v1.54 beta4：应用慢变量门控配置（BSD 提醒开关 /
     * BSD 无车常显开关 / BSD 自动弹窗开关 / BSD 预警红色遮罩开关）。
     * v1.54 beta4：焦点抑制与原厂相机包名功能停用，不再写入/读取相关 prefs。
     * MainActivity 的开关改动后调用：写入 prefs 并推送 ACTION_APPLY_CONFIG
     * 让常驻服务即时生效（服务可能因未联动/手动浮窗而存续，直接改 prefs 不会被读取）。
     */
    public static void applyConfig(Context ctx, boolean bsdOn,
                                   boolean bsdShowAlways, boolean bsdAutoOverlay,
                                   boolean bsdRedOverlay) {
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        ed.putBoolean("bsd_warning_enabled", bsdOn);
        ed.putBoolean("bsd_show_always", bsdShowAlways);
        ed.putBoolean("bsd_auto_overlay", bsdAutoOverlay);
        ed.putBoolean("bsd_red_overlay", bsdRedOverlay);
        ed.apply();
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_APPLY_CONFIG);
        safeStart(ctx, i);
    }

    /** v1.54：BSD 常显窄条位置一键复位：清除 bsd_left/right_x/y 持久化键并恢复默认位置。 */
    public static void resetBsdPositions(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_RESET_BSD_POS);
        safeStart(ctx, i);
    }

    /** v1.54 beta4：召唤/关闭 BSD 预警浮窗编辑态（主界面「编辑预警浮窗」按钮调用）。
     * 未召唤时同时召唤左右预警浮窗进入可编辑状态；已召唤时同时关闭并保存两侧配置。 */
    public static void editBsdWarning(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_EDIT_BSD_WARNING);
        safeStart(ctx, i);
    }

    /** v1.54 beta4：预警浮窗同步转向灯浮窗（左对左、右对右：位置/大小/画面 pan 缩放）。 */
    public static void syncBsdToTurn(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_SYNC_BSD_TO_TURN);
        safeStart(ctx, i);
    }

    /** v1.54 beta4：单独关闭某侧 BSD 预警编辑浮窗并保存该侧配置（编辑窗右上角按钮）。 */
    public static void closeBsdWindow(Context ctx, String side) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_CLOSE_BSD_WINDOW);
        if (side != null) {
            i.putExtra(EXTRA_BSD_SIDE, side);
        }
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
        // v1.52：读取慢变量门控相关偏好；signalGate 在联动开启（startLinkInternal）时随 adb 启动
        loadConfigPrefs();
        signalGate = new SignalGate(this);
        sideWarning = new SideWarning(this, wm);
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
        } else if (ACTION_APPLY_CONFIG.equals(action)) {
            applyConfigInternal();
        } else if (ACTION_RESET_BSD_POS.equals(action)) {
            resetBsdPositionsInternal();
        } else if (ACTION_EDIT_BSD_WARNING.equals(action)) {
            toggleBsdEditInternal();
        } else if (ACTION_SYNC_BSD_TO_TURN.equals(action)) {
            syncBsdToTurnInternal();
        } else if (ACTION_CLOSE_BSD_WINDOW.equals(action)) {
            String side = intent.getStringExtra(EXTRA_BSD_SIDE);
            closeBsdEditWindowInternal(side);
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
                // v1.52：联动开启时同步启动 SignalGate 慢变量轮询（BSD/AVM/挡位冗余）
                // v1.54 beta4：焦点抑制停用，不再下发原厂相机包名列表
                if (signalGate != null) {
                    signalGate.start(adb);
                }
                // v1.52：BSD 提醒开启时挂载盲区窄条
                if (bsdWarningEnabled && sideWarning != null) {
                    sideWarning.attach();
                }
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
        // v1.52：联动关闭时同步停止 SignalGate 慢变量轮询并摘除 BSD 窄条
        if (signalGate != null) {
            signalGate.stop();
        }
        if (sideWarning != null) {
            sideWarning.detach();
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
                    // v1.56 beta3：记录窄路恢复打灯快照（方向 + 仍亮标记）。窄路辅助窗口在屏期间
                    // 打灯亮起会被 showOverlayForDirection 抑制不弹窗，窄路消失后据此恢复打灯浮窗。
                    narrowTurnDirSnapshot = direction;
                    narrowTurnStillOn = true;
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
                    // v1.56 beta3：打灯熄灭清除窄路恢复快照的“仍亮”标记，窄路消失不再恢复打灯浮窗。
                    narrowTurnStillOn = false;
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

    // ---- v1.52：慢变量门控配置加载 / 应用 / 回调 ----

    private void loadConfigPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bsdWarningEnabled = prefs.getBoolean("bsd_warning_enabled", false);
        bsdShowAlways = prefs.getBoolean("bsd_show_always", false);
        bsdAutoOverlay = prefs.getBoolean("bsd_auto_overlay", true);
        bsdRedOverlay = prefs.getBoolean("bsd_red_overlay", true);
        rExitSuppressMs = prefs.getLong("r_exit_suppress_ms", 2000L);
        if (rExitSuppressMs < 0) rExitSuppressMs = 2000L;
        // v1.57 beta3：仪表 H.264 投影开关/地址（主界面无 UI，仅保留 prefs 钩子供调试）
        iclProjectionEnabled = prefs.getBoolean("icl_projection_enable", true);
        iclIp = prefs.getString("icl_ip", ICLinkProjector.DEFAULT_HOST);
        iclPort = prefs.getInt("icl_port", ICLinkProjector.DEFAULT_PORT);
    }

    /** ACTION_APPLY_CONFIG：重新加载偏好并即时生效（BSD 窄条挂载/摘除）。v1.54 beta4 不再下发焦点包名。 */
    private void applyConfigInternal() {
        loadConfigPrefs();
        if (bsdWarningEnabled) {
            if (sideWarning != null && linkActive.get()) {
                sideWarning.attach();
            }
        } else {
            if (sideWarning != null) {
                sideWarning.detach();
            }
        }
        serviceLog("配置已应用：BSD提醒=" + bsdWarningEnabled
                + " BSD常显=" + bsdShowAlways + " BSD自动弹窗=" + bsdAutoOverlay
                + " BSD红遮罩=" + bsdRedOverlay);
    }

    /** v1.54 beta4：焦点抑制已停用，抑制判定方法移除（不再判断原厂相机前台）。 */

    /** SignalGate.Listener：500ms 慢变量快照回调（在轮询线程内触发，转主线程处理）。 */
    @Override
    public void onSignalTick(final SignalGate.Snapshot s) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (sideWarning != null) {
                    sideWarning.update(s, bsdShowAlways);
                }
                // v1.56 beta3：窄路辅助窗口边沿检测（裸包名窗口 com.xiaopeng.xmart.camera，
                // SignalGate 1s 降频检测）。
                // 出现边沿（0->1）：窄路辅助在屏 → 主动收起已显示浮窗（打灯/BSD 主浮窗经
                // hideOverlayAndCamera 并清 AVM 绑定状态，BSD 右独立窗经 hideBsdRightOverlay），
                // 收起前打灯浮窗若处于 AVM 绑定路径（已下发 mode=8）则 sendAvmMode 恢复 360 全景默认。
                // 消失边沿（1->0）：解除抑制后按当前状态恢复——打灯信号仍亮恢复打灯浮窗
                //（showOverlayForDirection 自动下发 mode=8）；BSD 对应侧仍为 1 则按当前电平恢复
                // 弹窗（BSD 边沿触发特性导致窄路期间上升沿被抑制后无新沿，须按电平直接恢复），
                // 恢复后进入 2s 防抖窗口，窗口内窄路再现不重复收起。
                long nowMs = System.currentTimeMillis();
                boolean narrow = s.narrowAssistActive;
                if (narrow && !lastNarrowAssistActive) {
                    lastNarrowAssistActive = true;
                    if (nowMs < narrowExitSuppressUntilMs) {
                        serviceLog("窄路辅助窗口出现（退出恢复防抖窗口内，不重复收起）");
                    } else {
                        collectNarrowAssistOverlays();
                    }
                } else if (!narrow && lastNarrowAssistActive) {
                    lastNarrowAssistActive = false;
                    restoreAfterNarrowExit(s);
                    narrowExitSuppressUntilMs = nowMs + NARROW_EXIT_SUPPRESS_MS;
                }
                // v1.54：BSD 预警自动弹窗（最低优先级）——检测 SCU_BSD_LEFT/RIGHT_WARNING 0->1
                // 边沿弹对应侧摄像头浮窗；BSD 预警熄灭且由 BSD 弹起时关闭对应侧浮窗。
                // 开关关闭时复位状态并释放由 BSD 弹起的浮窗。更高优先级（打灯/倒车/窄路）
                // 的抑制在 onBsdEdge 内部判定。
                if (bsdAutoOverlay) {
                    boolean bsdL = s.bsdLeft;
                    boolean bsdR = s.bsdRight;
                    if (bsdL && !lastBsdLeft) {
                        serviceLog("BSD 预警: 左侧 0->1，请求自动弹左摄像头");
                        onBsdEdge("LEFT", true);
                    } else if (!bsdL && lastBsdLeft) {
                        onBsdEdge("LEFT", false);
                        if ("LEFT".equals(bsdAutoActiveDir)) {
                            serviceLog("BSD 预警: 左侧熄灭，关闭 BSD 左浮窗");
                        }
                    }
                    if (bsdR && !lastBsdRight) {
                        serviceLog("BSD 预警: 右侧 0->1，请求自动弹右摄像头");
                        onBsdEdge("RIGHT", true);
                    } else if (!bsdR && lastBsdRight) {
                        onBsdEdge("RIGHT", false);
                        if ("RIGHT".equals(bsdAutoActiveDir)) {
                            serviceLog("BSD 预警: 右侧熄灭，关闭 BSD 右浮窗");
                        }
                    }
                    lastBsdLeft = bsdL;
                    lastBsdRight = bsdR;
                    // v1.56 beta1：右独立窗稳态补弹——右侧 BSD 预警中但右独立窗未显示
                    // （右先亮、右窗创建失败、或任何路径漏建）且左侧处于 BSD 左显示态时，
                    // 补建右独立窗实现左右双开（showBsdRightOverlay 幂等，创建失败下轮重试）。
                    if (bsdR && !bsdRightOverlayShowing && bsdRightOverlayView == null
                            && !bsdEditActive) {
                        boolean leftBsdDisplayed = "LEFT".equals(bsdAutoActiveDir)
                                || (direction != null && "LEFT".equals(direction)
                                    && lastActiveDir == null && !"MANUAL".equals(direction));
                        if (leftBsdDisplayed) {
                            showBsdRightOverlay();
                        }
                    }
                } else {
                    lastBsdLeft = false;
                    lastBsdRight = false;
                    if (bsdAutoActiveDir != null) {
                        serviceLog("BSD 自动弹窗开关已关闭，释放 BSD 弹起浮窗");
                        releaseBsdAutoOverlay();
                    }
                }
                // v1.56 beta1：打灯浮窗联动 BSD 红框实时化——bsd_red_overlay 开关开启、
                // 打灯浮窗显示中（lastActiveDir 非 MANUAL 且非 null）、BSD 自动弹窗未占用时，
                // 左右 BSD 信号 0->1/1->0 边沿实时调用 onBsdEdgeForTurn 叠加/移除对应侧红框；
                // 条件不满足（开关关闭/无打灯浮窗/BSD 弹窗占用）时复位边沿跟踪状态。
                if (bsdRedOverlay && lastActiveDir != null && !"MANUAL".equals(lastActiveDir)
                        && bsdAutoActiveDir == null) {
                    boolean turnL = s.bsdLeft;
                    boolean turnR = s.bsdRight;
                    if (turnL && !lastTurnBsdLeft) {
                        onBsdEdgeForTurn("LEFT", true);
                    } else if (!turnL && lastTurnBsdLeft) {
                        onBsdEdgeForTurn("LEFT", false);
                    }
                    if (turnR && !lastTurnBsdRight) {
                        onBsdEdgeForTurn("RIGHT", true);
                    } else if (!turnR && lastTurnBsdRight) {
                        onBsdEdgeForTurn("RIGHT", false);
                    }
                    lastTurnBsdLeft = turnL;
                    lastTurnBsdRight = turnR;
                } else {
                    lastTurnBsdLeft = false;
                    lastTurnBsdRight = false;
                }
                // v1.53：焦点解析上屏日志（仅 pkg/hit 变化时打，避免 500ms 刷屏淹没 50 行日志窗）
                if (s.focusPkg != null
                        && (!s.focusPkg.equals(lastFocusLogPkg) || s.focusHit != lastFocusLogHit)) {
                    lastFocusLogPkg = s.focusPkg;
                    lastFocusLogHit = s.focusHit;
                    serviceLog("焦点解析: pkg=" + s.focusPkg + " hit=" + s.focusHit);
                }
                long now = System.currentTimeMillis();
                // v1.53：R 挡进行中持续刷新抑制窗口 —— gearLevel==3 期间打灯同样不弹浮窗，
                // 且退出后仍保留 rExitSuppressMs 延时窗口（下方边沿逻辑兜底）。
                if (s.gearLevel == 3) {
                    rExitSuppressUntilMs = now + rExitSuppressMs;
                }
                // R 挡退出边沿（3 -> 非3）检测：仅当本轮明确解析到 GEARLEV 才更新状态，
                // 容错态（-1）保持上一轮，避免格式缺失误判退出。
                if (s.gearLevel != -1 && lastGearForRExit != -1
                        && lastGearForRExit == 3 && s.gearLevel != 3) {
                    rExitSuppressUntilMs = now + rExitSuppressMs;
                    serviceLog("检测到 R 挡退出（3 -> " + s.gearLevel + "），进入 "
                            + rExitSuppressMs + "ms 打灯抑制窗口");
                    // v1.53：倒车退出边沿且打灯浮窗存活（AVM 绑定视角路径）→ 立即补发 display-mode=8
                    // 刷新画面源，抵抗外部抢占了 AVM 视角导致的画面残留/带偏。
                    if (overlayView != null && avmModeActive) {
                        sendAvmMode(8);
                        lastAvmRefreshMs = now;
                        serviceLog("AVM 守护：倒车退出边沿立即补发 display-mode=8");
                    }
                }
                if (s.gearLevel != -1) {
                    lastGearForRExit = s.gearLevel;
                }
                // v1.53：AVM 画面守护（节流重发）—— 打灯浮窗存活且检测到 AVM 显示模式被抢占
                // （解析到且 != 8）时，约 1.5s 一次重发 sendAvmMode(8) 夺回绑定视角。
                if (overlayView != null && avmModeActive
                        && s.avmDisplayMode != -1 && s.avmDisplayMode != 8
                        && now - lastAvmRefreshMs >= AVM_REFRESH_INTERVAL_MS) {
                    lastAvmRefreshMs = now;
                    sendAvmMode(8);
                    serviceLog("AVM 守护：检测到显示模式被抢占（mode=" + s.avmDisplayMode
                            + "），重发 display-mode=8");
                }
            }
        });
    }

    /** Camera1 回退钩子：Camera2 打开失败时若偏好 camera1_fallback=true 且纹理就绪，尝试 Camera1 预览。 */
    private void tryCamera1Fallback() {
        if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("camera1_fallback", false)) {
            return;
        }
        if (textureView == null || textureView.getSurfaceTexture() == null) {
            Log.e(TAG, "camera1 fallback: textureView not ready");
            return;
        }
        try {
            camera1Preview = new Camera1Preview(textureView.getSurfaceTexture(), BUFFER_W, BUFFER_H);
            if (camera1Preview.open()) {
                serviceLog("Camera2 打开失败，已回退 Camera1 预览（Phase 2 验证态）");
            } else {
                serviceLog("Camera1 回退失败，浮窗仅保留静态画面");
                camera1Preview = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "camera1 fallback error", e);
            camera1Preview = null;
        }
    }

    // ---- v1.54：BSD 预警自动弹窗（最低优先级） ----

    /** BSD 最低优先级抑制判定：打灯/其它方向浮窗激活中、手动浮窗显示中、R 挡倒车显示中、
     * 窄路辅助动态窗口在屏 → 一律抑制 BSD 自动弹窗（BSD 仅在这些更高优先级缺席时弹出）。 */
    private boolean shouldSuppressBsdAuto() {
        if (signalGate != null) {
            SignalGate.Snapshot s = signalGate.snapshot();
            if (s.narrowAssistActive) {
                serviceLog("BSD 自动弹窗：窄路辅助动态窗口在屏，抑制不弹浮窗");
                return true;
            }
            if (s.gearLevel == 3) {
                serviceLog("BSD 自动弹窗：R 挡倒车显示中（gearLevel=3），抑制不弹浮窗");
                return true;
            }
        }
        if (lastActiveDir != null && !"MANUAL".equals(lastActiveDir)) {
            serviceLog("BSD 自动弹窗：打灯/其它方向浮窗激活中（" + lastActiveDir + "），抑制不弹浮窗");
            return true;
        }
        if (manualVisible.get()) {
            serviceLog("BSD 自动弹窗：手动浮窗显示中，抑制不弹浮窗");
            return true;
        }
        return false;
    }

    /** BSD 边沿回调（主线程）：rising=true 为 SCU_BSD_*_WARNING 0->1 预警上升沿，false 为 1->0 熄灭沿。 */
    private void onBsdEdge(String dir, boolean rising) {
        if (!rising) {
            // 熄灭沿：若该侧浮窗由 BSD 弹起则关闭对应侧（沿用打灯去抖关闭逻辑）
            if (dir.equals(bsdAutoActiveDir)) {
                bsdAutoActiveDir = null;
                if (lastActiveDir != null && !"MANUAL".equals(lastActiveDir)) {
                    // 更高优先级（打灯/手动）已接管主浮窗，仅清 BSD 归属标记，不关浮窗
                    return;
                }
                hideOverlayAndCamera();
                serviceLog("BSD 自动弹窗：BSD 预警熄灭，已关闭 " + dir + " 摄像头浮窗");
            }
            if ("RIGHT".equals(dir) && bsdRightOverlayShowing) {
                hideBsdRightOverlay();
                serviceLog("BSD 自动弹窗：右侧预警熄灭，已关闭 BSD 右浮窗");
            }
            return;
        }
        // 上升沿：最低优先级抑制判定（打灯/倒车/窄路/手动占用时不弹）
        if (shouldSuppressBsdAuto()) {
            return;
        }
        if ("LEFT".equals(dir)) {
            // v1.56 beta1：右侧 BSD 主浮窗显示中（bsdAutoActiveDir=="RIGHT" 且相机就绪）时，
            // 先把右侧画面转为右独立窗显示，再执行左上升沿逻辑，实现左主浮窗+右独立窗双开，
            // 避免左上升沿覆盖 direction 导致右侧显示消失（先建右窗、后切左方向，右侧画面持续显示）。
            if ("RIGHT".equals(bsdAutoActiveDir)
                    && overlayView != null && cameraDevice != null) {
                showBsdRightOverlay();
                bsdAutoActiveDir = null;
                serviceLog("BSD 自动弹窗：左预警到来，右侧先转为 BSD 右独立窗显示，再切左主浮窗（双开）");
            }
            // 左预警：弹出左摄像头浮窗（等同打灯左浮窗逻辑）
            bsdAutoActiveDir = "LEFT";
            bsdAutoCalling = true;
            showOverlayForDirection("LEFT");
            bsdAutoCalling = false;
            // v1.54 beta3：BSD 预警弹窗边界加红色遮罩（示意预警状态，仅预警弹窗显示）
            showRedOverlayOn(overlayView);
            // v1.54 beta4：左预警应用用户编辑保存的 bsd_left_ 键（位置/大小/画面），未编辑跳过
            applyBsdLeftConfigIfPresent();
        } else {
            // 右预警：左右同时预警（BSD 双开）→ 弹独立只读右窗（避让公式）；
            // 仅右预警 → 弹出右摄像头主浮窗（等同打灯右浮窗逻辑）
            if (signalGate != null && signalGate.snapshot().bsdLeft) {
                showBsdRightOverlay();
            } else {
                bsdAutoActiveDir = "RIGHT";
                bsdAutoCalling = true;
                showOverlayForDirection("RIGHT");
                bsdAutoCalling = false;
                // v1.54 beta3：仅右预警（主浮窗）同样加红色遮罩
                showRedOverlayOn(overlayView);
            }
        }
    }

    /** 开关关闭/服务清理时释放由 BSD 弹起的全部浮窗（右窗无条件移除，主窗仅当归属 BSD 时关闭）。 */
    private void releaseBsdAutoOverlay() {
        hideBsdRightOverlay();
        if (bsdAutoActiveDir != null) {
            bsdAutoActiveDir = null;
            if (lastActiveDir == null || "MANUAL".equals(lastActiveDir)) {
                hideOverlayAndCamera();
            }
        }
    }

    /**
     * v1.56 beta3：窄路辅助窗口出现边沿——主动收起已显示浮窗。
     * 1) 打灯浮窗（lastActiveDir 非 MANUAL）：记录恢复快照后经 hideOverlayAndCamera 收起并清
     *    avmModeActive；收起前若处于 AVM 绑定路径（avmModeActive==true，已下发 display-mode=8）
     *    则补发 sendAvmMode(8) 恢复 360 全景默认视角。
     * 2) BSD 主浮窗（bsdAutoActiveDir 非 null）：清归属后同样 hideOverlayAndCamera 收起；BSD 主浮窗
     *    实际走 AVM 绑定路径（getAvmBinding 恒返回 8），按 avmModeActive 判断是否补发 mode=8；
     *    物理预览路径（avmModeActive==false）不涉 AVM mode，无需发送。
     * 3) BSD 右独立窗：调 hideBsdRightOverlay 单独收起（复用 overlay_window 布局，不涉 AVM mode）。
     * 手动浮窗（MANUAL）为用户主动调试，不在自动收起范围。
     */
    private void collectNarrowAssistOverlays() {
        serviceLog("窄路辅助窗口出现，主动收起已显示浮窗");
        boolean anyTurn = lastActiveDir != null && !"MANUAL".equals(lastActiveDir);
        boolean anyBsdMain = bsdAutoActiveDir != null;
        if (anyTurn) {
            narrowTurnDirSnapshot = lastActiveDir;
            narrowTurnStillOn = true;
        } else {
            narrowTurnDirSnapshot = null;
            narrowTurnStillOn = false;
        }
        boolean wasAvm = avmModeActive;
        if (anyTurn || anyBsdMain) {
            if (anyBsdMain) {
                bsdAutoActiveDir = null;
            }
            hideOverlayAndCamera();
            if (wasAvm) {
                sendAvmMode(8);
                serviceLog("窄路辅助收起：浮窗曾绑定 AVM 视角，恢复 display-mode=8（360 全景默认）");
            } else {
                serviceLog("窄路辅助收起：浮窗为物理预览路径，无需下发 AVM mode");
            }
        }
        if (bsdRightOverlayShowing) {
            hideBsdRightOverlay();
            serviceLog("窄路辅助收起：BSD 右独立窗已收起");
        }
    }

    /**
     * v1.56 beta3：窄路辅助窗口消失边沿——解除抑制后按当前状态恢复浮窗。
     * 1) 打灯信号仍亮（窄路期间未收到熄灭回调）且记录过方向 → 恢复打灯浮窗：
     *    showOverlayForDirection(dir) 内 AVM 绑定块自动 sendAvmMode(8)（绑定视角即 360 全景默认），
     *    红框由 syncTurnBsdRedOverlay 同步；若打灯已熄灭则窄路期间抑制的新亮起方向已由
     *    onSignalChanged 快照接续，同样可恢复。
     * 2) BSD 对应侧信号仍为 1（有车）→ 按当前电平直接恢复弹窗（复用 onBsdEdge 上升沿，
     *    含双开/左右逻辑与红色遮罩；窄路期间上升沿被 shouldSuppressBsdAuto 抑制后无新沿，
     *    必须按电平补沿）。打灯已恢复时 onBsdEdge 内部打灯占用抑制自然让位。
     * 恢复完成后进入 2s 防抖窗口（调用方设置 narrowExitSuppressUntilMs），窗口内窄路再现不重复收起。
     */
    private void restoreAfterNarrowExit(SignalGate.Snapshot s) {
        serviceLog("窄路辅助窗口消失，按当前状态恢复浮窗");
        // 1) 打灯仍亮：恢复打灯浮窗（AVM 绑定路径自动下发 display-mode=8）
        if (narrowTurnStillOn && narrowTurnDirSnapshot != null) {
            String dir = narrowTurnDirSnapshot;
            serviceLog("窄路恢复：打灯信号仍亮（" + dir + "），恢复打灯浮窗 + display-mode=8");
            showOverlayForDirection(dir);
        }
        // 2) BSD 侧仍 1：按当前电平恢复弹窗（左亮恢复左主浮窗；右亮按双开/左右逻辑恢复）
        if (s.bsdLeft) {
            serviceLog("窄路恢复：BSD 左侧信号仍为 1，按当前电平恢复弹窗");
            onBsdEdge("LEFT", true);
        }
        if (s.bsdRight) {
            serviceLog("窄路恢复：BSD 右侧信号仍为 1，按当前电平恢复弹窗");
            onBsdEdge("RIGHT", true);
        }
        // 消耗本 tick 刚出现的 BSD 上升沿（若有），避免紧随其后的 BSD 边沿检测块重复弹窗；
        // 正常无新沿时赋值与已有状态一致，无副作用。
        lastBsdLeft = s.bsdLeft;
        lastBsdRight = s.bsdRight;
        // 清理恢复快照
        narrowTurnDirSnapshot = null;
        narrowTurnStillOn = false;
    }

    /** v1.54：BSD 双开时右侧独立只读浮窗——位置固定为紧挨左浮窗预设位置的右侧
     * （rightX = leftX + 左浮窗宽 + 8dp），无论左浮窗是否实际出现均按该公式摆放；
     * 不参与拖动/编辑（复用 overlay_window 布局，隐藏关闭按钮）。 */
    private void showBsdRightOverlay() {
        if (bsdRightOverlayShowing || bsdRightOverlayView != null) return;
        try {
            LayoutInflater li = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            bsdRightOverlayView = li.inflate(R.layout.overlay_window, null);
            bsdRightTextureView = bsdRightOverlayView.findViewById(R.id.overlay_texture);
            if (bsdRightTextureView != null) {
                bsdRightTextureView.setClipToOutline(true);
                bsdRightTextureView.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
                    }
                });
                bsdRightTextureView.setSurfaceTextureListener(bsdRightSurfaceListener);
            }
            bsdRightLabelView = bsdRightOverlayView.findViewById(R.id.overlay_label);
            if (bsdRightLabelView != null) bsdRightLabelView.setText("BSD 右侧预警");
            View closeBtn = bsdRightOverlayView.findViewById(R.id.overlay_close);
            if (closeBtn != null) closeBtn.setVisibility(View.GONE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            migrateLegacyOverlayScale(dm.widthPixels, dm.heightPixels);
            // v1.54 beta4：右窗宽高优先用 bsd_right_ 独立键（编辑保存过才生效），
            // 否则回退 beta3 的 left_ 键/避让公式逻辑
            boolean bsdRightEdited = prefs.contains("bsd_right_overlay_w")
                    || prefs.contains("bsd_right_cam_scale");
            int w;
            int h;
            int x;
            int y;
            if (bsdRightEdited) {
                int minW = (int) (dm.widthPixels * MIN_SIZE_RATIO);
                int minH = (int) (dm.heightPixels * MIN_SIZE_RATIO);
                w = prefs.getInt("bsd_right_overlay_w", readOverlayW(dm.widthPixels));
                h = prefs.getInt("bsd_right_overlay_h", readOverlayH(dm.heightPixels));
                if (w < minW) w = minW;
                if (w > dm.widthPixels) w = dm.widthPixels;
                if (h < minH) h = minH;
                if (h > dm.heightPixels) h = dm.heightPixels;
                if (prefs.getBoolean("bsd_right_overlay_pos_recorded", false)) {
                    x = prefs.getInt("bsd_right_overlay_pos_x",
                            dm.widthPixels - w - 120);
                    y = prefs.getInt("bsd_right_overlay_pos_y",
                            dm.heightPixels - h - 120);
                } else {
                    int leftX = prefs.getInt("left_overlay_pos_x", 120);
                    int leftY = prefs.getInt("left_overlay_pos_y", dm.heightPixels - h - 120);
                    x = leftX + w + dp(BSD_DUAL_GAP_DP);
                    y = leftY;
                }
            } else {
                // 右窗宽高/位置完全按左窗配置计算：w/h 用 left_ 键，位置取左窗持久化 x/y 右侧偏移
                w = readOverlayW(dm.widthPixels);
                h = readOverlayH(dm.heightPixels);
                int leftX = prefs.getInt("left_overlay_pos_x", 120);
                int leftY = prefs.getInt("left_overlay_pos_y", dm.heightPixels - h - 120);
                x = leftX + w + dp(BSD_DUAL_GAP_DP);
                y = leftY;
            }
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    w, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = x;
            lp.y = y;
            wm.addView(bsdRightOverlayView, lp);
            bsdRightOverlayShowing = true;
            // v1.54 beta3：BSD 预警右窗加红色遮罩 + 复用右转向灯画面源渲染（与位置解耦）
            showRedOverlayOn(bsdRightOverlayView);
            applyBsdRightRenderTransform();
            serviceLog("BSD 自动弹窗：BSD 双开，右浮窗已弹（x=" + x + ", y=" + y + ", w=" + w + "）");
            // 主浮窗会话已就绪时重建预览会话以包含右窗 surface（同一路预览双窗输出）
            if (cameraDevice != null && textureView != null && textureView.isAvailable()) {
                createPreviewSession();
            }
        } catch (Exception e) {
            Log.e(TAG, "BSD 右窗 addView failed", e);
            hideBsdRightOverlay();
        }
    }

    /** 移除 BSD 右浮窗并释放其 surface；主会话存活时重建预览会话去掉右窗 target。 */
    private void hideBsdRightOverlay() {
        if (bsdRightOverlayView != null) {
            // v1.54 beta3：关闭前移除红色遮罩
            hideRedOverlayOn(bsdRightOverlayView);
            try {
                wm.removeView(bsdRightOverlayView);
            } catch (Exception ignored) {
            }
            bsdRightOverlayView = null;
        }
        bsdRightOverlayShowing = false;
        bsdRightTextureView = null;
        bsdRightLabelView = null;
        if (bsdRightSurface != null) {
            bsdRightSurface.release();
            bsdRightSurface = null;
        }
        if (cameraDevice != null && textureView != null && textureView.isAvailable()) {
            createPreviewSession();
        }
    }

    /** BSD 右浮窗 TextureView 监听：surface 就绪后把右窗 target 加入预览会话。 */
    private final TextureView.SurfaceTextureListener bsdRightSurfaceListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
            // v1.54 beta3：右窗 surface 就绪后重放渲染矩阵（复用右转向灯画面源，与位置解耦）
            applyBsdRightRenderTransform();
            if (cameraDevice != null && textureView != null && textureView.isAvailable()) {
                createPreviewSession();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
            // v1.54 beta3：右窗尺寸变化重放渲染矩阵
            applyBsdRightRenderTransform();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }
    };

    // ---- v1.54 beta3：BSD 预警红色遮罩 + 右预警画面复用右转向灯画面源 ----

    /** 在宿主浮窗边界加一小圈向内的半透明红色遮罩（示意 BSD 预警状态）。
     * 仅 BSD 预警弹窗调用（onBsdEdge/showBsdRightOverlay）；开关关闭时不加。
     * 宿主必须为 overlay_window 的 FrameLayout；遮罩 View 不可点击，不拦截拖动/编辑手势。
     * v1.54 beta4：红边改为呼吸红色渐变闪烁——alpha 在低-高区间周期性循环，替换静态描边。 */
    private void showRedOverlayOn(View host) {
        if (host == null || !bsdRedOverlay) return;
        if (host.findViewWithTag("bsd_red_overlay") != null) return;
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(0x00000000);
        gd.setStroke(dp(9), 0x66FF0000); // v1.54 beta7：呼吸红框描边加粗为 3 倍（3dp→9dp）
        View rv = new View(this);
        rv.setTag("bsd_red_overlay");
        rv.setBackground(gd);
        rv.setClickable(false);
        rv.setFocusable(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        ((FrameLayout) host).addView(rv, lp);
        // v1.54 beta4：呼吸闪烁动画——透明度 0.15 -> 1.0 循环往复，红边随之渐变闪动
        android.animation.ObjectAnimator anim = android.animation.ObjectAnimator.ofFloat(rv, "alpha", 0.15f, 1.0f);
        anim.setDuration(900);
        anim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        anim.start();
        rv.setTag(R.id.overlay_border, anim);
    }

    /** 移除宿主浮窗上的 BSD 预警红色遮罩（无则跳过），并取消呼吸动画。 */
    private void hideRedOverlayOn(View host) {
        if (host == null) return;
        View rv = host.findViewWithTag("bsd_red_overlay");
        if (rv != null) {
            Object anim = rv.getTag(R.id.overlay_border);
            if (anim instanceof android.animation.Animator) {
                ((android.animation.Animator) anim).cancel();
            }
            ((FrameLayout) host).removeView(rv);
        }
    }

    /** v1.54 beta7：打灯浮窗联动 BSD 红框——BSD 信号边沿到来时，若对应侧打灯变道浮窗
     * 正在显示（非 BSD 弹起、非手动），叠加/移除呼吸红框。复用 showRedOverlayOn（内部受
     * bsd_red_overlay 开关控制）；打灯期间 BSD 自动弹窗已被最低优先级抑制，红框为替代提醒。 */
    private void onBsdEdgeForTurn(String dir, boolean rising) {
        if (overlayView == null || direction == null) return;
        if ("MANUAL".equals(direction)) return;
        if (bsdAutoActiveDir != null) return;
        if (!dir.equals(direction)) return;
        if (rising) {
            showRedOverlayOn(overlayView);
            serviceLog("BSD 联动红框: 打" + ("LEFT".equals(dir) ? "左" : "右")
                    + "转向灯期间对应侧 BSD 有车，浮窗叠加呼吸红框");
        } else {
            hideRedOverlayOn(overlayView);
            serviceLog("BSD 联动红框: " + ("LEFT".equals(dir) ? "左" : "右")
                    + "侧 BSD 信号消失，移除打灯浮窗红框");
        }
    }

    /** v1.54 beta7：打灯浮窗建立/方向切换后同步当前 BSD 状态——对应侧 BSD 已有车（无 0->1 边沿）
     * 时立即叠加呼吸红框，保证浮窗显示期间红框状态与 BSD 信号实时一致。 */
    private void syncTurnBsdRedOverlay() {
        if (overlayView == null || direction == null || "MANUAL".equals(direction)) return;
        if (bsdAutoActiveDir != null) return;
        if (!bsdRedOverlay) return;
        if (signalGate == null) return;
        SignalGate.Snapshot s = signalGate.snapshot();
        boolean warn = "LEFT".equals(direction) ? s.bsdLeft : s.bsdRight;
        if (warn) {
            if (overlayView.findViewWithTag("bsd_red_overlay") == null) {
                showRedOverlayOn(overlayView);
                serviceLog("BSD 联动红框: 打" + ("LEFT".equals(direction) ? "左" : "右")
                        + "转向灯浮窗显示期间对应侧 BSD 已有车，叠加呼吸红框");
            }
        } else {
            hideRedOverlayOn(overlayView);
        }
    }

    /** v1.54 beta3：BSD 右预警浮窗渲染矩阵——默认复用右转向灯画面源设置（right_cam_scale /
     * right_pan_x / right_pan_y，与打右转向灯浮窗同一套持久化键，包含默认初始右下 1/4 裁切
     * 与用户编辑后的自由平移），使右预警画面与打右转向灯时完全一致。
     * v1.54 beta4：若用户已用「编辑预警浮窗」保存过 bsd_right_ 独立键（存在 bsd_right_cam_scale），
     * 则优先使用 bsd_right_ 独立配置，未编辑时保持 beta3 的 right_ 键回退逻辑。
     * 画面内容与浮窗位置彻底解耦：矩阵只依赖窗口尺寸与画面配置，不读 lp.x/lp.y。 */
    private void applyBsdRightRenderTransform() {
        if (bsdRightOverlayView == null || bsdRightTextureView == null) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) bsdRightOverlayView.getLayoutParams();
        int vw = lp.width;
        int vh = lp.height;
        if (vw <= 0) vw = 1;
        if (vh <= 0) vh = 1;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        float defW = dm.widthPixels * OVERLAY_DEF_W_RATIO;
        float defH = defW * 9f / 16f;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // v1.54 beta4：bsd_right_ 独立键优先，未编辑回退 right_ 键（beta3 行为不变）
        boolean bsdEdited = prefs.contains("bsd_right_cam_scale");
        float cs = prefs.getFloat(bsdEdited ? "bsd_right_cam_scale" : "right_cam_scale",
                CAM_SCALE_DEFAULT);
        if (cs < CAM_SCALE_MIN) cs = CAM_SCALE_MIN;
        if (cs > CAM_SCALE_MAX) cs = CAM_SCALE_MAX;
        float Wv = defW * cs;
        float Hv = defH * cs;
        float scale = Wv / BUFFER_W;
        float dx;
        float dy;
        // 与 applyRenderTransform 的 defaultCrop 判定一致：右预警未编辑过（无 bsd_right_cam_scale
        // 键、也无 right_cam_scale 键）时按默认初始裁切显示（四宫格右下 1/4）；已编辑则完全复用
        // 用户调整后的画面配置（bsd_ 键或 right_ 键）
        boolean defaultCrop = !prefs.contains("bsd_right_cam_scale")
                && !prefs.contains("right_cam_scale");
        if (defaultCrop) {
            float fit = Math.min(vw / Wv, vh / Hv);
            scale = (Wv / BUFFER_W) * fit;
            dx = (vw - Wv * fit) / 2f;
            dy = (vh - Hv * fit) / 2f;
        } else {
            float panX = prefs.getFloat(bsdEdited ? "bsd_right_pan_x" : "right_pan_x", 0f);
            float panY = prefs.getFloat(bsdEdited ? "bsd_right_pan_y" : "right_pan_y", 0f);
            dx = (vw - Wv) / 2f + panX;
            dy = (vh - Hv) / 2f + panY;
        }
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.reset();
        m.postScale(scale, scale);
        m.postTranslate(dx, dy);
        bsdRightTextureView.setTransform(m);
        serviceLog("BSD右窗RT vw=" + vw + " vh=" + vh + " Wv=" + (int) Wv + " Hv=" + (int) Hv
                + " scale=" + String.format("%.4f", scale) + " dx=" + (int) dx + " dy=" + (int) dy
                + " src=" + (bsdEdited ? "bsd_right_" : "right_"));
    }

    /** v1.54：BSD 常显窄条位置一键复位（ACTION_RESET_BSD_POS 入口）。 */
    private void resetBsdPositionsInternal() {
        if (sideWarning != null) {
            sideWarning.resetPositions();
        }
        serviceLog("BSD 常显窄条位置已一键复位为默认（x=8dp, y=90dp）");
    }

    /** 按方向显示/切换浮窗：首次弹窗创建 overlay，方向变化释放旧摄像头并按新方向重开。 */
    private void showOverlayForDirection(String dir) {
        // v1.54：BSD 自动弹窗调用（bsdAutoCalling=true）已由 onBsdEdge 完成最低优先级抑制判定，
        // 这里跳过打灯专属抑制块；同时不参与打灯幂等锁/方向锁写入，避免污染打灯链路。
        boolean bsdCall = bsdAutoCalling;
        // v1.52/v1.53 beta2：抑制优先级（仅作用于转向灯弹窗，不干扰 MANUAL 手动调试）：
        // 窄路辅助动态窗口（最高） > GEARLEV==3 倒车显示中 > R 挡退出短延时窗口 > 原厂相机焦点 > 单边转向。
        if (!bsdCall && !"MANUAL".equals(dir)) {
            // v1.53 beta2：窄路辅助动态窗口在屏（裸包名窗口）时打灯一律抑制，
            // 避免转向浮窗覆盖窄路辅助画面（最高优先级）。
            if (signalGate != null && signalGate.snapshot().narrowAssistActive) {
                serviceLog("窄路辅助动态窗口在屏，打灯抑制不弹浮窗");
                return;
            }
            // v1.53：R 挡进行中（gearLevel==3 已正确解析）打灯一律抑制，
            // 避免倒车影像期间弹转向浮窗抢占 AVM 画面。
            if (signalGate != null && signalGate.snapshot().gearLevel == 3) {
                serviceLog("R 挡进行中（gearLevel=3），打灯抑制不弹浮窗");
                return;
            }
            long remain = rExitSuppressUntilMs - System.currentTimeMillis();
            if (remain > 0) {
                serviceLog("R 挡退出延时抑制窗口内（剩余 " + remain + "ms），打灯不弹浮窗");
                return;
            }
        }
        // v1.57 beta3：浮窗弹窗即异步启动仪表 H.264 投影（连接失败静默降级，浮窗不受影响）。
        // 多次打灯循环：stop 后再次 show 可重新启动，不泄漏编码器/socket/线程。
        startProjectionIfEnabled();
        // v1.39：同方向持续亮起期间若浮窗与相机均已就绪，忽略重复触发，绝不重发 AVM 绑定视角。
        // 打灯后转向灯信号理论上只产生一次 0->1 边沿；若车机 dumpsys 输出抖动出现假边沿，
        // 再次进入本方法会重复 sendAvmMode 抢占 AVM 视角（表现为倒挡影像被覆盖、转向画面卡顿），
        // 此幂等锁 + SignalMonitor 缺失行保持原状态双重兜底，确保打灯期间只下发一次绑定视角。
        // v1.54：BSD 自动弹窗调用不参与幂等锁（0->1 边沿每轮至多一次，无重复触发风险）。
        if (!bsdCall && dir.equals(lastActiveDir) && overlayView != null && cameraDevice != null) {
            serviceLog("同方向信号持续亮起，忽略重复触发，不重发 AVM 视角");
            return;
        }
        // v1.57：打灯/手动接管浮窗时无条件清理 BSD 归属标记并移除预警红框——
        // 预警红框与预警浮窗完全绑定（随浮窗出现/消失），浮窗一旦被转向/手动接管
        // （不再限于同方向），立即移除预警红框；清理归属保证转向红框独立判定
        // （syncTurnBsdRedOverlay / onBsdEdgeForTurn 不再被残留归属短路），
        // 同时避免 BSD 预警熄灭沿把已接管浮窗误关（见 onBsdEdge）。
        if (!bsdCall) {
            if (bsdAutoActiveDir != null) {
                serviceLog("打灯/手动接管浮窗，清理 BSD 归属标记（" + bsdAutoActiveDir + "）");
                bsdAutoActiveDir = null;
            }
            hideRedOverlayOn(overlayView);
        }
        if (!bsdCall) {
            lastActiveDir = dir;
        }
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
                // v1.54 beta7：打灯浮窗建立后同步当前 BSD 状态——对应侧 BSD 已有车则立即叠加呼吸红框
                syncTurnBsdRedOverlay();
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
        // v1.57 beta3：熄灯/预警结束收窗，同步干净停止投影（stop 幂等）
        stopProjection();
    }

    // ---- v1.57 beta3：仪表 H.264 投影联动 ----

    /** 启用仪表投影时异步启动（失败静默降级，绝不阻塞/影响浮窗）。 */
    private void startProjectionIfEnabled() {
        if (!iclProjectionEnabled) {
            return;
        }
        try {
            if (icProjector.isRunning()) {
                return;
            }
            icProjector.start(iclIp, iclPort);
        } catch (Throwable t) {
            Log.w(TAG, "startProjection failed, silent: " + t.getMessage());
        }
    }

    /** 同步停止仪表投影（幂等，可重复调用）。 */
    private void stopProjection() {
        try {
            icProjector.stop();
        } catch (Throwable t) {
            Log.w(TAG, "stopProjection failed, silent: " + t.getMessage());
        }
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
            // v1.54 beta3：关闭前移除 BSD 预警红色遮罩（若存在）
            hideRedOverlayOn(overlayView);
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
            // v1.52：Camera2 打开失败时尝试 Camera1 回退钩子（默认关闭，偏好 camera1_fallback 开启）
            tryCamera1Fallback();
        }
    }

    /**
     * 按当前方向选择摄像头 ID：优先使用测试页配置的左/右镜头 ID，
     * 未配置时统一回退 ids[0]（video51 鱼眼广角）。
     * v1.28 起仅作为未绑定 AVM 视角时的兜底；v1.36 右转向未配置时与左转向一致用 ids[0]，
     * 避免右转向落到 ids[1]（video52）导致画面缺一块（实车确认左转向 video51 画面完整）。 */
    private String pickCameraId(String[] ids) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // v1.54 beta6：BSD 编辑态优先读 bsd_ 前缀 cameraId（左 bsd_left_camera_id、
        // 右 bsd_right_camera_id），回退打灯键，保证编辑窗画面与预警浮窗同源
        if (bsdEditActive) {
            String bsdKey = "LEFT".equals(direction) ? "bsd_left_camera_id" : "bsd_right_camera_id";
            String bsdSaved = prefs.getString(bsdKey, null);
            if (bsdSaved != null) {
                for (String id : ids) {
                    if (id.equals(bsdSaved)) return id;
                }
            }
        }
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
        // v1.52：Camera1 回退实例一并释放（幂等）
        if (camera1Preview != null) {
            camera1Preview.close();
            camera1Preview = null;
        }
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
        // v1.54：BSD 右浮窗 surface 一并释放
        if (bsdRightSurface != null) {
            bsdRightSurface.release();
            bsdRightSurface = null;
        }
        // v1.54 beta4：BSD 编辑窗 surface 一并释放
        if (bsdEditLeft != null) {
            bsdEditLeft.releaseSurface();
        }
        if (bsdEditRight != null) {
            bsdEditRight.releaseSurface();
        }
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice device) {
            cameraDevice = device;
            // v1.54 beta4：无主窗 textureView（BSD 编辑窗独立预览）时也建立会话
            if (hasAnyPreviewTarget()) {
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

    /** v1.54 beta4：是否存在任一可用预览目标（主窗 / BSD 右窗 / BSD 编辑窗）。 */
    private boolean hasAnyPreviewTarget() {
        if (textureView != null && textureView.isAvailable()) return true;
        if (bsdRightTextureView != null && bsdRightTextureView.isAvailable()) return true;
        if (bsdEditLeft != null && bsdEditLeft.isTargetReady()) return true;
        if (bsdEditRight != null && bsdEditRight.isTargetReady()) return true;
        return false;
    }

    private void createPreviewSession() {
        if (cameraDevice == null) return;
        try {
            java.util.ArrayList<Surface> targets = new java.util.ArrayList<>();
            if (textureView != null && textureView.isAvailable()
                    && textureView.getSurfaceTexture() != null) {
                SurfaceTexture st = textureView.getSurfaceTexture();
                st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
                bufferW = BUFFER_W;
                bufferH = BUFFER_H;
                if (previewSurface != null) {
                    previewSurface.release();
                    previewSurface = null;
                }
                previewSurface = new Surface(st);
                targets.add(previewSurface);
            }
            // v1.54：BSD 双窗——右浮窗纹理就绪时一并加入同一路预览输出
            if (bsdRightTextureView != null && bsdRightTextureView.isAvailable()
                    && bsdRightTextureView.getSurfaceTexture() != null) {
                if (bsdRightSurface != null) {
                    bsdRightSurface.release();
                    bsdRightSurface = null;
                }
                bsdRightTextureView.getSurfaceTexture().setDefaultBufferSize(BUFFER_W, BUFFER_H);
                bsdRightSurface = new Surface(bsdRightTextureView.getSurfaceTexture());
                targets.add(bsdRightSurface);
            }
            // v1.54 beta4：BSD 编辑双窗（左/右）纹理就绪时一并加入预览输出
            if (bsdEditLeft != null) {
                bsdEditLeft.addSurfaceTarget(targets);
            }
            if (bsdEditRight != null) {
                bsdEditRight.addSurfaceTarget(targets);
            }
            // v1.57 beta3：仪表 H.264 投影——启用且编码器已创建时追加编码器 input Surface
            // 到同一路预览会话（MediaCodec Surface 输入直接消费 Camera2 预览帧）。
            // 编码器未就绪则正常建会话，不影响浮窗。
            if (iclProjectionEnabled) {
                Surface iclSurface = icProjector.getInputSurface();
                if (iclSurface != null && iclSurface.isValid()) {
                    targets.add(iclSurface);
                }
            }
            if (targets.isEmpty()) {
                // 无任何可用预览目标（例如编辑窗刚关闭、主窗纹理未就绪），保持摄像头关闭状态
                releaseCamera();
                return;
            }
            final CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            for (Surface target : targets) {
                builder.addTarget(target);
            }
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            cameraDevice.createCaptureSession(targets,
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
        // v1.52：Camera1 回退实例一并释放（幂等）
        if (camera1Preview != null) {
            camera1Preview.close();
            camera1Preview = null;
        }
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
        // v1.54：BSD 右浮窗 surface 一并释放
        if (bsdRightSurface != null) {
            bsdRightSurface.release();
            bsdRightSurface = null;
        }
        // v1.54 beta4：BSD 编辑窗 surface 一并释放
        if (bsdEditLeft != null) {
            bsdEditLeft.releaseSurface();
        }
        if (bsdEditRight != null) {
            bsdEditRight.releaseSurface();
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
        // v1.52：销毁兜底停止 SignalGate 慢变量轮询并摘除 BSD 窄条
        if (signalGate != null) {
            signalGate.stop();
        }
        if (sideWarning != null) {
            sideWarning.detach();
        }
        linkActive.set(false);
        manualVisible.set(false);
        // v1.54 beta4：服务销毁时隐藏 BSD 编辑窗（不保存，与主窗一并移除）
        if (bsdEditLeft != null) {
            bsdEditLeft.removeFromWindow();
            bsdEditLeft = null;
        }
        if (bsdEditRight != null) {
            bsdEditRight.removeFromWindow();
            bsdEditRight = null;
        }
        releaseCamera();
        removeOverlay();
        if (bgThread != null) bgThread.quitSafely();
        serviceLog("前台服务已停止");
        super.onDestroy();
    }

    // ---- v1.54 beta4：BSD 预警浮窗自定义编辑窗 ----

    /**
     * v1.54 beta4：BSD 预警浮窗独立编辑窗（左右各一）。
     * 复用 overlay_window 布局：单指拖动位置（松手保存 bsd_left/right_ 键）、
     * 双击进入编辑（单指平移画面 / 双指缩放 / 拖边改形状，保存 bsd_ 前缀独立键）、
     * 右上角关闭按钮单独关闭该侧并保存对应配置。
     * 画面渲染与位置解耦：默认未编辑时 fitCenter 完整显示，编辑后按 bsd_ 键自由渲染。
     */
    private class BsdEditWindow {
        final String side;          // "LEFT" / "RIGHT"
        final String bsdPrefix;     // "bsd_left_" / "bsd_right_"
        final String turnPrefix;    // "left_" / "right_"（打灯对应键，同步与回退来源）
        final View rootView;
        final TextureView textureView;
        final View closeView;
        final View borderView;
        final android.widget.TextView editHintView;
        final View cropHandleL, cropHandleR, cropHandleT, cropHandleB;
        final View cropHandleTL, cropHandleTR, cropHandleBL, cropHandleBR;
        boolean editMode = false;
        float camScale = CAM_SCALE_DEFAULT;
        float panX = 0f;
        float panY = 0f;
        Surface editSurface = null;
        final WindowManager.LayoutParams lp;

        BsdEditWindow(String side) {
            this.side = side;
            this.bsdPrefix = "LEFT".equals(side) ? "bsd_left_" : "bsd_right_";
            this.turnPrefix = "LEFT".equals(side) ? "left_" : "right_";
            LayoutInflater li = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            rootView = li.inflate(R.layout.overlay_window, null);
            textureView = rootView.findViewById(R.id.overlay_texture);
            if (textureView != null) {
                textureView.setClipToOutline(true);
                textureView.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
                    }
                });
                textureView.setSurfaceTextureListener(bsdEditSurfaceListener);
            }
            TextView label = rootView.findViewById(R.id.overlay_label);
            if (label != null) {
                label.setText("LEFT".equals(side) ? "BSD 左预警编辑" : "BSD 右预警编辑");
            }
            closeView = rootView.findViewById(R.id.overlay_close);
            if (closeView != null) {
                closeView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        closeBsdWindow(CameraOverlayService.this, BsdEditWindow.this.side);
                    }
                });
            }
            borderView = rootView.findViewById(R.id.overlay_border);
            cropHandleL = rootView.findViewById(R.id.handle_left);
            cropHandleR = rootView.findViewById(R.id.handle_right);
            cropHandleT = rootView.findViewById(R.id.handle_top);
            cropHandleB = rootView.findViewById(R.id.handle_bottom);
            cropHandleTL = rootView.findViewById(R.id.handle_tl);
            cropHandleTR = rootView.findViewById(R.id.handle_tr);
            cropHandleBL = rootView.findViewById(R.id.handle_bl);
            cropHandleBR = rootView.findViewById(R.id.handle_br);
            editHintView = rootView.findViewById(R.id.overlay_edit_hint);
            android.graphics.drawable.GradientDrawable borderBg = new android.graphics.drawable.GradientDrawable();
            borderBg.setStroke(dp(2), 0xFFFFD98A);
            borderBg.setColor(0x00000000);
            if (borderView != null) borderView.setBackground(borderBg);
            if (editHintView != null) editHintView.setText("BSD 预警编辑");
            setupWindowEdgeHandle2(cropHandleL, "left");
            setupWindowEdgeHandle2(cropHandleR, "right");
            setupWindowEdgeHandle2(cropHandleT, "top");
            setupWindowEdgeHandle2(cropHandleB, "bottom");
            setupWindowCornerHandle2(cropHandleTL, "tl");
            setupWindowCornerHandle2(cropHandleTR, "tr");
            setupWindowCornerHandle2(cropHandleBL, "bl");
            setupWindowCornerHandle2(cropHandleBR, "br");

            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            // 窗口宽高：bsd_ 键优先，其次打灯对应键，最后默认
            int defW = (int) (dm.widthPixels * OVERLAY_DEF_W_RATIO);
            int defH = (int) (defW * 9f / 16f);
            int w = prefs.getInt(bsdPrefix + "overlay_w",
                    prefs.getInt(turnPrefix + "overlay_w", defW));
            int h = prefs.getInt(bsdPrefix + "overlay_h",
                    prefs.getInt(turnPrefix + "overlay_h", defH));
            int minW = (int) (dm.widthPixels * MIN_SIZE_RATIO);
            int minH = (int) (dm.heightPixels * MIN_SIZE_RATIO);
            if (w < minW) w = minW;
            if (w > dm.widthPixels) w = dm.widthPixels;
            if (h < minH) h = minH;
            if (h > dm.heightPixels) h = dm.heightPixels;
            lp = new WindowManager.LayoutParams(
                    w, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            // 位置：bsd_ recorded 键优先，其次打灯 recorded 键，最后默认贴边
            boolean bsdRec = prefs.getBoolean(bsdPrefix + "overlay_pos_recorded", false);
            boolean turnRec = prefs.getBoolean(turnPrefix + "overlay_pos_recorded", false);
            if (bsdRec) {
                lp.x = prefs.getInt(bsdPrefix + "overlay_pos_x",
                        "LEFT".equals(side) ? 120 : (dm.widthPixels - w - 120));
                lp.y = prefs.getInt(bsdPrefix + "overlay_pos_y", dm.heightPixels - h - 120);
            } else if (turnRec) {
                lp.x = prefs.getInt(turnPrefix + "overlay_pos_x",
                        "LEFT".equals(side) ? 120 : (dm.widthPixels - w - 120));
                lp.y = prefs.getInt(turnPrefix + "overlay_pos_y", dm.heightPixels - h - 120);
            } else {
                lp.x = "LEFT".equals(side) ? 120 : (dm.widthPixels - w - 120);
                lp.y = dm.heightPixels - h - 120;
            }
            // 画面配置：bsd_ 键优先，其次打灯对应键
            camScale = prefs.getFloat(bsdPrefix + "cam_scale",
                    prefs.getFloat(turnPrefix + "cam_scale", CAM_SCALE_DEFAULT));
            panX = prefs.getFloat(bsdPrefix + "pan_x",
                    prefs.getFloat(turnPrefix + "pan_x", 0f));
            panY = prefs.getFloat(bsdPrefix + "pan_y",
                    prefs.getFloat(turnPrefix + "pan_y", 0f));
            clampCamScale();
        }

        void clampCamScale() {
            if (camScale < CAM_SCALE_MIN) camScale = CAM_SCALE_MIN;
            if (camScale > CAM_SCALE_MAX) camScale = CAM_SCALE_MAX;
        }

        void addToWindow() {
            try {
                wm.addView(rootView, lp);
                makeDraggable2(rootView);
                applyRenderTransform();
            } catch (Exception e) {
                Log.e(TAG, "BSD 编辑窗 addView failed side=" + side, e);
            }
        }

        void removeFromWindow() {
            try {
                wm.removeView(rootView);
            } catch (Exception ignored) {
            }
            // v1.54 beta6：移除即释放本窗 Surface（幂等），避免关闭编辑窗后 surface 占用残留
            releaseSurface();
        }

        boolean isTargetReady() {
            return textureView != null && textureView.isAvailable();
        }

        /** 将本窗 TextureView 的 Surface 加入预览输出目标列表（配合 createPreviewSession）。 */
        void addSurfaceTarget(java.util.ArrayList<Surface> targets) {
            if (textureView == null || !textureView.isAvailable()
                    || textureView.getSurfaceTexture() == null) {
                return;
            }
            if (editSurface != null) {
                editSurface.release();
                editSurface = null;
            }
            textureView.getSurfaceTexture().setDefaultBufferSize(BUFFER_W, BUFFER_H);
            editSurface = new Surface(textureView.getSurfaceTexture());
            targets.add(editSurface);
        }

        void releaseSurface() {
            if (editSurface != null) {
                editSurface.release();
                editSurface = null;
            }
        }

        private final TextureView.SurfaceTextureListener bsdEditSurfaceListener =
                new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                        st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
                        applyRenderTransform();
                        // v1.54 beta6：条件放宽为 cameraDevice 就绪即重建会话——createPreviewSession
                        // 内部会聚合本编辑窗 surface 并在无目标时自动释放，避免编辑态独立预览时
                        // 主窗纹理未就绪导致黑屏（surface 初始化时序修复）
                        if (cameraDevice != null) {
                            createPreviewSession();
                        }
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                        st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
                        applyRenderTransform();
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                        return true;
                    }

                    @Override
                    public void onSurfaceTextureUpdated(SurfaceTexture st) {
                    }
                };

        /** 保存本窗编辑配置到 bsd_ 前缀独立键（位置/大小/画面缩放/平移）。 */
        void saveConfigInternal() {
            SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            ed.putInt(bsdPrefix + "overlay_w", lp.width);
            ed.putInt(bsdPrefix + "overlay_h", lp.height);
            ed.putFloat(bsdPrefix + "cam_scale", camScale);
            ed.putFloat(bsdPrefix + "pan_x", panX);
            ed.putFloat(bsdPrefix + "pan_y", panY);
            ed.putInt(bsdPrefix + "overlay_pos_x", lp.x);
            ed.putInt(bsdPrefix + "overlay_pos_y", lp.y);
            ed.putBoolean(bsdPrefix + "overlay_pos_recorded", true);
            ed.apply();
            serviceLog("BSD 预警编辑已保存 side=" + side + " w=" + lp.width + " h=" + lp.height
                    + " camScale=" + camScale + " pan=(" + (int) panX + "," + (int) panY
                    + ") pos=(" + lp.x + "," + lp.y + ")");
        }

        /** 从 prefs 重新加载配置（同步转向灯后立即刷新用）。 */
        void reloadFromPrefs() {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int defW = (int) (dm.widthPixels * OVERLAY_DEF_W_RATIO);
            int defH = (int) (defW * 9f / 16f);
            int w = prefs.getInt(bsdPrefix + "overlay_w",
                    prefs.getInt(turnPrefix + "overlay_w", defW));
            int h = prefs.getInt(bsdPrefix + "overlay_h",
                    prefs.getInt(turnPrefix + "overlay_h", defH));
            int minW = (int) (dm.widthPixels * MIN_SIZE_RATIO);
            int minH = (int) (dm.heightPixels * MIN_SIZE_RATIO);
            if (w < minW) w = minW;
            if (w > dm.widthPixels) w = dm.widthPixels;
            if (h < minH) h = minH;
            if (h > dm.heightPixels) h = dm.heightPixels;
            lp.width = w;
            lp.height = h;
            if (prefs.getBoolean(bsdPrefix + "overlay_pos_recorded", false)) {
                lp.x = prefs.getInt(bsdPrefix + "overlay_pos_x", lp.x);
                lp.y = prefs.getInt(bsdPrefix + "overlay_pos_y", lp.y);
            }
            camScale = prefs.getFloat(bsdPrefix + "cam_scale",
                    prefs.getFloat(turnPrefix + "cam_scale", CAM_SCALE_DEFAULT));
            panX = prefs.getFloat(bsdPrefix + "pan_x",
                    prefs.getFloat(turnPrefix + "pan_x", 0f));
            panY = prefs.getFloat(bsdPrefix + "pan_y",
                    prefs.getFloat(turnPrefix + "pan_y", 0f));
            clampCamScale();
            try {
                wm.updateViewLayout(rootView, lp);
            } catch (Exception ignored) {
            }
            setEditMode2(editMode);
        }

        /** 编辑窗渲染：与主窗同构但使用 bsd_ 前缀独立键（未编辑 fitCenter 完整显示，编辑后自由平移）。 */
        void applyRenderTransform() {
            if (textureView == null) return;
            int vw = lp.width;
            int vh = lp.height;
            if (vw <= 0) vw = 1;
            if (vh <= 0) vh = 1;
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            float defW = dm.widthPixels * OVERLAY_DEF_W_RATIO;
            float defH = defW * 9f / 16f;
            float cs = camScale;
            clampCamScale();
            cs = camScale;
            float Wv = defW * cs;
            float Hv = defH * cs;
            float scale = Wv / BUFFER_W;
            float dx;
            float dy;
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            boolean defaultCrop = !prefs.contains(bsdPrefix + "cam_scale") && !editMode;
            if (defaultCrop) {
                float fit = Math.min(vw / Wv, vh / Hv);
                scale = (Wv / BUFFER_W) * fit;
                dx = (vw - Wv * fit) / 2f;
                dy = (vh - Hv * fit) / 2f;
            } else {
                dx = (vw - Wv) / 2f + panX;
                dy = (vh - Hv) / 2f + panY;
            }
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.reset();
            m.postScale(scale, scale);
            m.postTranslate(dx, dy);
            textureView.setTransform(m);
            serviceLog("BSD编辑窗RT side=" + side + " vw=" + vw + " vh=" + vh
                    + " Wv=" + (int) Wv + " Hv=" + (int) Hv + " pan=(" + (int) panX + "," + (int) panY
                    + ") scale=" + String.format("%.4f", scale));
        }

        void setEditMode2(boolean on) {
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
                editHintView.setText(on ? "BSD编辑 · 单指平移画面，双指缩放，拖边改形状" : "BSD 预警编辑");
            }
            if (on) applyRenderTransform();
        }

        /** 双击切换编辑模式：进入加载 bsd_ 键画面配置（从未编辑时以默认 1/4 裁切为基准），退出保存。 */
        void toggleEditMode2() {
            if (editMode) {
                saveConfigInternal();
                setEditMode2(false);
                serviceLog("BSD 预警编辑模式退出，配置已保存 side=" + side);
            } else {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                camScale = prefs.getFloat(bsdPrefix + "cam_scale",
                        prefs.getFloat(turnPrefix + "cam_scale", CAM_SCALE_DEFAULT));
                panX = prefs.getFloat(bsdPrefix + "pan_x",
                        prefs.getFloat(turnPrefix + "pan_x", 0f));
                panY = prefs.getFloat(bsdPrefix + "pan_y",
                        prefs.getFloat(turnPrefix + "pan_y", 0f));
                // 从未编辑过（无 bsd_cam_scale 键）时，以默认左下/右下 1/4 裁切区域作为编辑初始值
                if (!prefs.contains(bsdPrefix + "cam_scale")
                        && !prefs.contains(turnPrefix + "cam_scale")) {
                    DisplayMetrics dm = new DisplayMetrics();
                    wm.getDefaultDisplay().getRealMetrics(dm);
                    float defW = dm.widthPixels * OVERLAY_DEF_W_RATIO;
                    float defH = defW * 9f / 16f;
                    camScale = 2f;
                    panX = "LEFT".equals(side) ? defW / 2f : -defW / 2f;
                    panY = -defH / 2f;
                }
                clampCamScale();
                setEditMode2(true);
                serviceLog("BSD 预警编辑模式进入（" + side + "），双击保存退出");
            }
        }

        /** BSD 编辑窗触摸：非编辑单指拖动位置（松手保存 bsd_ 键），编辑模式平移画面/双指缩放，双击切换。 */
        void makeDraggable2(final View view) {
            if (view == null) return;
            view.setOnTouchListener(new View.OnTouchListener() {
                private int downX;
                private int downY;
                private int initX;
                private int initY;
                private float initPanX;
                private float initPanY;
                private boolean dragging = false;
                private long lastTapTime = 0L;
                private boolean doubleTapConsumed = false;
                private float startSpan = 0f;
                private float startCamScale = 0f;
                private boolean pinchActive = false;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (rootView == null) return false;
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
                            long now = System.currentTimeMillis();
                            if (now - lastTapTime < 400L) {
                                lastTapTime = 0L;
                                doubleTapConsumed = true;
                                toggleEditMode2();
                            } else {
                                lastTapTime = 0L;
                                doubleTapConsumed = false;
                            }
                            return true;
                        case MotionEvent.ACTION_POINTER_DOWN:
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
                                    camScale = newScale;
                                    clampCamScale();
                                    applyRenderTransform();
                                }
                                return true;
                            }
                            if (editMode) {
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
                                        wm.updateViewLayout(rootView, lp);
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
                            if (wasDragging && !editMode) {
                                // 拖动结束保存 bsd_ 键位置（不吸附，预警窗自由摆放）
                                SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                                ed.putInt(bsdPrefix + "overlay_pos_x", lp.x);
                                ed.putInt(bsdPrefix + "overlay_pos_y", lp.y);
                                ed.putBoolean(bsdPrefix + "overlay_pos_recorded", true);
                                ed.apply();
                                serviceLog("BSD 预警编辑窗位置拖动自动保存 side=" + side + " pos=(" + lp.x + "," + lp.y + ")");
                            }
                            if (!wasDragging && !doubleTapConsumed) {
                                lastTapTime = System.currentTimeMillis();
                            }
                            doubleTapConsumed = false;
                            return wasDragging;
                    }
                    return false;
                }
            });
        }

        /** BSD 编辑窗边缘手柄：改变窗口形状（参照主窗 setupWindowEdgeHandle）。 */
        void setupWindowEdgeHandle2(final View handle, final String sside) {
            if (handle == null) return;
            handle.setOnTouchListener(new View.OnTouchListener() {
                private int startRaw = 0;
                private int startW = 0;
                private int startH = 0;
                private int startX = 0;
                private int startY = 0;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (!editMode) return false;
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startW = lp.width;
                            startH = lp.height;
                            startX = lp.x;
                            startY = lp.y;
                            boolean horizontal = "left".equals(sside) || "right".equals(sside);
                            startRaw = horizontal ? (int) event.getRawX() : (int) event.getRawY();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            boolean h = "left".equals(sside) || "right".equals(sside);
                            int cur = h ? (int) event.getRawX() : (int) event.getRawY();
                            int d = cur - startRaw;
                            DisplayMetrics dm = new DisplayMetrics();
                            wm.getDefaultDisplay().getRealMetrics(dm);
                            int minW = (int) (dm.widthPixels * MIN_SIZE_RATIO);
                            int minH = (int) (dm.heightPixels * MIN_SIZE_RATIO);
                            if ("left".equals(sside)) {
                                int newW = startW - d;
                                if (newW < minW) newW = minW;
                                if (newW > dm.widthPixels) newW = dm.widthPixels;
                                lp.width = newW;
                                lp.x = startX + (startW - newW);
                                if (lp.x < 0) {
                                    lp.x = 0;
                                    lp.width = startX + startW;
                                }
                            } else if ("right".equals(sside)) {
                                int newW = startW + d;
                                if (newW < minW) newW = minW;
                                if (newW > dm.widthPixels) newW = dm.widthPixels;
                                lp.width = newW;
                            } else if ("top".equals(sside)) {
                                int newH = startH - d;
                                if (newH < minH) newH = minH;
                                if (newH > dm.heightPixels) newH = dm.heightPixels;
                                lp.height = newH;
                                lp.y = startY + (startH - newH);
                                if (lp.y < 0) {
                                    lp.y = 0;
                                    lp.height = startY + startH;
                                }
                            } else {
                                int newH = startH + d;
                                if (newH < minH) newH = minH;
                                if (newH > dm.heightPixels) newH = dm.heightPixels;
                                lp.height = newH;
                            }
                            try {
                                wm.updateViewLayout(rootView, lp);
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

        /** BSD 编辑窗四角等比例缩放手柄（参照主窗 setupWindowCornerHandle）。 */
        void setupWindowCornerHandle2(final View handle, final String corner) {
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
                    if (!editMode) return false;
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startRawX = (int) event.getRawX();
                            startRawY = (int) event.getRawY();
                            startW = lp.width;
                            startH = lp.height;
                            startX = lp.x;
                            startY = lp.y;
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
                                if (corner.contains("r")) newW = startW + dx;
                                else newW = startW - dx;
                                newH = (int) (newW / ratio);
                            } else {
                                if (corner.contains("b")) newH = startH + dy;
                                else newH = startH - dy;
                                newW = (int) (newH * ratio);
                            }
                            if (newW < 1) newW = 1;
                            if (newH < 1) newH = 1;
                            int minW = (int) Math.max(dm.widthPixels * MIN_SIZE_RATIO,
                                    dm.heightPixels * MIN_SIZE_RATIO * ratio);
                            int maxW = (int) Math.min(dm.widthPixels, dm.heightPixels * ratio);
                            if (newW < minW) newW = minW;
                            if (newW > maxW) newW = maxW;
                            newH = (int) (newW / ratio);
                            if (newH < 1) newH = 1;
                            lp.width = newW;
                            lp.height = newH;
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
                            if (lp.x < 0) {
                                int rightEdge = startX + startW;
                                lp.x = 0;
                                lp.width = rightEdge;
                                lp.height = (int) (rightEdge / ratio);
                                lp.y = ("tl".equals(corner) || "tr".equals(corner))
                                        ? (startY + startH - lp.height) : startY;
                            }
                            if (lp.y < 0) {
                                int bottomEdge = startY + startH;
                                lp.y = 0;
                                lp.height = bottomEdge;
                                lp.width = (int) (bottomEdge * ratio);
                                lp.x = ("tl".equals(corner) || "bl".equals(corner))
                                        ? (startX + startW - lp.width) : startX;
                            }
                            try {
                                wm.updateViewLayout(rootView, lp);
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
    }

    /** v1.54 beta4：编辑预警浮窗按钮——未召唤时同时召唤左右预警编辑窗；已召唤时同时关闭并保存。 */
    private void toggleBsdEditInternal() {
        if (bsdEditActive) {
            if (bsdEditLeft != null) {
                bsdEditLeft.saveConfigInternal();
                bsdEditLeft.removeFromWindow();
                bsdEditLeft = null;
            }
            if (bsdEditRight != null) {
                bsdEditRight.saveConfigInternal();
                bsdEditRight.removeFromWindow();
                bsdEditRight = null;
            }
            bsdEditActive = false;
            // v1.54 beta6：编辑态独立启动的预览在退出时完整释放（closeCamera/releaseCamera
            // 幂等），避免 camera 占用残留导致后续打不开
            if (bsdEditPreviewActive) {
                bsdEditPreviewActive = false;
                releaseCamera();
                if (savedDirectionBeforeEdit != null) {
                    direction = savedDirectionBeforeEdit;
                    savedDirectionBeforeEdit = null;
                }
            }
            serviceLog("BSD 预警浮窗编辑已退出，两侧配置已保存");
        } else {
            bsdEditActive = true;
            // 编辑期间关闭由 BSD 弹起的自动浮窗，避免与编辑窗重叠
            releaseBsdAutoOverlay();
            if (bsdEditLeft == null) {
                bsdEditLeft = new BsdEditWindow("LEFT");
                bsdEditLeft.addToWindow();
            }
            if (bsdEditRight == null) {
                bsdEditRight = new BsdEditWindow("RIGHT");
                bsdEditRight.addToWindow();
            }
            // v1.54 beta6：编辑窗 addView 后立即按对应侧 cameraId 打开摄像头预览并保持。
            // 已有相机在屏（打灯/BSD/手动浮窗）则复用并重建会话聚合编辑窗画面；
            // 否则以左侧 bsd_left_camera_id（回退 left_camera_id）独立启动一路预览，
            // 左右编辑窗作为同一路输出目标，编辑期间持续保持实时画面。
            if (cameraDevice != null) {
                createPreviewSession();
            } else {
                savedDirectionBeforeEdit = direction;
                direction = "LEFT";
                bsdEditPreviewActive = true;
                openCamera();
                // 编辑窗 surface 若尚未就绪，onOpened -> hasAnyPreviewTarget 暂不建会话，
                // 由 bsdEditSurfaceListener.onSurfaceTextureAvailable 在纹理就绪后补建
            }
            serviceLog("BSD 预警浮窗编辑已进入：左右预警窗可拖动/双击编辑，再点按钮或点✕关闭保存");
        }
    }

    /** v1.54 beta4：预警浮窗同步转向灯浮窗——左对左、右对右复制位置/大小/画面 pan 缩放。
     * v1.54 beta5：同步范围增加 camera_id（left_camera_id -> bsd_left_camera_id、
     * right_camera_id -> bsd_right_camera_id），保证预警浮窗画面与转向灯浮窗使用同一摄像头。 */
    private void syncBsdToTurnInternal() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        String[] from = new String[]{"left_", "right_"};
        String[] to = new String[]{"bsd_left_", "bsd_right_"};
        String[] keys = new String[]{"overlay_w", "overlay_h", "overlay_pos_x", "overlay_pos_y",
                "cam_scale", "pan_x", "pan_y", "camera_id"};
        for (int i = 0; i < from.length; i++) {
            for (String k : keys) {
                if (prefs.contains(from[i] + k)) {
                    if ("overlay_pos_x".equals(k) || "overlay_pos_y".equals(k)) {
                        ed.putInt(to[i] + k, prefs.getInt(from[i] + k, 0));
                    } else if ("overlay_w".equals(k) || "overlay_h".equals(k)) {
                        ed.putInt(to[i] + k, prefs.getInt(from[i] + k, 0));
                    } else if ("camera_id".equals(k)) {
                        ed.putString(to[i] + k, prefs.getString(from[i] + k, "8"));
                    } else {
                        ed.putFloat(to[i] + k, prefs.getFloat(from[i] + k, 0f));
                    }
                }
            }
            ed.putBoolean(to[i] + "overlay_pos_recorded", true);
        }
        ed.apply();
        if (bsdEditLeft != null) bsdEditLeft.reloadFromPrefs();
        if (bsdEditRight != null) bsdEditRight.reloadFromPrefs();
        serviceLog("BSD 预警浮窗已同步转向灯浮窗配置（左对左/右对右：位置/大小/画面/cameraId）");
    }

    /** v1.54 beta4：单独关闭某侧 BSD 预警编辑窗并保存该侧配置（编辑窗右上角 ✕）。 */
    private void closeBsdEditWindowInternal(String side) {
        if ("LEFT".equals(side) && bsdEditLeft != null) {
            bsdEditLeft.saveConfigInternal();
            bsdEditLeft.removeFromWindow();
            bsdEditLeft = null;
            serviceLog("BSD 左预警编辑窗已单独关闭并保存");
        } else if ("RIGHT".equals(side) && bsdEditRight != null) {
            bsdEditRight.saveConfigInternal();
            bsdEditRight.removeFromWindow();
            bsdEditRight = null;
            serviceLog("BSD 右预警编辑窗已单独关闭并保存");
        }
        if (bsdEditLeft == null && bsdEditRight == null) {
            bsdEditActive = false;
            // v1.54 beta6：最后一个编辑窗关闭后，若为编辑态独立启动的预览则完整释放
            if (bsdEditPreviewActive) {
                bsdEditPreviewActive = false;
                releaseCamera();
                if (savedDirectionBeforeEdit != null) {
                    direction = savedDirectionBeforeEdit;
                    savedDirectionBeforeEdit = null;
                }
            }
        }
    }

    /** v1.54 beta4：BSD 左预警弹窗时应用用户编辑的 bsd_left_ 键（位置/大小/画面），未编辑时保持 beta3 现状。 */
    private void applyBsdLeftConfigIfPresent() {
        if (overlayView == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.contains("bsd_left_cam_scale")
                && !prefs.contains("bsd_left_overlay_w")) {
            return;
        }
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int minW = (int) (dm.widthPixels * MIN_SIZE_RATIO);
            int minH = (int) (dm.heightPixels * MIN_SIZE_RATIO);
            int w = prefs.getInt("bsd_left_overlay_w", lp.width);
            int h = prefs.getInt("bsd_left_overlay_h", lp.height);
            if (w < minW) w = minW;
            if (w > dm.widthPixels) w = dm.widthPixels;
            if (h < minH) h = minH;
            if (h > dm.heightPixels) h = dm.heightPixels;
            lp.width = w;
            lp.height = h;
            if (prefs.getBoolean("bsd_left_overlay_pos_recorded", false)) {
                lp.x = prefs.getInt("bsd_left_overlay_pos_x", lp.x);
                lp.y = prefs.getInt("bsd_left_overlay_pos_y", lp.y);
            }
            wm.updateViewLayout(overlayView, lp);
            applyBsdLeftRenderTransform();
            serviceLog("BSD 左预警弹窗应用 bsd_left_ 自定义配置 w=" + w + " h=" + h);
        } catch (Exception e) {
            Log.e(TAG, "applyBsdLeftConfigIfPresent failed", e);
        }
    }

    /** v1.54 beta4：BSD 左预警画面渲染——使用 bsd_left_ 独立键（含默认初始左下 1/4 裁切与编辑后的自由平移）。 */
    private void applyBsdLeftRenderTransform() {
        if (overlayView == null || textureView == null) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
        int vw = lp.width;
        int vh = lp.height;
        if (vw <= 0) vw = 1;
        if (vh <= 0) vh = 1;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        float defW = dm.widthPixels * OVERLAY_DEF_W_RATIO;
        float defH = defW * 9f / 16f;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        float cs = prefs.getFloat("bsd_left_cam_scale",
                prefs.getFloat("left_cam_scale", CAM_SCALE_DEFAULT));
        if (cs < CAM_SCALE_MIN) cs = CAM_SCALE_MIN;
        if (cs > CAM_SCALE_MAX) cs = CAM_SCALE_MAX;
        float Wv = defW * cs;
        float Hv = defH * cs;
        float scale = Wv / BUFFER_W;
        float dx;
        float dy;
        boolean defaultCrop = !prefs.contains("bsd_left_cam_scale");
        if (defaultCrop) {
            float fit = Math.min(vw / Wv, vh / Hv);
            scale = (Wv / BUFFER_W) * fit;
            dx = (vw - Wv * fit) / 2f;
            dy = (vh - Hv * fit) / 2f;
        } else {
            float panX = prefs.getFloat("bsd_left_pan_x",
                    prefs.getFloat("left_pan_x", 0f));
            float panY = prefs.getFloat("bsd_left_pan_y",
                    prefs.getFloat("left_pan_y", 0f));
            dx = (vw - Wv) / 2f + panX;
            dy = (vh - Hv) / 2f + panY;
        }
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.reset();
        m.postScale(scale, scale);
        m.postTranslate(dx, dy);
        textureView.setTransform(m);
        serviceLog("BSD左窗RT vw=" + vw + " vh=" + vh + " Wv=" + (int) Wv + " Hv=" + (int) Hv
                + " scale=" + String.format("%.4f", scale) + " dx=" + (int) dx + " dy=" + (int) dy);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
