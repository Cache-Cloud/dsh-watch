package cn.dsh.watch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 直连 DSH 的数据层：对外提供与旧中继相同的「接口 + 回调」形状。
 *
 * 这样界面层（MainActivity）几乎不用改：它本来就在调 `api.get(base, "/api/sessions", ...)`，
 * 现在改成 `direct.sessions(...)`，拿到的是同一种 JSON。真正的差别是数据从哪来：
 *
 * - 旧方案：手表 → 电脑上的中继 → DSH。中继负责签名 cookie、裁剪数据、开事件流。
 * - 新方案：手表 → DSH。签名 cookie 由 token 兑换（见 DshLink），裁剪由 State 在本地做。
 *
 * 线程模型：所有网络请求走固定线程池，结果回到主线程（与 Api 一致），
 * 因此界面代码的写法不需要变。
 */
final class Direct {
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final DshLink link = new DshLink();
    /** cookie 的持久化存储（连上之后由 MainActivity 注入）。 */
    private Store store;

    /** 注入持久化存储：让 cookie 能跨 App 重启复用，而不是每次都去兑换。 */
    void attach(Store store) {
        this.store = store;
    }
    /** 事件流处理者：只有需要实时提问时才建立。 */
    private Events events;

    interface Callback {
        void onResult(Api.Result result);
    }

    private void deliver(final Callback callback, final Api.Result result) {
        main.post(() -> callback.onResult(result));
    }

    private void success(final Callback callback, final JSONObject value) {
        deliver(callback, Api.Result.success(value));
    }

    private void failure(final Callback callback, final String error) {
        deliver(callback, Api.Result.failure(error));
    }

    /**
     * 校验并登录一个地址。
     *
     * 这一步是全流程的关键：用「带 token 的网址」换一张十年有效的会话 cookie。
     * 换成功就说明地址、token、域名围栏都对得上，后面所有请求都用这张票。
     */
    void login(final String rawBase, final String token, final boolean insecureTls, final Callback callback) {
        final String base = trimBase(rawBase);
        pool.execute(() -> {
            try {
                link.reset();
                adoptSaved(base);
                String cookie = link.ensureCookie(base, token, insecureTls);
                // 用一次真实 RPC 确认这张票能用，而不是只看兑换是否成功。
                JSONObject args = new JSONObject();
                args.put("_request", new JSONObject());
                JSONObject value = link.rpc(base, "session/list", args, insecureTls);
                persistCookie(base, token);
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("service", "dsh-direct");
                out.put("cookie", cookie != null && !cookie.isEmpty());
                out.put("sessions", value.optJSONArray("items") == null ? 0 : value.optJSONArray("items").length());
                success(callback, out);
            } catch (Exception error) {
                failure(callback, friendly(error));
            }
        });
    }

    /**
     * 只停后台活动，保留凭据与存档。
     *
     * App 被系统回收时该走这条：用户没点「断开连接」，下一次启动应当直接用存档 cookie。
     */
    void stopBackground() {
        if (events != null) {
            events.stop();
            events = null;
        }
    }

    /** 断开：清凭据、停事件流。 */
    void disconnect() {
        if (events != null) {
            events.stop();
            events = null;
        }
        link.reset();
        if (store != null) store.clearSession();
    }

    /** 请求失败文案：把异常翻成人话。 */
    static String friendly(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        // 文案要指向真正的原因：说错方向比不说更耽误事。
        if (error instanceof java.net.SocketTimeoutException) {
            return "连接超时：网络能通但服务器没在限定时间内回应。"
                    + "如果是手表走公网，多半是隧道或反代把请求卡住了";
        }
        if (error instanceof java.net.ConnectException) {
            return "连不上这个地址：域名解析到了，但对方端口没有服务在监听。"
                    + "请确认地址与端口（dsh web 默认 3377）";
        }
        if (error instanceof java.net.UnknownHostException) {
            return "找不到这个域名，请检查地址是否拼错";
        }
        if (error instanceof java.net.SocketException) {
            return "网络连接被中断：" + message;
        }
        if (message.contains("401")) return "登录凭据已失效：token 可能已随 DSH 重启更换，请重新粘贴网址";
        return message;
    }

