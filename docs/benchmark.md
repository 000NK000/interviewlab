# InterviewLab Concurrency and Failure Recovery Validation

## Expanded local benchmark (2026-09-23)

The PostgreSQL judging workload was expanded to 64 submissions initiated concurrently on one Windows machine. The API runs in one JVM, and a separate judge JVM contains four worker threads. The WebSocket workload was expanded to 100 rooms and 200 simulated clients. Each judge program sleeps for 250 ms before printing `ok`; two sequential warm-up jobs run before measurement. The API, judge, and PostgreSQL use dedicated benchmark processes, containers, and ports, separate from the regular service on port 18080.

| Scenario | Result | Local measurements |
|---|---:|---:|
| PostgreSQL, one API JVM and one separate judge JVM with four worker threads, 64 concurrent submissions | 64/64 passed | Batch completion throughput: 4.5 jobs/s; POST p50/p95: 190.1/360 ms; completion p50/p95: 7.36/13.81 s |
| Force-terminate the separate judge process, then restart it | 16/16 passed | 17.65 s from termination to all jobs completing, including approximately 3.58 s in the script's fixed startup-wait phase |
| 100 rooms, 200 simultaneously connected simulated WebSocket clients | 100/100 rooms passed | Cursor, edit, stale-version conflict, and reconnect checks in every room; edit broadcast p50/p95: 18/27 ms |

The WebSocket test uses a synchronization barrier after both connections in every room receive their initial snapshots. This ensures that all 200 connections are **online simultaneously** before concurrent operations begin. Edit latency is measured from the candidate sending an edit to the interviewer receiving its broadcast. This is a short local test; it does not establish long-term connection stability, public Internet latency, or scaling across API instances. Judge throughput and completion latency use the method described below and include Docker container startup, the program's 250 ms sleep, and up to approximately 200 ms of polling observation delay. The 64 submissions were queued for bounded execution; the normal batch had at most four records observed in `RUNNING` state.

After the judge process crashes, `RUNNING` records with old leases may temporarily coexist with records being executed by the restarted process. The peak observed `RUNNING` count during recovery was seven; it does not represent seven new workers. The system provides at-least-once execution and eventual recovery, rather than exactly-once execution during failures. Raw results are available in [`judge-load-results-64jobs.json`](judge-load-results-64jobs.json) and [`websocket-load-results-200clients.json`](websocket-load-results-200clients.json).

To reproduce, start in the project root, build the JAR, and ensure Docker Desktop is available:

```powershell
cd backend
mvn -B verify
cd ..
node scripts/load-test.mjs --db=postgres --split-workers=true --port=18084 --postgres-port=15436 --jobs=64 --recovery-jobs=16 --workers=4 --queue=128
cd backend
mvn -B '-Dtest=MultiRoomLoadTest' '-Dinterviewlab.ws.rooms=100' '-Dinterviewlab.ws.out=../docs/websocket-load-results-200clients.json' test
```

## Baseline measurements (2026-09-22)

The baseline tests ran locally on Windows on 2026-09-22. The machine had an Intel Core Ultra 9 285K, 24 logical processors, and 31.3 GiB of memory, with Java 17, Node 24, Docker Desktop, and PostgreSQL 16. Judge jobs were Python programs that slept for 250 ms before printing `ok`; recovery jobs slept for two seconds. Each judge benchmark included two sequential warm-up jobs before measurement.

| Scenario | Judge jobs completed | Batch completion throughput | POST p50/p95 | Completion p50/p95 | Jobs completed after failure |
|---|---:|---:|---:|---:|---:|
| PostgreSQL, two worker threads in the API JVM | 20/20 | 2.2 jobs/s | 74.1/119.7 ms | 4.72/9.01 s | 12/12 after force-terminating the API |
| PostgreSQL, separate API and judge JVMs, two worker threads in the judge JVM | 12/12 | 2.3 jobs/s | 45/82.6 ms | 2.67/5.25 s | 8/8 after force-terminating only the judge process |
| H2 file database, `WRITE_DELAY=0` | 8/8 | 2.2 jobs/s | 7.4/11.2 ms | 1.95/3.67 s | 8/8 after force-terminating the API |

Each scenario also issued eight concurrent edits with the same base version: one was accepted and seven returned HTTP 409. Both PostgreSQL runs had at most two submissions observed in `RUNNING` state; the others remained `QUEUED`. In the separate-process test, the API remained accessible when the judge process was forcibly terminated. The PostgreSQL measurements come from the final version with a transactional lock enforcing global queue capacity. Earlier results without that lock are not combined with these measurements. The three raw reports are [`interviewlab-final-postgres-results.json`](interviewlab-final-postgres-results.json), [`interviewlab-final-split-results.json`](interviewlab-final-split-results.json), and [`interviewlab-h2-write-delay-0.json`](interviewlab-h2-write-delay-0.json).

**Measurement method:** POST latency runs from sending the request to receiving a successful response. Completion latency runs from sending the request until a GET poll, performed every 200 ms, first observes a terminal state. Throughput is the number of completed jobs divided by the elapsed time from the batch's first submission until all jobs are observed in a terminal state. The p50/p95 values use the nearest-rank method. Measurements therefore include Docker startup, execution, and up to approximately 200 ms of observation delay. In separate-process tests, the reported worker restart-to-ready time is a script phase with a fixed 3.5-second wait after startup; it is not an actively probed readiness metric.

