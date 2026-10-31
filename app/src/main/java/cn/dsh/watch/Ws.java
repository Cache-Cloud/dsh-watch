package cn.dsh.watch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 一个最小可用的 WebSocket 客户端（RFC 6455，仅文本帧 + 必要控制帧）。
 *
 * 为什么自己写而不引依赖：手表 APK 用「javac + d8」手工构建，不引第三方库能让
 * 构建链保持在「有 JDK 和 Android SDK 就能出包」的水平；这里需要的功能也很窄——
 * DSH 的 /api/remote.mux 只会发文本帧与 ping 控制帧。
 *
 * 必须实现的细节（DSH 的网关就会盯着这些）：
 * - 客户端发出的帧一律要掩码（mask），否则服务端直接断开；
 * - 服务端每 2 秒发一次 ping，客户端必须回 pong，否则会被判定为「无活动」而终止；
 * - 握手要带上 Cookie（浏览器会话票），这才是鉴权所在。
 */
final class Ws {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    /** 上一次握手失败的说明，便于上层给出可读错误。 */
    final String handshakeInfo;

    private Ws(Socket socket, InputStream in, OutputStream out, String handshakeInfo) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.handshakeInfo = handshakeInfo;
    }

    /**
     * 建立连接。
     *
     * @param url    形如 ws://host/api/remote.mux 或 wss://...
     * @param cookie 浏览器会话票（`name=value`）
     * @param insecure 是否放行任意 TLS 证书（与 Api 的策略一致）
     */
    static Ws connect(String url, String cookie, boolean insecure) throws Exception {
        URL parsed = new URL(url.replace("ws://", "http://").replace("wss://", "https://"));
        boolean tls = "https".equals(parsed.getProtocol());
        int port = parsed.getPort();
        if (port <= 0) port = tls ? 443 : 80;
        String host = parsed.getHost();
        String path = parsed.getFile();
        if (path.isEmpty()) path = "/";

        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10_000);
        socket.setSoTimeout(0);
        socket.setTcpNoDelay(true);
        Socket channel = socket;
        if (tls) {
            javax.net.ssl.SSLSocketFactory factory = insecure
                    ? Tls.insecureFactory()
                    : (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) factory.createSocket(socket, host, port, true);
            if (insecure) {
                // 关掉主机名校验（不是 SSLSocket 的能力，要用 SSLParameters）。
                javax.net.ssl.SSLParameters parameters = new javax.net.ssl.SSLParameters();
                parameters.setEndpointIdentificationAlgorithm(null);
                ssl.setSSLParameters(parameters);
            }
            ssl.startHandshake();
            channel = ssl;
        }

        InputStream in = channel.getInputStream();
        OutputStream out = channel.getOutputStream();
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);

        StringBuilder request = new StringBuilder();
        request.append("GET ").append(path).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(host).append(':').append(port).append("\r\n");
        request.append("Upgrade: websocket\r\n");
        request.append("Connection: Upgrade\r\n");
        request.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        request.append("Sec-WebSocket-Version: 13\r\n");
        // Origin 必须与请求的 authority 完全一致（含端口）：DSH 的请求信任校验
        // 会比对 Origin 与 Host，漏掉端口就会被当成跨域而返回 403。
        request.append("Origin: ").append(tls ? "https://" : "http://")
                .append(host).append(':').append(port).append("\r\n");
        if (cookie != null && !cookie.isEmpty()) {
            request.append("Cookie: ").append(cookie).append("\r\n");
        }
        request.append("\r\n");
        out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        String status = readStatusLine(in);
        if (!status.contains("101")) {
            // 读掉响应体，让错误信息尽量具体（401 就是票失效）。
            throw new IOException("握手失败：" + status);
        }
        // 读完剩余的握手头。
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            // 头部内容对客户端没有必要解析（扩展、子协议双方都没开）。
            if (line.length() > 1_000_000) break;
        }
        return new Ws(channel, in, out, status);
    }

    private static String readStatusLine(InputStream in) throws IOException {
        String line = readLine(in);
        if (line.isEmpty()) throw new IOException("连接被关闭（没有任何响应）");
        return line;
    }

    /** 按 CRLF 读一行（握手头是纯 ASCII）。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int current;
        while ((current = in.read()) != -1) {
            if (current == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') length--;
                return new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            buffer.write(current);
            if (buffer.size() > 8 * 1024 * 1024) break;
        }
        if (buffer.size() == 0) return "";
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 发送一个文本帧。 */
    synchronized void sendText(String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        writeFrame(0x1, payload);
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode);
        int length = payload.length;
        if (length < 126) {
            frame.write(0x80 | length);
        } else if (length < 65536) {
            frame.write(0x80 | 126);
            frame.write((length >>> 8) & 0xFF);
            frame.write(length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame.write((int) (((long) length >>> shift) & 0xFF));
            }
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        frame.write(mask, 0, 4);
        for (int i = 0; i < length; i++) {
            frame.write(payload[i] ^ mask[i % 4]);
        }
        byte[] bytes = frame.toByteArray();
        out.write(bytes);
        out.flush();
    }

    /**
     * 阻塞读一帧，返回文本帧内容；控制帧就地处理（ping→pong，close→返回 null）。
     *
     * @return 文本帧内容，或 null 表示连接已关闭
     */
    String readText() throws IOException {
        while (true) {
            int first = in.read();
            if (first == -1) return null;
            int second = in.read();
            if (second == -1) return null;

            int opcode = first & 0x0F;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;
            if (length == 126) {
                length = readUnsigned(2);
            } else if (length == 127) {
                length = readUnsigned(8);
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(mask, 0, 4);
            }
            byte[] payload = new byte[(int) Math.min(length, 64L * 1024 * 1024)];
            readFully(payload, 0, payload.length);
            // 超长帧（我们其实不会遇到）剩下的部分丢弃，避免污染下一条。
            for (long skip = length - payload.length; skip > 0; skip -= 4096) {
                int step = (int) Math.min(4096L, skip);
                byte[] scratch = new byte[step];
                readFully(scratch, 0, step);
            }
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }

            if (opcode == 0x8) {
                // close：礼貌地回一个 close 帧，然后退出循环。
                try { writeFrame(0x8, new byte[0]); } catch (IOException ignored) { }
                return null;
            }
            if (opcode == 0x9) {
                writeFrame(0xA, payload);
                continue;
            }
            if (opcode == 0xA) {
                // pong：心跳回应，不需要处理。
                continue;
            }
            if (opcode == 0x1 || opcode == 0x0 || opcode == 0x2) {
                return new String(payload, StandardCharsets.UTF_8);
            }
            // 其它保留 opcode：忽略。
        }
    }

    private long readUnsigned(int bytes) throws IOException {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            int current = in.read();
            if (current == -1) throw new IOException("连接已关闭");
            value = (value << 8) | (current & 0xFF);
        }
        return value;
    }

    private void readFully(byte[] target, int offset, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int read = in.read(target, offset + done, length - done);
            if (read == -1) throw new IOException("连接已关闭");
            done += read;
        }
    }

    void close() {
        try { writeFrame(0x8, new byte[0]); } catch (Exception ignored) { }
        try { socket.close(); } catch (Exception ignored) { }
    }
}