    // ---------------------------------------------------------------- 会话列表

    /**
     * 会话列表：与中继 /api/sessions 的输出完全一致。
     *
     * 一次拉全量（含子代理、不截断）再过滤：截断必须发生在过滤之后，
     * 否则更新时间很新的几百条子代理会把父会话挤出列表。
     */
    void sessions(final String base, final String token, final boolean insecureTls, final boolean includeSubagents,
                  final Callback callback) {
        pool.execute(() -> {
            try {
                JSONObject value = rpc(base, token, insecureTls, "session/list", listArgs());
                List<JSONObject> all = State.normalizeSessionList(value, true, 0);
                List<JSONObject> visible = new ArrayList<>();
                for (JSONObject item : all) {
                    if (!includeSubagents && item.optBoolean("subagent", false)) continue;
                    visible.add(item);
                }
                if (visible.size() > State.MAX_SESSIONS) {
                    visible = new ArrayList<>(visible.subList(0, State.MAX_SESSIONS));
                }
                State.attachSubagentCounts(all, visible);

                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("sessions", new JSONArray(visible));
                out.put("projects", State.groupByProject(visible));
                out.put("pending", pendingOf(base, token, insecureTls, visible));
                success(callback, out);
            } catch (Exception error) {
                failure(callback, friendly(error));
            }
        });
    }

    /** 子任务列表：与 /api/subagents 一致。信息全在 session/list 里，不必逐个拉投影。 */
    void subagents(final String base, final String token, final boolean insecureTls, final String parentId,
                   final Callback callback) {
        pool.execute(() -> {
            try {
                JSONObject value = rpc(base, token, insecureTls, "session/list", listArgs());
                List<JSONObject> all = State.normalizeSessionList(value, true, 0);
                success(callback, State.subagentsOf(all, parentId));
            } catch (Exception error) {
                failure(callback, friendly(error));
            }
        });
    }

