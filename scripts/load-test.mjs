#!/usr/bin/env node
// Isolated, repeatable judge throughput and restart-recovery benchmark.
// Requires Node.js 22+, a built backend JAR, and a Docker daemon accessible
// to the Java process. It never connects to the normal 18080 app/database.
import { spawn, spawnSync } from 'node:child_process'
import { once } from 'node:events'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { performance } from 'node:perf_hooks'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const args = Object.fromEntries(process.argv.slice(2).map(part => {
  const match = /^--([a-z-]+)=(.*)$/.exec(part)
  if (!match) throw new Error(`Expected --name=value, got ${part}`)
  return [match[1], match[2]]
}))
const integer = (name, fallback, min, max) => {
  const value = Number(args[name] ?? fallback)
  if (!Number.isInteger(value) || value < min || value > max) throw new Error(`Invalid --${name}`)
  return value
}
const port = integer('port', 18082, 1024, 65535)
const loadJobs = integer('jobs', 20, 4, 64)
const recoveryJobs = integer('recovery-jobs', 12, 4, 64)
const workers = integer('workers', 2, 1, 8)
const queueCapacity = integer('queue', 64, Math.max(loadJobs, recoveryJobs), 256)
const databaseMode = args.db ?? 'postgres'
if (!['postgres', 'h2'].includes(databaseMode)) throw new Error('Use --db=postgres or --db=h2')
const splitWorkers = args['split-workers'] === 'true'
if (splitWorkers && databaseMode === 'h2') throw new Error('Split workers require PostgreSQL; H2 is single-process development mode')
const postgresPort = integer('postgres-port', 15434, 1024, 65535)
const id = new Date().toISOString().replace(/[:.]/g, '-') + '-' + Math.random().toString(16).slice(2, 8)
const runDir = path.resolve(args['work-dir'] ?? path.join(root, '.benchmark-runs', id))
const outPath = path.resolve(args.out ?? path.join(runDir, 'results.json'))
if (fs.existsSync(runDir)) throw new Error(`Run directory already exists: ${runDir}`)
fs.mkdirSync(runDir, { recursive: true })
fs.mkdirSync(path.dirname(outPath), { recursive: true })
const jar = path.join(root, 'backend', 'target', 'interviewlab-api-1.0.0.jar')
if (!fs.existsSync(jar)) throw new Error(`Build the backend JAR first: ${jar}`)
const base = `http://127.0.0.1:${port}`
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const terminal = new Set(['PASSED', 'FAILED', 'ERROR'])
const percentile = (values, p) => {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  return Math.round(sorted[Math.ceil(p * sorted.length) - 1] * 10) / 10
}
const summary = values => ({ p50: percentile(values, .5), p95: percentile(values, .95), p99: percentile(values, .99), max: values.length ? Math.round(Math.max(...values) * 10) / 10 : null })
const round = value => Math.round(value * 10) / 10
const report = {
  startedAt: new Date().toISOString(),
  config: { port, loadJobs, recoveryJobs, workers, queueCapacity, databaseMode, splitWorkers, ...(databaseMode === 'postgres' ? { postgresPort, postgresImage: 'postgres:16-alpine' } : {}), pollIntervalMs: 200, jobSleepMs: 250, recoveryJobSleepMs: 2000 },
  environment: { platform: process.platform, arch: process.arch, node: process.version, cpuModel: os.cpus()[0]?.model, logicalCpus: os.cpus().length, memoryGiB: round(os.totalmem() / 2 ** 30) },
  artifacts: { runDir, stdoutLog: path.join(runDir, 'api.stdout.log'), stderrLog: path.join(runDir, 'api.stderr.log') }
}
let server
let workerProcess
let postgresName
let interviewerCookie
let candidateCookie
const password = 'Bench!' + Math.random().toString(36).slice(2) + '9'
const databasePassword = 'Bench!' + Math.random().toString(36).slice(2) + '9'
const interviewerEmail = `bench-interviewer-${id}@example.test`
const candidateEmail = `bench-candidate-${id}@example.test`

