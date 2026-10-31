package cn.dsh.watch;

/**
 * TLS 策略集中处。
 *
 * 手表上没有方便的证书安装入口，而 DSH 常常跑在自签证书或内网明文地址上。
 * 因此这里统一「放行任意证书」的策略，让 Api / DshLink / Ws 三处行为一致——
 * 风险由地址私密性与 token 承担，而不是靠证书链。
 *
 * 单独抽成一个类还有结构上的好处：DshLink / Ws / Events / State 这些协议层
 * 可以脱离 Api（也就是脱离 Android 的 HttpURLConnection 封装）单独测试。
 */
final class Tls {
    private static javax.net.ssl.SSLSocketFactory insecure;

    private Tls() {}

    /** 放行全部证书的 SSL 工厂（懒加载并缓存）。 */
    static synchronized javax.net.ssl.SSLSocketFactory insecureFactory() {
        if (insecure != null) return insecure;
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
            };
            javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
            insecure = context.getSocketFactory();
        } catch (Exception error) {
            throw new IllegalStateException("无法初始化 TLS：", error);
        }
        return insecure;
    }
}
