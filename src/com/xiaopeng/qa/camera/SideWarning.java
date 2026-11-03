package com.xiaopeng.qa.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * v1.52：BSD 盲区提醒旁路窄条（独立于主浮窗的 TYPE_APPLICATION_OVERLAY 窗口）。
 * 四态更新：bsdLeft -> 左侧红条"盲区有车"；bsdRight -> 右侧红条；双侧同时 -> 左右各一条；
 * 无告警 -> 全部隐藏。Phase 1 调试态：bsdUnknown（8295 属性未确认）时显示灰色
 * "BSD 信号未确认"条，提示数据源尚未验证，不误报红色告警。
 * v1.53：属性解析成功后按值显示红条/隐藏，仅解析失败保留灰条；BSD 条整条可拖动，
 * 拖动结束将位置 x/y 持久化到 SharedPreferences，下次 attach 按上次位置显示。
 * v1.53 beta2：新增 bsdShowAlways 无车常显调试开关：开启时无车（值0）也保持显示
 * （有车红条/无车灰条），方便拖动调位置与测试；关闭恢复正常逻辑。
 * 由 CameraOverlayService 在联动开启时 attach、联动关闭/服务销毁时 detach。
 */
public class SideWarning {

    /** 窄条高度（dp）。 */
    public static final int WARN_BAR_HEIGHT_DP = 24;
    /** 窄条宽度（dp）。 */
    public static final int WARN_BAR_WIDTH_DP = 220;
    /** 拖动位置持久化偏好文件名（与 CameraOverlayService 共用同一偏好库）。 */
    private static final String PREFS_NAME = "cameraassist";
    private static final String KEY_LEFT_X = "bsd_left_x";
    private static final String KEY_LEFT_Y = "bsd_left_y";
    private static final String KEY_RIGHT_X = "bsd_right_x";
    private static final String KEY_RIGHT_Y = "bsd_right_y";

    private final Context ctx;
    private final WindowManager wm;
    private View leftBar;
    private View rightBar;
    private boolean attached = false;

    public SideWarning(Context ctx, WindowManager wm) {
        this.ctx = ctx;
        this.wm = wm;
    }

    /** 挂载两个窄条窗口（幂等）。 */
    public void attach() {
        if (attached || wm == null) return;
        try {
            LayoutInflater li = (LayoutInflater) ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            leftBar = li.inflate(R.layout.overlay_warning, null);
            rightBar = li.inflate(R.layout.overlay_warning, null);
            wm.addView(leftBar, buildLp(true));
            wm.addView(rightBar, buildLp(false));
            // v1.53：BSD 条整条可拖动（参考主浮窗 makeDraggable 拖动实现），
            // 拖动结束位置持久化，下次 attach 按上次位置恢复。
            makeDraggable(leftBar, true);
            makeDraggable(rightBar, false);
            attached = true;
        } catch (Exception e) {
            // 悬浮窗权限缺失等场景：静默失败，下次 update 不再显示
            leftBar = null;
            rightBar = null;
            attached = false;
        }
    }

    /** 移除窄条窗口（幂等）。 */
    public void detach() {
        if (!attached) return;
        try {
            if (leftBar != null) wm.removeView(leftBar);
            if (rightBar != null) wm.removeView(rightBar);
        } catch (Exception ignored) {
        }
        leftBar = null;
        rightBar = null;
        attached = false;
    }

    /** v1.52/v1.53 beta2：按快照更新两侧盲区条。
     * 正常态：bsdUnknown=true 灰条；值1红条；值0隐藏。
     * v1.53 beta2：bsdShowAlways=true 时无车（值0）也保持显示调试样式
     * （有车红条/无车灰条），便于拖动调位置与测试；解析失败灰条逻辑不受影响。 */
    public void update(SignalGate.Snapshot s, boolean bsdShowAlways) {
        if (s == null) return;
        if (!attached) return;
        if (s.bsdUnknown) {
            setBar(leftBar, false, false);
            setBar(rightBar, false, false);
            // Phase 1 调试态：属性未确认 -> 灰色提示条（左侧）
            setBar(leftBar, true, false);
            return;
        }
        boolean left = s.bsdLeft;
        boolean right = s.bsdRight;
        if (bsdShowAlways) {
            // 无车常显：两侧均保持可见（有车红条/无车灰条）
            setBar(leftBar, true, left);
            setBar(rightBar, true, right);
        } else {
            setBar(leftBar, left, true);
            setBar(rightBar, right, true);
        }
    }