async function request(method, endpoint, cookie, body, expected = [200]) {
  const response = await fetch(base + endpoint, {
    method,
    headers: { ...(cookie ? { Cookie: cookie } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(20000)
  })
  const raw = await response.text()
  let data
  try { data = raw ? JSON.parse(raw) : null } catch { data = raw }
  if (!expected.includes(response.status)) throw new Error(`${method} ${endpoint}: HTTP ${response.status} ${raw.slice(0, 300)}`)
  return { status: response.status, data, headers: response.headers }
}

async function ready(timeoutMs = 60000) {
  const until = performance.now() + timeoutMs
  while (performance.now() < until) {
    try {
      const response = await fetch(base + '/api/auth/me', { signal: AbortSignal.timeout(1000) })
      if (response.status === 403 || response.status === 200) return
    } catch { /* waiting for port */ }
    await sleep(250)
  }
  throw new Error('Backend did not become ready; inspect the run logs')
}

async function unavailable() {
  try {
    await fetch(base + '/api/auth/me', { signal: AbortSignal.timeout(1000) })
    return false
  } catch { return true }
}

function docker(...arguments_) {
  const result = spawnSync('docker', arguments_, { encoding: 'utf8', windowsHide: true })
  if (result.error || result.status !== 0) throw new Error(`docker ${arguments_.join(' ')} failed: ${result.stderr || result.error}`)
  return result.stdout.trim()
}

async function startPostgres() {
  postgresName = `interviewlab-bench-${Math.random().toString(16).slice(2, 10)}`
  docker('run', '--rm', '-d', '--name', postgresName,
    '-e', 'POSTGRES_DB=interviewlab', '-e', 'POSTGRES_USER=bench', '-e', `POSTGRES_PASSWORD=${databasePassword}`,
    '-p', `127.0.0.1:${postgresPort}:5432`, 'postgres:16-alpine')
  const until = performance.now() + 30000
  while (performance.now() < until) {
    const result = spawnSync('docker', ['exec', postgresName, 'pg_isready', '-U', 'bench', '-d', 'interviewlab'], { encoding: 'utf8', windowsHide: true })
    if (result.status === 0) return
    await sleep(250)
  }
  throw new Error('Benchmark PostgreSQL did not become ready')
}

async function startServer() {
  const output = fs.openSync(report.artifacts.stdoutLog, 'a')
  const error = fs.openSync(report.artifacts.stderrLog, 'a')
  const env = {
    ...process.env,
    PORT: String(port),
    DATABASE_URL: databaseMode === 'postgres'
      ? `jdbc:postgresql://127.0.0.1:${postgresPort}/interviewlab`
      : `jdbc:h2:file:${path.join(runDir, 'database').replaceAll('\\', '/')};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0`,
    DATABASE_USER: databaseMode === 'postgres' ? 'bench' : 'sa',
    DATABASE_PASSWORD: databaseMode === 'postgres' ? databasePassword : '',
    JUDGE_WORKERS: splitWorkers ? '0' : String(workers),
    JUDGE_QUEUE_CAPACITY: String(queueCapacity),
    JUDGE_TIMEOUT_SECONDS: '8',
    METRICS_PORT: '0'
  }
  server = spawn('java', ['-jar', jar], { cwd: path.join(root, 'backend'), env, stdio: ['ignore', output, error], windowsHide: true })
  fs.closeSync(output)
  fs.closeSync(error)
  await ready()
}

async function startWorker() {
  const output = fs.openSync(path.join(runDir, 'worker.stdout.log'), 'a')
  const error = fs.openSync(path.join(runDir, 'worker.stderr.log'), 'a')
  const env = {
    ...process.env,
    PORT: '0', SERVER_ADDRESS: '127.0.0.1', METRICS_PORT: '0',
    DATABASE_URL: databaseMode === 'postgres'
      ? `jdbc:postgresql://127.0.0.1:${postgresPort}/interviewlab`
      : `jdbc:h2:file:${path.join(runDir, 'database').replaceAll('\\', '/')};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0;AUTO_SERVER=TRUE`,
    DATABASE_USER: databaseMode === 'postgres' ? 'bench' : 'sa',
    DATABASE_PASSWORD: databaseMode === 'postgres' ? databasePassword : '',
    JUDGE_WORKERS: String(workers), JUDGE_QUEUE_CAPACITY: String(queueCapacity), JUDGE_TIMEOUT_SECONDS: '8'
  }
  workerProcess = spawn('java', ['-jar', jar], { cwd: path.join(root, 'backend'), env, stdio: ['ignore', output, error], windowsHide: true })
  fs.closeSync(output)
  fs.closeSync(error)
  await sleep(3500)
  if (workerProcess.exitCode !== null) throw new Error('Worker process exited during startup; inspect worker.stderr.log')
}

async function stopWorker() {
  if (!workerProcess || workerProcess.exitCode !== null) return
  const exit = once(workerProcess, 'exit')
  workerProcess.kill('SIGKILL')
  await exit
  workerProcess = null
}

async function stopServer() {
  if (!server || server.exitCode !== null) return
  const exit = once(server, 'exit')
  server.kill('SIGKILL') // Intentional abrupt failure of this isolated child process.
  await exit
  server = null
}

async function register(email, displayName) {
  const result = await request('POST', '/api/auth/register', null, { email, displayName, password })
  const cookie = result.headers.get('set-cookie')?.split(';')[0]
  if (!cookie?.startsWith('JSESSIONID=')) throw new Error('Registration did not create a session cookie')
  return cookie
}

async function login(email) {
  const result = await request('POST', '/api/auth/login', null, { email, password })
  const cookie = result.headers.get('set-cookie')?.split(';')[0]
  if (!cookie?.startsWith('JSESSIONID=')) throw new Error('Login did not create a session cookie')
  return cookie
}

async function makeRoom(title) {
  const created = (await request('POST', '/api/rooms', interviewerCookie, { title, prompt: 'Read stdin and print ok.', durationMinutes: 45 })).data
  await request('POST', `/api/rooms/${created.room.id}/join`, candidateCookie, { inviteToken: created.inviteToken })
  await request('POST', `/api/rooms/${created.room.id}/start`, interviewerCookie)
  return created.room.id
}

async function setCode(roomId, code, baseVersion) {
  return (await request('PUT', `/api/rooms/${roomId}/code`, candidateCookie, { code, language: 'python', baseVersion })).data
}

async function submit(roomId) {
  const started = performance.now()
  const response = await request('POST', `/api/rooms/${roomId}/submissions`, candidateCookie, { cases: [{ input: '', expected: 'ok' }] })
  return { id: response.data.id, httpStatus: response.status, submittedAt: started, acceptanceMs: round(performance.now() - started) }
}

async function observe(submissions, cookie, timeoutMs = 120000) {
  const pending = new Map(submissions.map(item => [item.id, item]))
  const completed = []
  let peakQueued = 0, peakRunning = 0
  const until = performance.now() + timeoutMs
  while (pending.size && performance.now() < until) {
    const statuses = await Promise.all([...pending.keys()].map(id => request('GET', `/api/submissions/${id}`, cookie)))
    let queued = 0, running = 0
    for (const response of statuses) {
      const submission = response.data
      if (submission.status === 'QUEUED') queued++
      if (submission.status === 'RUNNING') running++
      if (terminal.has(submission.status)) {
        const original = pending.get(submission.id)
        completed.push({ id: submission.id, status: submission.status, acceptanceMs: original.acceptanceMs, completionLatencyMs: round(performance.now() - original.submittedAt), resultJson: submission.resultJson })
        pending.delete(submission.id)
      }
    }
    peakQueued = Math.max(peakQueued, queued)
    peakRunning = Math.max(peakRunning, running)
    if (pending.size) await sleep(200)
  }
  if (pending.size) throw new Error(`${pending.size} submissions did not finish within ${timeoutMs} ms`)
  return { completed, peakQueued, peakRunning }
}

async function versionRace() {
  const room = await makeRoom('Concurrent version check')
  const contenders = 8
  const responses = await Promise.all(Array.from({ length: contenders }, (_, index) =>
    request('PUT', `/api/rooms/${room}/code`, candidateCookie, { code: `print(${index})`, language: 'python', baseVersion: 0 }, [200, 409])))
  const accepted = responses.filter(item => item.status === 200).length
  const conflicts = responses.filter(item => item.status === 409).length
  const current = (await request('GET', `/api/rooms/${room}`, candidateCookie)).data
  const revisions = (await request('GET', `/api/rooms/${room}/revisions`, candidateCookie)).data
  return { contenders, accepted, conflicts, finalVersion: current.version, revisionCount: revisions.length, passed: accepted === 1 && conflicts === contenders - 1 && current.version === 1 && revisions.length === 1 }
}

async function recovery(roomId) {
  const current = (await request('GET', `/api/rooms/${roomId}`, candidateCookie)).data
  await setCode(roomId, 'import time\ntime.sleep(2)\nprint("ok")', current.version)
  const submissions = await Promise.all(Array.from({ length: recoveryJobs }, () => submit(roomId)))
  let beforeCrash
  const until = performance.now() + 10000
  while (performance.now() < until) {
    const list = (await request('GET', `/api/rooms/${roomId}/submissions`, candidateCookie)).data
    const selected = list.filter(item => submissions.some(s => s.id === item.id))
    const queued = selected.filter(item => item.status === 'QUEUED').length
    const running = selected.filter(item => item.status === 'RUNNING').length
    if (queued > 0 && running > 0) { beforeCrash = { queued, running, total: selected.length }; break }
    await sleep(100)
  }
  if (!beforeCrash) throw new Error('Could not observe both queued and running work before crash')
  const crashedAt = performance.now()
  if (splitWorkers) {
    await stopWorker()
    if (await unavailable()) throw new Error('API stopped when only worker was terminated')
    await startWorker()
  } else {
    await stopServer()
    if (!(await unavailable())) throw new Error('Isolated backend is still listening after forced termination')
    await startServer()
  }
  const restartedAt = performance.now()
  if (!splitWorkers) candidateCookie = await login(candidateEmail) // Sessions are intentionally process-local.
  const observed = await observe(submissions, candidateCookie, 150000)
  const finishedAt = performance.now()
  const passed = observed.completed.filter(item => item.status === 'PASSED').length
  return {
    beforeCrash,
    restartedAndReadyMs: round(restartedAt - crashedAt),
    postReadyCompletionMs: round(finishedAt - restartedAt),
    totalCrashToCompletionMs: round(finishedAt - crashedAt),
    terminal: observed.completed.length,
    passed,
    failed: observed.completed.filter(item => item.status !== 'PASSED').length,
    uniqueIds: new Set(observed.completed.map(item => item.id)).size,
    peakQueuedAfterRestart: observed.peakQueued,
    peakRunningAfterRestart: observed.peakRunning,
    submissions: observed.completed.map(({ id, status, completionLatencyMs }) => ({ id, status, completionLatencyMs })),
    success: passed === recoveryJobs && new Set(observed.completed.map(item => item.id)).size === recoveryJobs
  }
}

try {
  if (!(await unavailable())) throw new Error(`Port ${port} is already occupied; choose another --port`)
  if (databaseMode === 'postgres') {
    console.log(`Starting isolated PostgreSQL on 127.0.0.1:${postgresPort}`)
    await startPostgres()
  }
  console.log(`Starting isolated InterviewLab at ${base}`)
  await startServer()
  if (splitWorkers) await startWorker()
  interviewerCookie = await register(interviewerEmail, 'Benchmark Interviewer')
  candidateCookie = await register(candidateEmail, 'Benchmark Candidate')
  const roomId = await makeRoom('Benchmark judge workload')
  report.roomId = roomId
  await setCode(roomId, 'import time\ntime.sleep(0.25)\nprint("ok")', 0)
  console.log('Warming Docker/JVM with two sequential submissions...')
  const warmup = []
  for (let i = 0; i < 2; i++) {
    const submission = await submit(roomId)
    const result = await observe([submission], candidateCookie)
    warmup.push(result.completed[0].status)
  }
  report.warmup = warmup
  if (warmup.some(status => status !== 'PASSED')) throw new Error(`Warmup failed: ${warmup.join(', ')}`)

  console.log(`Submitting ${loadJobs} judge jobs concurrently...`)
  const loadStart = performance.now()
  const submitted = await Promise.all(Array.from({ length: loadJobs }, () => submit(roomId)))
  const observed = await observe(submitted, candidateCookie)
  const durationMs = performance.now() - loadStart
  const passed = observed.completed.filter(item => item.status === 'PASSED').length
  report.judgeLoad = {
    accepted: submitted.length, passed, failed: observed.completed.length - passed,
    batchDurationMs: round(durationMs), throughputPerSecond: round(passed / (durationMs / 1000)),
    acceptanceLatencyMs: summary(submitted.map(item => item.acceptanceMs)),
    completionLatencyMs: summary(observed.completed.map(item => item.completionLatencyMs)),
    peakQueuedObserved: observed.peakQueued, peakRunningObserved: observed.peakRunning,
    submissions: observed.completed.map(({ id, status, acceptanceMs, completionLatencyMs }) => ({ id, status, acceptanceMs, completionLatencyMs }))
  }
  console.log(`Judge: ${passed}/${loadJobs} passed; ${report.judgeLoad.throughputPerSecond} jobs/s`)
  report.versionRace = await versionRace()
  console.log(`Version race: ${report.versionRace.accepted} accepted, ${report.versionRace.conflicts} conflicts`)
  console.log(`Submitting ${recoveryJobs} slower jobs, then terminating the ${splitWorkers ? 'worker' : 'backend'}...`)
  report.recovery = await recovery(roomId)
  console.log(`Recovery: ${report.recovery.passed}/${recoveryJobs} finished after restart`)
  report.overallPass = passed === loadJobs && report.versionRace.passed && report.recovery.success
  report.finishedAt = new Date().toISOString()
  fs.writeFileSync(outPath, JSON.stringify(report, null, 2) + '\n')
  console.log(`Results: ${outPath}`)
  if (!report.overallPass) process.exitCode = 1
} catch (error) {
  report.error = String(error.stack ?? error)
  report.finishedAt = new Date().toISOString()
  fs.writeFileSync(outPath, JSON.stringify(report, null, 2) + '\n')
  console.error(report.error)
  console.error(`Partial results and logs: ${outPath}`)
  process.exitCode = 1
} finally {
  await stopWorker()
  await stopServer()
  if (postgresName) {
    const stopped = spawnSync('docker', ['stop', postgresName], { encoding: 'utf8', windowsHide: true })
    if (stopped.status !== 0) console.error(`Could not stop benchmark container ${postgresName}: ${stopped.stderr}`)
  }
}
