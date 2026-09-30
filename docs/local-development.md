# Local Development and API Guide

This guide covers configuration, optional local AI, PostgreSQL, separate API and judge processes, and repeatable validation. Commands use Windows PowerShell and assume the project root unless a directory change is shown. See [benchmark results](benchmark.md) for measured behavior and [deployment guidance](deployment.md) for the current operational limits.

## Prerequisites and default startup

Install Java 17, Maven 3.6+, Node.js 22.12+ with npm, and Docker Desktop. Start Docker Desktop and pull the language runtimes:

```powershell
docker pull python:3.12-alpine
docker pull eclipse-temurin:17-jdk-alpine
```

Start the backend in one terminal:

```powershell
cd backend
mvn spring-boot:run
```

Start the frontend from the project root in another terminal:

```powershell
cd frontend
npm ci
npm run dev
```

Open http://localhost:15173. The API defaults to port 18080; health and Prometheus endpoints use port 18081 on loopback. The Vite development server proxies `/api` and `/ws` to the backend.

The default database is an H2 file database under `backend/data/`, with delayed writes disabled. Database files, environment files, logs, dependencies, and build output are excluded from Git.

For a local two-person trial, register separate interviewer and candidate accounts using different browser profiles. The interviewer creates a room and shares its invitation link. After the candidate joins, the interviewer clicks **Start interview**. Regenerating an invitation invalidates its previous link. Browser sessions are stored in the API process; restarting it requires signing in again.

Curated problems include Java and Python method templates. Implement the requested method in `Solution` or `MedianFinder`; keep the supplied `Main` or `__main__` harness to translate standard input and output. Custom problems also use stdin/stdout. The built-in problem bank remains available when local AI is offline.

## Configuration

Set variables in the terminal that launches each process. [`.env.example`](../.env.example) documents common options, but the backend and Vite configuration do not automatically load a root `.env` file into the shell.

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `18080` | Backend HTTP port; `0` requests an ephemeral port. |
| `SERVER_ADDRESS` | Framework default | Spring Boot HTTP bind address; set `127.0.0.1` for a separate local judge process. |
| `METRICS_PORT` | `18081` | Health and Prometheus port, bound to `127.0.0.1`; `0` requests an ephemeral port. |
| `APP_ORIGIN` | `http://localhost:15173` | Allowed browser origin for application requests and WebSocket connections. |
| `SECURE_COOKIE` | `false` | Set `true` when serving the application over HTTPS. |
| `DATABASE_URL` | `jdbc:h2:file:./data/interviewlab;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0` | JDBC URL, relative to the backend working directory for the H2 default. |
| `DATABASE_USER` | `sa` | Database username. |
| `DATABASE_PASSWORD` | Empty | Database password. |
| `JUDGE_WORKERS` | `2` | Worker threads in this JVM; use `0` for an API-only process. |
| `JUDGE_QUEUE_CAPACITY` | `32` | Maximum unfinished submissions accepted by the shared database queue. |
| `JUDGE_TIMEOUT_SECONDS` | `8` | Timeout used for judge execution. |
| `OLLAMA_URL` | `http://127.0.0.1:11434` | Ollama HTTP endpoint used by the backend. |
| `OLLAMA_MODEL` | `qwen3.5:9b-q8_0` | Installed Ollama model name. |
| `INTERVIEWLAB_API_TARGET` | `http://localhost:18080` | Backend proxy target for the frontend development server; set in its terminal. |
| `INTERVIEWLAB_API_URL` | `http://127.0.0.1:18080` | Backend URL used by `scripts/curated-smoke.mjs`. |

For example, to move the backend HTTP and metrics ports:

```powershell
$env:PORT='18090'
$env:METRICS_PORT='18091'
cd backend
mvn spring-boot:run
```

In the frontend terminal, set `$env:INTERVIEWLAB_API_TARGET='http://localhost:18090'` before starting Vite. If the frontend port changes, update `server.port` in [`frontend/vite.config.js`](../frontend/vite.config.js) and set the matching `APP_ORIGIN` in the backend terminal. Use the same hostname in the browser and configured origin.

