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
 * 摄像头实机测试页：枚举车机所有可读摄像头（ID/朝向/最大预览分辨率），
 * 支持上一台/下一台切换并实时预览，方便确认哪一路摄像头能真正打开。
 * 界面元素刻意放大，适配车机大屏（sw1333dp）。
 */
public class CameraTestActivity extends Activity
        implements TextureView.SurfaceTextureListener, View.OnClickListener {

    private static final String TAG = "CameraAssist";
    private static final int REQ_CAMERA = 200;

    // ---- AVM 视角控制：display-mode 取值（见技术方案 5.3 实测标定）----
    private static final int AVM_3D = 4;      // 3D 环视
    private static final int AVM_FRONT = 16;  // 2D 单路前视
    private static final int AVM_360 = 21;    // 360 三分屏
    // 实车 dump 已确认真实结构：display-mode 由 setAvmDisplayMode(int) 写入；
    // v1.27 起 switchAvmView 按 ①setAvmDisplayMode(int) ②set(String,int) ③私有 put(String,String) 三优先级反射写入

    private TextureView textureView;
    private TextView tvCamIndex;
    private Button btnBack;
    private Button btnAvm3d;
    private Button btnAvmFront;
    private Button btnAvm360;
    private Button btnDumpAvm;
    private EditText etAvmCustom;
    private Button btnAvmCustom;
    // v1.41：移除路数列表、上一个/下一个、左右转向绑定按钮（转向灯绑定固定 display-mode=8，不再暴露配置）
    /** 当前 AVM 视角（-1 表示未知/未设置），用于按钮激活态高亮。 */
    private int currentAvmMode = -1;

    private CameraManager cameraManager;
    private HandlerThread bgThread;
    private Handler bgHandler;

    private String[] cameraIds;
    private final List<CamInfo> camInfos = new ArrayList<>();
    private int currentIndex = 0;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Surface previewSurface;
    private Size previewSize;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "CameraTestActivity onCreate");
        setContentView(R.layout.activity_camera_test);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        textureView = findViewById(R.id.test_texture);
        tvCamIndex = findViewById(R.id.tv_cam_index);
        btnBack = findViewById(R.id.btn_back);
        btnAvm3d = findViewById(R.id.btn_avm_3d);
        btnAvmFront = findViewById(R.id.btn_avm_front);
        btnAvm360 = findViewById(R.id.btn_avm_360);
        btnDumpAvm = findViewById(R.id.btn_dump_avm);
        etAvmCustom = findViewById(R.id.et_avm_custom);
        btnAvmCustom = findViewById(R.id.btn_avm_custom);

        btnBack.setOnClickListener(this);
        btnAvm3d.setOnClickListener(this);
        btnAvmFront.setOnClickListener(this);
        btnAvm360.setOnClickListener(this);
        btnDumpAvm.setOnClickListener(this);
        btnAvmCustom.setOnClickListener(this);
        updateAvmButtons();

        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        bgThread = new HandlerThread("camera-test");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        textureView.setSurfaceTextureListener(this);

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

    /** 枚举所有可读摄像头并展示列表，随后打开第一台。 */
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

        currentIndex = 0;
        updateIndexLabel();
        if (cameraIds.length > 0) {
            openCamera(currentIndex);
        } else {
            Toast.makeText(this, "未发现可用摄像头", Toast.LENGTH_LONG).show();
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

    private void updateIndexLabel() {
        if (cameraIds == null || cameraIds.length == 0) {
            tvCamIndex.setText("正在预览：无可用摄像头");
        } else {
            tvCamIndex.setText("正在预览：第 " + (currentIndex + 1) + " / " + cameraIds.length
                    + " 台（ID " + camInfos.get(currentIndex).id + "）");
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
        } else if (id == R.id.btn_back) {
            finish();
        }
    }

    // v1.41：已删除 bindAvmRole/bindNow/saveAvmBinding/showCustomBindDialog 等转向绑定方法（绑定固定 display-mode=8）

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

    // v1.41：已删除 switchCamera（上一个/下一个按钮一并移除）

    private void openCamera(int index) {
        closeCamera();
        if (index < 0 || index >= cameraIds.length) return;
        try {
            cameraManager.openCamera(cameraIds[index], deviceCallback, bgHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "open camera failed id=" + cameraIds[index], e);
            Toast.makeText(this, "打开摄像头失败: ID " + cameraIds[index], Toast.LENGTH_LONG).show();
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
            Toast.makeText(CameraTestActivity.this,
                    "摄像头打开出错: ID " + cameraIds[currentIndex], Toast.LENGTH_LONG).show();
        }
    };

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

    private void createPreviewSession() {
        if (cameraDevice == null || textureView == null) return;
        try {
            previewSize = pickPreviewSize(cameraIds[currentIndex]);
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
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
                            Toast.makeText(CameraTestActivity.this,
                                    "预览会话配置失败", Toast.LENGTH_LONG).show();
                        }
                    }, bgHandler);
            configureTransform(textureView.getWidth(), textureView.getHeight());
        } catch (CameraAccessException e) {
            Log.e(TAG, "createPreviewSession failed", e);
        }
    }

    /** 按屏幕旋转与传感器方向校正预览画面，保证横屏显示正向。 */
    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || textureView == null) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else {
            // 竖屏（手机测试）或 0 度：不旋转但按 cover 等比填满，避免画面拉伸变形
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewWidth / previewSize.getWidth(),
                    (float) viewHeight / previewSize.getHeight());
            matrix.postScale(scale, scale, centerX, centerY);
            if (rotation == Surface.ROTATION_180) {
                matrix.postRotate(180, centerX, centerY);
            }
        }
        textureView.setTransform(matrix);
    }

    private void closeCamera() {
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

    // ---- TextureView.SurfaceTextureListener ----

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        if (cameraDevice != null) {
            createPreviewSession();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        closeCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    @Override
    protected void onDestroy() {
        closeCamera();
        if (bgThread != null) {
            bgThread.quitSafely();
        }
        super.onDestroy();
    }
}
