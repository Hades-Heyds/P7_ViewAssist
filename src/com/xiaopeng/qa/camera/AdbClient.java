package com.xiaopeng.qa.camera;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import android.util.Log;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 精简 ADB 客户端：TCP 直连本机 adbd，实现 CNXN/AUTH/OPEN/WRTE/CLSE 协议。
 * AUTH 签名使用「伪 SHA1 + 裸 RSA」（DigestInfo 头 + token 原文，一次 RSA 模幂）。
 *
 * v1.40 集成车友 adb-auth-demo 认证方案（在保留 v1.13 连接复用与自动重连基础上）：
 *  - CNXN version 修正为标准 0x01000001；
 *  - 发送端补 data_crc32（java.util.zip.CRC32）；
 *  - AUTH TYPE=3 公钥载荷改为「从内置私钥动态推导的 ADB blob」（words|n0inv|N|RR|e 全小端），
 *    替换原 OpenSSH ssh-rsa 硬编码，消除“格式错 + 密钥错”死路；
 *  - 签名被拒后转配对流程：独立线程等待用户在车机弹窗点「允许」（180s 超时，30s 无响应重发公钥），
 *    等待期间不阻塞轮询线程，且保持 socket 不关（否则授权弹窗会消失）；
 *  - 认证完成增加 exec("id") 探活，确认以 uid=2000(shell) 身份工作后才复用连接。
 */
public final class AdbClient {

    // ---- ADB 协议常量 ----
    private static final int CMD_CNXN = 0x4E584E43;
    private static final int CMD_AUTH = 0x48545541;
    private static final int CMD_OPEN = 0x4E45504F;
    private static final int CMD_OKAY = 0x59414B4F;
    private static final int CMD_WRTE = 0x45545257;
    private static final int CMD_CLSE = 0x45534C43;

    private static final int A_VERSION = 0x01000001;
    private static final int MAX_PAYLOAD = 256 * 1024;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_PUBKEY = 3;

    /** 配对（首次授权）等待上限：等用户在车机弹窗点允许。 */
    private static final long PAIR_TIMEOUT = 180_000L;
    /** 配对等待期间无响应时，周期重发 TYPE=3 公钥，避免授权弹窗被系统收走。 */
    private static final long PAIR_REPUB_GAP = 30_000L;

    /** DigestInfo(SHA1) = 30 21 30 09 06 05 2B 0E 03 02 1A 05 00 04 14 */
    private static final byte[] DI_SHA1 = new byte[]{
            (byte) 0x30, (byte) 0x21, (byte) 0x30, (byte) 0x09, (byte) 0x06, (byte) 0x05,
            (byte) 0x2B, (byte) 0x0E, (byte) 0x03, (byte) 0x02, (byte) 0x1A, (byte) 0x05,
            (byte) 0x00, (byte) 0x04, (byte) 0x14
    };

    private final String host;
    private final int port;
    private final byte[] privateKeyDer; // PKCS#8 DER
    private int nextLocalId = 0x4e000001;

    // ---- v1.13 连接复用状态（单条已认证长连接）；volatile 供配对线程与轮询线程间可见 ----
    private volatile Socket sock;
    private volatile InputStream in;
    private volatile OutputStream out;
    private final Object connLock = new Object();
    /** 配对授权等待中（独立线程负责，期间不新建连接，否则弹窗消失）。 */
    private volatile boolean pairing;

    public AdbClient(String host, int port, byte[] pemBytes) throws Exception {
        this.host = host;
        this.port = port;
        String pem = new String(pemBytes, StandardCharsets.UTF_8);
        String b64 = pem
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        this.privateKeyDer = Base64.getDecoder().decode(b64);
    }

    /** 执行一条 shell 命令并返回其 stdout 文本（复用已认证连接，断线自动重连重认证）。 */
    public String exec(String shellCommand) throws IOException {
        try {
            return execInternal(shellCommand);
        } catch (IOException e) {
            Log.e(TAG, "ADB 链路异常 堆栈=" + Log.getStackTraceString(e));
            throw e;
        }
    }

    private String execInternal(String shellCommand) throws IOException {
        synchronized (connLock) {
            // 未连接或已失效 -> 新建连接并完成 AUTH（首次需用户点授权弹窗时由独立配对线程等待）
            ensureConnection();
            try {
                return execOnConnection(shellCommand);
            } catch (IOException e) {
                // 连接异常：标记失效，下次 exec 自动重连重新认证
                closeQuietly();
                throw e;
            }
        }
    }