## H2 failure reproduction and durability limits

Early H2 file-mode tests used the default delayed-write setting. After the backend was forcibly terminated and restarted, some submission IDs that had received successful responses returned HTTP 404. The original failure report is [`interviewlab-benchmark-h2-failure.json`](interviewlab-benchmark-h2-failure.json). With the new database claim logic but the same default write delay, eight recovery jobs again returned 404; that result is recorded in [`interviewlab-h2-retest.json`](interviewlab-h2-retest.json). After changing the H2 URL to `WRITE_DELAY=0`, all 8/8 jobs recovered in the same scenario. This comparison supports delayed writes as a key factor. A single passing run does not establish durability under arbitrary power loss or disk failure; PostgreSQL is used for the primary demonstration.

The revised queue keeps jobs in the database instead of copying them into a bounded in-memory queue. After a failure, new workers claim jobs when their leases expire, so a full queue does not cause already accepted jobs to be marked `ERROR`. A row lock on `judge_queue_gate` serializes capacity checks. A test with eight simultaneous submissions, capacity two, and no active workers produced exactly two accepted submissions and six HTTP 429 responses.

## Multi-room WebSocket tests

The baseline `MultiRoomLoadTest` created eight rooms, 16 users, and 16 WebSocket connections in an H2 test database. It exercised cursor transmission, editing, stale-version conflicts, and candidate reconnection concurrently across rooms. All 8/8 rooms passed. Local edit broadcast latency was 8 ms at p50 and 11 ms at p95. The concurrent operation phase took 160 ms, excluding account and room preparation. Raw data is in [`websocket-load-results.json`](websocket-load-results.json). This early test did not include a connection barrier, so its result alone does not prove that all 16 connections were online at the same instant. The expanded 200-connection test above adds that barrier. These local tests do not establish public Internet performance or long-term connection reliability.

The curated problem test in `scripts/curated-smoke.mjs` uses real Docker containers to validate Two Sum. Both Python and Java passed 3/3 cases: one sample and two additional cases. Repeating the same `Idempotency-Key` returned the same submission ID, and the additional cases' input and expected output were absent from client-facing results. See [`curated-smoke-results.json`](curated-smoke-results.json).

## Reproduction commands

Run from the project root after starting Docker Desktop, pulling the required images, and checking that the default benchmark ports are available:

```powershell
cd backend
mvn -B verify
cd ..
node scripts/load-test.mjs --db=postgres --port=18082 --postgres-port=15434 --jobs=20 --recovery-jobs=12 --workers=2 --queue=64
node scripts/load-test.mjs --db=postgres --split-workers=true --port=18083 --postgres-port=15435 --jobs=12 --recovery-jobs=8 --workers=2 --queue=64
cd backend
mvn -B '-Dtest=MultiRoomLoadTest' '-Dinterviewlab.ws.out=../docs/websocket-load-results.json' test
```

The script starts and terminates only its own temporary processes and PostgreSQL container; it does not connect to the default service on port 18080. Use `--db=h2` to retest the file database. Separate API and judge processes require PostgreSQL. Benchmark logs are written to `.benchmark-runs/`; use `--out=...` to save the raw JSON report.

## Implementation details

1. `JudgeService.submit` locks `judge_queue_gate` inside a database transaction, checks the number of unfinished jobs, and persists a snapshot of the code, language, and test cases. It returns HTTP 429 when capacity is exhausted. An optional `Idempotency-Key` makes retries return the same submission; reusing a key with different content returns HTTP 409.
2. `JudgeService.claimNext` lets workers query eligible IDs and use a conditional database update to replace a `QUEUED` state or an expired `RUNNING` lease with a new lease. Only the worker whose update succeeds may execute the job. Leases last ten seconds and renew every two seconds.
3. `JudgeService.execute` runs short-lived Docker containers with networking disabled, a read-only root filesystem, a non-root user, and limits on CPU, memory, process count, runtime, and output. An old container may continue running after its judge process crashes, so retries provide **at-least-once execution**, not exactly-once execution.
4. `RoomService.edit` takes a write lock on the room row and compares `baseVersion`; stale edits return HTTP 409. The client retains unsent drafts, and the revision table records every accepted version.
5. `RoomSocket` immediately broadcasts edits and cursors within the current process. The API periodically reads judge status from the database and pushes changes made by the separate judge process. Shared database jobs do not provide WebSocket synchronization across API replicas, so the current deployment uses one API instance.
6. Micrometer/Prometheus exposes queue depth, running jobs, submission/completion counts, queue wait and execution durations, and WebSocket connection counts on a dedicated local port. API and judge metrics are collected separately in the separate-process configuration; process-local counters should not be interpreted as global totals.

## Interpretation and limitations

The expanded local PostgreSQL test completed 64/64 concurrent submissions with four worker threads in one judge JVM, at a batch completion throughput of 4.5 jobs/s. It recovered 16/16 jobs after that judge process was forcibly terminated and restarted. A separate short WebSocket test validated editing, conflicts, and reconnects across 100 rooms with 200 simultaneously connected simulated clients, with local p95 edit broadcast latency of 27 ms.

The 64 figure counts **submissions**, not users or simultaneously executing containers. The 200 figure counts simulated clients, not real users. These measurements do not establish a production SLA, cross-host scaling, long-term connection reliability, a real-user study, or cloud deployment performance. Additional problem tests are hidden only from the client; their source is public, so they are not an anti-cheating mechanism.
