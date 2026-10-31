package cn.dsh.watch;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * 直连 DSH：浏览器 Cookie 鉴权 + Typert RPC。
 *
 * 与旧中继方案的差别：不需要电脑上跑任何中继程序。DSH 的 /api 认的是它自己签发的
 * `dsh-auth-<hash(authority)>` 会话 cookie，这张 cookie 有一个公开的兑换入口：
 *
 *     GET /?token=<launch token>
 *     → 303，Set-Cookie: dsh-auth-...=v1.<payload>.<signature>; Max-Age=315360000
 *
 * 也就是说：**只要拿到带 token 的网址，就能换到一张十年有效的 cookie**，
 * 之后所有 RPC 与事件流都用这张 cookie，再不需要那个 token。
 * 这正是「只需要输入带 token 的 DSH 网址」能成立的全部原因。
 *
 * 两个实测约束：
 * - cookie 绑定签发时的 host:port（authority），换地址要重新兑换；
 * - HTTPS 下 authority 写法是 `https:origin`（含协议），HTTP 下是 `host:port`。
 *   因此这里不自己算 cookie 名，而是直接沿用服务端 Set-Cookie 给出的名字，
 *   只把它的 Max-Age/Expires 当作有效期，这样就永远和 DSH 的实现保持一致。
 */
final class DshLink {
    /** 单次 RPC 超时。会话页响应可达数百 KB，给足时间。 */
    private static final int TIMEOUT_MS = 20_000;
    /** 响应上限：session/page 原本就有几百 KB，留足余量。 */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    /** 兑换 token 得到的会话 cookie 原文（形如 `name=value`）。 */
    private String cookie;
    /** cookie 所属的 authority（host:port 或 origin），用于判断是否需要重新兑换。 */
    private String cookieAuthority = "";
    /** cookie 过期时间（毫秒）；0 表示未知，按十年算。 */
    private long cookieExpiresAt;

    String token = "";

    /**
     * 拿到一张可用于请求的 cookie：没有、过期、或换了地址时先兑换一次。
     *
     * @param base 形如 `http://192.168.1.5:3377` 或 `https://wang.kutina.cn`
     * @param token 网址里的 token 参数
     * @return 请求头要用的 `name=value`
     * @throws Exception 兑换失败时抛出，交由调用方转成可读文案
     */
    synchronized String ensureCookie(final String base, final String token, final boolean insecureTls)
            throws Exception {
        this.token = token == null ? "" : token;
        String authority = authorityOf(base);
        if (cookie != null && authority.equals(cookieAuthority)
                && (cookieExpiresAt <= 0 || System.currentTimeMillis() < cookieExpiresAt - 60_000L)) {
            return cookie;
        }
        cookie = exchange(base, this.token, insecureTls, authority);
        cookieAuthority = authority;
        return cookie;
    }

    /** 当前是否已有一张可用 cookie（内部用来决定要不要走兑换）。 */
    synchronized boolean hasCookie() {
        return cookie != null;
    }

    /**
     * 预置一张已保存的 cookie，避免启动时再兑换一次。
     *
     * @param saved        形如 `dsh-auth-…=v1.…`
     * @param authority    这张 cookie 绑定的 authority
     * @param expiresAt    到期时间（毫秒，0 表示未知）
     */
    synchronized void adopt(String saved, String authority, long expiresAt) {
        if (saved == null || saved.isEmpty()) return;
        this.cookie = saved;
        this.cookieAuthority = authority == null ? "" : authority;
        this.cookieExpiresAt = expiresAt;
    }

    /** 当前 cookie 绑定的 authority（供调用方判断是否还能用）。 */
    synchronized String cachedAuthority() {
        return cookieAuthority;
    }

    /** 当前 cookie 的到期时间（毫秒，0 表示未知）。 */
    synchronized long cachedExpiresAt() {
        return cookieExpiresAt;
    }

    /** 当前持有的 cookie 原文（用于保存）；没有则空串。 */
    synchronized String cachedCookie() {
        return cookie == null ? "" : cookie;
    }

