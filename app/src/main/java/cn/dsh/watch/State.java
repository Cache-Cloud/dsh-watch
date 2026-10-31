package cn.dsh.watch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据归一化层：把 DSH 的原始会话数据压成手表能直接渲染的紧凑结构。
 *
 * 这一层原来跑在电脑上的中继程序里。既然改成手表直连 DSH，它就必须搬进 App：
 * 手表屏幕只有 233dp 见方，而 `session/page` 的原始响应可达数百 KB（含大量流式
 * 分片与工具 diff），不压缩根本渲染不动、也很费电。
 *
 * 归一化规则（与中继时期保持一致，均基于实测的数据形状）：
 * - 只保留人类写的 user/message：DSH 会把时间上下文、记忆快照、系统指令也写成
 *   user/message，直接渲染会淹没真实对话；
 * - 同一步里的多条 assistant/message 合并成一条（正文 + 去重后的工具名）；
 * - reasoning 块单独成「思考」条目，正文里不混入；
 * - tool/result、step/*、turn/* 不进入消息列表。
 *
 * 输出结构与旧中继的 /api/* 完全一致，因此界面层（MainActivity/Api）不需要跟着改。
 */
final class State {
    /** 单条消息正文上限：再长在手表上也没人读完，还会把内存拉满。 */
    private static final int MAX_TEXT_CHARS = 4_000;
    /** 会话列表上限。 */
    static final int MAX_SESSIONS = 60;
    /** 步骤里单条文本上限。 */
    private static final int MAX_STEP_TEXT = 1_200;

    private static final String SUBAGENT_ORIGIN = "subagent";

    /** 工具名 → 手表上的短标签。 */
    private static final Map<String, String> TOOL_LABELS = new LinkedHashMap<>();
    static {
        TOOL_LABELS.put("pwsh", "命令");
        TOOL_LABELS.put("bash", "命令");
        TOOL_LABELS.put("read", "读文件");
        TOOL_LABELS.put("edit", "改文件");
        TOOL_LABELS.put("write", "写文件");
        TOOL_LABELS.put("grep", "搜索");
        TOOL_LABELS.put("glob", "找文件");
        TOOL_LABELS.put("skill", "技能");
        TOOL_LABELS.put("subagent", "子任务");
        TOOL_LABELS.put("subagent_fork", "子任务");
        TOOL_LABELS.put("web_search", "联网");
        TOOL_LABELS.put("web_fetch", "联网");
        TOOL_LABELS.put("generate_image", "画图");
        TOOL_LABELS.put("edit_image", "改图");
        TOOL_LABELS.put("ask_user_question", "提问");
        TOOL_LABELS.put("update_goal", "目标");
        TOOL_LABELS.put("todo_write", "清单");
        TOOL_LABELS.put("computer_use", "操作电脑");
        TOOL_LABELS.put("android_tap", "操作手机");
        TOOL_LABELS.put("android_screenshot", "手机截屏");
        TOOL_LABELS.put("present", "交付");
    }

    private State() {}

    // ------------------------------------------------------------------ 基础工具

    static String textOf(JSONObject object, String key) {
        if (object == null) return "";
        String value = object.optString(key, "");
        return value == null ? "" : value;
    }

    /** 截断并标注省略。 */
    static String truncate(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    /** 从 content 数组里抽纯文本（text 块）。 */
    static String textOfContent(JSONArray content) {
        if (content == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            if (!"text".equals(textOf(block, "type"))) continue;
            String text = textOf(block, "text");
            if (text.isEmpty()) continue;
            if (builder.length() > 0) builder.append('\n');
            builder.append(text);
        }
        return builder.toString().trim();
    }

    /** 从 content 数组里抽 reasoning（思考）文本，多段合并。 */
    static String reasoningOfContent(JSONArray content) {
        if (content == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            if (!"reasoning".equals(textOf(block, "type"))) continue;
            String text = textOf(block, "text");
            if (text.isEmpty()) continue;
            if (builder.length() > 0) builder.append('\n');
            builder.append(text);
        }
        return builder.toString().trim();
    }

    /** 从 content 数组里抽工具调用名（去重、保序，转成中文短标签）。 */
    static List<String> toolsOfContent(JSONArray content) {
        List<String> names = new ArrayList<>();
        if (content == null) return names;
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            if (!"tool-call".equals(textOf(block, "type"))) continue;
            String name = textOf(block, "name");
            String label = name.isEmpty() ? "工具" : (TOOL_LABELS.containsKey(name) ? TOOL_LABELS.get(name) : name);
            if (!names.contains(label)) names.add(label);
        }
        return names;
    }

    /**
     * 判断一条 user/message 是否来自真人。
     *
     * 只有 source.kind 为 user / user-question-reply 的才是对话内容；
     * 没有 source 的老格式退回按 role 判断，避免漏掉真实消息。
     */
    static boolean isHumanMessage(JSONObject data) {
        JSONObject source = data == null ? null : data.optJSONObject("source");
        String kind = source == null ? "" : textOf(source, "kind");
        if (source == null || kind.isEmpty()) {
            JSONObject message = data == null ? null : data.optJSONObject("message");
            return "user".equals(textOf(message, "role")) || "user".equals(textOf(data, "role"));
        }
        return "user".equals(kind) || "user-question-reply".equals(kind);
    }

