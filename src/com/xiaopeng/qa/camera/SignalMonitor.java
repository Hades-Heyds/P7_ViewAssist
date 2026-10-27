package com.xiaopeng.qa.camera;

import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 转向灯轮询 + 边沿触发状态机。
 * 300ms 周期执行 dumpsys car_service | grep 过滤，解析左右转向灯状态与当前档位；
 * L/R 独立变量：任一方向 0->1 边沿立即回调对应方向（direction=LEFT/RIGHT），
 * 不等另一方向（旧方向）灯去抖熄灭；仅两灯均为 0 时经 OFF_DEBOUNCE_MS 去抖后
 * 回调 direction=null/anyOn=false 关闭浮窗。
 * v1.48：双闪（左右同时亮）不触发打灯联动；R 档（倒挡）打灯不召唤浮窗、不介入视角，
 * 均直接抑制且若浮窗正在显示立即收起，避免影响倒车影像。
 */
public final class SignalMonitor {

    private static final String TAG = "SignalMonitor";

    public interface Listener {
        void onLog(String line);

        /**
         * 边沿事件回调：
         * anyOn=true 时 direction 为刚 0->1 翻转的方向（"LEFT"/"RIGHT"），立即切换对应方向浮窗；
         * anyOn=false 时 direction 为 null（两灯均 0 且持续 OFF_DEBOUNCE_MS，关闭浮窗）。
         */
        void onSignalChanged(String direction, boolean anyOn);

        /**
         * v1.45：ADB 连接确认回调。首次 exec 成功即视为已建立已认证连接，
         * 由监听方在此刻检测/补授悬浮窗等权限（新车机安装后浮窗授权缺失场景）。
         */
        default void onAdbConnected() {
        }
    }

    private static final String CMD =
            "dumpsys car_service | grep -E 'lastEvent: Property=BCM_(L|R)TURNLAMP_ST|VCU_CURRENT_GEARLEV'";
    // 车机 dumpsys 输出含 floatValues:[...] 再紧跟 int32Values:[...]，
    // 旧正则 [^\[]* 会停在 floatValues 的 '[' 前导致永远匹配不到 int32Values（浮窗不弹），
    // 改用非贪婪 .*? 跨越 floatValues 段。
    private static final Pattern PATTERN =
            Pattern.compile("Property=(\\w+).*?int32Values:\\s*\\[([^\\]]*)\\]");

    private final AdbClient adb;
    private final Listener listener;
    private final long periodMs;
    /** 关灯去抖：两灯均 0 且持续该时长才关闭浮窗（原 3000ms 缩短为 1000ms）。
     * 单方向熄灭（另一方向仍亮）不触发关闭，方向切换由 0->1 边沿即时完成。 */
    private static final long OFF_DEBOUNCE_MS = 1000L;
    /** v1.48：倒挡（R 档）档位值：VCU_CURRENT_GEARLEV int32Values[0]。
     * P7 车机 P 档=4（前端已确认 gear===4），按 P/R/N/D 递减推断 R=3。
     * 若实车轮询日志显示 R 档为其它值（R 档时日志会输出 gear=实际值），仅需改此常量。 */
    private static final int GEAR_REVERSE = 3;
    /** 最小轮询间隔：两次 exec 之间至少间隔 200ms（低于调用方 periodMs 300ms，
     * 防止下限覆盖周期；亮起检测最坏延迟约 0.3s）。 */
    private static final long MIN_POLL_INTERVAL_MS = 200L;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private long offSinceMs = -1L;
    private Thread thread;
    private final Object lock = new Object();
    /** v1.45：首次 exec 成功即触发一次 onAdbConnected，供监听方补授权限。 */
    private boolean connectedNotified = false;

    public SignalMonitor(AdbClient adb, Listener listener, long periodMs) {
        this.adb = adb;
        this.listener = listener;
        this.periodMs = periodMs;
    }

    public boolean start() {
        // 单例保护：无论并发还是重复调用，轮询线程只允许启动一次。
        // 旧线程若尚未退出（stop join 超时、阻塞于 Socket read），不创建新线程，
        // 返回 false 交由调用方提示，避免"显示运行中但线程哑火"。
        synchronized (lock) {
            if (running.get()) return true; // 已在运行
            if (thread != null && thread.isAlive()) {
                Log.e(TAG, "旧轮询线程未退出（可能阻塞于 Socket read 15s），暂不启动新线程");
                return false;
            }
            thread = null;
            running.set(true);
            thread = new Thread(this::loop, "signal-monitor");
            thread.setDaemon(true);
            thread.start();
            return true;
        }
    }

