package com.xiaopeng.qa.camera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 摄像头实机测试页（v1.54 beta2）：
 * - 左右并排两个视频预览窗（横向排列不重叠），每窗独立打开一路 cameraId 实时预览
 * - 每窗底下可修改/选择该路 video 的 cameraId（输入框 + 应用按钮，列出 CameraManager 可用列表）
 * - 预览区域高度 240dp -> 420dp，修复原预览窗比例太矮画面看不全的问题
 * 保留 v1.54 能力：AVM 视角切换（3D/2D 前视/360 三分屏/自定义）、dump AVM 参数。
 */
public class CameraTestActivity extends Activity implements View.OnClickListener {

    private static final String TAG = "CameraAssist";
    private static final int REQ_CAMERA = 200;

    // ---- AVM 视角控制：display-mode 取值（见技术方案 5.3 实测标定）----
    private static final int AVM_3D = 4;      // 3D 环视
    private static final int AVM_FRONT = 16;  // 2D 单路前视
    private static final int AVM_360 = 21;    // 360 三分屏

    private Button btnBack;
    private Button btnAvm3d;
    private Button btnAvmFront;
    private Button btnAvm360;
    private Button btnDumpAvm;
    private EditText etAvmCustom;
    private Button btnAvmCustom;
    /** 当前 AVM 视角（-1 表示未知/未设置），用于按钮激活态高亮。 */
    private int currentAvmMode = -1;

    private CameraManager cameraManager;
    private HandlerThread bgThread;
    private Handler bgHandler;

    private String[] cameraIds;
    private final List<CamInfo> camInfos = new ArrayList<>();

    // ---- v1.54 beta2：双路独立预览窗 ----
    private final PreviewWindow leftWindow = new PreviewWindow();
    private final PreviewWindow rightWindow = new PreviewWindow();

    private static class CamInfo {
        final String id;
        final int facing;
        final String maxSize;

        CamInfo(String id, int facing, String maxSize) {
            this.id = id;
            this.facing = facing;
            this.maxSize = maxSize;
        }
    }

    /** 一路独立的预览窗状态：预览控件 + 相机资源 + 监听回调。 */
    private static class PreviewWindow {
        TextureView textureView;
        TextView tvInfo;
        EditText etCamId;
        Button btnApply;
        String cameraId;                 // 当前 cameraId（字符串，车机可能非纯数字）
        CameraDevice cameraDevice;
        CameraCaptureSession captureSession;
        Surface previewSurface;
        Size previewSize;
        SurfaceTextureListener surfaceListener;
        CameraDevice.StateCallback deviceCallback;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "CameraTestActivity onCreate (v1.54 beta2 dual preview)");
        setContentView(R.layout.activity_camera_test);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        btnBack = findViewById(R.id.btn_back);
        btnAvm3d = findViewById(R.id.btn_avm_3d);
        btnAvmFront = findViewById(R.id.btn_avm_front);
        btnAvm360 = findViewById(R.id.btn_avm_360);
        btnDumpAvm = findViewById(R.id.btn_dump_avm);
        etAvmCustom = findViewById(R.id.et_avm_custom);
        btnAvmCustom = findViewById(R.id.btn_avm_custom);

        // 双窗控件绑定
        leftWindow.textureView = findViewById(R.id.test_texture_left);
        leftWindow.tvInfo = findViewById(R.id.tv_cam_left_info);
        leftWindow.etCamId = findViewById(R.id.et_cam_id_left);
        leftWindow.btnApply = findViewById(R.id.btn_apply_left);

        rightWindow.textureView = findViewById(R.id.test_texture_right);
        rightWindow.tvInfo = findViewById(R.id.tv_cam_right_info);
        rightWindow.etCamId = findViewById(R.id.et_cam_id_right);
        rightWindow.btnApply = findViewById(R.id.btn_apply_right);