## PostgreSQL

From the project root:

```powershell
docker compose up -d postgres
$env:DATABASE_URL='jdbc:postgresql://localhost:15432/interviewlab'
$env:DATABASE_USER='interviewlab'
$env:DATABASE_PASSWORD='interviewlab-local-only'
cd backend
mvn spring-boot:run
```

[`compose.yaml`](../compose.yaml) starts only PostgreSQL, binds its host port to `127.0.0.1:15432`, and stores data in the `interviewlab_postgres` volume. The password shown above is a local development default. The backend runs on the host and invokes the host Docker CLI for judging.

Switching `DATABASE_URL` selects a different database; it does not migrate existing H2 accounts or rooms.

## Separate API and judge processes

Use PostgreSQL for this setup. Start the database first, then configure the same database credentials in both terminals.

API terminal, starting from the project root:

```powershell
$env:DATABASE_URL='jdbc:postgresql://localhost:15432/interviewlab'
$env:DATABASE_USER='interviewlab'
$env:DATABASE_PASSWORD='interviewlab-local-only'
$env:JUDGE_WORKERS='0'
$env:PORT='18080'
$env:METRICS_PORT='18081'
cd backend
mvn spring-boot:run
```

Judge terminal, also starting from the project root:

```powershell
$env:DATABASE_URL='jdbc:postgresql://localhost:15432/interviewlab'
$env:DATABASE_USER='interviewlab'
$env:DATABASE_PASSWORD='interviewlab-local-only'
$env:JUDGE_WORKERS='2'
$env:PORT='0'
$env:SERVER_ADDRESS='127.0.0.1'
$env:METRICS_PORT='0'
cd backend
mvn spring-boot:run
```

This starts one API JVM and one judge JVM with two worker threads. Setting `JUDGE_WORKERS=4` creates four worker threads in that judge JVM, as used by the expanded benchmark. The judge's HTTP and metrics listeners receive ephemeral loopback ports. Keep queue-capacity settings consistent between processes.

The API maintains browser sessions and WebSocket connections. Judge workers claim persisted jobs from PostgreSQL with ten-second leases, renewed every two seconds. After a crash, jobs with expired leases can be claimed again. An old container may continue running after its judge JVM is forcibly terminated, so execution is at least once. The [deployment guide](deployment.md) explains isolation requirements and the current single-API-instance limit.

## Optional local AI

The backend calls Ollama's HTTP API; no cloud API key is required. The default model verified locally is `qwen3.5:9b-q8_0`. Ensure Ollama is running and the model is installed:

```powershell
ollama pull qwen3.5:9b-q8_0
ollama list
```

If `ollama` is not on the command path, run it from its installation directory. Override `OLLAMA_URL` or `OLLAMA_MODEL` in the backend terminal to use another endpoint or installed model.

The room creation page can ask the model to select a problem by difficulty and topic from the fixed problem bank. The interview room includes three assistant roles:

- **Interviewer:** provides up to three progressive hints, moving from concepts to data structures or invariants and then implementation steps. It also asks a focused question about the current code.
- **Code reviewer:** assesses correctness, complexity, edge cases, and code quality. Only judge results whose code exactly matches the current editor state are treated as test evidence. During a live interview, only the interviewer can access reviews.
- **Communication evaluator:** after the interview ends, evaluates pasted transcripts or written notes for clarification, explanation, testing, and collaboration. The application does not record audio or perform speech transcription.

AI requests go from the backend to the configured Ollama endpoint. Results are stored in the database's `ai_events` table. Only one model generation request runs at a time; a busy model returns HTTP 429, allowing the user to retry later. Communication scores reflect evidence in the supplied text. Unrecorded spoken answers cannot be treated as observed, and model conclusions should be checked against the original notes. AI feedback does not replace human judgment or complete program testing.

## Build and validation

Run backend tests and build the frontend:

```powershell
cd backend
mvn test
cd ../frontend
npm ci
npm run build -- --configLoader runner
```

For the end-to-end smoke test, start the backend on port 18080 and Docker first, then run from the project root using PowerShell 7:

