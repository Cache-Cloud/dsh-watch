package cn.dsh.watch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
 import java.util.Map;

/**
 * DSH 的事件流（$events）客户端。
 *
 * 为什么必须有它：**前台正在等待回答的提问不会进投影**，只通过 waterfall 帧推给
 * 已连接的客户端。手表要「在手表上回答 AI 的提问」，就必须：
 * 1. 连上 /api/remote.mux 并打开 $events 逻辑流；
 * 2. 收到 `type=waterfall, event=user-questions/request` 时记下 eventId 与题目；
 * 3. 回答时用 **同一条流的 clientId** 调 `$events/result` 回填。
 *
 * 另一个好处：DSH 在客户端重连时会把**所有仍待处理的 waterfall 事件重放**一遍，
 * 因此「手表刚打开才收到提问」也能拿到——重连本身就是一次补齐遗漏的机会。
 */
final class Events implements Runnable {
    /** 待处理提问的保留窗口：超过就没有应答价值了。 */
    private static final long WINDOW_MS = 30L * 60 * 1000;

    private final String base;
    private final String token;
    private final boolean insecureTls;
    private final Runnable onQuestion;
    private final Runnable onState;

    private volatile boolean running;
    /** DSH 分配的 clientId；应答 $events/result 时必须带上它。 */
    volatile String clientId = "";
    /** 事件流是否已经握手完成（列表页据此知道「实时提问可用」）。 */
    volatile boolean ready;

    /** eventId / callId → 提问记录（保持插入顺序，便于取最近一条）。 */
    private final Map<String, JSONObject> pending = new LinkedHashMap<>();
    private Thread thread;
    private Ws socket;