    public void stop() {
        // 原子置位停止标志，确保旧线程在下一轮循环检查时真正退出；
        // interrupt 打断 sleep，随后 join 等待线程终结。
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
                // join 超时（旧线程阻塞于 Socket read，最长 15s）时保留 thread 引用不置 null；
                // 旧线程退出时由 loop 的 finally 复位 running 并唤醒，
                // 下一次 start() 会检查 isAlive，确保旧线程退出后才允许新线程启动。
                if (thread == t && !t.isAlive()) thread = null;
                lock.notifyAll();
            }
        }
    }

    private void loop() {
        boolean prevLeft = false, prevRight = false;
        int prevGear = 0;
        boolean prevReverse = false;
        int failCount = 0;
        try {
        while (running.get()) {
            try {
                String out = adb.exec(CMD);
                // v1.39：默认保持上一轮解析值，仅当本轮明确解析到对应转向灯属性行才更新。
                // 挂倒挡/切换显示模式时 dumpsys 输出可能瞬时缺失转向灯行，若直接置 false
                // 会伪造 0->1 假边沿，导致打灯期间重复回调（等效周期重发，覆盖倒挡影像并卡顿）。
                // v1.48：档位同策略——仅解析到 GEARLEV 行才更新，缺失保持上一轮，防止误抑制。
                boolean left = prevLeft, right = prevRight;
                boolean reverse = prevReverse;
                int gear = prevGear;
                boolean gearParsed = false;
                if (out != null) {
                    for (String line : out.split("\\r?\\n")) {
                        Matcher m = PATTERN.matcher(line);
                        if (m.find()) {
                            int v = 0;
                            try {
                                v = Integer.parseInt(m.group(2).trim());
                            } catch (Exception ignored) {
                            }
                            boolean on = v != 0;
                            if (m.group(1).contains("LTURNLAMP")) left = on;
                            else if (m.group(1).contains("RTURNLAMP")) right = on;
                            else if (m.group(1).contains("GEARLEV")) {
                                gear = v;
                                gearParsed = true;
                            }
                        }
                    }
                }
                if (gearParsed) {
                    reverse = gear == GEAR_REVERSE;
                    if (reverse != prevReverse) {
                        Log.i(TAG, "档位变化 gear=" + gear + " reverse=" + reverse
                                + "（R档实车应输出 gear=" + GEAR_REVERSE + "，若不符请改 GEAR_REVERSE 常量）");
                        listener.onLog("档位变化 gear=" + gear + (reverse ? "，R 档：抑制打灯联动" : ""));
                    }
                }
                failCount = 0;
                // v1.45：连接确认（首次 exec 成功）回调一次，监听方在此补授悬浮窗等权限
                if (!connectedNotified) {
                    connectedNotified = true;
                    try {
                        listener.onAdbConnected();
                    } catch (Exception e) {
                        Log.e(TAG, "onAdbConnected 回调异常: " + e.getMessage());
                    }
                }
                // v1.48：抑制优先级 —— R 档 > 双闪 > 正常单边亮起。
                // R 档（倒车）绝不召唤打灯浮窗、不介入 AVM 视角，避免覆盖倒车影像；
                // 双闪（左右同时亮）是危险警告灯，同样不触发打灯变道浮窗。
                // 若进入 R 档/双闪前浮窗正显示（行车打灯途中挂倒挡、单边亮起转双闪），立即收起。
                boolean leftEdgeOn = left && !prevLeft;
                boolean rightEdgeOn = right && !prevRight;
                boolean any = left || right;
                boolean prevAny = prevLeft || prevRight;
                boolean hazard = left && right;
                boolean prevHazard = prevLeft && prevRight;
                if (reverse) {
                    if (!prevReverse && prevAny) {
                        Log.i(TAG, "R 档介入：收起打灯浮窗（不覆盖倒车影像） L=" + left + " R=" + right);
                        listener.onSignalChanged(null, false);
                    }
                    offSinceMs = -1;
                } else if (hazard) {
                    if (!prevHazard && prevAny) {
                        Log.i(TAG, "双闪检测：不触发打灯浮窗并收起 L=" + left + " R=" + right);
                        listener.onSignalChanged(null, false);
                    }
                    offSinceMs = -1;
                } else if (leftEdgeOn || rightEdgeOn) {
                    // 方向即时切换：亮起方向即回调方向，MainActivity 据此切对应方向浮窗
                    String dir = leftEdgeOn ? "LEFT" : "RIGHT";
                    Log.i(TAG, "转向信号 亮起边沿 dir=" + dir + " L=" + left + " R=" + right + " gear=" + gear);
                    listener.onSignalChanged(dir, true);
                    offSinceMs = -1;
                } else if (!any) {
                    // 双灯均 0：从亮->灭边沿开始去抖计时，持续 OFF_DEBOUNCE_MS 后关浮窗
                    if (prevAny) {
                        offSinceMs = System.currentTimeMillis();
                    } else if (offSinceMs != -1) {
                        long elapsed = System.currentTimeMillis() - offSinceMs;
                        if (elapsed >= OFF_DEBOUNCE_MS) {
                            Log.i(TAG, "转向信号 熄灭 L=" + left + " R=" + right);
                            listener.onSignalChanged(null, false);
                            offSinceMs = -1;
                        }
                    }
                } else {
                    // 仍有灯亮（持续亮起或单方向 1->0 另一方向仍亮）：重置熄灭计时，不回调
                    offSinceMs = -1;
                }
                prevLeft = left;
                prevRight = right;
                prevGear = gear;
                prevReverse = reverse;
            } catch (Exception e) {
                failCount++;
                Log.e("AdbClient", "轮询失败: " + e);
                if (failCount == 1 || failCount % 10 == 0) {
                    listener.onLog("轮询失败(第" + failCount + "次): " + e.getMessage());
                }
            }
            try {
                // 轮询间隔取调用方周期与下限较大者：periodMs=300ms、下限 200ms，实际周期 300ms
                Thread.sleep(Math.max(periodMs, MIN_POLL_INTERVAL_MS));
            } catch (InterruptedException e) {
                break;
            }
        }
        } finally {
            // 线程退出时复位 running，避免旧线程残留导致下一次 start() 哑火；
            // 同时唤醒可能等待旧线程退出的调用方。
            running.set(false);
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }
}
