package dev.interviewlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={
    "spring.datasource.url=jdbc:h2:mem:multiroom;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.judge-workers=0",
    "management.server.port=0"
})
class MultiRoomLoadTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    record Pair(HttpClient interviewer,HttpClient candidate,String room) {}
    record Result(long editMs,boolean isolated,boolean reconnected,boolean conflict) {}
    String base() { return "http://127.0.0.1:"+port; }
    HttpClient client() { CookieManager cookies=new CookieManager();cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);return HttpClient.newBuilder().cookieHandler(cookies).build(); }
    JsonNode call(HttpClient client,String method,String path,Object body) throws Exception {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base()+path)).header("Content-Type","application/json");
        if("GET".equals(method)) request.GET(); else request.method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body());
    }
    static class Messages implements WebSocket.Listener {
        final BlockingQueue<String> queue=new LinkedBlockingQueue<>();final StringBuilder partial=new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last) {
            partial.append(text);if(last){queue.add(partial.toString());partial.setLength(0);}socket.request(1);return null;
        }
        JsonNode take(ObjectMapper json,String type) throws Exception {
            for(int i=0;i<12;i++) { String raw=queue.poll(10,TimeUnit.SECONDS);assertNotNull(raw,"Missing WebSocket "+type);JsonNode node=json.readTree(raw);if(type.equals(node.path("type").asText())) return node; }
            fail("Missing WebSocket "+type);return null;
        }
    }
    WebSocket connect(HttpClient client,String room,Messages messages) throws Exception {
        return client.newWebSocketBuilder().header("Origin","http://localhost:15173")
            .buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/rooms/"+room),messages).get(10,TimeUnit.SECONDS);
    }
    Pair prepare(int index) throws Exception {
        HttpClient a=client(),b=client();String suffix=UUID.randomUUID().toString().substring(0,8);
        call(a,"POST","/api/auth/register",Map.of("email","ws-a-"+index+"-"+suffix+"@test.local","password","password123","displayName","A"));
        call(b,"POST","/api/auth/register",Map.of("email","ws-b-"+index+"-"+suffix+"@test.local","password","password123","displayName","B"));
        JsonNode made=call(a,"POST","/api/rooms",Map.of("title","Room "+index,"prompt","Print a value","durationMinutes",45));
        String room=made.path("room").path("id").asText();
        call(b,"POST","/api/rooms/"+room+"/join",Map.of("inviteToken",made.path("inviteToken").asText()));
        call(a,"POST","/api/rooms/"+room+"/start",Map.of());
        return new Pair(a,b,room);
    }
    Result exercise(Pair pair,CyclicBarrier allConnected) throws Exception {
        Messages a=new Messages(),b=new Messages();WebSocket wsA=connect(pair.interviewer,pair.room,a),wsB=connect(pair.candidate,pair.room,b);
        a.take(json,"snapshot");b.take(json,"snapshot");
        // Every room keeps both sockets open before any room starts its edit.
        allConnected.await(30,TimeUnit.SECONDS);
        wsA.sendText("{\"type\":\"cursor\",\"line\":3,\"column\":5}",true).get(10,TimeUnit.SECONDS);
        assertEquals(3,b.take(json,"cursor").path("line").asInt());
        long started=System.nanoTime();
        wsB.sendText("{\"type\":\"edit\",\"code\":\"print(1)\",\"language\":\"python\",\"baseVersion\":0}",true).get(10,TimeUnit.SECONDS);
        JsonNode accepted=a.take(json,"code");long editMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        b.take(json,"code");
        wsA.sendText("{\"type\":\"edit\",\"code\":\"print(0)\",\"language\":\"python\",\"baseVersion\":0}",true).get(10,TimeUnit.SECONDS);
        boolean conflict=a.take(json,"conflict").path("room").path("version").asInt()==1;
        wsB.sendClose(WebSocket.NORMAL_CLOSURE,"reconnect").get(10,TimeUnit.SECONDS);
        Messages resumed=new Messages();WebSocket wsB2=connect(pair.candidate,pair.room,resumed);
        boolean reconnected=resumed.take(json,"snapshot").path("room").path("version").asInt()==1;
        boolean isolated=accepted.path("room").path("id").asText().equals(pair.room)
            && call(pair.candidate,"GET","/api/rooms/"+pair.room,null).path("version").asInt()==1;
        wsA.sendClose(WebSocket.NORMAL_CLOSURE,"done").get(10,TimeUnit.SECONDS);
        wsB2.sendClose(WebSocket.NORMAL_CLOSURE,"done").get(10,TimeUnit.SECONDS);
        return new Result(editMs,isolated,reconnected,conflict);
    }
    @Test void multipleRoomsEditCursorConflictAndReconnect() throws Exception {
        int count=Integer.getInteger("interviewlab.ws.rooms",8);
        List<Pair> pairs=new ArrayList<>();for(int i=0;i<count;i++) pairs.add(prepare(i));
        ExecutorService pool=Executors.newFixedThreadPool(count);
        CyclicBarrier allConnected=new CyclicBarrier(count);
        long start=System.nanoTime();
        try {
            List<Future<Result>> futures=new ArrayList<>();for(Pair pair:pairs) futures.add(pool.submit(()->exercise(pair,allConnected)));
            List<Result> results=new ArrayList<>();for(Future<Result> future:futures) results.add(future.get(60,TimeUnit.SECONDS));
            assertTrue(results.stream().allMatch(r->r.isolated&&r.reconnected&&r.conflict));
            List<Long> latencies=results.stream().map(Result::editMs).sorted().toList();
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("rooms",count);report.put("users",count*2);report.put("simultaneousSockets",count*2);
            report.put("editP50Ms",latencies.get((int)Math.ceil(count*.5)-1));report.put("editP95Ms",latencies.get((int)Math.ceil(count*.95)-1));
            report.put("durationMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));report.put("cursorChecks",count);
            report.put("conflictChecks",count);report.put("reconnectChecks",count);report.put("passed",true);report.put("time",Instant.now().toString());
            String path=System.getProperty("interviewlab.ws.out");
            if(path!=null) Files.writeString(Path.of(path),json.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n");
            System.out.println("MULTI_ROOM_LOAD="+json.writeValueAsString(report));
        } finally { pool.shutdownNow(); }
    }
}
