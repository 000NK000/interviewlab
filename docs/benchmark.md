# InterviewLab 并发与故障恢复验证

## 扩容复测（2026-09-23）

在同一台 Windows 本机上，把 PostgreSQL 判题场景扩大到 64 个同时发起的提交；API 与判题进程分离，单个判题 JVM 内配置 4 个 worker 线程。WebSocket 场景扩大到 100 个房间、200 个模拟客户端。判题程序仍先休眠 250 ms 再输出 `ok`；正式计时前顺序预热两次。API、worker 和 PostgreSQL 使用压测专属进程/容器与端口，不连接正常的 18080 服务。

| 场景 | 结果 | 本机测量 |
|---|---:|---:|
| PostgreSQL，独立 API 进程与单个判题 JVM（4 个 worker 线程），64 个并发提交 | 64/64 通过 | 完成吞吐 4.5 个/s；POST p50/p95 190.1/360 ms；完成 p50/p95 7.36/13.81 s |
| 强制终止独立 worker，再重启 | 16/16 通过 | 从终止到全部完成 17.65 s，其中脚本固定等待 worker 启动约 3.58 s |
| 100 房间、200 个同时在线的模拟 WebSocket 客户端 | 100/100 房间通过 | 每房间检查光标、编辑、旧版本冲突和重连；编辑广播 p50/p95 18/27 ms |

WebSocket 测试在所有房间的两条连接收到初始快照后使用同步屏障，确保 200 条连接**同时保持在线**，再并发执行操作。编辑延迟从候选端发送编辑到面试官端收到广播；这是一轮短时本机测试，不能推断长时间稳定连接、公网延迟或跨 API 实例扩容。判题吞吐和完成延迟按下文相同方法统计，包含 Docker 容器启动、250 ms 程序休眠与最多约 200 ms 的状态轮询误差。64 是提交数，不是 64 个同时运行的容器；正常批次观测到最多 4 个 `RUNNING`。

worker 崩溃后，数据库中旧租约的 `RUNNING` 记录可与新 worker 正在执行的记录暂时并存；恢复阶段轮询到的 `RUNNING` 峰值为 7，不等于 7 个新 worker。该系统提供至少一次执行与最终恢复，不能声称故障期间恰好执行一次。原始结果分别见 [`judge-load-results-64jobs.json`](judge-load-results-64jobs.json) 和 [`websocket-load-results-200clients.json`](websocket-load-results-200clients.json)。

复现命令（项目根目录，先构建 JAR 并确认 Docker Desktop 可用）：

```powershell
cd backend
mvn -B verify
cd ..
node scripts/load-test.mjs --db=postgres --split-workers=true --port=18084 --postgres-port=15436 --jobs=64 --recovery-jobs=16 --workers=4 --queue=128
cd backend
mvn -B '-Dtest=MultiRoomLoadTest' '-Dinterviewlab.ws.rooms=100' '-Dinterviewlab.ws.out=../docs/websocket-load-results-200clients.json' test
```

## 基线实测（2026-09-22）

2026-09-22 在 Windows 本机运行。机器为 Intel Core Ultra 9 285K、24 个逻辑处理器、31.3 GiB 内存；Java 17、Node 24、Docker Desktop、PostgreSQL 16。判题作业是 Python 程序，先休眠 250 ms 再输出 `ok`；故障恢复作业休眠 2 秒。每组判题在正式计时前进行了两次顺序预热。

| 场景 | 判题完成 | 完成吞吐 | POST p50/p95 | 完成 p50/p95 | 故障后完成 |
|---|---:|---:|---:|---:|---:|
| PostgreSQL，API 内含 2 个 worker | 20/20 | 2.2 个/s | 74.1/119.7 ms | 4.72/9.01 s | API 强制终止后 12/12 |
| PostgreSQL，API 与 2 个 worker 分进程 | 12/12 | 2.3 个/s | 45/82.6 ms | 2.67/5.25 s | 仅 worker 强制终止后 8/8 |
| H2 文件库，`WRITE_DELAY=0` | 8/8 | 2.2 个/s | 7.4/11.2 ms | 1.95/3.67 s | API 强制终止后 8/8 |

三组都进行了 8 个同版本并发编辑请求，结果是 1 个接受、7 个返回 409。两组 PostgreSQL 判题观测到最多 2 个 `RUNNING`，其余处于 `QUEUED`。分进程测试中 API 在 worker 被强制终止时仍可访问。PostgreSQL 数值来自加入全局容量事务锁后的最终版本；此前无事务容量锁的结果不与此混用。三组原始 JSON 分别见 [`interviewlab-final-postgres-results.json`](interviewlab-final-postgres-results.json)、[`interviewlab-final-split-results.json`](interviewlab-final-split-results.json)、[`interviewlab-h2-write-delay-0.json`](interviewlab-h2-write-delay-0.json)。

**测量方法：**POST 延迟从请求发出到收到成功响应；完成延迟从请求发出到每 200 ms 的 GET 轮询首次观察到终态。吞吐为完成任务数除以批次首个提交到全部观察到终态的时间。p50/p95 采用 nearest-rank。因此数值包含 Docker 启动、执行及最多约 200 ms 的观察误差。分进程测试的“worker 重启到可用”时间是启动后固定等待 3.5 秒的脚本阶段，不是主动探测的 readiness 指标。

