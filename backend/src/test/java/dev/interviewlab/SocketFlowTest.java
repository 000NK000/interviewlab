package dev.interviewlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={
    "spring.datasource.url=jdbc:h2:mem:socketflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SocketFlowTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    String root() { return "http://localhost:"+port; }
    HttpClient client() {
        CookieManager cookies=new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }
    JsonNode post(HttpClient client,String path,String body) throws Exception {
        HttpRequest request=HttpRequest.newBuilder(URI.create(root()+path)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());
        return json.readTree(response.body());
    }
    static class Messages implements WebSocket.Listener {
        final BlockingQueue<String> queue=new LinkedBlockingQueue<>();
        final StringBuilder current=new StringBuilder();
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket,CharSequence data,boolean last) {
            current.append(data);
            if(last) { queue.add(current.toString());current.setLength(0); }
            webSocket.request(1);return null;
        }
        String take() throws Exception { String value=queue.poll(5,TimeUnit.SECONDS);assertNotNull(value,"WebSocket message missing");return value; }
        JsonNode takeType(ObjectMapper json,String type) throws Exception {
            for(int i=0;i<6;i++) { JsonNode message=json.readTree(take());if(type.equals(message.path("type").asText())) return message; }
            fail("WebSocket message type missing: "+type);return null;
        }
    }
    @Test void twoParticipantsReceiveEditsAndCursor() throws Exception {
        HttpClient interviewer=client(),candidate=client();
        String suffix=java.util.UUID.randomUUID().toString().substring(0,8);
        post(interviewer,"/api/auth/register","{\"email\":\"a-"+suffix+"@test.local\",\"password\":\"password123\",\"displayName\":\"A\"}");
        post(candidate,"/api/auth/register","{\"email\":\"b-"+suffix+"@test.local\",\"password\":\"password123\",\"displayName\":\"B\"}");
        JsonNode created=post(interviewer,"/api/rooms","{\"title\":\"Pairing\",\"prompt\":\"Read input\",\"durationMinutes\":45}");
        String id=created.path("room").path("id").asText();
        post(candidate,"/api/rooms/"+id+"/join","{\"inviteToken\":\""+created.path("inviteToken").asText()+"\"}");
        post(interviewer,"/api/rooms/"+id+"/start","{}");
        Messages a=new Messages(),b=new Messages();
        WebSocket wsA=interviewer.newWebSocketBuilder().header("Origin","http://localhost:15173")
            .buildAsync(URI.create("ws://localhost:"+port+"/ws/rooms/"+id),a).get(5,TimeUnit.SECONDS);
        WebSocket wsB=candidate.newWebSocketBuilder().header("Origin","http://localhost:15173")
            .buildAsync(URI.create("ws://localhost:"+port+"/ws/rooms/"+id),b).get(5,TimeUnit.SECONDS);
        assertEquals("snapshot",json.readTree(a.take()).path("type").asText());
        assertEquals("snapshot",json.readTree(b.take()).path("type").asText());
        wsB.sendText("{\"type\":\"edit\",\"code\":\"print(1)\",\"language\":\"python\",\"baseVersion\":0}",true).get(5,TimeUnit.SECONDS);
        JsonNode edit=a.takeType(json,"code");
        assertEquals("code",edit.path("type").asText());
        assertEquals(1,edit.path("room").path("version").asInt());
        assertEquals("print(1)",edit.path("room").path("code").asText());
        assertEquals("code",b.takeType(json,"code").path("type").asText());
        wsA.sendText("{\"type\":\"cursor\",\"line\":3,\"column\":5}",true).get(5,TimeUnit.SECONDS);
        JsonNode cursor=b.takeType(json,"cursor");
        assertEquals("cursor",cursor.path("type").asText());
        assertEquals(3,cursor.path("line").asInt());
        wsA.sendClose(WebSocket.NORMAL_CLOSURE,"done").get(5,TimeUnit.SECONDS);
        wsB.sendClose(WebSocket.NORMAL_CLOSURE,"done").get(5,TimeUnit.SECONDS);
        post(candidate,"/api/auth/logout","{}");
        post(candidate,"/api/auth/logout","{}"); // stale sessions must still be able to leave the UI
    }
}
