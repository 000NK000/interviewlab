# InterviewLab

A locally runnable collaborative coding and mock interview platform built with Spring Boot, Vue, WebSocket, and Docker. It supports shared editing, Java/Python judging, interview feedback, and optional local AI assistants. See the local quickstart below and the [reproducible load tests and measured results](docs/benchmark.md).

一个可本地运行的实时协作编程与模拟面试平台。面试官创建房间并分享邀请链接；候选人加入后可共同编辑 Java/Python 代码、运行自定义测试、查看代码历史，结束后由面试官提交评分与反馈。

## 已实现

- 邮箱和密码注册/登录；BCrypt 密码哈希、HttpOnly 且 SameSite=Strict 的会话 Cookie。
- 面试官/候选人房间权限、邀请令牌哈希存储、重新生成邀请（旧链接随即失效）。
- Monaco + WebSocket 双方编辑及光标提示；版本号校验、冲突草稿保留、断线重连。
- 每次被接受的编辑保存为修订，可拖动时间线回放。
- PostgreSQL/H2 数据库持久化判题任务；事务容量锁、租约领取与心跳，worker 崩溃后到期重试。API 和 worker 可分进程运行；状态通过 WebSocket 推送。
- `Idempotency-Key` 可避免网络重试重复提交；同一 key 携带不同代码或用例返回 409。
- Java 17/Python 3.12 Docker 判题；禁网、只读根文件系统、非 root 用户、CPU/内存/进程数/输出量与超时限制，运行后清理容器。
- 9 道固定题目均有题意说明、stdin/stdout 格式、样例和后端额外测试；每道题提供 Java/Python 方法模板，新建房间时自动加载，旧房间可点 `Load template`。模板中的 `Main`/`__main__` 只负责判题器的输入输出，练习时实现 `Solution`（或 `MedianFinder`）即可；本地 AI 离线也能手动选题。
- H2 本地数据库（关闭延迟写入）；可切换 PostgreSQL。JUnit 集成测试、Prometheus 指标与 GitHub Actions 构建。
- 可选的本地 AI：按难度从固定题库选题、三阶段提示、针对当前代码追问、代码评审、依据文字转录/笔记评价沟通，并保存结果历史。

## 本地 AI

本项目通过本机 Ollama 的 HTTP API 调用模型，不需要云端 API key。本机已验证默认模型 qwen3.5:9b-q8_0。先确认 Ollama 正在运行且模型已下载；没有模型时可执行：

~~~powershell
ollama pull qwen3.5:9b-q8_0
ollama list
~~~

若命令行找不到 ollama，可以从安装目录运行它；API 默认地址为 http://127.0.0.1:11434。可以用 OLLAMA_URL 和 OLLAMA_MODEL 环境变量切换地址与已安装模型。

建房页的 Local AI 区域可根据难度和题型选择一道固定题库中的题。面试房间下方的 Interview assistants 区域提供三个角色：

- Interviewer：现场按当前代码提问；提示最多三次，依次从概念、数据结构/不变量到实现步骤。
- Code reviewer：评估正确性、时间和空间复杂度、边界条件及代码质量；只把与当前代码完全匹配的判题结果当作测试证据。面试进行中仅面试官能查看评审。
- Communication evaluator：面试结束后，粘贴**已在本地转录的语音文字**或手写笔记，评价需求澄清、思路表达、测试意识和协作。应用本身目前不录音或做语音转录。

所有 AI 请求从后端发往配置的 Ollama 地址，结果保存在本地数据库的 ai_events 表中。每次只运行一个模型生成任务；繁忙时 API 返回 429，可稍后重试。沟通评分只反映输入文字里记录到的证据，未记录的口头回答不能当作已发生；使用者仍需对照原笔记核实模型结论。AI 评价只是练习反馈，不能代替真实面试官或完整测试。

## 本地启动（Windows PowerShell）

需要 Java 17、Maven 3.6+、Node.js 22+、Docker Desktop。先启动 Docker Desktop，并预拉取运行时镜像：

~~~powershell
docker pull python:3.12-alpine
docker pull eclipse-temurin:17-jdk-alpine
~~~

终端一：

~~~powershell
cd backend
mvn spring-boot:run
~~~

终端二：

~~~powershell
cd frontend
npm ci
npm run dev
~~~

打开 http://localhost:15173。API 默认监听 18080；健康和 Prometheus 指标只在本机 18081 端口开放。若端口被占用，修改 PORT、METRICS_PORT、APP_ORIGIN、前端的 INTERVIEWLAB_API_TARGET，以及 vite.config.js 中的前端端口。首次使用先注册两个账户，面试官创建房间，把邀请链接发给另一个账户，候选人加入后面试官点击 Start interview。可离线选择题库题目，也可自行出题。代码需从标准输入读取，并把结果写到标准输出。重新生成邀请会使旧链接失效。

### PostgreSQL 模式

~~~powershell
docker compose up -d postgres
$env:DATABASE_URL='jdbc:postgresql://localhost:15432/interviewlab'
$env:DATABASE_USER='interviewlab'
$env:DATABASE_PASSWORD='interviewlab-local-only'
cd backend
mvn spring-boot:run
~~~

compose.yaml 仅启动数据库。判题服务在主机上执行 Docker CLI，因此后端按上述方式在主机运行；不要把 Docker socket 挂进对公网开放的 API 容器。Compose 文件的数据库口令仅用于本地开发。

### API 与判题 worker 分进程