## H2 故障复现与修复边界

早期 H2 文件模式使用默认延迟写入设置。强制终止后端后，曾出现已返回成功响应的 submission ID 在重启后返回 404；旧失败数据保留在 [`interviewlab-benchmark-h2-failure.json`](interviewlab-benchmark-h2-failure.json)。使用新数据库领取逻辑但仍保持默认写入延迟时，8 个恢复任务再次出现 404，记录在 [`interviewlab-h2-retest.json`](interviewlab-h2-retest.json)。把 H2 URL 改成 `WRITE_DELAY=0` 后，相同 8 任务场景 8/8 恢复。这个对照支持延迟写入是关键因素，但单次通过不等于 H2 对任意断电或磁盘故障具备生产级持久性；正式演示使用 PostgreSQL。

新队列不再把数据库中的任务复制到容量有限的内存队列。任务保留在数据库中，故障后由新 worker 在租约过期时领取，因此队列已满不会把已接受任务标成 `ERROR`。容量限制通过 `judge_queue_gate` 行锁串行检查；单元测试同时发起 8 次提交，在容量 2、无 worker 的配置下，精确得到 2 次接受和 6 次 429。

## 多房间 WebSocket 测试

`MultiRoomLoadTest` 在 H2 测试库中创建 8 个房间、16 个用户和 16 条 WebSocket 连接；并发进行每房间的光标传输、编辑、旧版本冲突与候选人断线重连。8/8 个房间均通过，编辑广播的本机 p50 为 8 ms、p95 为 11 ms。并发操作阶段耗时 160 ms，不含账户和房间准备。原始数据见 `docs/websocket-load-results.json`。这一早期测试没有连接同步屏障，不能单凭它证明 16 条连接在同一瞬间在线；上面的 200 连接扩容复测加入了同步屏障。这仅是本机测试，不代表公网或长时间连接可靠性。

固定题目端到端测试 `scripts/curated-smoke.mjs` 使用真实 Docker 容器验证 Two Sum：Python、Java 均为 3/3 用例通过（1 样例、2 个额外用例），重复发送相同 `Idempotency-Key` 得到相同 submission ID，且额外用例的输入和预期输出未出现在客户端结果中。数据见 `docs/curated-smoke-results.json`。

## 复现命令

从项目根目录执行，先确认 Docker Desktop 可用、镜像已拉取，且压测默认端口空闲：

```powershell
cd backend
mvn -B verify
cd ..
node scripts/load-test.mjs --db=postgres --port=18082 --postgres-port=15434 --jobs=20 --recovery-jobs=12 --workers=2 --queue=64
node scripts/load-test.mjs --db=postgres --split-workers=true --port=18083 --postgres-port=15435 --jobs=12 --recovery-jobs=8 --workers=2 --queue=64
cd backend
mvn -B '-Dtest=MultiRoomLoadTest' '-Dinterviewlab.ws.out=../docs/websocket-load-results.json' test
```

脚本仅启动并终止自己的临时进程和 PostgreSQL 容器，不连接默认 18080 服务。`--db=h2` 可复测文件库；分进程模式要求 PostgreSQL。压测日志写入 `.benchmark-runs/`，可用 `--out=...` 保存原始 JSON。

## 代码深挖要点

1. `JudgeService.submit`：在数据库事务内锁定 `judge_queue_gate`，检查未完成数量，将代码/语言/用例快照落库；容量满返回 429。可选 `Idempotency-Key` 使同一请求重试返回同一 submission，key 对应不同内容返回 409。
2. `JudgeService.claimNext`：多个 worker 查询可领取 ID，再用有条件的数据库更新把 `QUEUED` 或租约过期的 `RUNNING` 改成新租约；更新成功的一个 worker 才能执行。每 2 秒续约，租约 10 秒。
3. `JudgeService.execute`：短生命周期 Docker 容器禁网、只读根文件系统、非 root，并限制 CPU、内存、进程数、运行时间和输出量。worker 崩溃后旧容器可能仍在运行，因此新 worker 的重试属于**至少一次执行**，不能声称 exactly-once。
4. `RoomService.edit`：对房间行加写锁并比较 `baseVersion`，旧编辑返回 409。客户端保留未提交草稿；修订表记录每个被接受的版本。
5. `RoomSocket`：本进程编辑/光标即时广播；API 定时从数据库读取判题状态并推送来自独立 worker 的变化。数据库共享任务不等于 WebSocket 跨 API 副本同步，所以当前保持单 API 实例。
6. Micrometer/Prometheus：本机独立端口输出排队数、运行数、提交/完成计数、排队等待与执行计时、WebSocket 连接数。分进程时 API 与 worker 的指标分别采集，不能将单进程指标误读为全局总量。

## 简历表述边界

可以写：本机 PostgreSQL 测试中，4 个 worker 完成 64/64 个并发提交（完成吞吐 4.5 个任务/秒）；单独终止 worker 后恢复 16/16 个任务；100 个房间的 200 条同时在线模拟 WebSocket 连接通过短时编辑、冲突和重连测试（本机编辑广播 p95 27 ms）。64 是**提交数**，不是 64 个用户；200 是模拟客户端，不是真实用户。这不是生产 SLA、跨主机扩容证明、长时间连接可靠性、真实用户研究或云部署结果。题库额外测试只对客户端隐藏，源码公开，不能作为防作弊机制。