```powershell
pwsh -File scripts/smoke.ps1
node scripts/curated-smoke.mjs
```

The smoke script creates test accounts and a room, then checks unauthorized room access (HTTP 403), stale edits (HTTP 409), Java/Python judging, room completion, and feedback. The curated test checks Two Sum samples and additional cases, retry idempotency, and omission of hidden inputs from client results. These scripts create records in the running application's database.

The standalone benchmark uses dedicated processes, a separate PostgreSQL container, and an isolated database. Its default ports are 18082 and 15434; it does not connect to the regular service on port 18080. Build the JAR and pull the benchmark database image first:

```powershell
docker pull postgres:16-alpine
cd backend
mvn -B verify
cd ..
node scripts/load-test.mjs --jobs=20 --recovery-jobs=12 --workers=2
```

The benchmark submits concurrent jobs, checks version conflicts, forcibly terminates its test backend, and verifies recovery after restart. It stops its own processes and container afterward and writes logs and raw results to `.benchmark-runs/`.

Use `--port`, `--postgres-port`, `--queue`, and `--out` to customize the run. Use `--db=h2` to test H2, or `--split-workers=true` with PostgreSQL to terminate and restart only the separate judge process. For the multi-room WebSocket test, run from `backend/`:

```powershell
mvn -B '-Dtest=MultiRoomLoadTest' test
```

The [benchmark report](benchmark.md) provides expanded test commands for 64 submissions and 200 simultaneous simulated WebSocket connections, raw results, measurement definitions, and limitations.

## API reference

REST routes are relative to the backend origin. Authentication uses a session cookie, with BCrypt password hashes and HttpOnly, SameSite=Strict cookies. Room and submission access is restricted to participants. The WebSocket endpoint uses the same authenticated session. The health and metrics endpoints use the separate management port.

| Operation | Method and path |
|---|---|
| Register | `POST /api/auth/register` |
| Login / current user / logout | `POST /api/auth/login`, `GET /api/auth/me`, `POST /api/auth/logout` |
| Create / list rooms | `POST /api/rooms`, `GET /api/rooms` |
| Room details | `GET /api/rooms/{id}` |
| Join / regenerate invitation | `POST /api/rooms/{id}/join`, `POST /api/rooms/{id}/invitation` |
| Start / end interview | `POST /api/rooms/{id}/start`, `POST /api/rooms/{id}/end` |
| Update code / revision history | `PUT /api/rooms/{id}/code`, `GET /api/rooms/{id}/revisions` |
| Submit code / list room submissions | `POST /api/rooms/{id}/submissions`, `GET /api/rooms/{id}/submissions` |
| Submission result | `GET /api/submissions/{id}` |
| Read / save feedback | `GET /api/rooms/{id}/feedback`, `PUT /api/rooms/{id}/feedback` |
| Curated problem bank | `GET /api/problems` |
| Health / Prometheus | `GET /actuator/health`, `GET /actuator/prometheus` on the management port |
| Real-time collaboration | `WS /ws/rooms/{id}` |
| AI status / problem selection | `GET /api/ai/status`, `POST /api/ai/problems` |
| AI hint | `POST /api/ai/rooms/{id}/hints` |
| AI follow-up question | `POST /api/ai/rooms/{id}/questions` |
| AI code review | `POST /api/ai/rooms/{id}/review` |
| AI communication evaluation | `POST /api/ai/rooms/{id}/communication` |
| AI history | `GET /api/ai/rooms/{id}/events` |

Code updates include `code`, `language`, and `baseVersion`. A stale version returns HTTP 409. Submission bodies contain a `cases` array of objects with `input` and `expected` fields; the submission uses a snapshot of the room's saved code. Send an `Idempotency-Key` header to make retries return the same submission. Reusing a key with changed code or test cases returns HTTP 409, and exhausted queue capacity returns HTTP 429.

Implementation references: [authentication and room routes](../backend/src/main/java/dev/interviewlab/Api.java), [AI routes](../backend/src/main/java/dev/interviewlab/AiApi.java), [backend configuration](../backend/src/main/resources/application.yml), and [frontend proxy configuration](../frontend/vite.config.js).