PostgreSQL 启动后，在 API 终端设置 `JUDGE_WORKERS=0`，然后运行 `mvn spring-boot:run`。在另一个终端使用**同一组** `DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD`，再设置：

~~~powershell
$env:JUDGE_WORKERS='2'
$env:PORT='0'
$env:SERVER_ADDRESS='127.0.0.1'
$env:METRICS_PORT='0'
cd backend
mvn spring-boot:run
~~~

worker 的 HTTP 端口由系统随机分配且仅绑定本机。API 单实例保存登录会话；worker 只通过数据库领取任务。worker 租约为 10 秒，每 2 秒续约，崩溃后过期任务可由新 worker 重新领取。它是至少一次执行语义；旧容器仍可能在 worker 被强制终止后运行。生产部署时请让 worker 运行在独立受限主机/VM 上，且不把 Docker socket 暴露给 API。

## 验证

~~~powershell
cd backend
mvn test
cd ../frontend
npm run build -- --configLoader runner
~~~

端到端脚本见 scripts/smoke.ps1。先启动后端和 Docker，然后在项目根目录运行：

~~~powershell
pwsh -File scripts/smoke.ps1
~~~

脚本创建独立测试用户和房间，验证非参与者 403、旧版本 409，以及 Java/Python 判题、结束和反馈流程。`node scripts/curated-smoke.mjs` 另行验证题库样例和额外用例；它需要运行中的后端和 Docker。

### 可复现的并发与故障恢复压测

先构建后端 JAR，并确认 Docker Desktop 可用。压测脚本默认启动独立的 PostgreSQL 容器和后端进程，使用 18082/15434 端口和独立测试数据库，不连接正常的 18080 服务。它会并发提交判题任务、检查版本冲突，再强制终止测试后端并验证重启恢复。测试结束会停止自己启动的进程和容器；运行日志与原始结果保存在 `.benchmark-runs/`。

~~~powershell
cd backend
mvn -B verify
cd ..
node scripts/load-test.mjs --jobs=20 --recovery-jobs=12 --workers=2
~~~

需要 Node.js 22+ 和可用的 `postgres:16-alpine`、`python:3.12-alpine` 镜像。可通过 `--port`、`--postgres-port`、`--queue`、`--out` 调整参数；`--db=h2` 可单独复测 H2 模式；加 `--split-workers=true` 可验证分进程 worker 故障恢复。多房间 WebSocket 测试可运行 `mvn -B '-Dtest=MultiRoomLoadTest' test`。指标、实测数据和边界见 [docs/benchmark.md](docs/benchmark.md)。

扩容复测使用 64 个并发提交；API 与判题进程分离，单个判题 JVM 内配置 4 个 worker 线程。WebSocket 场景使用 100 个房间的 200 条同时在线模拟连接。完整命令、原始 JSON 和测试边界见 [docs/benchmark.md](docs/benchmark.md)。

## 核心接口

| 操作 | 接口 |
|---|---|
| 注册 / 登录 / 当前用户 | POST /api/auth/register, POST /api/auth/login, GET /api/auth/me |
| 建房 / 列表 / 详情 | POST /api/rooms, GET /api/rooms, GET /api/rooms/{id} |
| 加入 / 重新邀请 | POST /api/rooms/{id}/join, POST /api/rooms/{id}/invitation |
| 开始 / 结束 | POST /api/rooms/{id}/start, POST /api/rooms/{id}/end |
| 版本编辑 / 历史 | PUT /api/rooms/{id}/code, GET /api/rooms/{id}/revisions |
| 判题 / 反馈 | POST /api/rooms/{id}/submissions, GET /api/submissions/{id}, PUT /api/rooms/{id}/feedback |
| 固定题库 | GET /api/problems |
| 指标（本机独立端口） | GET /actuator/health, GET /actuator/prometheus |
| 实时协作 | WS /ws/rooms/{id} |
| 本地 AI 状态 / 选题 | GET /api/ai/status, POST /api/ai/problems |
| AI 提示 / 追问 / 评审 / 沟通评价 | POST /api/ai/rooms/{id}/hints, /questions, /review, /communication |
| AI 历史 | GET /api/ai/rooms/{id}/events |

## 架构与边界

~~~mermaid
flowchart LR
  Vue[Vue + Monaco] <-->|REST / WebSocket| API[Spring Boot]
  API --> DB[(H2 / PostgreSQL)]
  API --> DBQ[(Durable submission queue)]
  DBQ --> Workers[Leased judge workers]
  Workers --> Docker[Ephemeral Docker containers]
~~~

这是可实际使用的本地版本。数据库保存任务、领取租约及结果，支持 API 与判题 worker 分进程。网页登录会话仍在 API 进程内，API 重启后需重新登录；多个 API 副本之间尚无共享会话与 WebSocket 广播。当前 CI 负责测试与构建，尚未配置 AWS 发布流水线。音频转录、OAuth 和管理员后台尚未实现。额外题目测试只对客户端隐藏，题库源码公开，不能当作防作弊安全机制。

代码执行的容器限制适合本地练习与可信小范围试用；Docker 不是面向不可信公众的完整安全边界。公开部署前应采用独立判题主机/VM、最小权限、速率限制、审计与镜像固定摘要。生产环境需使用 HTTPS、设置 SECURE_COOKIE=true、将 APP_ORIGIN 设为实际域名，并改用 PostgreSQL。

部署拓扑、指标含义及公开部署前的剩余条件见 [docs/deployment.md](docs/deployment.md)。
