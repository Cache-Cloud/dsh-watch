# DSH 手表 · 直连版

在智能手表上查看和操作**服务器上**的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)。

这是 [DING-HAO-RAN/dsh-watch](https://github.com/DING-HAO-RAN/dsh-watch) 的一个分支，为「DSH 常驻在服务器上」这一场景重写：

- **手表直连 DSH**，不需要电脑上跑中继程序
- **只需要输入一条带 token 的网址**，不需要配对码、不需要局域网发现
- **可以用 GitHub Actions 构建**，不需要本机装 Android SDK / JDK

## 它做了什么

原版的手表 App 连的是「电脑上运行的中继」，中继再去连本机的 DSH。中继存在的理由有两个：

1. DSH 的 `/api` 只认它自己签发的签名 cookie，手表无法自行鉴权；
2. 一次会话的原始响应有几百 KB，手表收不下也没必要收。

本分支把这两件事都搬到了手表端自己解决：

**鉴权**：DSH 有一个公开的兑换入口——访问 `GET /?token=<launch token>` 会返回 303，
并下发一张 `dsh-auth-…` 的会话 cookie（默认有效期 10 年）。也就是说：

```
带 token 的网址  ──一次兑换──▶  长期会话 cookie  ──反复使用──▶  全部 RPC / 事件流
```

token 只在第一次用，之后手表自己保存 cookie。地址存在手表本地，重启也不用再输入。

**裁剪**：原来的归一化逻辑（`relay/src/state.mjs`）原样搬进了 App（`State.java`），
在手表本地把原始事件流压成紧凑的消息与步骤列表。输出的 JSON 结构与旧中继完全一致。

```
┌─────────────┐   带 cookie 的 HTTP/WS    ┌─────────────┐
│  手表端应用  │ ────────────────────────▶ │     DSH     │
│ (Android)   │   /api/<ns>/<method>      │ (服务器上)  │
│             │ ◀──────────────────────── │             │
└─────────────┘   /api/remote.mux ($events)└─────────────┘
```

## 功能

- 按**项目**浏览会话，点进去看对话正文
- 展开**工具调用**，看执行了什么命令、读了什么文件、返回了什么结果
- 查看**思考内容**（reasoning），折叠成独立条目（原版中继没有提供这一块）
- **发消息**、停止生成、往前翻历史
- 回答 AI 的提问（单选、多选、打字都支持）——前台等待中的提问通过 DSH 的事件流实时收到
- 切换**模型**与推理强度，查看 **token 用量**与上下文占用
- 看**子智能体**：谁被派出去干活、在跑还是做完了
- 看**计划与任务**：待办清单、团队任务板、长目标进展
- 查看会话里出现过的**文件**内容

开发环境为 OPPO Watch（Android 11，466×466 圆屏）。最低支持 Android 8.0（API 26）。

## 安装

### 方式 A：GitHub Actions 构建（推荐，零本地环境）

1. Fork 本仓库到你自己的 GitHub 账号
2. 打开 **Actions** → 左侧 **Build APK** → **Run workflow**
3. 跑完后在该次运行的 **Artifacts** 里下载 `dsh-watch-apk`

产物是一个已签名的 APK，用 adb 或手表的安装器装上即可：

```bash
adb install -r DSH手表.apk
```

> APK 用仓库里自带的**固定调试密钥**（`keystore/dsh-watch.jks`）签名，
> 因此每次构建出的包都能覆盖安装、平滑升级。这是一把公开的侧载用密钥，
> 想换成自己的见 [`keystore/README.md`](keystore/README.md)——
> 换密钥后需先卸载旧版再装。

### 方式 B：本机构建

需要 JDK 17（或 11）与约 350 MB 的 Android SDK 组件：

```bash
# 1. 取最小 SDK（腾讯云镜像；脚本也会回退到 Google 官方源）
scripts/fetch-sdk.sh --dest ./sdk

# 2. 出包（产物在 dist/DSH手表.apk）
scripts/build-apk.sh --sdk ./sdk --java-home "$JAVA_HOME" --output ./dist
```

这套脚本刻意不依赖 Gradle：只有十来个 Java 文件与两个资源目录，
用 `aapt2 + javac + d8 + zipalign + apksigner` 就能出包，漏洞点少、也快。

## 首次使用

1. 在服务器上像平常一样启动 DSH：

   ```bash
   dsh web --host 127.0.0.1 --port 3377
   ```

   它会打印一条形如 `http://127.0.0.1:3377/?token=xxxx` 的地址。

2. **手表要能访问到这个地址**。如果 DSH 只监听 `127.0.0.1`（默认如此），
   需要一层穿透。两种常见做法：
   - 用 Cloudflare Tunnel（或其它反代）把 `3377` 映射到一个公网域名；
   - 或让 DSH 监听内网地址（注意：`--host 0.0.0.0` 官方不支持，请配合反代使用）。

   > **重要**：走公网域名时，要把它加进 DSH 的 `trustedHosts`，否则请求会被 403 拒绝。
   > 在 profile 的 `cordis.patch.yml` 里加：

   ```yaml
   - id: connection
     name: "@deepseek-ai/dsh-client-connection"
     config:
       trustedHosts:
         - your-domain.example.com
   ```

3. 把手表能访问的那条**带 `?token=` 的完整地址**粘进 App 设置页，点「连接」。

   token 与域名必须配套：token 是每次启动 DSH 时新生成的，公网地址敲错会被判为未授权。

4. 连上后 App 会保存地址与 token，之后启动直接进会话列表。

## 用 adb 免输入（可选）

手表上敲长网址很痛苦，可以直接从电脑推过去：

```bash
adb shell am start -n cn.dsh.watch/.MainActivity \
  --es url "https://your-domain.example.com/?token=xxxxxxxx"
```

App 会预填设置页（不会自动连接，仍需点一下「连接」确认）。

## 安全说明

- **token 等于登录凭据**。任何拿到「带 token 网址」的人都能换到一张长效 cookie，
  进而完全控制你的 DSH。请当作密码对待，不要贴进聊天记录或截图。
- App 把 token 存在手表的私有 `SharedPreferences` 里，是为了 DSH 重启后能重新兑换。
- 公网访问一律用 `https://`。明文 HTTP 下凭据会在链路上裸奔。
- App 默认放行任意 TLS 证书（手表上装证书很麻烦）。这意味着它防不住中间人攻击，
  只适合连接你自己的服务器。
- 想立刻吊销：在 DSH 侧删除 `.dsh/.credentials.yaml` 里的 `client-connection/browser-session`
  记录并重启 DSH，所有已签发的 cookie 立即失效（token 也会随重启更换）。

## 与原版的差异

| | 原版 | 本分支 |
|---|---|---|
| 电脑端 | 必须运行中继（exe 或 node） | 不需要 |
| 连接方式 | 配对码 / 局域网广播发现 | 粘贴一条带 token 的网址 |
| 鉴权 | 中继读本机凭据自签 cookie | 手表用 token 兑换 cookie |
| 数据裁剪 | 中继服务端裁剪 | 手表本地裁剪 |
| 新增功能 | — | 思考内容、文件内容查看 |
| 构建 | PowerShell + Android SDK | GitHub Actions / bash + Android SDK |

原版的桌面中继、`desktop/`、`relay/` 等目录在本分支中不再使用，保留以便对照。

## 目录

```
app/                    手表 App 源码
  src/main/java/cn/dsh/watch/
    MainActivity.java   界面与页面流转
    Direct.java         直连数据层（对外接口与旧中继一致）
    DshLink.java        鉴权（token→cookie）与一元 RPC
    Ws.java             极简 WebSocket 客户端（RFC 6455）
    Events.java         DSH 事件流（$events）：实时提问与回填答案
    State.java          数据归一化（原 relay/src/state.mjs 的移植）
    Store.java          本地存储（地址 + token + 外观偏好）
    Api.java            JSON → 界面模型
scripts/
  fetch-sdk.sh          下载最小 Android SDK
  build-apk.sh          构建并签名 APK
.github/workflows/
  build-apk.yml         GitHub Actions：云端构建
```

## 许可

MIT，与原仓库一致。原版版权归 [DING-HAO-RAN](https://github.com/DING-HAO-RAN)。