    /** 清空缓存的凭据（断开连接时调用）。 */
    synchronized void reset() {
        cookie = null;
        cookieAuthority = "";
        cookieExpiresAt = 0L;
    }

    /**
     * 用 launch token 兑换会话 cookie。
     *
     * 关键点是请求的 Host 必须就是最终要访问的那个地址——cookie 的 authority 来自
     * 服务端看到的 Host，主机名写错就会拿到一张绑在别处的票。
     * 这里用 URL 自身的主机名，因此与「手表直连这个地址」天然一致。
     */
    private String exchange(String base, String token, boolean insecureTls, String authority) throws Exception {
        if (token.isEmpty()) throw new Exception("网址里缺少 token 参数");
        String url = base;
        if (!url.endsWith("/")) url = url + "/";
        url = url + "?token=" + urlEncode(token);

        HttpURLConnection connection = null;
        try {
            connection = open(url, insecureTls);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("accept", "text/html");
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) {
                throw new Exception(status == 401
                        ? "token 无效或已过期，请在电脑上重新取一条带 token 的网址"
                        : "这个地址拒绝了访问，请确认 DSH 的 trustedHosts 里有这个域名");
            }
            if (status < 200 || status >= 400) throw new Exception("兑换登录凭据失败（HTTP " + status + "）");
            String setCookie = connection.getHeaderField("Set-Cookie");
            if (setCookie == null || setCookie.isEmpty()) {
                throw new Exception("这个地址没有下发登录凭据，请确认它是 DSH 网页地址");
            }
            return parseSetCookie(setCookie);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * 从 Set-Cookie 里取出 `name=value` 与有效期。
     *
     * 只认 DSH 的会话 cookie 名（`dsh-auth-` 开头），避免把别的 cookie 当成凭据；
     * 有效期优先用 Max-Age，缺省再用 Expires。
     */
    private String parseSetCookie(String header) throws Exception {
        String[] parts = header.split(";");
        String pair = parts[0].trim();
        int at = pair.indexOf('=');
        if (at <= 0) throw new Exception("服务端下发的登录凭据格式异常");
        String name = pair.substring(0, at).trim();
        if (!name.startsWith("dsh-auth-")) throw new Exception("这个地址不是 DSH 网页（没有 dsh-auth cookie）");

        long expiresAt = 0L;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            int equals = part.indexOf('=');
            if (equals <= 0) continue;
            String key = part.substring(0, equals).trim().toLowerCase(java.util.Locale.ROOT);
            String value = part.substring(equals + 1).trim();
            if ("max-age".equals(key)) {
                try {
                    long seconds = Long.parseLong(value);
                    expiresAt = System.currentTimeMillis() + seconds * 1000L;
                } catch (NumberFormatException ignored) {
                    // 值异常就退回 Expires 或默认十年。
                }
            } else if ("expires".equals(key) && expiresAt == 0L) {
                expiresAt = parseHttpDate(value);
            }
        }
        cookieExpiresAt = expiresAt == 0L ? System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000 : expiresAt;
        cookie = pair;
        return pair;
    }

