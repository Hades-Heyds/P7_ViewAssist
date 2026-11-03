# P7 全视影像 (P7_ViewAssist)

小鹏 P7 「全视影像」(P7_ViewAssist)：因官方始终不提供侧后方视角，以及更多可能的影像功能；然而实际上 P7 配备完整的四向鱼眼镜头，并已加入车机视频流，因而相关功能皆可实现。

目前应用实现左、右转向灯变道呼出盲区视野浮窗，默认调用四路鱼眼镜头进行裁切；浮窗画面、大小、位置可自定义。

- 应用名：P7 全视影像
- 当前版本：v1.57_beta3（versionCode 58）
- 适配：8295P 芯片的小鹏 P7 车机（Android 10+，需已开启 ADB 且可授权悬浮窗）

## 核心功能：转向灯影像

- **打灯联动浮窗**：轮询车机转向灯信号（`BCM_LTURNLAMP_ST` / `BCM_RTURNLAMP_ST`），单边亮起边沿触发对应侧（左/右）摄像头浮窗，灯灭自动收起。
- **抑制逻辑（优先级）**：R 档 > 双闪 > 单边亮起。挂倒挡或打双闪时不召唤浮窗、不介入视角，避免覆盖倒车等外部影像。
- **AVM 视角绑定**：左右转向浮窗固定绑定 `display-mode=8`（四路鱼眼四宫格画面），打灯期间视角只下发一次。
- **画面不拉伸**：渲染与窗口拖拽解耦，画面恒 16:9 等比（scale 分母固定相机 buffer 常量 1280，`setDefaultBufferSize(1280, 720)`）。
- **浮窗自由调整**：任意拖拽、四角等比例缩放、位置吸附（左栏 8% / 底栏 92% / 阈值 20dp）。
- **手动关闭抑制**：打灯结束前可提前关闭浮窗，同方向误重开被抑制。
- **一键还原**：恢复左右浮窗默认位置/大小/画面缩放，绑定视角归位。
- **BSD 预警补盲**：监测后车预警信号（BSD），预警触发自动弹出对应侧侧后方影像，预警结束自动收起（SideWarning）。
- **信号门控**：信号解析与影像触发经 SignalGate 统一门控，避免重复触发与抖动误触发。
- **仪表推流（实验）**：ICLinkProjector 以 MediaCodec H.264 硬编，将摄像头画面经 TCP 推流给仪表侧（默认 172.20.1.40:9000），分帧为 4 字节大端长度头 + H.264 Annex-B 裸流，SPS/PPS 在关键帧前补发。

## 代码结构

```
cameraassist/
├── AndroidManifest.xml          # minSdk 29 / target 34，前台服务 + 悬浮窗 + 相机权限
├── src/com/xiaopeng/qa/camera/
│   ├── MainActivity.java        # 主界面：开关、浮窗位置/形状/缩放编辑、日志
│   ├── CameraOverlayService.java# 浮窗渲染服务：AVM 视角绑定、信号联动、拖拽/缩放/吸附、推流启停
│   ├── SignalMonitor.java       # 转向灯信号轮询与状态机（R 档 > 双闪 > 单边亮起）
│   ├── SignalGate.java          # 信号门控：事件去抖、触发合并、状态快照
│   ├── SideWarning.java         # BSD 后车预警监测与补盲浮窗联动
│   ├── ICLinkProjector.java     # 仪表推流：H.264 硬编 + TCP 推流（实验性）
│   ├── Camera1Preview.java      # Camera1 预览封装（兼容层）
│   ├── CameraTestActivity.java  # 开发预览页（连续点击主界面标题 10 次进入）
│   └── AdbClient.java           # ADB 认证连接（TYPE=3 公钥配对），授权/信号读取通道
└── res/
    ├── layout/                  # 主界面、浮窗、预览页、补盲浮窗布局
    ├── drawable/                # 按钮/面板背景、应用图标
    ├── mipmap-*/                # 各密度图标
    ├── raw/adb_key.pem          # 应用内置 ADB 配对私钥（应用专用随机密钥，非个人私钥）
    ├── raw/LICENSE.txt          # 开源许可声明（应用内展示）
    └── values/                  # 颜色、字符串、样式
```

## 转向灯影像实现要点

1. **信号解析**：`SignalMonitor` 每 300ms 轮询 `dumpsys`，解析 `BCM_LTURNLAMP_ST` / `BCM_RTURNLAMP_ST` 的 `int32Values[0]`（0 灭 / 1 亮）；轮询解析默认保持上一轮状态，仅明确解析到属性行才更新，防止 dumpsys 抖动产生假边沿。
2. **状态机优先级**：R 档（`VCU_CURRENT_GEARLEV` = 3）> 双闪（左右同时亮）> 单边亮起边沿。
3. **幂等下发**：Service 侧加同方向幂等锁，打灯期间同方向重复亮起且浮窗相机就绪则忽略，绝不重发 `sendAvmMode`；灯熄灭清锁，下次打灯重新下发。
4. **渲染解耦**：窗口形状拖拽与画面渲染分离，画面恒 16:9 不随窗口拉伸。
5. **权限自动化**：ADB 连接建立后自动授予相机与悬浮窗权限（失败不阻塞开关流程）。
6. **推流封装**：`ICLinkProjector` 复用浮窗相机 Surface 输入，`COLOR_FormatSurface` + 1280x720 / 2Mbps / 15fps / I 帧间隔 1；帧格式 4 字节大端长度头 + H.264 Annex-B ES 裸流，SPS/PPS 缓存并在关键帧前补发。

## 构建

离线手工构建链（无 Gradle）：

```
aapt2 compile → aapt2 link → javac（android.jar）→ d8（desugar）→ zipalign → apksigner
```

需 JDK + Android SDK build-tools；签名使用本地 keystore，仓库不含签名文件。构建脚本示例见各版本 `build_vNN.py`（离线链脚本）。

## 开源声明

- 本软件**免费开源 · 禁止倒卖**：允许个人学习、研究与非商业用途使用；禁止未经作者授权的商业转售、二次分发或倒卖行为。
- 基于 [LICENSE](LICENSE) 自定义开源许可授权；任何分发、转载须保留版权声明与本开源地址。
- 项目主页：https://github.com/Hades-Heyds/P7_ViewAssist
- 详细授权条款见仓库根目录 LICENSE 文件与应用内“关于”。

## 免责声明

- 本项目为个人学习研究项目，与小鹏汽车无任何隶属关系，非官方应用。
- 使用需车机已开启 ADB 与悬浮窗权限；请自行评估风险，因使用本项目造成的问题作者不承担责任。
- `res/raw/adb_key.pem` 为应用专用随机生成的 ADB 配对私钥，非任何个人真实密钥；如需自建配对，请替换为自己的密钥并同步配对端公钥。