        btnBack.setOnClickListener(this);
        btnAvm3d.setOnClickListener(this);
        btnAvmFront.setOnClickListener(this);
        btnAvm360.setOnClickListener(this);
        btnDumpAvm.setOnClickListener(this);
        btnAvmCustom.setOnClickListener(this);
        leftWindow.btnApply.setOnClickListener(this);
        rightWindow.btnApply.setOnClickListener(this);
        updateAvmButtons();

        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        bgThread = new HandlerThread("camera-test");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        // 每窗独立 SurfaceTextureListener / CameraDevice.StateCallback
        leftWindow.surfaceListener = new SurfaceTextureListener(leftWindow);
        rightWindow.surfaceListener = new SurfaceTextureListener(rightWindow);
        leftWindow.textureView.setSurfaceTextureListener(leftWindow.surfaceListener);
        rightWindow.textureView.setSurfaceTextureListener(rightWindow.surfaceListener);
        leftWindow.deviceCallback = new DeviceStateCallback(leftWindow);
        rightWindow.deviceCallback = new DeviceStateCallback(rightWindow);

        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            startTest();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            boolean ok = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, ok ? "相机权限已授予" : "相机权限被拒绝", Toast.LENGTH_LONG).show();
            if (ok) {
                startTest();
            }
        }
    }

    /** 枚举所有可读摄像头（ID/朝向/最大预览分辨率），初始化双窗默认 cameraId 并打开预览。 */
    private void startTest() {
        try {
            cameraIds = cameraManager.getCameraIdList();
        } catch (CameraAccessException e) {
            Log.e(TAG, "getCameraIdList failed", e);
            cameraIds = new String[0];
        }

        camInfos.clear();
        for (int i = 0; i < cameraIds.length; i++) {
            String id = cameraIds[i];
            try {
                CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
                Integer facingObj = ch.get(CameraCharacteristics.LENS_FACING);
                int facing = facingObj == null ? -1 : facingObj;
                String maxSize = "?";
                if (ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) != null) {
                    Size[] sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            .getOutputSizes(SurfaceTexture.class);
                    if (sizes != null && sizes.length > 0) {
                        Size best = sizes[0];
                        for (Size s : sizes) {
                            if (s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) {
                                best = s;
                            }
                        }
                        maxSize = best.getWidth() + "x" + best.getHeight();
                    }
                }
                camInfos.add(new CamInfo(id, facing, maxSize));
            } catch (CameraAccessException e) {
                Log.e(TAG, "read characteristics failed id=" + id, e);
                camInfos.add(new CamInfo(id, -1, "?"));
            }
        }

        // 可用 cameraId 列表展示（每窗信息行，含朝向/最大预览）
        StringBuilder avail = new StringBuilder("可用 cameraId: ");
        if (cameraIds.length == 0) {
            avail.append("无");
        } else {
            for (int i = 0; i < camInfos.size(); i++) {
                if (i > 0) avail.append(", ");
                avail.append(camInfos.get(i).id)
                        .append("(").append(facingName(camInfos.get(i).facing))
                        .append(" ").append(camInfos.get(i).maxSize).append(")");
            }
        }
        String availStr = avail.toString();

        // 默认分配：左窗第一路，右窗第二路（仅一路时复用同一路）
        String leftId = cameraIds.length > 0 ? cameraIds[0] : null;
        String rightId = cameraIds.length > 1 ? cameraIds[1] : leftId;

        initWindow(leftWindow, "左窗", leftId, availStr);
        initWindow(rightWindow, "右窗", rightId, availStr);

        if (leftId != null) {
            openCameraFor(leftWindow);
        } else {
            Toast.makeText(this, "未发现可用摄像头", Toast.LENGTH_LONG).show();
        }
    }

    /** 初始化单窗 UI 状态；cameraId 为 null 表示无可用摄像头。 */
    private void initWindow(PreviewWindow win, String label, String camId, String availStr) {
        if (camId != null) {
            win.cameraId = camId;
            win.etCamId.setText(camId);
            win.tvInfo.setText(label + " cameraId=" + camId + "\n" + availStr);
        } else {
            win.cameraId = null;
            win.tvInfo.setText(label + " cameraId: 无可用摄像头\n" + availStr);
        }
    }

    private static String facingName(int facing) {
        switch (facing) {
            case CameraMetadata.LENS_FACING_FRONT:
                return "前置";
            case CameraMetadata.LENS_FACING_BACK:
                return "后置";
            case CameraMetadata.LENS_FACING_EXTERNAL:
                return "外置";
            default:
                return "未知";
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_avm_3d) {
            switchAvmView(AVM_3D, "3D 环视");
        } else if (id == R.id.btn_avm_front) {
            switchAvmView(AVM_FRONT, "2D 前视");
        } else if (id == R.id.btn_avm_360) {
            switchAvmView(AVM_360, "360 三分屏");
        } else if (id == R.id.btn_avm_custom) {
            switchCustomAvmView();
        } else if (id == R.id.btn_dump_avm) {
            dumpAvmParameters();
        } else if (id == R.id.btn_apply_left) {
            applyCameraId(leftWindow, "左窗");
        } else if (id == R.id.btn_apply_right) {
            applyCameraId(rightWindow, "右窗");
        } else if (id == R.id.btn_back) {
            finish();
        }
    }

    /** 读取某窗输入框的 cameraId 并应用：校验合法性 -> 关闭旧相机 -> 重开新 cameraId 预览。 */
    private void applyCameraId(PreviewWindow win, String label) {
        if (win.etCamId == null || win.cameraId == null) return;
        String s = win.etCamId.getText().toString().trim();
        if (s.isEmpty()) {
            Toast.makeText(this, label + "：请输入 cameraId", Toast.LENGTH_SHORT).show();
            return;
        }
        // 校验：必须在 CameraManager 可用列表内
        boolean valid = false;
        for (String cid : cameraIds) {
            if (cid.equals(s)) {
                valid = true;
                break;
            }
        }
        if (!valid) {
            StringBuilder sb = new StringBuilder(label + "：cameraId 无效，可用列表: ");
            for (int i = 0; i < cameraIds.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(cameraIds[i]);
            }
            Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show();
            return;
        }
        if (s.equals(win.cameraId)) {
            Toast.makeText(this, label + "：cameraId 未变化，无需重开", Toast.LENGTH_SHORT).show();
            return;
        }
        win.cameraId = s;
        win.tvInfo.setText(label + " cameraId=" + s + "（切换重开预览）");
        closeCameraFor(win);
        openCameraFor(win);
    }

    /** 独立打开某窗的摄像头。 */
    private void openCameraFor(PreviewWindow win) {
        if (win.cameraId == null) return;
        closeCameraFor(win);
        try {
            cameraManager.openCamera(win.cameraId, win.deviceCallback, bgHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "open camera failed id=" + win.cameraId, e);
            Toast.makeText(this, "打开摄像头失败: ID " + win.cameraId, Toast.LENGTH_LONG).show();
        }
    }

    private class DeviceStateCallback extends CameraDevice.StateCallback {
        private final PreviewWindow win;

        DeviceStateCallback(PreviewWindow win) {
            this.win = win;
        }

        @Override
        public void onOpened(CameraDevice device) {
            win.cameraDevice = device;
            if (win.textureView != null && win.textureView.isAvailable()) {
                createPreviewSessionFor(win);
            }
        }

        @Override
        public void onDisconnected(CameraDevice device) {
            device.close();
            win.cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice device, int error) {
            device.close();
            win.cameraDevice = null;
            Toast.makeText(CameraTestActivity.this,
                    "摄像头打开出错: ID " + win.cameraId, Toast.LENGTH_LONG).show();
        }
    }

    /** 从该摄像头的可用预览尺寸中选择：优先 16:9 且不超过 1920x1080，否则取最大。 */
    private Size pickPreviewSize(String id) {
        Size[] sizes = null;
        try {
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
            if (ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) != null) {
                sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(SurfaceTexture.class);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "read sizes failed id=" + id, e);
        }
        if (sizes == null || sizes.length == 0) {
            return new Size(1280, 720);
        }
        Size best169 = null;
        Size largest = null;
        for (Size s : sizes) {
            if (largest == null || s.getWidth() * s.getHeight() > largest.getWidth() * largest.getHeight()) {
                largest = s;
            }
            if (Math.abs(s.getWidth() * 9 - s.getHeight() * 16) <= s.getHeight()
                    && s.getWidth() <= 1920) {
                if (best169 == null || s.getWidth() > best169.getWidth()) {
                    best169 = s;
                }
            }
        }
        return best169 != null ? best169 : largest;
    }

    /** 为指定窗创建预览会话（绑定该窗 TextureView Surface）。 */
    private void createPreviewSessionFor(PreviewWindow win) {
        if (win.cameraDevice == null || win.textureView == null) return;
        try {
            win.previewSize = pickPreviewSize(win.cameraId);
            SurfaceTexture st = win.textureView.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(win.previewSize.getWidth(), win.previewSize.getHeight());
            if (win.previewSurface != null) {
                win.previewSurface.release();
                win.previewSurface = null;
            }
            win.previewSurface = new Surface(st);

            final CaptureRequest.Builder builder =
                    win.cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(win.previewSurface);
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            win.cameraDevice.createCaptureSession(Collections.singletonList(win.previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            win.captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, bgHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "setRepeatingRequest failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "camera configure failed");
                            Toast.makeText(CameraTestActivity.this,
                                    "预览会话配置失败", Toast.LENGTH_LONG).show();
                        }
                    }, bgHandler);
            configureTransformFor(win);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createPreviewSessionFor failed", e);
        }
    }

    /** 按屏幕旋转与传感器方向校正该窗预览画面，保证横屏显示正向。 */
    private void configureTransformFor(PreviewWindow win) {
        if (win.previewSize == null || win.textureView == null) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, win.textureView.getWidth(), win.textureView.getHeight());
        RectF bufferRect = new RectF(0, 0, win.previewSize.getHeight(), win.previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewRect.height() / win.previewSize.getHeight(),
                    (float) viewRect.width() / win.previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else {
            // 竖屏（手机测试）或 0 度：不旋转但按 cover 等比填满，避免画面拉伸变形
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewRect.width() / win.previewSize.getWidth(),
                    (float) viewRect.height() / win.previewSize.getHeight());
            matrix.postScale(scale, scale, centerX, centerY);
            if (rotation == Surface.ROTATION_180) {
                matrix.postRotate(180, centerX, centerY);
            }
        }
        win.textureView.setTransform(matrix);
    }

    /** 关闭指定窗的相机资源。 */
    private void closeCameraFor(PreviewWindow win) {
        if (win.captureSession != null) {
            win.captureSession.close();
            win.captureSession = null;
        }
        if (win.cameraDevice != null) {
            win.cameraDevice.close();
            win.cameraDevice = null;
        }
        if (win.previewSurface != null) {
            win.previewSurface.release();
            win.previewSurface = null;
        }
    }

    // ---- TextureView.SurfaceTextureListener（每窗独立实例）----

    private class SurfaceTextureListener implements TextureView.SurfaceTextureListener {
        private final PreviewWindow win;

        SurfaceTextureListener(PreviewWindow win) {
            this.win = win;
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            if (win.cameraDevice != null) {
                createPreviewSessionFor(win);
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            closeCameraFor(win);
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }
    }

    /** 读取自定义视角输入框并下发 AVM 视角；非法输入 Toast 提示。 */
    private void switchCustomAvmView() {
        if (etAvmCustom == null) return;
        String s = etAvmCustom.getText().toString().trim();
        if (s.isEmpty()) {
            Toast.makeText(this, "请输入 display-mode 整数值", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int mode = Integer.parseInt(s);
            switchAvmView(mode, "自定义视角（display-mode=" + mode + "）");
        } catch (NumberFormatException e) {
            Toast.makeText(this, "输入无效，请输入整数 display-mode", Toast.LENGTH_LONG).show();
        }
    }

    // ---- AVM 参数 dump（调试）：反射枚举 Parameters 对象全部成员，供实车抓取真实成员名 ----

    /** 枚举 getCmdControlParameters() 返回对象的全部字段/方法到 logcat（tag=CameraAssist，前缀 AVM_DUMP）。 */
    private void dumpAvmParameters() {
        Log.i(TAG, "AVM_DUMP: === dump AVM Parameters begin ===");
        Object params = callGetCmdControlParameters();
        if (params == null) {
            Log.e(TAG, "AVM_DUMP: getCmdControlParameters 返回 null/调用失败，本机可能无 AVM 接口（手机无此接口属预期）");
            Toast.makeText(this, "AVM 接口不可用，dump 失败（手机无此接口属预期）", Toast.LENGTH_LONG).show();
            return;
        }
        Class<?> cls = params.getClass();
        Log.i(TAG, "AVM_DUMP: Parameters class=" + cls.getName());

        // 1) getDeclaredFields：全部字段（含私有），setAccessible 探测可用性，可读时附当前值
        try {
            Field[] fields = cls.getDeclaredFields();
            Log.i(TAG, "AVM_DUMP: --- declared fields (" + fields.length + ") ---");
            for (Field f : fields) {
                String mod = java.lang.reflect.Modifier.toString(f.getModifiers());
                boolean acc;
                try {
                    f.setAccessible(true);
                    acc = true;
                } catch (Exception e) {
                    acc = false;
                }
                String val = "";
                if (acc) {
                    try {
                        Object v = f.get(params);
                        val = " value=" + (v == null ? "null" : v.toString());
                    } catch (Exception ignored) {
                    }
                }
                Log.i(TAG, "AVM_DUMP: FIELD " + mod + " " + f.getType().getName() + " " + f.getName()
                        + " accessible=" + acc + val);
            }
        } catch (Exception e) {
            Log.e(TAG, "AVM_DUMP: getDeclaredFields failed: " + e);
        }

        // 2) getDeclaredMethods：全部声明方法（含私有/受保护），Modifier 标注可见性
        try {
            Method[] dms = cls.getDeclaredMethods();
            Log.i(TAG, "AVM_DUMP: --- declared methods (" + dms.length + ") ---");
            for (Method m : dms) {
                String mod = java.lang.reflect.Modifier.toString(m.getModifiers());
                StringBuilder ps = new StringBuilder();
                for (Class<?> pt : m.getParameterTypes()) {
                    if (ps.length() > 0) ps.append(", ");
                    ps.append(pt.getName());
                }
                Log.i(TAG, "AVM_DUMP: METHOD " + mod + " " + m.getReturnType().getName()
                        + " " + m.getName() + "(" + ps + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "AVM_DUMP: getDeclaredMethods failed: " + e);
        }

        // 3) getMethods：全部公共方法（含继承）
        try {
            Method[] pms = cls.getMethods();
            Log.i(TAG, "AVM_DUMP: --- public methods (" + pms.length + ") ---");
            for (Method m : pms) {
                StringBuilder ps = new StringBuilder();
                for (Class<?> pt : m.getParameterTypes()) {
                    if (ps.length() > 0) ps.append(", ");
                    ps.append(pt.getName());
                }
                Log.i(TAG, "AVM_DUMP: METHOD public " + m.getReturnType().getName()
                        + " " + m.getName() + "(" + ps + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "AVM_DUMP: getMethods failed: " + e);
        }

        // 4) cameraManager 上所有 get/setCmdControlParameters 方法完整签名（返回类型 + 参数类型）
        try {
            List<Method> avmMethods = new ArrayList<>();
            for (Method m : cameraManager.getClass().getMethods()) {
                String n = m.getName();
                if ("getCmdControlParameters".equals(n) || "setCmdControlParameters".equals(n)) {
                    avmMethods.add(m);
                }
            }
            Log.i(TAG, "AVM_DUMP: --- cameraManager get/setCmdControlParameters methods (" + avmMethods.size() + ") ---");
            for (Method m : avmMethods) {
                Log.i(TAG, "AVM_DUMP: CMETHOD " + buildMethodSig(m));
            }
        } catch (Exception e) {
            Log.e(TAG, "AVM_DUMP: enumerate cameraManager methods failed: " + e);
        }

        Log.i(TAG, "AVM_DUMP: === dump AVM Parameters end ===");
        Toast.makeText(this, "dump 完成，见 logcat (tag=CameraAssist AVM_DUMP)", Toast.LENGTH_LONG).show();
    }

    // ---- AVM 视角控制（技术方案第 5 章）：先取后改再送 ----

    /** 请求切换 AVM 视角。全程反射探测，每一步成功/失败写日志；手机无此接口属预期，不崩溃。 */
    private void switchAvmView(int displayMode, String modeName) {
        Log.i(TAG, "AVM: 请求切换视角 " + modeName + " (display-mode=" + displayMode + ")");
        Object params = callGetCmdControlParameters();
        if (params == null) {
            Log.e(TAG, "AVM: getCmdControlParameters 返回 null/调用失败，本机可能无 AVM 接口（手机属预期）");
            Toast.makeText(this, "AVM 接口不可用：" + modeName + "（手机无此接口属预期）", Toast.LENGTH_LONG).show();
            return;
        }
        Log.i(TAG, "AVM: 取得参数对象 class=" + params.getClass().getName());

        boolean written = writeAvmDisplayMode(params, displayMode);
        if (!written) {
            Log.e(TAG, "AVM: 三优先级写入均失败，无法写入 display-mode=" + displayMode);
            Toast.makeText(this, "AVM 参数写入失败（见日志）", Toast.LENGTH_LONG).show();
            return;
        }

        if (callSetCmdControlParameters(params)) {
            currentAvmMode = displayMode;
            updateAvmButtons();
            Log.i(TAG, "AVM: 切换成功 -> " + modeName + " (display-mode=" + displayMode + ")");
            Toast.makeText(this, "已切换 AVM 视角：" + modeName, Toast.LENGTH_LONG).show();
        } else {
            Log.e(TAG, "AVM: setCmdControlParameters 调用失败");
            Toast.makeText(this, "AVM 下发失败（见日志）", Toast.LENGTH_LONG).show();
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
            // 优先级①：单参数且参数类型可接收 Parameters 对象
            for (Method m : overloads) {
                if (m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0].isInstance(params)) {
                    return tryInvokeSet(m, params);
                }
            }
            if (flatten != null) {
                // 优先级②：双参数 (int,String)，cameraId=0 + flatten() 字符串
                for (Method m : overloads) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 2 && pts[0] == int.class && pts[1] == String.class) {
                        return tryInvokeSet(m, 0, flatten);
                    }
                }
                // 优先级③：单参数 String（flatten()）
                for (Method m : overloads) {
                    if (m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == String.class) {
                        return tryInvokeSet(m, flatten);
                    }
                }
            }
            // 优先级④：双参数 (int, Parameters)，cameraId=0
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
        // ① public setAvmDisplayMode(int)（实车确认真实方法）
        try {
            Method m = cls.getMethod("setAvmDisplayMode", int.class);
            m.invoke(params, value);
            Log.i(TAG, "AVM: 优先级1 setAvmDisplayMode(int) 调用成功 = " + value);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "AVM: 优先级1 setAvmDisplayMode(int) 失败: " + e);
        }
        // ② public set(String,int)，键 "display-mode"
        try {
            Method m = cls.getMethod("set", String.class, int.class);
            m.invoke(params, "display-mode", value);
            Log.i(TAG, "AVM: 优先级2 set(String,int) 调用成功 display-mode=" + value);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "AVM: 优先级2 set(String,int) 失败: " + e);
        }
        // ③ 私有 put(String,String)，键 "display-mode" 值 String.valueOf(value)
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

    /** 沿类继承链查找字段。 */
    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** 刷新 AVM 按钮激活态：当前视角按钮金色高亮。 */
    private void updateAvmButtons() {
        setAvmButtonActive(btnAvm3d, currentAvmMode == AVM_3D);
        setAvmButtonActive(btnAvmFront, currentAvmMode == AVM_FRONT);
        setAvmButtonActive(btnAvm360, currentAvmMode == AVM_360);
    }

    private void setAvmButtonActive(Button btn, boolean active) {
        if (btn == null) return;
        if (active) {
            btn.setBackgroundResource(R.drawable.bg_button_active);
            btn.setTextColor(0xFFFFD98A);
        } else {
            btn.setBackgroundResource(R.drawable.bg_button);
            btn.setTextColor(0xFFFFFFFF);
        }
    }

    @Override
    protected void onDestroy() {
        closeCameraFor(leftWindow);
        closeCameraFor(rightWindow);
        if (bgThread != null) {
            bgThread.quitSafely();
        }
        super.onDestroy();
    }
}