    // ------------------------------------------------------------------ 会话列表

    /**
     * 从会话摘要里取出待答问题（每个会话可能有不止一条）。
     *
     * `session/list` 的 projections 里就带 userQuestions，因此不必单独拉一次投影
     * 就能知道哪个会话在等人回答。state 为 open（前台等待）或 continued（已超时
     * 挂起）都要保留——两者应答通道不同。
     */
    static List<JSONObject> activeQuestionsOf(JSONObject item) {
        List<JSONObject> result = new ArrayList<>();
        JSONObject values = item == null ? null : item.optJSONObject("projections");
        values = values == null ? null : values.optJSONObject("values");
        JSONObject questions = values == null ? null : values.optJSONObject("userQuestions");
        JSONArray active = questions == null ? null : questions.optJSONArray("active");
        if (active == null) return result;
        for (int i = 0; i < active.length(); i++) {
            JSONObject entry = active.optJSONObject(i);
            if (entry == null) continue;
            if (compactQuestions(entry.optJSONArray("questions")).length() == 0) continue;
            result.add(entry);
        }
        return result;
    }

    /**
     * 把 `session/list` 的返回压成会话列表。
     *
     * @param includeSubagents 是否包含子代理会话
     * @param limit            返回上限，0 表示不截断（截断必须发生在过滤之后，
     *                         否则更新时间很新的几百条子代理会把父会话挤出去）
     */
    static List<JSONObject> normalizeSessionList(JSONObject value, boolean includeSubagents, int limit) {
        List<JSONObject> sessions = new ArrayList<>();
        JSONArray items = value == null ? null : value.optJSONArray("items");
        if (items == null) return sessions;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String origin = textOf(item, "origin");
            boolean isSubagent = SUBAGENT_ORIGIN.equals(origin);
            if (isSubagent && !includeSubagents) continue;

            JSONObject projections = item.optJSONObject("projections");
            JSONObject values = projections == null ? null : projections.optJSONObject("values");
            if (values == null) values = new JSONObject();

            String cwd = textOf(item, "cwd");
            JSONObject modelSelection = values.optJSONObject("modelSelection");
            if (modelSelection == null) modelSelection = new JSONObject();
            JSONObject model = modelSelection.optJSONObject("next");
            if (model == null) model = modelSelection.optJSONObject("lastUsed");

            JSONObject identity = values.optJSONObject("subagent");
            JSONObject timing = values.optJSONObject("subagentTiming");
            if (timing == null) timing = new JSONObject();
            JSONObject stats = values.optJSONObject("sessionStats");

            String title = textOf(values, "title").trim();
            if (title.isEmpty()) title = cwd.isEmpty() ? "未命名会话" : projectNameOf(cwd);

            JSONObject session = new JSONObject();
            try {
                session.put("id", textOf(item, "sessionId"));
                session.put("title", title);
                session.put("running", item.optBoolean("running", false));
                session.put("blank", item.optBoolean("blank", false));
                session.put("waiting", activeQuestionsOf(item).size() > 0);
                session.put("updatedAt", item.optLong("updatedAt", 0L));
                session.put("seq", projections == null ? 0L : projections.optLong("asOfSeq", 0L));
                session.put("subagent", isSubagent);
                session.put("parentId", textOf(item, "parentSessionId"));
                session.put("subagentLabel", identity == null ? "" : textOf(identity, "label"));
                session.put("subagentMode", identity == null ? "" : textOf(identity, "mode"));
                session.put("agentAvailable", item.optBoolean("agentAvailable", false));
                session.put("settledMs", numberOr(timing.opt("settledMs")));
                // active 存在（哪怕是 null）就表示这个子代理此刻仍在跑。
                session.put("active", timing.has("active"));
                session.put("completed", timing.optBoolean("lastTurnCompleted", false));
                session.put("turns", stats == null ? 0L : numberOr(stats.opt("turns")));
                session.put("steps", stats == null ? 0L : numberOr(stats.opt("steps")));
                session.put("cwd", cwd);
                session.put("project", projectNameOf(cwd));
                session.put("projectPath", cwd);
                session.put("provider", model == null ? "" : textOf(model, "provider"));
                session.put("model", model == null ? "" : textOf(model, "model"));
                session.put("effort", model == null ? "" : textOf(model, "reasoningEffort"));
            } catch (Exception ignored) {
                // 全都是字符串/布尔/数字，不会失败。
            }
            sessions.add(session);
        }
        // 按更新时间倒序。
        java.util.Collections.sort(sessions, (left, right) ->
                Long.compare(right.optLong("updatedAt", 0L), left.optLong("updatedAt", 0L)));
        if (limit > 0 && sessions.size() > limit) return new ArrayList<>(sessions.subList(0, limit));
        return sessions;
    }

    /** 数字兜底：非有限数一律按 0，避免手表端拿到 null 崩掉。 */
    static long numberOr(Object value) {
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isNaN(number) && !Double.isInfinite(number)) return (long) number;
        }
        return 0L;
    }

    /** 项目的显示名：取工作目录最后一段；盘符根目录没有信息量时退回完整路径。 */
    static String projectNameOf(String cwd) {
        if (cwd == null || cwd.trim().isEmpty()) return "未分组";
        String[] parts = cwd.split("[\\\\/]+");
        String last = "";
        for (String part : parts) {
            if (!part.isEmpty()) last = part;
        }
        if (last.isEmpty() || last.endsWith(":")) return cwd;
        return last;
    }

    /** 子代理状态：running / done / idle。 */
    static String stateOf(JSONObject session) {
        if (session.optBoolean("running", false)) return "running";
        if (session.optBoolean("completed", false)) return "done";
        if (session.optBoolean("agentAvailable", false)) return "idle";
        return "done";
    }

    /** 某个父会话派生的子任务列表。 */
    static JSONObject subagentsOf(List<JSONObject> sessions, String parentId) {
        List<JSONObject> list = new ArrayList<>();
        for (JSONObject session : sessions) {
            if (!parentId.equals(textOf(session, "parentId"))) continue;
            String label = textOf(session, "subagentLabel");
            if (label.isEmpty()) label = textOf(session, "title");
            if (label.isEmpty()) label = "子任务";
            JSONObject entry = new JSONObject();
            try {
                entry.put("id", textOf(session, "id"));
                entry.put("label", label);
                entry.put("title", textOf(session, "title"));
                entry.put("mode", textOf(session, "subagentMode"));
                entry.put("state", stateOf(session));
                entry.put("running", session.optBoolean("running", false));
                entry.put("settledMs", session.optLong("settledMs", 0L));
                entry.put("active", session.optBoolean("active", false));
                entry.put("completed", session.optBoolean("completed", false));
                entry.put("updatedAt", session.optLong("updatedAt", 0L));
                entry.put("turns", session.optLong("turns", 0L));
                entry.put("steps", session.optLong("steps", 0L));
            } catch (Exception ignored) {
                // 同上。
            }
            list.add(entry);
        }
        java.util.Collections.sort(list, (left, right) -> {
            boolean leftRunning = left.optBoolean("running", false);
            boolean rightRunning = right.optBoolean("running", false);
            if (leftRunning != rightRunning) return leftRunning ? -1 : 1;
            return Long.compare(right.optLong("updatedAt", 0L), left.optLong("updatedAt", 0L));
        });
        JSONObject root = new JSONObject();
        try {
            root.put("ok", true);
            root.put("parentId", parentId);
            root.put("subagents", new JSONArray(list));
        } catch (Exception ignored) {
            // 不会失败。
        }
        return root;
    }

    /** 把子任务数量补回可见会话上（列表里显示「N 个子任务在跑」）。 */
    static void attachSubagentCounts(List<JSONObject> all, List<JSONObject> sessions) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (JSONObject item : all) {
            if (!item.optBoolean("subagent", false)) continue;
            String parentId = textOf(item, "parentId");
            if (parentId.isEmpty()) continue;
            int[] entry = counts.get(parentId);
            if (entry == null) {
                entry = new int[]{0, 0};
                counts.put(parentId, entry);
            }
            entry[0] += 1;
            if (item.optBoolean("running", false)) entry[1] += 1;
        }
        for (JSONObject session : sessions) {
            int[] entry = counts.get(textOf(session, "id"));
            try {
                session.put("subagentCount", entry == null ? 0 : entry[0]);
                session.put("subagentRunning", entry == null ? 0 : entry[1]);
            } catch (Exception ignored) {
                // 不会失败。
            }
        }
    }

    /** 按项目（工作目录）折叠：首屏先看有哪些项目，点进去再看会话。 */
    static JSONArray groupByProject(List<JSONObject> sessions) {
        Map<String, JSONObject> groups = new LinkedHashMap<>();
        for (JSONObject session : sessions) {
            String project = textOf(session, "project");
            if (project.isEmpty()) project = "未分组";
            JSONObject group = groups.get(project);
            if (group == null) {
                group = new JSONObject();
                try {
                    group.put("project", project);
                    group.put("path", textOf(session, "projectPath"));
                    group.put("sessions", new JSONArray());
                    group.put("running", 0);
                    group.put("waiting", 0);
                    group.put("updatedAt", 0L);
                } catch (Exception ignored) {
                    // 不会失败。
                }
                groups.put(project, group);
            }
            JSONArray inner = group.optJSONArray("sessions");
            inner.put(session);
            try {
                if (session.optBoolean("running", false)) group.put("running", group.optInt("running", 0) + 1);
                if (session.optBoolean("waiting", false)) group.put("waiting", group.optInt("waiting", 0) + 1);
                if (session.optLong("updatedAt", 0L) > group.optLong("updatedAt", 0L)) {
                    group.put("updatedAt", session.optLong("updatedAt", 0L));
                }
            } catch (Exception ignored) {
                // 不会失败。
            }
        }
        List<JSONObject> out = new ArrayList<>(groups.values());
        for (JSONObject group : out) {
            List<JSONObject> inner = jsonToList(group.optJSONArray("sessions"));
            java.util.Collections.sort(inner, (left, right) ->
                    Long.compare(right.optLong("updatedAt", 0L), left.optLong("updatedAt", 0L)));
            try {
                group.put("sessions", new JSONArray(inner));
            } catch (Exception ignored) {
                // 不会失败。
            }
        }
        java.util.Collections.sort(out, (left, right) ->
                Long.compare(right.optLong("updatedAt", 0L), left.optLong("updatedAt", 0L)));
        return new JSONArray(out);
    }

    private static List<JSONObject> jsonToList(JSONArray array) {
        List<JSONObject> list = new ArrayList<>();
        if (array == null) return list;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) list.add(item);
        }
        return list;
    }

    // ------------------------------------------------------------------ 消息与步骤

    /**
     * 把 `session/page` 的 records 归一化为消息列表。
     *
     * 与 {@link #normalizeSteps} 的区别：这里只关心「谁说了什么」，工具调用只留名字，
     * 供列表页的摘要用。
     */
    static JSONArray normalizeMessages(JSONArray records) {
        JSONArray messages = new JSONArray();
        JSONObject pending = null;
        long pendingSeq = 0L;
        long pendingTime = 0L;
        StringBuilder pendingText = new StringBuilder();
        List<String> pendingTools = new ArrayList<>();

        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            JSONObject event = record == null ? null : record.optJSONObject("event");
            if (event == null) continue;
            long seq = event.optLong("seq", 0L);
            long time = event.optLong("time", 0L);
            JSONObject data = event.optJSONObject("data");
            if (data == null) data = new JSONObject();
            String type = textOf(event, "type");

            if ("user/message".equals(type)) {
                if (!isHumanMessage(data)) continue;
                // 用户消息是天然的分隔：先把累积的助手内容收掉。
                pending = flushMessage(messages, pending, pendingText, pendingTools);
                pendingText.setLength(0);
                pendingTools.clear();
                JSONObject message = data.optJSONObject("message");
                if (message == null) message = data;
                String text = textOfContent(message.optJSONArray("content"));
                if (text.isEmpty()) continue;
                pendingSeq = seq;
                pendingTime = time;
                JSONObject item = new JSONObject();
                try {
                    item.put("seq", seq);
                    item.put("role", "user");
                    item.put("text", truncate(text, MAX_TEXT_CHARS));
                    item.put("tools", new JSONArray());
                    item.put("time", time);
                } catch (Exception ignored) {
                    // 不会失败。
                }
                messages.put(item);
                continue;
            }

            if ("assistant/message".equals(type)) {
                JSONObject message = data.optJSONObject("message");
                if (message == null) message = new JSONObject();
                JSONArray content = message.optJSONArray("content");
                String text = textOfContent(content);
                List<String> tools = toolsOfContent(content);
                if (pending == null) {
                    pending = new JSONObject();
                    pendingSeq = seq;
                    pendingTime = time;
                }
                if (!text.isEmpty()) {
                    if (pendingText.length() > 0) pendingText.append("\n\n");
                    pendingText.append(text);
                }
                for (String tool : tools) {
                    if (!pendingTools.contains(tool)) pendingTools.add(tool);
                }
                pendingSeq = seq;
                pendingTime = time;
                continue;
            }

            if ("turn/end".equals(type)) {
                pending = flushMessage(messages, pending, pendingText, pendingTools);
                pendingText.setLength(0);
                pendingTools.clear();
            }
        }
        flushMessage(messages, pending, pendingText, pendingTools);
        return messages;
    }

    private static JSONObject flushMessage(JSONArray messages, JSONObject pending, StringBuilder text,
                                           List<String> tools) {
        if (pending == null) return null;
        String body = text.toString().trim();
        if (body.isEmpty() && tools.isEmpty()) return null;
        try {
            pending.put("text", truncate(body, MAX_TEXT_CHARS));
            pending.put("tools", new JSONArray(tools));
            messages.put(pending);
        } catch (Exception ignored) {
            // 不会失败。
        }
        return null;
    }

    /**
     * 把会话事件压成「步骤」序列，尽量还原电脑上的观感。
     *
     * 与中继时期的实现相比多了一件事：把 reasoning 块单独成 `kind=thinking` 的条目。
     * 手表界面本来就按 thinking 这个 kind 折叠展示（见 renderSteps），中继没给就等于
     * 把思考全丢了；直连之后我们拿得到，就该给出来。
     *
     * @param records `session/page` 的 records
     * @param maxText 单条文本上限
     */
    static JSONArray normalizeSteps(JSONArray records, int maxText) {
        JSONArray steps = new JSONArray();
        Map<String, JSONObject> pendingTools = new LinkedHashMap<>();
        JSONObject pending = null;
        StringBuilder pendingText = new StringBuilder();
        List<String> pendingTools_ = new ArrayList<>();
        long pendingSeq = 0L;
        long pendingTime = 0L;

        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            JSONObject event = record == null ? null : record.optJSONObject("event");
            if (event == null) continue;
            long seq = event.optLong("seq", 0L);
            long time = event.optLong("time", 0L);
            JSONObject data = event.optJSONObject("data");
            if (data == null) data = new JSONObject();
            String type = textOf(event, "type");

            if ("user/message".equals(type)) {
                if (!isHumanMessage(data)) continue;
                flushStep(steps, pending, pendingText, pendingTools_);
                pending = null;
                pendingText.setLength(0);
                pendingTools_.clear();
                JSONObject message = data.optJSONObject("message");
                if (message == null) message = data;
                String text = textOfContent(message.optJSONArray("content"));
                if (text.isEmpty()) continue;
                steps.put(stepMessage(seq, time, "user", text, maxText, null));
                continue;
            }

            if ("assistant/message".equals(type)) {
                JSONObject message = data.optJSONObject("message");
                if (message == null) message = new JSONObject();
                JSONArray content = message.optJSONArray("content");
                String text = textOfContent(content);
                String reasoning = reasoningOfContent(content);
                if (pending == null) {
                    pending = new JSONObject();
                    pendingSeq = seq;
                    pendingTime = time;
                }
                if (!text.isEmpty()) {
                    if (pendingText.length() > 0) pendingText.append("\n\n");
                    pendingText.append(text);
                }
                pendingSeq = seq;
                pendingTime = time;

                // 思考单独成条：排在正文之前，与电脑上的顺序一致。
                if (!reasoning.isEmpty()) {
                    steps.put(stepThinking(seq, time, reasoning, maxText));
                }

                JSONArray calls = toolCallsOf(content);
                for (int c = 0; c < calls.length(); c++) {
                    JSONObject call = calls.optJSONObject(c);
                    if (call == null) continue;
                    flushStep(steps, pending, pendingText, pendingTools_);
                    pending = null;
                    pendingText.setLength(0);
                    pendingTools_.clear();
                    String name = textOf(call, "name");
                    if (name.isEmpty()) name = "工具";
                    String label = TOOL_LABELS.containsKey(name) ? TOOL_LABELS.get(name) : name;
                    JSONObject tool = new JSONObject();
                    try {
                        tool.put("kind", "tool");
                        tool.put("seq", seq);
                        tool.put("time", time);
                        tool.put("name", name);
                        tool.put("label", label);
                        tool.put("detail", describeToolCall(name, call.opt("arguments")));
                        tool.put("status", "running");
                        tool.put("result", "");
                        tool.put("files", new JSONArray());
                        tool.put("isError", false);
                    } catch (Exception ignored) {
                        // 不会失败。
                    }
                    steps.put(tool);
                    String callId = textOf(call, "id");
                    if (!callId.isEmpty()) pendingTools.put(callId, tool);
                }
                continue;
            }

            if ("tool/result".equals(type)) {
                JSONObject message = data.optJSONObject("message");
                if (message == null) message = new JSONObject();
                String callId = textOf(message, "toolCallId");
                flushStep(steps, pending, pendingText, pendingTools_);
                pending = null;
                pendingText.setLength(0);
                pendingTools_.clear();

                JSONArray content = message.optJSONArray("content");
                String text = textOfContent(content);
                JSONObject meta = data.optJSONObject("meta");
                JSONArray diffs = meta == null ? null : meta.optJSONArray("diffs");
                List<String> files = new ArrayList<>();
                if (diffs != null) {
                    for (int d = 0; d < diffs.length(); d++) {
                        JSONObject diff = diffs.optJSONObject(d);
                        String path = diff == null ? "" : textOf(diff, "path");
                        if (!path.isEmpty()) files.add(path);
                    }
                }
                boolean error = message.optBoolean("isError", false);
                JSONObject tool = pendingTools.get(callId);
                if (tool == null) {
                    // 没有对应调用（例如从中间翻页）：单独成一条，信息不丢。
                    JSONObject orphan = new JSONObject();
                    try {
                        orphan.put("kind", "tool");
                        orphan.put("seq", seq);
                        orphan.put("time", time);
                        orphan.put("name", "");
                        orphan.put("label", "工具结果");
                        orphan.put("detail", "");
                        orphan.put("status", error ? "error" : "done");
                        orphan.put("result", clip(text, maxText));
                        orphan.put("files", new JSONArray(files));
                        orphan.put("isError", error);
                    } catch (Exception ignored) {
                        // 不会失败。
                    }
                    steps.put(orphan);
                    continue;
                }
                try {
                    tool.put("status", error ? "error" : "done");
                    tool.put("result", clip(text, maxText));
                    tool.put("isError", error);
                    tool.put("files", new JSONArray(files));
                } catch (Exception ignored) {
                    // 不会失败。
                }
                continue;
            }

            if ("turn/end".equals(type)) {
                flushStep(steps, pending, pendingText, pendingTools_);
                pending = null;
                pendingText.setLength(0);
                pendingTools_.clear();
            }
        }
        flushStep(steps, pending, pendingText, pendingTools_);
        return steps;
    }

    private static JSONArray toolCallsOf(JSONArray content) {
        JSONArray calls = new JSONArray();
        if (content == null) return calls;
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            if ("tool-call".equals(textOf(block, "type"))) calls.put(block);
        }
        return calls;
    }

    private static JSONObject stepMessage(long seq, long time, String role, String text, int maxText,
                                          JSONArray tools) {
        JSONObject step = new JSONObject();
        try {
            step.put("kind", "message");
            step.put("seq", seq);
            step.put("time", time);
            step.put("role", role);
            step.put("text", clip(text, maxText));
            step.put("tools", tools == null ? new JSONArray() : tools);
        } catch (Exception ignored) {
            // 不会失败。
        }
        return step;
    }

    private static JSONObject stepThinking(long seq, long time, String reasoning, int maxText) {
        JSONObject step = new JSONObject();
        try {
            step.put("kind", "thinking");
            step.put("seq", seq);
            step.put("time", time);
            step.put("role", "assistant");
            step.put("text", "");
            step.put("thinking", clip(reasoning, maxText));
            step.put("tools", new JSONArray());
        } catch (Exception ignored) {
            // 不会失败。
        }
        return step;
    }

    private static void flushStep(JSONArray steps, JSONObject pending, StringBuilder text, List<String> tools) {
        if (pending == null) return;
        String body = text.toString().trim();
        if (body.isEmpty() && tools.isEmpty()) return;
        try {
            pending.put("text", clip(body, MAX_STEP_TEXT));
            pending.put("tools", new JSONArray(tools));
            steps.put(pending);
        } catch (Exception ignored) {
            // 不会失败。
        }
    }

    /** 截断长文本并标注省略。 */
    static String clip(String text, int limit) {
        if (text == null) return "";
        String value = text.trim();
        return value.length() <= limit ? value : value.substring(0, limit) + "…（已省略）";
    }

    /**
     * 从工具参数里抽出最有信息量的一句说明。
     *
     * 手表上不可能展示完整 JSON，因此按工具类型挑关键字段：
     * 命令类给命令，文件类给路径，搜索类给关键词。
     */
    static String describeToolCall(String name, Object rawArguments) {
        Object args = rawArguments;
        if (rawArguments instanceof String) {
            try {
                args = new JSONObject((String) rawArguments);
            } catch (Exception error) {
                return clipOneLine((String) rawArguments, 120);
            }
        }
        if (!(args instanceof JSONObject)) return "";
        JSONObject object = (JSONObject) args;
        String[] order = {"description", "file_path", "path", "notebook_path",
                "command", "pattern", "query", "url", "prompt", "name", "subagent_type", "skill"};
        for (String key : order) {
            String value = textOf(object, key);
            if (!value.trim().isEmpty()) {
                return clipOneLine(value, "command".equals(key) ? 160 : 120);
            }
        }
        return clipOneLine(object.toString(), 100);
    }

    /** 压成单行短文本。 */
    static String clipOneLine(String text, int limit) {
        if (text == null) return "";
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    // ------------------------------------------------------------------ 用量与模型

    /** 会话的用量与运行统计。格式化留给手表端（不同语言单位写法不同）。 */
    static JSONObject normalizeUsage(JSONObject values) {
        JSONObject tokens = values == null ? null : values.optJSONObject("tokenUsage");
        if (tokens == null) tokens = new JSONObject();
        JSONObject pressure = values == null ? null : values.optJSONObject("contextPressure");
        if (pressure == null) pressure = new JSONObject();
        JSONObject breakdown = values == null ? null : values.optJSONObject("contextBreakdown");
        if (breakdown == null) breakdown = new JSONObject();
        JSONObject stats = values == null ? null : values.optJSONObject("sessionStats");
        if (stats == null) stats = new JSONObject();
        JSONObject selection = values == null ? null : values.optJSONObject("modelSelection");
        JSONObject next = selection == null ? null : selection.optJSONObject("next");
        if (next == null) next = new JSONObject();

        long contextWindow = numberOr(pressure.opt("contextWindow"));
        long pressureTokens = numberOr(pressure.opt("pressureTokens"));
        JSONObject usage = new JSONObject();
        try {
            usage.put("inputTokens", numberOr(tokens.opt("uncachedInputTokens")));
            usage.put("outputTokens", numberOr(tokens.opt("outputTokens")));
            usage.put("cacheReadTokens", numberOr(tokens.opt("cacheReadTokens")));
            usage.put("cacheWriteTokens", numberOr(tokens.opt("cacheWriteTokens")));
            usage.put("pressureTokens", pressureTokens);
            usage.put("projectedTokens", numberOr(pressure.opt("projectedTokens")));
            usage.put("contextWindow", contextWindow);
            usage.put("percent", contextWindow > 0
                    ? Math.round((double) pressureTokens / contextWindow * 1000d) / 10d : 0d);
            usage.put("systemTokens", numberOr(breakdown.opt("systemTokens")));
            usage.put("toolsTokens", numberOr(breakdown.opt("toolsTokens")));
            usage.put("messageTokens", numberOr(breakdown.opt("messageTokens")));
            usage.put("turns", numberOr(stats.opt("turns")));
            usage.put("steps", numberOr(stats.opt("steps")));
            usage.put("llmMs", numberOr(stats.opt("llmMs")));
            usage.put("toolMs", numberOr(stats.opt("toolMs")));
            usage.put("ttftMs", numberOr(stats.opt("ttftMs")));
            usage.put("provider", textOf(next, "provider"));
            usage.put("model", textOf(next, "model"));
            usage.put("effort", textOf(next, "reasoningEffort"));
            usage.put("agentPreset", textOf(values, "agentPreset"));
        } catch (Exception ignored) {
            // 不会失败。
        }
        return usage;
    }

    /** 把 `session/modelCatalog` 压成供应商 → 模型 → 推理档位。 */
    static JSONObject normalizeModelCatalog(JSONObject catalog) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray groups = catalog == null ? null : catalog.optJSONArray("groups");
        JSONArray routableRaw = catalog == null ? null : catalog.optJSONArray("routableProviders");
        List<String> routable = new ArrayList<>();
        if (routableRaw != null) {
            for (int i = 0; i < routableRaw.length(); i++) {
                String value = routableRaw.optString(i, "");
                if (!value.isEmpty()) routable.add(value);
            }
        }
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.optJSONObject(i);
                if (group == null) continue;
                String provider = textOf(group, "id");
                if (provider.isEmpty()) continue;
                if (!routable.isEmpty() && !routable.contains(provider)) continue;

                List<JSONObject> models = new ArrayList<>();
                JSONArray rawModels = group.optJSONArray("models");
                if (rawModels != null) {
                    for (int m = 0; m < rawModels.length(); m++) {
                        JSONObject model = rawModels.optJSONObject(m);
                        if (model == null) continue;
                        String id = textOf(model, "id");
                        if (id.isEmpty()) continue;
                        String name = textOf(model, "name");
                        JSONObject reasoning = model.optJSONObject("reasoning");
                        JSONArray effortsRaw = reasoning == null ? null : reasoning.optJSONArray("efforts");
                        List<String> efforts = new ArrayList<>();
                        if (effortsRaw != null) {
                            for (int e = 0; e < effortsRaw.length(); e++) {
                                JSONObject effort = effortsRaw.optJSONObject(e);
                                String effortId = effort == null ? "" : textOf(effort, "id");
                                if (!effortId.isEmpty()) efforts.add(effortId);
                            }
                        }
                        JSONObject entry = new JSONObject();
                        try {
                            entry.put("id", id);
                            entry.put("name", name.isEmpty() ? id : name);
                            entry.put("efforts", new JSONArray(efforts));
                        } catch (Exception ignored) {
                            // 不会失败。
                        }
                        models.add(entry);
                    }
                }
                if (models.isEmpty()) continue;
                String groupName = textOf(group, "name");
                JSONObject entry = new JSONObject();
                try {
                    entry.put("provider", provider);
                    entry.put("name", groupName.isEmpty() ? provider : groupName);
                    entry.put("models", new JSONArray(models));
                } catch (Exception ignored) {
                    // 不会失败。
                }
                out.add(entry);
            }
        }
        JSONObject result = new JSONObject();
        JSONObject def = catalog == null ? null : catalog.optJSONObject("default");
        if (def == null) def = new JSONObject();
        JSONObject defaultEntry = new JSONObject();
        try {
            defaultEntry.put("provider", textOf(def, "provider"));
            defaultEntry.put("model", textOf(def, "model"));
            defaultEntry.put("effort", textOf(def, "reasoningEffort"));
            result.put("groups", new JSONArray(out));
            result.put("default", defaultEntry);
        } catch (Exception ignored) {
            // 不会失败。
        }
        return result;
    }

    // ------------------------------------------------------------------ 计划与任务

    /**
     * 会话的「计划与任务」。
     *
     * 四块来源不同、含义也不同：
     * - todos：todo_write 产生的待办清单，会逐项勾选；
     * - mode：只是「计划模式」的开关状态，没有正文；
     * - team：Agent Teams 的成员与任务板（谁负责、什么状态、被谁阻塞）；
     * - goal：长目标（objective + phase），只在有目标时存在。
     */
    static JSONObject normalizePlan(JSONObject values) {
        JSONArray todosRaw = values == null ? null : values.optJSONArray("todos");
        List<JSONObject> todos = new ArrayList<>();
        if (todosRaw != null) {
            for (int i = 0; i < todosRaw.length(); i++) {
                JSONObject item = todosRaw.optJSONObject(i);
                if (item == null) continue;
                String content = textOf(item, "content");
                if (content.isEmpty()) continue;
                JSONObject todo = new JSONObject();
                try {
                    todo.put("content", content);
                    String status = textOf(item, "status");
                    todo.put("status", status.isEmpty() ? "pending" : status);
                } catch (Exception ignored) {
                    // 不会失败。
                }
                todos.add(todo);
            }
        }

        JSONObject teamRaw = values == null ? null : values.optJSONObject("agentTeam");
        List<JSONObject> members = new ArrayList<>();
        List<JSONObject> tasks = new ArrayList<>();
        if (teamRaw != null) {
            JSONArray membersRaw = teamRaw.optJSONArray("members");
            if (membersRaw != null) {
                for (int i = 0; i < membersRaw.length(); i++) {
                    JSONObject item = membersRaw.optJSONObject(i);
                    if (item == null) continue;
                    String name = textOf(item, "name");
                    if (name.isEmpty()) continue;
                    JSONObject member = new JSONObject();
                    try {
                        member.put("name", name);
                        String role = textOf(item, "role");
                        member.put("role", role.isEmpty() ? "teammate" : role);
                        String phase = textOf(item, "phase");
                        member.put("phase", phase.isEmpty() ? "active" : phase);
                        member.put("error", textOf(item, "error"));
                    } catch (Exception ignored) {
                        // 不会失败。
                    }
                    members.add(member);
                }
            }
            JSONArray tasksRaw = teamRaw.optJSONArray("tasks");
            if (tasksRaw != null) {
                for (int i = 0; i < tasksRaw.length(); i++) {
                    JSONObject item = tasksRaw.optJSONObject(i);
                    if (item == null) continue;
                    if ("deleted".equals(textOf(item, "status"))) continue;
                    String subject = textOf(item, "subject");
                    if (subject.isEmpty()) continue;
                    JSONObject task = new JSONObject();
                    try {
                        task.put("id", textOf(item, "id"));
                        task.put("subject", subject);
                        String status = textOf(item, "status");
                        task.put("status", status.isEmpty() ? "pending" : status);
                        task.put("owner", textOf(item, "ownerName"));
                        task.put("ready", item.optBoolean("ready", false));
                        task.put("blockedBy", item.optJSONArray("blockedBy") == null
                                ? new JSONArray() : item.optJSONArray("blockedBy"));
                    } catch (Exception ignored) {
                        // 不会失败。
                    }
                    tasks.add(task);
                }
            }
        }

        JSONObject plan = new JSONObject();
        try {
            plan.put("todos", new JSONArray(todos));
            JSONObject mode = new JSONObject();
            JSONObject planMode = values == null ? null : values.optJSONObject("plan");
            mode.put("active", planMode != null && planMode.optBoolean("active", false));
            mode.put("pending", planMode != null && planMode.optBoolean("pending", false));
            plan.put("mode", mode);
            JSONObject team = new JSONObject();
            team.put("members", new JSONArray(members));
            team.put("tasks", new JSONArray(tasks));
            plan.put("team", team);

            JSONObject goalRaw = values == null ? null : values.optJSONObject("goal");
            JSONObject goalInner = goalRaw == null ? null : goalRaw.optJSONObject("goal");
            if (goalInner != null) {
                JSONObject goal = new JSONObject();
                goal.put("objective", textOf(goalInner, "objective"));
                String phase = textOf(goalInner, "phase");
                goal.put("phase", phase.isEmpty() ? "active" : phase);
                goal.put("rounds", numberOr(goalRaw.opt("roundsStarted")));
                goal.put("maxRounds", numberOr(goalInner.opt("maxGoalRounds")));
                goal.put("blockedReason", textOf(goalInner, "blockedReason"));
                plan.put("goal", goal);
            }

            JSONObject identity = values == null ? null : values.optJSONObject("subagent");
            if (identity != null) {
                JSONObject subagent = new JSONObject();
                subagent.put("label", textOf(identity, "label"));
                subagent.put("mode", textOf(identity, "mode"));
                plan.put("subagent", subagent);
            }
        } catch (Exception ignored) {
            // 不会失败。
        }
        return plan;
    }

    // ------------------------------------------------------------------ 提问

    /**
     * 提问内容的紧凑形式。
     *
     * 注意：DSH 的前台提问帧只带 eventId + questions，没有 callId；
     * 超时挂起的提问（continued）才在投影里带 callId。
     */
    static JSONArray compactQuestions(JSONArray questions) {
        JSONArray out = new JSONArray();
        if (questions == null) return out;
        for (int i = 0; i < questions.length(); i++) {
            JSONObject entry = questions.optJSONObject(i);
            if (entry == null) continue;
            String id = textOf(entry, "id");
            if (id.isEmpty()) continue;
            JSONArray optionsRaw = entry.optJSONArray("options");
            List<JSONObject> options = new ArrayList<>();
            if (optionsRaw != null) {
                for (int j = 0; j < optionsRaw.length(); j++) {
                    JSONObject option = optionsRaw.optJSONObject(j);
                    if (option == null) continue;
                    String label = textOf(option, "label");
                    if (label.isEmpty()) continue;
                    JSONObject compact = new JSONObject();
                    try {
                        compact.put("label", label);
                        compact.put("description", textOf(option, "description"));
                    } catch (Exception ignored) {
                        // 不会失败。
                    }
                    options.add(compact);
                }
            }
            JSONObject question = new JSONObject();
            try {
                question.put("id", id);
                question.put("header", textOf(entry, "header"));
                question.put("question", textOf(entry, "question"));
                question.put("multiSelect", entry.optBoolean("multiSelect", false));
                question.put("options", new JSONArray(options));
            } catch (Exception ignored) {
                // 不会失败。
            }
            out.put(question);
        }
        return out;
    }

    /** 记录里最早的 seq，手表用它继续往前翻历史。 */
    static long earliestSeqOf(JSONArray records) {
        long earliest = 0L;
        if (records == null) return 0L;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            JSONObject event = record == null ? null : record.optJSONObject("event");
            if (event == null) continue;
            long seq = event.optLong("seq", 0L);
            if (seq > 0 && (earliest == 0L || seq < earliest)) earliest = seq;
        }
        return earliest;
    }
}