    /** 设置单个窄条显示状态；red=true 红条告警，false 灰条调试态。 */
    private void setBar(View bar, boolean show, boolean red) {
        if (bar == null) return;
        bar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        TextView tv = (TextView) bar.findViewById(R.id.warning_text);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(red ? 0xCCE53935 : 0xCCB0BEC5);
        bg.setCornerRadius(6f);
        bar.setBackground(bg);
        tv.setText(red ? "盲区有车" : "BSD 信号未确认");
    }

    private WindowManager.LayoutParams buildLp(boolean left) {
        int h = dp(WARN_BAR_HEIGHT_DP);
        int w = dp(WARN_BAR_WIDTH_DP);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // 靠左/靠右的中部横条
        lp.x = left ? dp(8) : 0;
        lp.y = dp(90);
        if (!left) {
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = dp(8);
        }
        // v1.53：拖动位置持久化恢复（未配置过则用上方默认位置）
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String kx = left ? KEY_LEFT_X : KEY_RIGHT_X;
        String ky = left ? KEY_LEFT_Y : KEY_RIGHT_Y;
        if (prefs.contains(kx)) lp.x = prefs.getInt(kx, lp.x);
        if (prefs.contains(ky)) lp.y = prefs.getInt(ky, lp.y);
        return lp;
    }

    /** v1.53：整条可拖动（参考 CameraOverlayService makeDraggable 实现）；
     * 拖动结束将位置 x/y 持久化到 SharedPreferences。 */
    private void makeDraggable(final View bar, final boolean left) {
        bar.setOnTouchListener(new View.OnTouchListener() {
            private int downRawX, downRawY, initX, initY;
            private boolean dragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = (int) event.getRawX();
                        downRawY = (int) event.getRawY();
                        initX = lp.x;
                        initY = lp.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) event.getRawX() - downRawX;
                        int dy = (int) event.getRawY() - downRawY;
                        if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
                            dragging = true;
                        }
                        if (dragging) {
                            lp.x = initX + dx;
                            lp.y = initY + dy;
                            try {
                                wm.updateViewLayout(v, lp);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            SharedPreferences.Editor ed = ctx.getSharedPreferences(
                                    PREFS_NAME, Context.MODE_PRIVATE).edit();
                            ed.putInt(left ? KEY_LEFT_X : KEY_RIGHT_X, lp.x);
                            ed.putInt(left ? KEY_LEFT_Y : KEY_RIGHT_Y, lp.y);
                            ed.apply();
                        }
                        dragging = false;
                        return true;
                }
                return false;
            }
        });
    }

    /** v1.54：BSD 常显窄条位置一键复位——清除 bsd_left_x/y、bsd_right_x/y 持久化键，
     * 并立即把已挂载的窄条恢复为默认位置（左侧 x=8dp/y=90dp；右侧贴右缘 x=8dp/y=90dp）。 */
    public void resetPositions() {
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        ed.remove(KEY_LEFT_X);
        ed.remove(KEY_LEFT_Y);
        ed.remove(KEY_RIGHT_X);
        ed.remove(KEY_RIGHT_Y);
        ed.apply();
        applyDefaultPosition(leftBar, true);
        applyDefaultPosition(rightBar, false);
    }

    private void applyDefaultPosition(View bar, boolean left) {
        if (bar == null || !attached) return;
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) bar.getLayoutParams();
            lp.gravity = left ? (Gravity.TOP | Gravity.START) : (Gravity.TOP | Gravity.END);
            lp.x = dp(8);
            lp.y = dp(90);
            wm.updateViewLayout(bar, lp);
        } catch (Exception ignored) {
        }
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
