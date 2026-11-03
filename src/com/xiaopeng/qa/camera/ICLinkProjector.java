package com.xiaopeng.qa.camera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v1.57 beta3：仪表 H.264 投影模块（ICLinkProjector）。
 *
 * 车机侧把浮窗当前影像（Camera2 预览会话中追加的编码器 input Surface）经
 * MediaCodec H.264 硬编 + TCP 推流到仪表侧（仪表 172.20.1.40:9000，仪表侧独立解码写屏）。
 *
 * 分帧协议：4 字节大端长度头 + H.264 Annex-B ES（MediaCodec 输出即 Annex-B，
 * 直接切帧加长度头发送）。
 *
 * 容错红线：编码器创建失败 / socket 连接失败 / 推流中 IO 异常，一律 try-catch
 * 静默降级（仅记录日志），绝不 crash、绝不阻塞或影响浮窗主流程。
 */
public class ICLinkProjector {

    private static final String TAG = "ICLinkProjector";

    // ---- 编码参数（与仪表侧联调后可能调整）----
    private static final int ENC_WIDTH = 1280;
    private static final int ENC_HEIGHT = 720;
    private static final int ENC_BIT_RATE = 2_000_000;      // 2 Mbps
    private static final int ENC_FRAME_RATE = 15;
    private static final int ENC_I_FRAME_INTERVAL = 1;      // 1 秒一个 IDR 关键帧
    private static final int DEQUEUE_TIMEOUT_US = 10_000;

    // ---- 帧通道参数（待仪表侧锁定帧通道后对齐）----
    public static String DEFAULT_HOST = "172.20.1.40";
    public static int DEFAULT_PORT = 9000;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    /** 发送队列容量：防背压（队列满丢最旧帧保最新），不阻塞编码线程。 */
    private static final int QUEUE_CAPACITY = 8;
    private static final long SEND_POLL_MS = 200L;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lock = new Object();
    private final ArrayBlockingQueue<byte[]> frameQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private MediaCodec codec;
    private Surface inputSurface;
    private Socket socket;
    private OutputStream out;
    private Thread pushThread;
    private Thread sendThread;

    /** 返回编码器 input Surface，供 Camera2 预览会话追加（编码器未就绪时返回 null）。 */
    public Surface getInputSurface() {
        synchronized (lock) {
            return inputSurface;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 启动投影：同步创建 H.264 编码器 + input Surface（耗时短），
     * socket 连接在后台推流线程内异步完成（失败静默降级，不阻塞调用方）。
     * 幂等：已启动时直接返回。
     */
    public void start(String host, int port) {
        synchronized (lock) {
            if (running.get()) {
                return;
            }
            try {
                MediaCodec c = MediaCodec.createEncoderByType("video/avc");
                MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", ENC_WIDTH, ENC_HEIGHT);
                fmt.setInteger(MediaFormat.KEY_BIT_RATE, ENC_BIT_RATE);
                fmt.setInteger(MediaFormat.KEY_FRAME_RATE, ENC_FRAME_RATE);
                fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, ENC_I_FRAME_INTERVAL);
                fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                Surface s = c.createInputSurface();
                c.start();

                Socket sk = new Socket();
                codec = c;
                inputSurface = s;
                socket = sk;
                out = null;
                running.set(true);

                pushThread = new Thread(() -> pushLoop(), "icl-push");
                sendThread = new Thread(() -> sendLoop(host, port), "icl-send");
                pushThread.start();
                sendThread.start();
                Log.i(TAG, "ICLinkProjector started: " + host + ":" + port
                        + " enc=" + ENC_WIDTH + "x" + ENC_HEIGHT + "@" + ENC_FRAME_RATE + "fps");
            } catch (Throwable t) {
                // 编码器创建失败：静默降级，绝不 crash
                Log.w(TAG, "start failed, silent fallback: " + t.getMessage());
                releaseInternal();
            }
        }
    }

    /**
     * 停止投影（幂等，可重复调用）：停止推流线程、释放编码器、关闭 socket。
     * 多次打灯循环（弹窗→收窗→再弹）不得泄漏编码器/socket/线程；
     * stop 后再次 show 可重新启动。
     */
    public void stop() {
        Thread p;
        Thread s;
        synchronized (lock) {
            if (!running.get()) {
                return;
            }
            running.set(false);
            p = pushThread;
            s = sendThread;
            frameQueue.clear();
            // 关闭 socket 以唤醒 sendLoop 可能的写阻塞 / connect 阻塞
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (Throwable ignored) {
            }
        }
        if (p != null) {
            try {
                p.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
        if (s != null) {
            try {
                s.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
        releaseInternal();
        Log.i(TAG, "ICLinkProjector stopped");
    }

    /** 编码输出循环：dequeue 编码帧，SPS/PPS 缓存并在关键帧前补发，帧数据入队（防背压）。 */
    private void pushLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] config = null;
        while (running.get()) {
            try {
                int idx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (idx < 0) {
                    continue;
                }
                boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                byte[] data = new byte[info.size];
                codec.getOutputBuffer(idx).get(data, 0, info.size);
                codec.releaseOutputBuffer(idx, false);
                if (isConfig) {
                    // SPS/PPS：缓存，待下一个关键帧前补发（解码器必须先收到 SPS/PPS 才能解 IDR）
                    config = data;
                    continue;
                }
                if (isKey && config != null) {
                    enqueue(config);
                }
                enqueue(data);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "push loop error, silent: " + t.getMessage());
                break;
            }
        }
    }

    /** 入队：队列满则丢最旧帧保最新（防背压，发送慢时不阻塞编码线程）。 */
    private void enqueue(byte[] data) {
        if (!frameQueue.offer(data)) {
            frameQueue.poll();
            frameQueue.offer(data);
        }
    }

    /** 发送循环：后台连接仪表，随后取帧封装 4 字节大端长度头 + Annex-B ES 写 socket。 */
    private void sendLoop(String host, int port) {
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            out = socket.getOutputStream();
            Log.i(TAG, "ICLinkProjector connected: " + host + ":" + port);
        } catch (Throwable t) {
            // 连接失败：静默降级（编码器继续工作，帧被丢弃），不影响浮窗
            Log.w(TAG, "connect failed, projection drops: " + t.getMessage());
            return;
        }
        byte[] head = new byte[4];
        while (running.get()) {
            try {
                byte[] frame = frameQueue.poll(SEND_POLL_MS, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                int len = frame.length;
                head[0] = (byte) ((len >>> 24) & 0xFF);
                head[1] = (byte) ((len >>> 16) & 0xFF);
                head[2] = (byte) ((len >>> 8) & 0xFF);
                head[3] = (byte) (len & 0xFF);
                out.write(head);
                out.write(frame);
                out.flush();
            } catch (Throwable t) {
                // 推流中 IO 异常：静默降级
                Log.w(TAG, "send loop error, silent: " + t.getMessage());
                break;
            }
        }
    }

    /** 释放编码器 / input Surface / socket（幂等）。 */
    private void releaseInternal() {
        try {
            if (codec != null) {
                codec.stop();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (codec != null) {
                codec.release();
            }
        } catch (Throwable ignored) {
        }
        codec = null;
        try {
            if (inputSurface != null) {
                inputSurface.release();
            }
        } catch (Throwable ignored) {
        }
        inputSurface = null;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Throwable ignored) {
        }
        socket = null;
        out = null;
        frameQueue.clear();
    }
}
