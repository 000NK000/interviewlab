package dev.interviewlab;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service class JudgeService {
    private final SubmissionRepo submissions; private final JudgeQueueGateRepo gates; private final RoomService rooms; private final ObjectMapper json; private final RoomSocket socket;
    private final ExecutorService workers; private final ScheduledExecutorService heartbeats=Executors.newScheduledThreadPool(1);
    private final TransactionTemplate transactions; private final MeterRegistry metrics; private final int workerCount,capacity;
    private final String owner=UUID.randomUUID().toString();
    private final int timeoutSeconds; private volatile boolean running=true;
    JudgeService(SubmissionRepo submissions,JudgeQueueGateRepo gates,RoomService rooms,ObjectMapper json,RoomSocket socket,TransactionTemplate transactions,MeterRegistry metrics,@Value("${app.judge-workers:2}") int workerCount,@Value("${app.judge-queue-capacity:32}") int capacity,@Value("${app.judge-timeout-seconds:8}") int timeoutSeconds) {
        this.submissions=submissions;this.gates=gates;this.rooms=rooms;this.json=json;this.socket=socket;
        this.transactions=transactions;this.metrics=metrics;this.workerCount=Math.max(0,workerCount);this.capacity=Math.max(1,capacity);
        this.workers=Executors.newFixedThreadPool(Math.max(1,this.workerCount));this.timeoutSeconds=Math.max(2,timeoutSeconds);
        io.micrometer.core.instrument.Gauge.builder("interviewlab.judge.pending",submissions,r->r.countByStatusIn(List.of("QUEUED"))).register(metrics);
        io.micrometer.core.instrument.Gauge.builder("interviewlab.judge.running",submissions,r->r.countByStatusIn(List.of("RUNNING"))).register(metrics);
    }
    @EventListener(ApplicationReadyEvent.class) void start() {
        if(!gates.existsById(1)) gates.saveAndFlush(new JudgeQueueGate(1));
        for(int i=0;i<workerCount;i++) workers.submit(this::loop);
    }
    @PreDestroy void stop() { running=false;workers.shutdownNow();heartbeats.shutdownNow(); }
    @Transactional Submission submit(UUID roomId,UUID user,List<RoomApi.TestCase> cases) {
        return submit(roomId,user,cases,null);
    }
    @Transactional Submission submit(UUID roomId,UUID user,List<RoomApi.TestCase> cases,String idempotencyKey) {
        Room room=rooms.require(roomId,user);
        if(!"LIVE".equals(room.status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Run is available during a live interview");
        if(room.code.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Write code before running");
        if(cases==null || cases.size()>5) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide up to 5 custom test cases");
        for(var c:cases) if(c==null || c.input()==null || c.expected()==null || c.input().length()>2000 || c.expected().length()>2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid test case");
        if(cases.isEmpty() && room.problemId==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide test cases");
        gates.gate().orElseThrow(()->new IllegalStateException("Judge queue gate missing"));
        String requestKey=null;
        String fingerprint=null;
        if(idempotencyKey!=null) {
            if(idempotencyKey.isBlank()||idempotencyKey.length()>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid idempotency key");
            requestKey=hash(user+":"+roomId+":"+idempotencyKey);
            try { fingerprint=hash(room.language+"\n"+room.code+"\n"+json.writeValueAsString(cases)); }
            catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid test cases"); }
            Optional<Submission> prior=submissions.findByRequestKey(requestKey);
            if(prior.isPresent()) return sameRequest(prior.get(),fingerprint);
        }
        if(submissions.countByStatusIn(List.of("QUEUED","RUNNING"))>=capacity+workerCount) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Judge queue full");
        try {
            String encodedCases=json.writeValueAsString(cases);
            if(encodedCases.length()>10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Test cases are too large together");
            Submission fresh=new Submission(room,user,encodedCases);fresh.requestKey=requestKey;fresh.requestFingerprint=fingerprint;
            Submission s=submissions.saveAndFlush(fresh);
            metrics.counter("interviewlab.judge.submissions").increment();
            socket.publishSubmission(s);return s;
        } catch(org.springframework.dao.DataIntegrityViolationException e) {
            if(requestKey!=null) return sameRequest(submissions.findByRequestKey(requestKey).orElseThrow(()->e),fingerprint);
            throw e;
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid test cases"); }
    }
    private Submission sameRequest(Submission existing,String fingerprint) {
        if(!Objects.equals(existing.requestFingerprint,fingerprint)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key was used for a different submission");
        return existing;
    }
    private String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    List<Submission> list(UUID roomId,UUID user) { rooms.require(roomId,user);return submissions.findByRoomIdOrderByCreatedAtDesc(roomId); }
    Submission get(UUID id,UUID user) { Submission s=submissions.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Submission not found"));rooms.require(s.roomId,user);return s; }
    private void loop() {
        while(running) try {
            UUID id=claimNext();if(id==null) { Thread.sleep(200);continue; }
            Submission s=submissions.findById(id).orElse(null);if(s==null) continue;
            metrics.timer("interviewlab.judge.queue.wait").record(java.time.Duration.between(s.createdAt,s.startedAt));
            long executionStarted=System.nanoTime();
            socket.publishSubmission(s);
            var heartbeat=heartbeats.scheduleAtFixedRate(()->{try { transactions.executeWithoutResult(tx->submissions.heartbeat(id,owner,Instant.now().plusSeconds(10))); } catch(Exception ignored) {}},2,2,TimeUnit.SECONDS);
            try {
                var custom=json.readValue(s.casesJson,new TypeReference<List<RoomApi.TestCase>>(){});
                Room room=rooms.require(s.roomId,s.authorId);
                List<RoomApi.TestCase> cases=new ArrayList<>(ProblemBank.samples(room.problemId));
                cases.addAll(ProblemBank.hidden(room.problemId));
                cases.addAll(custom);
                int hiddenStart=ProblemBank.samples(room.problemId).size();
                int hiddenEnd=hiddenStart+ProblemBank.hidden(room.problemId).size();
                List<Map<String,Object>> results=new ArrayList<>(); boolean allPass=true; boolean infra=false;
                for(int i=0;i<cases.size();i++) {
                    var c=cases.get(i); Execution e=execute(s,c.input());
                    boolean passed=e.exitCode==0 && !e.timedOut && normalize(e.output).equals(normalize(c.expected()));
                    if(!passed) allPass=false;
                    if(e.infrastructureError) infra=true;
                    boolean hidden=i>=hiddenStart && i<hiddenEnd;
                    Map<String,Object> item=new LinkedHashMap<>();item.put("case",i+1);item.put("passed",passed);item.put("hidden",hidden);item.put("exitCode",e.exitCode);item.put("timedOut",e.timedOut);
                    if(!hidden) { item.put("expected",c.expected());item.put("actual",e.output.length()>2000?e.output.substring(0,2000)+"\n[truncated]":e.output); }
                    results.add(item);
                    if(e.infrastructureError || e.timedOut) break;
                }
                s.status=infra?"ERROR":allPass?"PASSED":"FAILED";
                s.resultJson=json.writeValueAsString(Map.of("cases",results));
            } catch(Exception e) { s.status="ERROR";s.resultJson="{\"error\":\"Judge unavailable or failed\"}"; }
            heartbeat.cancel(false);
            metrics.timer("interviewlab.judge.execution").record(System.nanoTime()-executionStarted,TimeUnit.NANOSECONDS);
            metrics.counter("interviewlab.judge.completed","status",s.status).increment();
            String finalStatus=s.status, finalResult=s.resultJson;
            int updated=transactions.execute(tx->submissions.complete(id,owner,finalStatus,finalResult,Instant.now()));
            if(updated==1) socket.publishSubmission(submissions.findById(id).orElse(s));
        } catch(InterruptedException e) { Thread.currentThread().interrupt();break; } catch(Exception ignored) { /* one failed job must not stop the worker */ }
    }
    private UUID claimNext() {
        for(UUID id:submissions.claimable(Instant.now(),org.springframework.data.domain.PageRequest.of(0,20))) {
            Instant now=Instant.now();
            try {
                Integer updated=transactions.execute(tx->submissions.claim(id,owner,now,now.plusSeconds(10)));
                if(updated!=null && updated==1) return id;
            } catch(Exception ignored) { /* competing worker may have claimed it */ }
        }
        return null;
    }
    private String normalize(String s) { return s.replace("\r\n","\n").stripTrailing(); }
    record Execution(String output,int exitCode,boolean timedOut,boolean infrastructureError) {}
    private Execution execute(Submission submission,String input) {
        Path dir=null; String name="interviewlab-"+UUID.randomUUID().toString().substring(0,12);
        try {
            dir=Files.createTempDirectory("interviewlab-judge-");
            boolean java="java".equals(submission.language);
            Files.writeString(dir.resolve(java?"Main.java":"main.py"),submission.code,StandardCharsets.UTF_8);
            String image=java?"eclipse-temurin:17-jdk-alpine":"python:3.12-alpine";
            List<String> args=new ArrayList<>(List.of("docker","run","--rm","--name",name,"--network","none","--read-only","--cap-drop","ALL","--security-opt","no-new-privileges","--pids-limit","64","--memory","256m","--memory-swap","256m","--cpus","1","--user","65534:65534","--log-driver","none","--tmpfs","/tmp:rw,noexec,nosuid,size=64m","--mount","type=bind,source="+dir.toAbsolutePath()+",target=/workspace,readonly","-i",image));
            if(java) args.addAll(List.of("sh","-c","javac -encoding UTF-8 -d /tmp /workspace/Main.java && java -cp /tmp Main"));
            else args.addAll(List.of("python","/workspace/main.py"));
            Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
            LimitedOutput capture=new LimitedOutput(p);Thread reader=new Thread(capture,"judge-output");reader.setDaemon(true);reader.start();
            try(OutputStream stdin=p.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            boolean ended=p.waitFor(timeoutSeconds,TimeUnit.SECONDS);
            if(!ended) p.destroyForcibly();
            reader.join(1000);
            String output=capture.text();
            boolean infra=output.contains("Cannot connect to the Docker daemon") || output.contains("error during connect") || output.contains("Unable to find image") || output.contains("pull access denied");
            return new Execution(output,ended?p.exitValue():-1,!ended || capture.overflow,infra);
        } catch(Exception e) { return new Execution("Judge unavailable: "+e.getClass().getSimpleName(),-1,false,true); }
        finally {
            try { new ProcessBuilder("docker","rm","-f",name).redirectErrorStream(true).start().waitFor(2,TimeUnit.SECONDS); } catch(Exception ignored) {}
            if(dir!=null) try { Files.deleteIfExists(dir.resolve("Main.java"));Files.deleteIfExists(dir.resolve("main.py"));Files.deleteIfExists(dir); } catch(IOException ignored) {}
        }
    }
    private static class LimitedOutput implements Runnable {
        final Process process; final ByteArrayOutputStream buffer=new ByteArrayOutputStream(); volatile boolean overflow=false;
        LimitedOutput(Process p) { process=p; }
        public void run() { try(InputStream in=process.getInputStream()) { byte[] chunk=new byte[1024];int n;while((n=in.read(chunk))!=-1) { synchronized(buffer) { int room=16384-buffer.size();if(room>0) buffer.write(chunk,0,Math.min(n,room));if(n>room) { overflow=true;process.destroyForcibly();break; } } } } catch(IOException ignored) {} }
        String text() { synchronized(buffer) { return buffer.toString(StandardCharsets.UTF_8); } }
    }
}
