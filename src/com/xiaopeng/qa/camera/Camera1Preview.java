package com.xiaopeng.qa.camera;

import android.graphics.SurfaceTexture;
import android.util.Log;

/**
 * v1.52：Camera1 预览降级（预留钩子，默认关闭，Phase 2 实车验证后经偏好
 * camera1_fallback=true 激活）。仅当 Camera2 打开失败（SecurityException /
 * CameraAccessException）且偏好开启时，由 CameraOverlayService.openCamera()
 * 的回退钩子调用。SurfaceTexture 直接作为 Camera1 预览输出目标，复用现有
 * TextureView 渲染管线（不引入 SurfaceView，最小侵入）。
 * Phase 1 仅骨架：open() 尝试 Camera.open + setPreviewTexture + startPreview，
 * 任何失败返回 false，不影响主链路。
 */
public class Camera1Preview {

    private static final String TAG = "Camera1Preview";

    private final SurfaceTexture target;
    private final int width;
    private final int height;
    private android.hardware.Camera camera;

    public Camera1Preview(SurfaceTexture target, int width, int height) {
        this.target = target;
        this.width = width;
        this.height = height;
    }

    /** 尝试打开 Camera1 预览。失败返回 false（上层记日志，不阻塞主链路）。 */
    public boolean open() {
        try {
            camera = android.hardware.Camera.open();
            if (camera == null) {
                Log.e(TAG, "Camera.open() 返回 null");
                return false;
            }
            if (target != null) {
                target.setDefaultBufferSize(width, height);
                camera.setPreviewTexture(target);
            }
            android.hardware.Camera.Parameters p = camera.getParameters();
            if (p != null) {
                // 尽量匹配 16:9，失败不阻塞（车机 Camera1 行为 Phase 2 校准）
                try {
                    p.setPreviewSize(width, height);
                    camera.setParameters(p);
                } catch (Exception ignored) {
                }
            }
            camera.setDisplayOrientation(0);
            camera.startPreview();
            Log.i(TAG, "Camera1 预览已启动 " + width + "x" + height);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Camera1 打开失败: " + e.getMessage());
            close();
            return false;
        }
    }

    /** 关闭并释放 Camera1（幂等）。 */
    public void close() {
        try {
            if (camera != null) {
                camera.stopPreview();
                camera.release();
            }
        } catch (Exception ignored) {
        }
        camera = null;
    }
}
