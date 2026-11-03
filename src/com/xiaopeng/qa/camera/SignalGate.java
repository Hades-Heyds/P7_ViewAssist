package com.xiaopeng.qa.camera;

import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.53 beta2：SignalGate 统一门控（8295 轻量版）。
 * 承载慢变量采集：BSD 盲区（SCU_BSD_LEFT/RIGHT_WARNING）、原厂相机前台焦点
 * （dumpsys window mCurrentFocus）、AVM_DISPLAY_MODE、VCU_CURRENT_GEARLEV 冗余采集、
 * 窄路辅助动态窗口（dumpsys window windows 裸包名窗口检测，1s 降频错峰）。
 * 500ms 低频轮询，与 SignalMonitor 300ms 快轮询（转向灯/挡位抑制）解耦并存。
 * 解析采用宽松正则 + 容错态：BSD 属性未解析到一律 bsdUnknown=true 防误报；
 * AVM/挡位未解析保持 -1；焦点包名命中可配置列表才置 factoryCameraForeground；
 * 窄路辅助窗口未解析到保持上一轮。
 * 线程安全：start/stop 原子，snapshot 返回最新不可变快照。
 */
public final class SignalGate {

    private static final String TAG = "SignalGate";

    /** 慢变量轮询周期（ms）。820A 为 250ms 高频，我方改 500ms 低频承载慢变量。 */
    public static final long POLL_PERIOD_MS = 500L;
    /** 轮询命令最小间隔兜底（ms），防止周期被意外压到极低。 */
    private static final long MIN_POLL_INTERVAL_MS = 400L;

    /** 复合命令：一次往返采集 car_service 慢变量 + window 焦点。
     * 820A 逆向确认 lastEvent 行含 Property= 与 value= 段；8295 格式差异走容错态。 */
    private static final String CMD =
            "dumpsys car_service | grep -E 'lastEvent: ?Property=(SCU_BSD_LEFT_WARNING|SCU_BSD_RIGHT_WARNING|AVM_DISPLAY_MODE|VCU_CURRENT_GEARLEV)'; "
                    + "dumpsys window | grep -E 'mCurrentFocus|mResumedActivity'";

    /** 窄路辅助动态窗口检测命令（v1.53 beta2）：
     * 降频 1s 错峰轮询，shell 端 grep 只留含包名的 Window 行，减少大段输出回传；
     * 匹配"裸包名窗口"（Window{... u0 com.xiaopeng.xmart.camera}:，无 /Activity 类名），
     * 存在即窄路辅助浮窗在屏，打灯弹窗需抑制。 */
    private static final String NARROW_CMD =
            "dumpsys window windows | grep -E 'Window #[0-9]+ Window\\{.*com\\.xiaopeng\\.xmart\\.camera'";
    /** 窄路辅助窗口检测降频：主轮询每 N 轮执行一次（500ms * 2 = 1s）。 */
    private static final int NARROW_POLL_EVERY = 2;

    // ---- 宽松解析正则（v1.53 双格式兼容）----
    // 820A：lastEvent 行含 value=N 段；8295：值在 int32Values: [N] 段，
    // 且 floatValues 段位于 int32Values 之前，须用非贪婪 .*? 跨段直达 int32Values。
    // 二分支优先匹配 value=（820A），无 value= 则匹配 int32Values 首个 token（8295）。
    private static final Pattern PATTERN_BSD_LEFT = Pattern.compile(
            "lastEvent:\\s*Property=SCU_BSD_LEFT_WARNING.*?(?:value=(\\S+)|int32Values:\\s*\\[\\s*([^\\],\\s]+))");
    private static final Pattern PATTERN_BSD_RIGHT = Pattern.compile(
            "lastEvent:\\s*Property=SCU_BSD_RIGHT_WARNING.*?(?:value=(\\S+)|int32Values:\\s*\\[\\s*([^\\],\\s]+))");
    private static final Pattern PATTERN_AVM = Pattern.compile(
            "lastEvent:\\s*Property=AVM_DISPLAY_MODE.*?(?:value=(\\S+)|int32Values:\\s*\\[\\s*([^\\],\\s]+))");
    private static final Pattern PATTERN_GEAR = Pattern.compile(
            "lastEvent:\\s*Property=VCU_CURRENT_GEARLEV.*?(?:value=(\\S+)|int32Values:\\s*\\[\\s*([^\\],\\s]+))");
    private static final Pattern PATTERN_FOCUS = Pattern.compile(
            "mCurrentFocus=.*?(\\S+?)/(\\S+)");