    /** 确保存在一条已认证的连接：没有则建立并完成 AUTH，认证通过后保持不关。 */
    private void ensureConnection() throws IOException {
        if (pairing) {
            // 配对授权进行中：不阻塞轮询线程（直接轻量失败），也不新建连接
            // （新建连接会令车机关闭当前配对 socket，授权弹窗随之消失）。
            throw new IOException("ADB 配对等待中（请在车机弹窗点击允许，最长 180s）");
        }
        if (sock != null && sock.isConnected() && !sock.isClosed()) {
            return;
        }
        closeQuietly();
        connectAndAuth();
    }

    /**
     * 建立 TCP 连接并完成 ADB AUTH 握手：
     * CNXN(0x01000001) -> 收 TOKEN 回伪 SHA1 裸 RSA 签名；签名被拒（再收 TOKEN）改发
     * TYPE=3 PUBKEY（ADB blob 动态推导）并转独立配对线程等待用户授权；
     * 认证通过后 exec("id") 探活确认 uid=2000 才复用连接。
     */
    private void connectAndAuth() throws IOException {
        Socket s = new Socket();
        try {
            long t0 = System.currentTimeMillis();
            Log.i(TAG, "Socket 连接开始 host=" + host + " port=" + port + " (connect timeout=5000ms)");
            s.connect(new InetSocketAddress(host, port), 5000);
            Log.i(TAG, "Socket 连接完成 耗时=" + (System.currentTimeMillis() - t0) + "ms");
            // AUTH 阶段使用独立长超时（60s）：给用户充足时间在车机弹窗上点授权确认；
            // 进入配对流程后由配对线程接管（2s 循环 + 30s 重发公钥 + 180s 总超时）。
            s.setSoTimeout(60000);
            OutputStream os = s.getOutputStream();
            InputStream is = s.getInputStream();

            // 1. CNXN（version=0x01000001 标准握手版本, maxdata, banner）
            byte[] banner = "host::features=shell_v2,cmd,stat_v2\0".getBytes(StandardCharsets.UTF_8);
            sendPacket(os, CMD_CNXN, A_VERSION, MAX_PAYLOAD, banner);
            Log.i(TAG, "已发 CNXN version=0x" + Integer.toHexString(A_VERSION) + " maxdata=" + MAX_PAYLOAD);

            // 2. AUTH 循环：先回签名；签名被拒则转 TYPE=3 公钥配对（独立线程等待授权）
            boolean authed = false;
            boolean signed = false;
            for (int i = 0; i < 8; i++) {
                Packet p = recvPacket(is);
                if (p.command == CMD_CNXN) {
                    authed = true;
                    Log.i(TAG, "收到 CNXN 认证通过 横幅前40字节=" + hexPrefix(p.payload, 40));
                    break;
                }
                if (p.command != CMD_AUTH || p.arg0 != AUTH_TOKEN) continue;
                Log.i(TAG, "收到 AUTH TOKEN 第" + (i + 1) + "次");
                if (!signed) {
                    byte[] sig;
                    try {
                        sig = signToken(p.payload);
                    } catch (Exception ex) {
                        Log.e(TAG, "ADB 签名失败: " + ex.getMessage() + " 堆栈=" + Log.getStackTraceString(ex));
                        throw new IOException("ADB 签名失败: " + ex.getMessage(), ex);
                    }
                    sendPacket(os, CMD_AUTH, AUTH_SIGNATURE, 0, sig);
                    signed = true;
                    Log.i(TAG, "已发 AUTH TYPE=2 签名");
                    continue;
                }
                // 签名被拒 = 车机未授权本应用公钥 -> 发 TYPE=3 PUBKEY 并进入配对等待
                Log.w(TAG, "签名被拒，转入 TYPE=3 公钥配对流程");
                byte[] pub;
                try {
                    pub = pubkeyPayload();
                } catch (Exception ex) {
                    Log.e(TAG, "ADB 公钥生成失败: " + ex.getMessage() + " 堆栈=" + Log.getStackTraceString(ex));
                    throw new IOException("ADB 公钥生成失败: " + ex.getMessage(), ex);
                }
                sendPacket(os, CMD_AUTH, AUTH_PUBKEY, 0, pub);
                Log.i(TAG, "已发 AUTH TYPE=3 PUBKEY（ADB blob 动态推导）");
                startPairing(s, is, os);
                // 配对 socket 归配对线程管理，此处不得关闭；直接抛“等待中”结束本次握手
                throw new IOException("ADB 配对等待中（请在车机弹窗点击允许，最长 180s）");
            }
            if (!authed) {
                Log.e(TAG, "认证失败（车机 adbd 未信任内置密钥）");
                throw new IOException("ADB 认证失败（车机 adbd 未信任内置密钥）");
            }

            // 3. 认证完成判定：exec("id") 探活确认 uid=2000(shell) 身份
            s.setSoTimeout(15000);
            this.sock = s;
            this.in = is;
            this.out = os;
            String who = execOnConnection("id");
            Log.i(TAG, "认证探活 id 输出=" + (who == null ? "null" : who.trim()));
            if (who == null || !who.contains("uid=2000")) {
                Log.e(TAG, "认证后探活未确认 uid=2000");
                closeQuietly();
                throw new IOException("ADB 认证未通过（exec id 探活失败）");
            }
            Log.i(TAG, "认证成功（uid=2000 探活通过），保持连接供复用（后续轮询不再走 AUTH）");
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("配对等待中")) {
                throw e; // 配对 socket 已交由配对线程，不在此关闭
            }
            try { s.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    /**
     * 独立配对线程：等待用户在车机屏幕点「允许」授权（首次 / 换密钥后）。
     *
     * 保持 socket 不关：授权弹窗绑定在这条 TCP 连接上，客户端一断 adbd 立即关掉弹窗。
     * 180s 总超时内每 30s 无响应重发 TYPE=3 公钥，防止弹窗被系统收走；
     * 收到 CNXN 即配对成功，随后 exec("id") 探活确认 uid=2000。
     * 全程不占用 connLock，轮询线程可继续以轻量失败方式轮询而不被阻塞。
     */
    private void startPairing(final Socket s, final InputStream is, final OutputStream os) {
        pairing = true;
        Log.i(TAG, "★ 请在车机屏幕上点「允许」授权（最长 180s，每 30s 自动重发公钥）");
        Thread t = new Thread(() -> {
            boolean ok = false;
            try {
                s.setSoTimeout(2000);
                long deadline = System.currentTimeMillis() + PAIR_TIMEOUT;
                long lastPub = System.currentTimeMillis();
                while (System.currentTimeMillis() < deadline) {
                    Packet p;
                    try {
                        p = recvPacket(is);
                    } catch (java.net.SocketTimeoutException ste) {
                        // 用户还没动作 -> 超过 30s 重发公钥，避免授权弹窗被系统收走
                        if (System.currentTimeMillis() - lastPub > PAIR_REPUB_GAP) {
                            sendPacket(os, CMD_AUTH, AUTH_PUBKEY, 0, pubkeyPayload());
                            Log.i(TAG, "配对等待中：30s 无响应，重发 TYPE=3 PUBKEY");
                            lastPub = System.currentTimeMillis();
                        }
                        continue;
                    }
                    if (p.command == CMD_CNXN) {
                        Log.i(TAG, "收到 CNXN，配对成功");
                        s.setSoTimeout(15000);
                        AdbClient.this.sock = s;
                        AdbClient.this.in = is;
                        AdbClient.this.out = os;
                        ok = true;
                        break;
                    }
                    if (p.command == CMD_AUTH && p.arg0 == AUTH_TOKEN) {
                        sendPacket(os, CMD_AUTH, AUTH_PUBKEY, 0, pubkeyPayload());
                        lastPub = System.currentTimeMillis();
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "配对线程异常: " + e.getMessage());
            } finally {
                pairing = false;
                if (ok) {
                    try {
                        String who = execOnConnection("id");
                        if (who != null && who.contains("uid=2000")) {
                            Log.i(TAG, "★ 配对成功（uid=2000 探活通过），之后无需再授权");
                        } else {
                            Log.e(TAG, "配对后探活未确认 uid=2000: " + (who == null ? "null" : who.trim()));
                            closeQuietly();
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "配对后探活失败: " + e.getMessage());
                        closeQuietly();
                    }
                } else {
                    Log.e(TAG, "配对未完成（超时或拒绝），关闭连接，等待下次重试");
                    closeQuietly();
                }
            }
        }, "adb-pair");
        t.start();
    }

    /** 在已认证连接上执行一条命令：OPEN -> 等 OKAY -> 收 WRTE(逐包回 OKAY) -> CLSE。不关闭底层连接。 */
    private String execOnConnection(String shellCommand) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();

        // 3. OPEN shell:<cmd>
        int localId = nextLocalId++;
        byte[] openPayload = ("shell:" + shellCommand + "\0").getBytes(StandardCharsets.UTF_8);
        sendPacket(out, CMD_OPEN, localId, 0, openPayload);
        Log.i(TAG, "已发 OPEN localId=0x" + Integer.toHexString(localId)
                + " 命令前80字节=" + textPrefix(shellCommand, 80));

        // 4. 收 OKAY/WRTE/CLSE，WRTE 逐包回 OKAY。
        //    deadline 从「流已建立」起算：收到首个 OKAY（OPEN 被接受）或首个 WRTE
        //    （数据开始返回）时重新计时 15 秒；等待首包期间由 socket soTimeout(15s) 兜底。
        //    deadline 到期仍未收到 CLSE 时，视为命令未完整返回，抛异常而非静默截断。
        int remoteId = 0;
        boolean streamOpen = true;
        boolean streamEstablished = false;
        boolean gotClse = false;
        long deadline = 0;
        while (streamOpen) {
            if (streamEstablished && System.currentTimeMillis() >= deadline) {
                Log.e(TAG, "流阶段超时：已建立 15 秒未收到 CLSE，输出不完整");
                throw new IOException("ADB 命令执行超时：流已建立 15 秒内未收到 CLSE，输出不完整");
            }
            Packet p = recvPacket(in);
            if (p.command == CMD_OKAY && p.arg1 == localId) {
                remoteId = p.arg0;
                Log.i(TAG, "收到 OKAY remoteId=0x" + Integer.toHexString(remoteId));
                if (!streamEstablished) {
                    streamEstablished = true;
                    deadline = System.currentTimeMillis() + 15000;
                }
            } else if (p.command == CMD_WRTE && p.arg1 == localId) {
                result.write(p.payload, 0, p.payload.length);
                sendPacket(out, CMD_OKAY, localId, p.arg0, null);
                Log.i(TAG, "收到 WRTE 字节数=" + p.payload.length + " 内容前120字节="
                        + textPrefix(new String(p.payload, StandardCharsets.UTF_8), 120));
                if (!streamEstablished) {
                    streamEstablished = true;
                    deadline = System.currentTimeMillis() + 15000;
                }
            } else if (p.command == CMD_CLSE && p.arg1 == localId) {
                streamOpen = false;
                gotClse = true;
                Log.i(TAG, "收到 CLSE");
            }
        }
        if (!gotClse) {
            Log.e(TAG, "流已结束但未收到 CLSE");
            throw new IOException("ADB 命令未完整返回：流已结束但未收到 CLSE，输出不完整");
        }

        // 5. CLSE 关闭流：仅结束当前 stream，不关闭底层 Socket，供后续命令复用
        try {
            sendPacket(out, CMD_CLSE, localId, remoteId, null);
            Log.i(TAG, "已发 CLSE localId=0x" + Integer.toHexString(localId)
                    + " remoteId=0x" + Integer.toHexString(remoteId) + "（连接保持复用）");
        } catch (IOException ignored) {
        }
        return result.toString(StandardCharsets.UTF_8.name());
    }

    /** 关闭底层连接并清空复用状态（连接失效时调用，下次 exec 自动重连重认证）。 */
    private void closeQuietly() {
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (sock != null) sock.close(); } catch (IOException ignored) {}
        sock = null;
        in = null;
        out = null;
    }

    /** v1.41：主动关闭连接（一次性授权任务使用完毕后释放底层 socket）。 */
    public void close() {
        closeQuietly();
    }

    /** 伪 SHA1 裸 RSA 签名：EM = 00 01 FF..FF 00 DigestInfo(SHA1)+token，s = EM^d mod n。 */
    private byte[] signToken(byte[] token) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(privateKeyDer));
        RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) pk;
        BigInteger n = rsa.getModulus();
        BigInteger d = rsa.getPrivateExponent();
        int k = (n.bitLength() + 7) / 8; // 模长字节数（2048 位 => 256）

        byte[] body = new byte[DI_SHA1.length + token.length];
        System.arraycopy(DI_SHA1, 0, body, 0, DI_SHA1.length);
        System.arraycopy(token, 0, body, DI_SHA1.length, token.length);

        int padLen = k - 3 - body.length;
        if (padLen < 8) throw new IllegalStateException("RSA 密钥过小，无法构造 PKCS#1 v1.5 填充");

        byte[] em = new byte[k];
        em[0] = 0x00;
        em[1] = 0x01;
        for (int i = 2; i < 2 + padLen; i++) em[i] = (byte) 0xFF;
        em[2 + padLen] = 0x00;
        System.arraycopy(body, 0, em, 3 + padLen, body.length);

        BigInteger s = new BigInteger(1, em).modPow(d, n);
        byte[] sb = s.toByteArray();
        byte[] sig = new byte[k];
        if (sb.length > k) {
            System.arraycopy(sb, sb.length - k, sig, 0, k); // 截断高位 0 前缀
        } else {
            System.arraycopy(sb, 0, sig, k - sb.length, sb.length); // 大端左对齐，不翻转
        }
        return sig;
    }

    /**
     * ADB AUTH TYPE=3 PUBKEY 载荷：base64(ADB 公钥 blob) + " 注释" + '\0'。
     * blob 结构（全部小端）：字数(4B) | n0inv(4B) | 模数 N(字数字节) | RR = R² mod N(同长) | e(4B)。
     * 与车友 adb-auth-demo 方案一致：从内置私钥动态推导公钥参数，避免 OpenSSH 格式硬编码
     * 导致“格式错 + 密钥错”双重错误。
     */
    private byte[] pubkeyPayload() throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(privateKeyDer));
        RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) pk;
        BigInteger n = rsa.getModulus();
        BigInteger e = rsa.getPublicExponent();
        int words = (n.bitLength() + 31) / 32;

        byte[] nb = leFixed(n, words * 4);
        BigInteger r = BigInteger.ONE.shiftLeft(words * 32);
        byte[] rrb = leFixed(r.multiply(r).mod(n), words * 4); // RR = R² mod N

        long n0 = 0;
        for (int i = 3; i >= 0; i--) n0 = (n0 << 8) | (nb[i] & 0xffL);
        BigInteger inv = BigInteger.valueOf(n0).modInverse(BigInteger.ONE.shiftLeft(32));
        long n0inv = (-inv.longValue()) & 0xFFFFFFFFL;

        ByteBuffer bb = ByteBuffer.allocate(8 + nb.length + rrb.length + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        bb.putInt((int) n0inv);
        bb.put(nb);
        bb.put(rrb);
        bb.putInt(e.intValue());

        String b64 = Base64.getEncoder().encodeToString(bb.array());
        return (b64 + " cameraassist@xpeng\0").getBytes(StandardCharsets.UTF_8);
    }

    /** 大端整数 -> 定长小端字节。 */
    private static byte[] leFixed(BigInteger v, int bytes) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[bytes];
        for (int i = 0; i < Math.min(be.length, bytes); i++) {
            out[i] = be[be.length - 1 - i];
        }
        return out;
    }

    // ---- 协议消息 ----

    private static final class Packet {
        int command, arg0, arg1;
        byte[] payload;
    }

    private static Packet recvPacket(InputStream in) throws IOException {
        byte[] head = new byte[24];
        readFully(in, head);
        ByteBuffer bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.command = bb.getInt();
        p.arg0 = bb.getInt();
        p.arg1 = bb.getInt();
        int len = bb.getInt();
        bb.getInt(); // data_crc32：部分车机 adbd 与标准实现不一致，接收端不校验
        bb.getInt(); // magic：同样不校验
        p.payload = new byte[len];
        if (len > 0) readFully(in, p.payload);
        return p;
    }

    /** 发一个包：头部 24 字节小端序 command|arg0|arg1|len|crc32|~command，发送端补 data_crc32。 */
    private static void sendPacket(OutputStream out, int command, int arg0, int arg1, byte[] payload)
            throws IOException {
        if (payload == null) payload = new byte[0];
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(payload);
        ByteBuffer bb = ByteBuffer.allocate(24 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(command);
        bb.putInt(arg0);
        bb.putInt(arg1);
        bb.putInt(payload.length);
        bb.putInt((int) crc.getValue());
        bb.putInt(~command);
        if (payload.length > 0) bb.put(payload);
        out.write(bb.array());
        out.flush();
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("ADB 连接被关闭");
            off += n;
        }
    }

    // ---- 日志辅助 ----

    private static final String TAG = "AdbClient";

    /** 取字节数组前 max 字节的 HEX 字符串。 */
    private static String hexPrefix(byte[] data, int max) {
        if (data == null) return "null";
        int n = Math.min(data.length, max);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i]));
        }
        return sb.toString();
    }

    /** 取字符串前 max 字符，超长省略号结尾。 */
    private static String textPrefix(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