    /** RFC 1123 日期解析；失败返回 0（视为有效期未知）。 */
    private static long parseHttpDate(String value) {
        try {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            return format.parse(value).getTime();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /**
     * 调用一个一元 RPC。
     *
     * 协议形状（实测）：POST /api/<namespace>/<method>，信封
     * `{type:"client-request", rpcId, method, payload:{args}}`，
     * 成功时 `{"result":{"ok":true,"value":...}}`。
     *
     * @param method 形如 `session/list`
     * @param args   顶层参数对象，字段名必须与 descriptor 精确匹配
     * @return 业务返回值
     */
    JSONObject rpc(final String base, final String method, final JSONObject args, final boolean insecureTls)
            throws Exception {
        String body = "{\"type\":\"client-request\",\"rpcId\":\"" + UUID.randomUUID().toString()
                + "\",\"method\":\"" + escape(method) + "\",\"payload\":{\"args\":" + args.toString() + "}}";
        String text = post(base, "/api/" + method, method, body, insecureTls);
        JSONObject envelope = new JSONObject(text);
        JSONObject result = envelope.optJSONObject("result");
        if (result == null) throw new Exception(method + " 的响应缺少 result");
        if (!result.optBoolean("ok", false)) {
            JSONObject error = result.optJSONObject("error");
            String code = error == null ? "" : error.optString("code", "");
            String message = error == null ? "未知错误" : error.optString("message", "未知错误");
            throw new Exception(explainError(code, message));
        }
        // 返回值可能是对象、数组、布尔，甚至 undefined（如 $events/result 只回 ok）。
        // 统一包成 {"value": ...}，调用方按需取；没有值也不算失败——调用成功就是成功。
        JSONObject value = result.optJSONObject("value");
        if (value != null) return value;
        JSONObject wrap = new JSONObject();
        Object raw = result.opt("value");
        if (raw != null) {
            wrap.put("value", raw);
        }
        return wrap;
    }

    /** 把 DSH 的错误码翻成用户能看懂的说明。 */
    private static String explainError(String code, String message) {
        if (code.startsWith("gateway/")) return "DSH 拒绝了这次调用：" + message;
        if (code.startsWith("remote-access/")) return "没有权限执行这个操作：" + message;
        return message;
    }

    private String post(String base, String path, String methodName, String body, boolean insecureTls)
            throws Exception {
        if (cookie == null) throw new Exception("尚未登录，请先在设置页填写带 token 的网址");
        HttpURLConnection connection = null;
        try {
            connection = open(base + path, insecureTls);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("content-type", "application/json; charset=utf-8");
            connection.setRequestProperty("accept", "application/json");
            connection.setRequestProperty("cookie", cookie);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String text = readAll(stream);
            if (status == 401) throw new UnauthorizedException("登录凭据已失效，请重新填写网址");
            if (status == 405) {
                throw new Exception("这个地址不接受 " + methodName + " 请求（HTTP 405）。"
                        + "通常是网址末尾多了斜杠，请只填到端口为止，例如 https://host:3377");
            }
            if (status < 200 || status >= 300) throw new Exception("服务返回错误（" + status + "）");
            return text;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** cookie 过期时用它区分「需要重新兑换」与真正的失败。 */
    static final class UnauthorizedException extends Exception {
        UnauthorizedException(String message) { super(message); }
    }

    private static HttpURLConnection open(String url, boolean insecureTls) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        if (connection instanceof javax.net.ssl.HttpsURLConnection) {
            if (insecureTls) {
                ((javax.net.ssl.HttpsURLConnection) connection)
                        .setSSLSocketFactory(Tls.insecureFactory());
                // 与 Api 共用策略：手表上没有方便的证书安装入口，风险由地址私密性承担。
                ((javax.net.ssl.HttpsURLConnection) connection)
                        .setHostnameVerifier((hostname, session) -> true);
            }
        }
        return connection;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        int total = 0;
        while ((read = stream.read(chunk)) != -1) {
            total += read;
            if (total > MAX_BYTES) break;
            buffer.write(chunk, 0, read);
        }
        buffer.close();
        return buffer.toString("UTF-8");
    }

    /** cookie 绑定的 authority：HTTP 用 host:port，HTTPS 用 scheme:host:port。 */
    static String authorityOf(String base) {
        try {
            URL url = new URL(base);
            int port = url.getPort();
            boolean https = "https".equals(url.getProtocol());
            if (port <= 0) port = https ? 443 : 80;
            String host = url.getHost();
            if (https) {
                String suffix = port == 443 ? "" : (":" + port);
                return "https://" + host + suffix;
            }
            return (host + ":" + port);
        } catch (Exception error) {
            return base;
        }
    }

    static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception error) {
            return value;
        }
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 事件流地址：把 http(s) 换成 ws(s)，路径固定 /api/remote.mux。 */
    static String muxUrl(String base) {
        if (base.startsWith("https://")) return "wss://" + base.substring(8) + "/api/remote.mux";
        if (base.startsWith("http://")) return "ws://" + base.substring(7) + "/api/remote.mux";
        return "ws://" + base + "/api/remote.mux";
    }
}