    /** 窄路辅助"裸包名窗口"行（v1.53 beta2）：
     * Window #N Window{hash u0 com.xiaopeng.xmart.camera}: —— 包名后直接是 }（无 /Activity 类名），
     * 与 com.xiaopeng.xmart.camera/xxx 的 Activity 窗口严格区分；
     * userId 不限定（u0/u10 等均可），核心判据是包名后紧跟 }。 */
    private static final Pattern PATTERN_NARROW_WINDOW = Pattern.compile(
            "Window #\\d+ Window\\{[^}]*? com\\.xiaopeng\\.xmart\\.camera\\}");

    /** 不可变快照：一次轮询的全部慢变量状态。 */
    public static final class Snapshot {
        public final boolean bsdLeft;
        public final boolean bsdRight;
        /** BSD 属性未解析到（8295 未验证/格式差异）时置 true，防误报红色告警。 */
        public final boolean bsdUnknown;
        public final boolean factoryCameraForeground;
        /** 冗余采集，默认 -1（未解析）。 */
        public final int gearLevel;
        /** 冗余采集，默认 -1（未解析）。 */
        public final int avmDisplayMode;
        /** v1.53：本轮解析到的前台焦点包名（未解析到为 null），供上层上屏日志。 */
        public final String focusPkg;
        /** v1.53：本轮焦点包名是否命中原厂相机列表（仅 focusPkg != null 时有意义）。 */
        public final boolean focusHit;
        /** v1.53 beta2：窄路辅助动态窗口在屏（裸包名窗口检测）。 */
        public final boolean narrowAssistActive;

        public Snapshot(boolean bsdLeft, boolean bsdRight, boolean bsdUnknown,
                        boolean factoryCameraForeground, int gearLevel, int avmDisplayMode,
                        String focusPkg, boolean focusHit, boolean narrowAssistActive) {
            this.bsdLeft = bsdLeft;
            this.bsdRight = bsdRight;
            this.bsdUnknown = bsdUnknown;
            this.factoryCameraForeground = factoryCameraForeground;
            this.gearLevel = gearLevel;
            this.avmDisplayMode = avmDisplayMode;
            this.focusPkg = focusPkg;
            this.focusHit = focusHit;
            this.narrowAssistActive = narrowAssistActive;
        }

        @Override
        public String toString() {
            return "Snapshot{bsdL=" + bsdLeft + ", bsdR=" + bsdRight + ", bsdUnknown=" + bsdUnknown
                    + ", factoryFocus=" + factoryCameraForeground + ", gear=" + gearLevel
                    + ", avm=" + avmDisplayMode + ", focusPkg=" + focusPkg
                    + ", focusHit=" + focusHit + ", narrow=" + narrowAssistActive + "}";
        }
    }

    public interface Listener {
        void onSignalTick(Snapshot s);
    }

    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    private final Object lock = new Object();

    // 最新快照状态（在轮询线程内更新，snapshot() 同步读取）
    private volatile boolean bsdLeft;
    private volatile boolean bsdRight;
    private volatile boolean bsdUnknown = true; // 初始未确认
    private volatile boolean factoryCameraForeground;
    private volatile int gearLevel = -1;
    private volatile int avmDisplayMode = -1;
    /** v1.53 beta2：窄路辅助动态窗口在屏（volatile，snapshot 同步读）。 */
    private volatile boolean narrowAssistActive = false;

    // 状态保持：仅当本轮明确解析到对应属性才更新，缺失保持上一轮（防格式抖动误判）
    private boolean lastBsdLeft;
    private boolean lastBsdRight;
    private boolean lastBsdUnknown = true;
    private int lastGear = -1;
    private int lastAvm = -1;
    /** v1.53：最近一次成功解析到的焦点包名与命中结果（供 snapshot 携带上屏日志）。 */
    private volatile String lastFocusPkg = null;
    private volatile boolean lastFocusHit = false;
    /** v1.53 beta2：窄路辅助窗口状态保持（解析成功才更新，缺失保持上一轮）。 */
    private boolean lastNarrowAssistActive = false;