    /**
     * 会话详情：与 /api/session 一致。
     *
     * @param throughSeq 从哪个 seq 往前读；0 表示读最新
     */
    void session(final String base, final String token, final boolean insecureTls, final String sessionId,
                 final long throughSeq, final Callback callback) {
        pool.execute(() -> {
            try {
                // running 与父地址只能从列表摘要读（projections 里没有 running），
                // 子代理的历史又必须靠父地址才能读，因此这里要查一次全量列表。
                JSONObject listed = null;
                JSONObject summary = null;
                try {
                    listed = rpc(base, token, insecureTls, "session/list", listArgs());
                    List<JSONObject> all = State.normalizeSessionList(listed, true, 0);
                    for (JSONObject item : all) {
                        if (sessionId.equals(State.textOf(item, "id"))) {
                            summary = item;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    // 列表拉不到不影响读正文：顶多丢掉 running 与父地址。
                }
                success(callback, readSession(base, token, insecureTls, sessionId,
                        summary == null ? null : summary, throughSeq, events));
            } catch (Exception error) {
                failure(callback, friendly(error));
            }
        });
    }

    /** 组装一个会话详情（与中继的 readSession 等价）。 */
    private JSONObject readSession(String base, String token, boolean insecureTls, String sessionId,
                                   JSONObject summary, long throughSeq, Events eventStream) throws Exception {
        JSONObject request = new JSONObject();
        request.put("request", new JSONObject().put("sessionId", sessionId));
        JSONObject projection = rpc(base, token, insecureTls, "session/projections", request);
        JSONObject values = projection.optJSONObject("values");
        if (values == null) values = new JSONObject();
        long latest = projection.optLong("asOfSeq", 0L);
        long from = throughSeq > 0 ? Math.min(throughSeq, latest) : latest;

        // 子代理的历史必须用「父地址 + 子地址」读：DSH 对 origin=subagent 的会话
        // 直接给普通 session 地址会拒绝（subagent Sessions require their durable parent address）。
        String parentId = summary == null ? "" : State.textOf(summary, "parentId");
        String mode = summary == null ? "" : State.textOf(summary, "subagentMode");
        JSONObject address = new JSONObject();
        if (parentId.isEmpty()) {
            address.put("kind", "session");
            address.put("sessionId", sessionId);
        } else {
            address.put("kind", "subagent");
            address.put("parentSessionId", parentId);
            address.put("childSessionId", sessionId);
            address.put("mode", mode.isEmpty() ? "continuable" : mode);
        }
        JSONObject pageRequest = new JSONObject();
        pageRequest.put("address", address);
        pageRequest.put("throughSeq", from);
        pageRequest.put("maxMessages", 40);
        JSONObject page = rpc(base, token, insecureTls, "session/page",
                new JSONObject().put("request", pageRequest));

        JSONArray records = page.optJSONArray("records");

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("sessionId", sessionId);
        out.put("title", State.textOf(values, "title"));
        out.put("running", summary != null && summary.optBoolean("running", false));
        out.put("hasMore", page.optBoolean("hasMore", false));
        out.put("asOfSeq", latest);
        out.put("fromSeq", from);
        out.put("beforeSeq", State.earliestSeqOf(records));
        out.put("messages", State.normalizeMessages(records));
        out.put("steps", State.normalizeSteps(records, 1_200));
        out.put("usage", State.normalizeUsage(values));
        out.put("plan", State.normalizePlan(values));

        JSONObject question = questionOf(values, sessionId, summary, eventStream);
        if (question != null) out.put("question", question);

        // 会话里出现过的文件：这些工具结果里带 diff 的路径就是「可以查看的文件」。
        out.put("files", filesOf(records));
        return out;
    }

    /** 待答问题：投影里的（含 continued）优先，事件流推来的（前台 open）兜底。 */
    private JSONObject questionOf(JSONObject values, String sessionId, JSONObject summary, Events eventStream) {
        JSONObject questions = values.optJSONObject("userQuestions");
        JSONArray active = questions == null ? null : questions.optJSONArray("active");
        if (active != null && active.length() > 0) {
            JSONObject first = active.optJSONObject(0);
            if (first != null) {
                JSONObject out = new JSONObject();
                try {
                    out.put("callId", State.textOf(first, "callId"));
                    out.put("eventId", eventStream == null
                            ? "" : eventStream.eventIdFor(State.textOf(first, "callId")));
                    out.put("state", State.textOf(first, "state"));
                    out.put("questions", State.compactQuestions(first.optJSONArray("questions")));
                } catch (Exception ignored) {
                    // 不会失败。
                }
                return out;
            }
        }
        if (eventStream == null) return null;
        return eventStream.questionForSession(sessionId);
    }

    /** 会话里出现过的文件清单（供「文件」页展示）。 */
    private JSONArray filesOf(JSONArray records) {
        JSONArray files = new JSONArray();
        List<String> seen = new ArrayList<>();
        if (records == null) return files;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            JSONObject event = record == null ? null : record.optJSONObject("event");
            if (event == null) continue;
            if (!"tool/result".equals(State.textOf(event, "type"))) continue;
            JSONObject data = event.optJSONObject("data");
            JSONObject meta = data == null ? null : data.optJSONObject("meta");
            JSONArray diffs = meta == null ? null : meta.optJSONArray("diffs");
            if (diffs == null) continue;
            for (int d = 0; d < diffs.length(); d++) {
                JSONObject diff = diffs.optJSONObject(d);
                String path = diff == null ? "" : State.textOf(diff, "path");
                if (path.isEmpty() || seen.contains(path)) continue;
                seen.add(path);
                JSONObject entry = new JSONObject();
                try {
                    entry.put("path", path);
                    entry.put("name", fileNameOf(path));
                    entry.put("kind", kindOf(path));
                    entry.put("language", languageOf(path));
                    entry.put("size", 0L);
                } catch (Exception ignored) {
                    // 不会失败。
                }
                files.put(entry);
            }
        }
        return files;
    }

    private static String fileNameOf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String kindOf(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "markdown";
        String language = languageOf(path);
        if (!language.isEmpty()) return "code";
        if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".json")
                || lower.endsWith(".yml") || lower.endsWith(".yaml")) return "text";
        return "other";
    }

    private static String languageOf(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        String[] langs = {"java", "kt", "kts", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx",
                "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "rb", "php", "swift", "m",
                "sh", "bash", "ps1", "sql", "html", "css", "scss", "xml", "vue", "dart"};
        for (String lang : langs) {
            if (lower.endsWith("." + lang)) return lang;
        }
        return "";
    }

    // ---------------------------------------------------------------- 写入操作

    /** 发消息：与 /api/send 一致。 */
    void send(final String base, final String token, final boolean insecureTls, final String sessionId,
              final String text, final String mode, final Callback callback) {
        pool.execute(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("requestId", java.util.UUID.randomUUID().toString());
                request.put("sessionId", sessionId);
                request.put("mode", "steer".equals(mode) ? "steer" : "queue");
                request.put("content", new JSONArray().put(new JSONObject()
                        .put("type", "text").put("text", text)));
                request.put("clientTimeZone", java.util.TimeZone.getDefault().getID());
                rpc(base, token, insecureTls, "session/prompt", new JSONObject().put("request", request));
                success(callback, new JSONObject().put("ok", true));
            } catch (Exception error) {
                failure(callback, friendly(error));
            }
        });
    }

    /** 停止生成。 */
    void cancel(final String base, final String token, final boolean insecureTls, final String sessionId,
                final Callback callback) {
        pool.execute(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("sessionId", sessionId);
                rpc(base, token, insecureTls, "session/cancel", new JSONObject().put("request", request));
                success(callback, new JSONObject().put("ok", true));
            } catch (Exception error) {
                failure(callback, friendly(error));
            }
        });
    }

    /** 新建会话。 */
    void create(final String base, final String token, final boolean insecureTls, final Callback callback) {
        pool.execute(() -> {
            try {
                JSONObject value = rpc(base, token, insecureTls, "session/create",
                        new JSONObject().put("request", new JSONObject()));
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("sessionId", State.textOf(value, "sessionId"));
                success(callback, out);
            } catch (Exception error) {
                failure(callback, friendly(error));
            }
        });
    }

    /** 切换模型与推理强度。 */
    void selectModel(final String base, final String token, final boolean insecureTls, final String sessionId,
                     final String provider, final String model, final String effort, final Callback callback) {
        pool.execute(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("sessionId", sessionId);
                request.put("provider", provider);
                request.put("model", model);
                if (effort != null && !effort.isEmpty()) request.put("reasoningEffort", effort);
                JSONObject value = rpc(base, token, insecureTls, "session/selectModel",
                        new JSONObject().put("request", request));
                JSONObject selected = value.optJSONObject("selected");
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("provider", selected == null ? provider : State.textOf(selected, "provider"));
                out.put("model", selected == null ? model : State.textOf(selected, "model"));
                out.put("effort", selected == null ? effort : State.textOf(selected, "reasoningEffort"));
                success(callback, out);
            } catch (Exception error) {
                failure(callback, friendly(error));
            }
        });
    }

    /** 模型目录：与 /api/models 一致。 */
    void models(final String base, final String token, final boolean insecureTls, final Callback callback) {
        pool.execute(() -> {
            try {
                JSONObject catalog = rpc(base, token, insecureTls, "session/modelCatalog", new JSONObject());
                JSONObject normalized = State.normalizeModelCatalog(catalog);
                normalized.put("ok", true);
                success(callback, normalized);
            } catch (Exception error) {
                failure(callback, friendly(error));
            }
        });
    }

    /**
     * 回答提问。
     *
     * 两条通道（与中继保持一致，顺序很重要）：
     * 1. 问题仍在前台等待时，DSH 只认 $events/result 回填——它按 clientId + eventId
     *    关联，完全不需要 callId。这条通道需要一条活着的事件流。
     * 2. 问题已超时挂起（continued）时，用 RPC userQuestions/answer 补答。
     *
     * @param eventId 前台提问的 eventId；空串表示走 RPC 通道
     */
    void answer(final String base, final String token, final boolean insecureTls, final String sessionId,
                final String callId, final String eventId, final JSONArray answers, final Callback callback) {
        pool.execute(() -> {
            try {
                if (!eventId.isEmpty() && events != null) {
                    try {
                        events.resolve(base, token, insecureTls, eventId, answers);
                        JSONObject out = new JSONObject();
                        out.put("ok", true);
                        out.put("via", "events");
                        success(callback, out);
                        return;
                    } catch (Exception error) {
                        // 事件通道失败就退回 RPC 通道，不让用户白点。
                    }
                }
                if (callId.isEmpty()) {
                    failure(callback, "这个提问已经失效，请在电脑上重新提问");
                    return;
                }
                JSONObject args = new JSONObject();
                args.put("agentId", sessionId);
                args.put("callId", callId);
                args.put("answer", new JSONObject().put("answers", answers));
                JSONObject accepted = rpc(base, token, insecureTls, "userQuestions/answer", args);
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("via", "rpc");
                out.put("accepted", accepted.optBoolean("value", false));
                success(callback, out);
            } catch (Exception error) {
                failure(callback, friendly(error));
            }
        });
    }

    /**
     * 读一个会话里的文件（用 workspaceFiles/read）。
     *
     * DSH 要求 workspaceFileScope；手表只拿到会话 id 与路径，因此这里用
     * `workspaceFileScopeId = sessionId` 让 Host 自己配作用域（实测可行）。
     */
    void readFile(final String base, final String token, final boolean insecureTls, final String sessionId,
                  final String path, final Callback callback) {
        pool.execute(() -> {
            try {
                JSONObject args = new JSONObject();
                args.put("workspaceFileScopeId", sessionId);
                args.put("path", path);
                args.put("range", new JSONObject().put("offset", 1).put("limit", 500));
                JSONObject value = rpc(base, token, insecureTls, "workspaceFiles/read", args);
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("kind", kindOf(path));
                out.put("language", languageOf(path));
                out.put("text", State.textOf(value, "text"));
                out.put("truncated", !value.optBoolean("eof", true));
                success(callback, out);
            } catch (Exception error) {
                failure(callback, friendly(error));
            }
        });
    }

    // ---------------------------------------------------------------- 事件流

    /**
     * 开始接收 DSH 的事件流（$events）。
     *
     * 用途只有一个：**发现前台正在等待回答的提问**。
     * 投影（session/projections）里只能看到超时挂起的 continued 问题；
     * 前台等待中的 open 问题不会进投影，只会通过 waterfall 帧推给已连接的客户端，
     * 而且必须用同一条流的 clientId 才能把答案回填回去。
     *
     * @param onQuestion 收到新提问时回调（主线程）
     */
    void startEvents(final String base, final String token, final boolean insecureTls,
                     final Runnable onQuestion, final Runnable onState) {
        stopEvents();
        events = new Events(base, token, insecureTls, onQuestion, onState);
        events.start();
    }

    void stopEvents() {
        if (events != null) {
            events.stop();
            events = null;
        }
    }

    boolean eventsReady() {
        return events != null && events.ready;
    }

    // ---------------------------------------------------------------- 内部

    /** 统一入口：必要时重新兑换 cookie，再发 RPC。 */
    /**
     * 去掉 base 末尾的斜杠。
     *
     * 这个细节很容易踩：Endpoint.url("") 会拼出 `https://host/`（以 / 结尾），
     * 再拼 `/api/...` 就变成 `https://host//api/...`。DSH 用的是精确路径匹配，
     * 双斜杠会直接返回 405，并且错误文案看起来像“服务器没起来”，非常误导。
     */
    static String trimBase(String base) {
        if (base == null) return "";
        String value = base;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private JSONObject rpc(String rawBase, String token, boolean insecureTls, String method, JSONObject args)
            throws Exception {
        String base = trimBase(rawBase);
        try {
            adoptSaved(base);
            link.ensureCookie(base, token, insecureTls);
            persistCookie(base, token);
            return link.rpc(base, method, args, insecureTls);
        } catch (DshLink.UnauthorizedException error) {
            // 票失效（签名密钥变了或过期）：丢掉存档、重新兑换一次再试。
            link.reset();
            if (store != null) store.clearSession();
            link.ensureCookie(base, token, insecureTls);
            persistCookie(base, token);
            return link.rpc(base, method, args, insecureTls);
        }
    }

    /**
     * 如果存档里的 cookie 还匹配当前地址且没过期，就直接用它。
     *
     * 这样 DSH 重启（token 变了）也不影响手表：cookie 是 DSH 签发的长期凭据，
     * 签名密钥持久化在 .credentials.yaml 里，重启不变。
     */
    private void adoptSaved(String base) {
        if (store == null) return;
        String authority = DshLink.authorityOf(base);
        Store.Session saved = store.session();
        if (!saved.usable(authority)) return;
        link.adopt(saved.cookie, authority, saved.expiresAt);
    }

    /** 把刚拿到的 cookie 存下来（下次启动直接用，不必再兑换）。 */
    private void persistCookie(String base, String token) {
        if (store == null) return;
        String cookie = link.cachedCookie();
        if (cookie.isEmpty()) return;
        store.setSession(cookie, DshLink.authorityOf(base), link.cachedExpiresAt(), token);
    }

    private static JSONObject listArgs() {
        JSONObject args = new JSONObject();
        try {
            args.put("_request", new JSONObject());
        } catch (Exception ignored) {
            // 不会失败。
        }
        return args;
    }

    /**
     * 跨会话汇总待答问题（列表页首屏的提示用）。
     *
     * 两条来源必须合并，缺一不可：
     * - **投影**里的（多为超时挂起的 continued，也可能是 open）——只有它带得出题目正文；
     * - **事件流**推来的前台 open 提问——这类根本没进投影，但手表能拿到内容并能应答。
     *
     * 只为「有提问」的会话拉投影：几十个会话全拉一遍会很慢，而等待中的通常只有一两个。
     */
    private JSONArray pendingOf(String base, String token, boolean insecureTls, List<JSONObject> visible)
            throws Exception {
        JSONArray out = new JSONArray();
        java.util.Map<String, JSONObject> merged = new java.util.LinkedHashMap<>();

        for (JSONObject session : visible) {
            if (!session.optBoolean("waiting", false)) continue;
            String sessionId = State.textOf(session, "id");
            String title = State.textOf(session, "title");
            JSONObject projection;
            try {
                projection = rpc(base, token, insecureTls, "session/projections",
                        new JSONObject().put("request", new JSONObject().put("sessionId", sessionId)));
            } catch (Exception error) {
                continue;
            }
            JSONObject values = projection.optJSONObject("values");
            JSONObject questions = values == null ? null : values.optJSONObject("userQuestions");
            JSONArray active = questions == null ? null : questions.optJSONArray("active");
            if (active == null) continue;
            for (int i = 0; i < active.length(); i++) {
                JSONObject item = active.optJSONObject(i);
                String callId = State.textOf(item, "callId");
                JSONArray compact = State.compactQuestions(item.optJSONArray("questions"));
                if (compact.length() == 0) continue;
                JSONObject ask = new JSONObject();
                ask.put("sessionId", sessionId);
                ask.put("sessionTitle", title);
                ask.put("callId", callId);
                ask.put("eventId", events == null ? "" : events.eventIdFor(callId));
                ask.put("state", State.textOf(item, "state"));
                ask.put("questions", compact);
                merged.put(callId.isEmpty() ? sessionId : callId, ask);
            }
        }

        // 事件流里的前台提问：只补投影里还没有的那些。
        if (events != null) {
            for (JSONObject session : visible) {
                String sessionId = State.textOf(session, "id");
                JSONObject ask = events.pendingAskFor(sessionId, State.textOf(session, "title"));
                if (ask == null) continue;
                String key = State.textOf(ask, "callId");
                if (key.isEmpty()) key = sessionId;
                if (merged.containsKey(key)) continue;
                merged.put(key, ask);
            }
        }
        for (JSONObject ask : merged.values()) out.put(ask);
        return out;
    }
}