    Events(String base, String token, boolean insecureTls, Runnable onQuestion, Runnable onState) {
        this.base = base;
        this.token = token;
        this.insecureTls = insecureTls;
        this.onQuestion = onQuestion;
        this.onState = onState;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "dsh-events");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        Ws current = socket;
        socket = null;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) { }
        }
        if (thread != null) {
            try { thread.interrupt(); } catch (Exception ignored) { }
            thread = null;
        }
    }

    @Override
    public void run() {
        long backoff = 1_000L;
        while (running) {
            try {
                open();
                backoff = 1_000L;
                ready = true;
                notifyState();
                loop();
            } catch (Exception error) {
                // 任何异常都退回到重连：手表上的网络本来就不稳。
            } finally {
                ready = false;
                clientId = "";
                notifyState();
                Ws current = socket;
                socket = null;
                if (current != null) {
                    try { current.close(); } catch (Exception ignored) { }
                }
            }
            if (!running) break;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException interrupt) {
                break;
            }
            backoff = Math.min(backoff * 2, 15_000L);
        }
    }

    /** 建立 WebSocket 并打开 $events 逻辑流。 */
    private void open() throws Exception {
        // 每次连接都要确保 cookie 是新的（DSH 可能重启换密钥）。
        String cookie = newCookie();
        Ws candidate = Ws.connect(DshLink.muxUrl(base), cookie, insecureTls);
        socket = candidate;
        JSONObject open = new JSONObject();
        open.put("type", "open");
        open.put("streamId", "events");
        open.put("endpoint", "$events");
        open.put("payload", new JSONObject().put("args", new JSONObject()));
        candidate.sendText(open.toString());
    }

    /** 主循环：读帧并分发。 */
    private void loop() throws Exception {
        Ws current = socket;
        while (running && current != null) {
            String text = current.readText();
            if (text == null) return;
            handle(text);
        }
    }

    private void handle(String text) {
        JSONObject frame;
        try {
            frame = new JSONObject(text);
        } catch (Exception ignored) {
            return;
        }
        if (!"item".equals(State.textOf(frame, "type"))) return;
        JSONObject value = frame.optJSONObject("value");
        if (value == null) return;
        String type = State.textOf(value, "type");

        if ("ready".equals(type)) {
            clientId = State.textOf(value, "clientId");
            notifyState();
            return;
        }

        if ("waterfall".equals(type)) {
            String event = State.textOf(value, "event");
            if ("user-questions/request".equals(event)) {
                onQuestionFrame(value);
            } else {
                // 其它 waterfall 是别的插件在等人，手表不处理。
            }
            return;
        }

        if ("cancel".equals(type)) {
            String eventId = State.textOf(value, "eventId");
            if (!eventId.isEmpty()) {
                List<String> victims = new ArrayList<>();
                for (Map.Entry<String, JSONObject> entry : pending.entrySet()) {
                    if (eventId.equals(State.textOf(entry.getValue(), "eventId"))) victims.add(entry.getKey());
                }
                for (String key : victims) pending.remove(key);
                notifyState();
            }
        }
    }

    /** 收到一个提问帧：记下它，并通知界面上新提示。 */
    private void onQuestionFrame(JSONObject frame) {
        JSONObject request = frame.optJSONObject("request");
        if (request == null) return;
        String eventId = State.textOf(frame, "eventId");
        String callId = State.textOf(request, "callId");
        // 前台提问帧通常不带 callId，用 eventId 兼作标识（应答时 DSH 只认 eventId）。
        String key = callId.isEmpty() ? eventId : callId;
        if (key.isEmpty()) return;

        JSONObject record = new JSONObject();
        try {
            record.put("eventId", eventId);
            record.put("callId", callId);
            record.put("sessionId", State.textOf(frame, "agentId"));
            record.put("questions", request.optJSONArray("questions") == null
                    ? new JSONArray() : request.optJSONArray("questions"));
            record.put("at", System.currentTimeMillis());
        } catch (Exception ignored) {
            // 不会失败。
        }
        synchronized (pending) {
            pending.put(key, record);
            prune();
        }
        notifyState();
        if (onQuestion != null) onQuestion.run();
    }

    private void prune() {
        List<String> stale = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, JSONObject> entry : pending.entrySet()) {
            if (now - entry.getValue().optLong("at", now) > WINDOW_MS) stale.add(entry.getKey());
        }
        for (String key : stale) pending.remove(key);
        // 与中继一致：最多留 20 条，避免手表内存被刷爆。
        while (pending.size() > 20) {
            String first = pending.keySet().iterator().next();
            pending.remove(first);
        }
    }

    /** 查询某个会话当前的前台提问（列表页用）。 */
    JSONObject pendingAskFor(String sessionId, String sessionTitle) {
        synchronized (pending) {
            for (JSONObject record : pending.values()) {
                if (!sessionId.equals(State.textOf(record, "sessionId"))) continue;
                JSONArray questions = State.compactQuestions(record.optJSONArray("questions"));
                if (questions.length() == 0) continue;
                JSONObject ask = new JSONObject();
                try {
                    ask.put("sessionId", sessionId);
                    ask.put("sessionTitle", sessionTitle);
                    ask.put("callId", State.textOf(record, "callId"));
                    ask.put("eventId", State.textOf(record, "eventId"));
                    ask.put("state", "open");
                    ask.put("questions", questions);
                } catch (Exception ignored) {
                    // 不会失败。
                }
                return ask;
            }
        }
        return null;
    }

    /** 某个会话的提问（会话详情页用）。 */
    JSONObject questionForSession(String sessionId) {
        synchronized (pending) {
            for (JSONObject record : pending.values()) {
                if (!sessionId.equals(State.textOf(record, "sessionId"))) continue;
                JSONArray questions = State.compactQuestions(record.optJSONArray("questions"));
                if (questions.length() == 0) continue;
                JSONObject out = new JSONObject();
                try {
                    out.put("callId", State.textOf(record, "callId"));
                    out.put("eventId", State.textOf(record, "eventId"));
                    out.put("state", "open");
                    out.put("questions", questions);
                } catch (Exception ignored) {
                    // 不会失败。
                }
                return out;
            }
        }
        return null;
    }

    /** callId → eventId 反查（投影里的问题需要它才能走前台应答通道）。 */
    String eventIdFor(String callId) {
        if (callId.isEmpty()) return "";
        synchronized (pending) {
            JSONObject record = pending.get(callId);
            if (record == null) {
                for (JSONObject item : pending.values()) {
                    if (callId.equals(State.textOf(item, "callId"))) {
                        record = item;
                        break;
                    }
                }
            }
            return record == null ? "" : State.textOf(record, "eventId");
        }
    }

    /**
     * 回填一条 waterfall 的结果（回答前台提问）。
     *
     * 必须用这条流自己的 clientId：DSH 会把回复投递给「拥有该事件的那条流」。
     */
    void resolve(String base, String token, boolean insecureTls, String eventId, JSONArray answers)
            throws Exception {
        if (clientId.isEmpty()) throw new Exception("事件流尚未就绪");
        // 复用 Direct 的 RPC 通道不合适（它会另建连接），这里直接用当前 socket 的票。
        JSONObject value = new JSONObject();
        value.put("answers", answers);
        JSONObject outcome = new JSONObject();
        outcome.put("kind", "result");
        outcome.put("value", value);

        JSONObject args = new JSONObject();
        args.put("clientId", clientId);
        args.put("eventId", eventId);
        args.put("outcome", outcome);

        DshLink helper = new DshLink();
        helper.token = token;
        helper.ensureCookie(base, token, insecureTls);
        helper.rpc(base, "$events/result", args, insecureTls);

        // 应答成功后从待处理列表移除。
        synchronized (pending) {
            List<String> victims = new ArrayList<>();
            for (Map.Entry<String, JSONObject> entry : pending.entrySet()) {
                if (eventId.equals(State.textOf(entry.getValue(), "eventId"))) victims.add(entry.getKey());
            }
            for (String key : victims) pending.remove(key);
        }
        notifyState();
    }

    private String newCookie() throws Exception {
        DshLink helper = new DshLink();
        helper.token = token;
        return helper.ensureCookie(base, token, insecureTls);
    }

    private void notifyState() {
        if (onState != null) onState.run();
    }
}