    /** 可配置原厂相机包名列表，运行时经 setFactoryCameraPackages 更新。 */
    private volatile String[] factoryCameraPackages = new String[0];

    public SignalGate(Listener listener) {
        this.listener = listener;
    }

    /** 启动轮询线程；已运行则直接返回。adb 需已建立已认证连接。 */
    public synchronized boolean start(final AdbClient adb) {
        synchronized (lock) {
            if (running.get()) return true;
            if (thread != null && thread.isAlive()) {
                Log.e(TAG, "旧轮询线程未退出，暂不启动新线程");
                return false;
            }
            thread = null;
            running.set(true);
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop(adb);
                }
            }, "signal-gate");
            thread.setDaemon(true);
            thread.start();
            return true;
        }
    }

    /** 停止轮询线程（join 等待退出，阻塞于 Socket read 时保留引用由 loop finally 复位）。 */
    public synchronized void stop() {
        Thread t;
        synchronized (lock) {
            running.set(false);
            t = thread;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException ignored) {
            }
            synchronized (lock) {
                if (thread == t && !t.isAlive()) thread = null;
                lock.notifyAll();
            }
        }
    }

    /** 取最新快照（volatile 读，线程安全）。 */
    public Snapshot snapshot() {
        return new Snapshot(bsdLeft, bsdRight, bsdUnknown,
                factoryCameraForeground, gearLevel, avmDisplayMode,
                lastFocusPkg, lastFocusHit, narrowAssistActive);
    }

    /** 运行时更新原厂相机包名列表。 */
    public void setFactoryCameraPackages(String[] pkgs) {
        factoryCameraPackages = pkgs == null ? new String[0] : pkgs;
    }

    private void loop(AdbClient adb) {
        int failCount = 0;
        // v1.53 beta2：窄路辅助检测降频错峰计数（每 NARROW_POLL_EVERY 轮=1s 检测一次，
        // 与 500ms 主轮询错峰，避免 dumpsys window windows 大输出拖慢慢变量采集）。
        int narrowTick = 0;
        try {
            while (running.get()) {
                try {
                    String out = adb.exec(CMD);
                    if (out != null) {
                        parse(out);
                    } else {
                        failCount++;
                        if (failCount == 1 || failCount % 20 == 0) {
                            Log.w(TAG, "SignalGate 轮询无输出(第" + failCount + "次)");
                        }
                    }
                    // 窄路辅助窗口检测：1s 一次，grep 过滤后输出很小；失败保持上一轮
                    if (narrowTick % NARROW_POLL_EVERY == 0) {
                        String narrowOut = adb.exec(NARROW_CMD);
                        parseNarrow(narrowOut);
                    }
                    narrowTick++;
                } catch (Exception e) {
                    failCount++;
                    Log.e(TAG, "SignalGate 轮询失败: " + e.getMessage());
                    if (failCount == 1 || failCount % 20 == 0) {
                        Log.e(TAG, "SignalGate 轮询失败(第" + failCount + "次): " + e.getMessage());
                    }
                }
                // 通知监听方（即使解析失败也发容错态快照，保证上层状态不被旧值卡死）
                try {
                    listener.onSignalTick(snapshot());
                } catch (Exception e) {
                    Log.e(TAG, "onSignalTick 回调异常: " + e.getMessage());
                }
                try {
                    Thread.sleep(Math.max(POLL_PERIOD_MS, MIN_POLL_INTERVAL_MS));
                } catch (InterruptedException e) {
                    break;
                }
            }
        } finally {
            running.set(false);
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    /** 解析复合命令输出：每类信号仅当行内明确匹配才更新，缺失保持上一轮。 */
    private void parse(String out) {
        boolean bsdL = lastBsdLeft;
        boolean bsdR = lastBsdRight;
        boolean bsdU = lastBsdUnknown;
        int gear = lastGear;
        int avm = lastAvm;
        boolean bsdLParsed = false;
        boolean bsdRParsed = false;
        boolean bsdAnyParsed = false;
        boolean gearParsed = false;
        boolean avmParsed = false;
        boolean focusParsed = false;
        boolean focusHit = false;
        String focusPkg = null;

        for (String line : out.split("\\r?\\n")) {
            if (line.isEmpty()) continue;
            // 焦点（dumpsys window 段）
            if (!focusParsed) {
                Matcher fm = PATTERN_FOCUS.matcher(line);
                if (fm.find()) {
                    String pkg = fm.group(1);
                    focusPkg = pkg;
                    focusHit = isFactoryCameraPkg(pkg);
                    focusParsed = true;
                    continue;
                }
            }
            // 属性段（dumpsys car_service 段）
            Matcher bl = PATTERN_BSD_LEFT.matcher(line);
            if (bl.find()) {
                Boolean v = parseOnOff(matchedValue(bl));
                if (v != null) {
                    bsdL = v;
                    bsdLParsed = true;
                    bsdAnyParsed = true;
                }
                continue;
            }
            Matcher br = PATTERN_BSD_RIGHT.matcher(line);
            if (br.find()) {
                Boolean v = parseOnOff(matchedValue(br));
                if (v != null) {
                    bsdR = v;
                    bsdRParsed = true;
                    bsdAnyParsed = true;
                }
                continue;
            }
            Matcher gm = PATTERN_GEAR.matcher(line);
            if (gm.find()) {
                Integer v = parseIntSafe(matchedValue(gm));
                if (v != null) {
                    gear = v;
                    gearParsed = true;
                }
                continue;
            }
            Matcher am = PATTERN_AVM.matcher(line);
            if (am.find()) {
                Integer v = parseIntSafe(matchedValue(am));
                if (v != null) {
                    avm = v;
                    avmParsed = true;
                }
            }
        }

        // 容错态：BSD 任一侧均未解析到 -> bsdUnknown=true（防误报）；解析到任一属性则退出未知态
        if (bsdAnyParsed) {
            bsdU = false;
        } else {
            bsdU = true;
        }
        // 即使单侧缺失也保持另一侧解析值，未知态仅代表"未确认到有效属性"
        if (bsdLParsed) bsdU = false;
        if (bsdRParsed) bsdU = false;

        lastBsdLeft = bsdLeft = bsdL;
        lastBsdRight = bsdRight = bsdR;
        lastBsdUnknown = bsdUnknown = bsdU;
        if (gearParsed) {
            lastGear = gearLevel = gear;
        }
        if (avmParsed) {
            lastAvm = avmDisplayMode = avm;
        }
        // v1.53：焦点解析失败强制复位 factoryCameraForeground=false，杜绝 focusParsed 失败卡死旧值；
        // 解析成功则按本次命中结果更新，并记录包名/命中供上层上屏日志（仅在解析到焦点时刷新）。
        factoryCameraForeground = focusParsed ? focusHit : false;
        if (focusParsed) {
            lastFocusPkg = focusPkg;
            lastFocusHit = focusHit;
        }
    }

    /** v1.53 beta2：解析窄路辅助窗口检测输出（grep 过滤后）。
     * out==null（命令失败）保持上一轮；grep 无匹配（空串）明确置 false；
     * 有输出则任一裸包名窗口行命中即置 true（与 Activity 窗口严格区分）。 */
    private void parseNarrow(String out) {
        if (out == null) return;
        boolean active = false;
        if (!out.trim().isEmpty()) {
            for (String line : out.split("\\r?\\n")) {
                if (PATTERN_NARROW_WINDOW.matcher(line).find()) {
                    active = true;
                    break;
                }
            }
        }
        lastNarrowAssistActive = narrowAssistActive = active;
    }

    /** 取兼容正则捕获值：优先 820A value= 分组（group1），其次 8295 int32Values 分组（group2）。 */
    private String matchedValue(Matcher m) {
        String v = m.group(1);
        if (v == null || v.isEmpty()) v = m.group(2);
        return v;
    }

    /** 宽松布尔解析：0/1、true/false、on/off、空值统一收敛；解析失败返回 null（保持原值）。 */
    private Boolean parseOnOff(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) return null;
        if ("1".equals(s) || "true".equals(s) || "on".equals(s)) return Boolean.TRUE;
        if ("0".equals(s) || "false".equals(s) || "off".equals(s)) return Boolean.FALSE;
        try {
            int v = Integer.parseInt(s);
            return v != 0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseIntSafe(String raw) {
        if (raw == null) return null;
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isFactoryCameraPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        for (String p : factoryCameraPackages) {
            if (p != null && !p.isEmpty() && pkg.equals(p)) {
                return true;
            }
        }
        return false;
    }
}
