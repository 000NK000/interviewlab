package dev.interviewlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.stereotype.Component;

@Configuration @EnableWebSocket class SocketConfig implements WebSocketConfigurer {
    private final RoomSocket socket; private final RoomService rooms;
    @org.springframework.beans.factory.annotation.Value("${app.origin:http://localhost:15173}") private String appOrigin;
    SocketConfig(RoomSocket socket,RoomService rooms) { this.socket=socket;this.rooms=rooms; }
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket,"/ws/rooms/*").setAllowedOrigins(appOrigin).addInterceptors(new HandshakeInterceptor() {
            public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes) {
                if(!(request instanceof ServletServerHttpRequest servlet)) return false;
                Object id=servlet.getServletRequest().getSession(false)==null?null:servlet.getServletRequest().getSession(false).getAttribute(SecurityConfig.USER_KEY);
                if(!(id instanceof UUID user)) return false;
                try {
                    URI uri=request.getURI(); UUID room=UUID.fromString(uri.getPath().substring(uri.getPath().lastIndexOf('/')+1));
                    rooms.require(room,user);attributes.put("user",user);attributes.put("room",room);return true;
                } catch(Exception ignored) { return false; }
            }
            public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception exception) {}
        });
    }
}

@Component class RoomSocket extends org.springframework.web.socket.handler.TextWebSocketHandler {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RoomSocket.class);
    private final RoomService rooms; private final ObjectMapper json; private final SubmissionRepo submissions;
    private final Map<UUID,Set<WebSocketSession>> active=new ConcurrentHashMap<>();
    private final Map<UUID,String> lastStatus=new ConcurrentHashMap<>();
    RoomSocket(RoomService rooms,ObjectMapper json,SubmissionRepo submissions,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.rooms=rooms;this.json=json;this.submissions=submissions;
        io.micrometer.core.instrument.Gauge.builder("interviewlab.websocket.connections",active,m->m.values().stream().mapToInt(Set::size).sum()).register(metrics);
    }
    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID room=(UUID)session.getAttributes().get("room"),user=(UUID)session.getAttributes().get("user");
        active.computeIfAbsent(room,k->ConcurrentHashMap.newKeySet()).add(session);
        send(session,Map.of("type","snapshot","room",rooms.get(room,user)));
        broadcast(room,Map.of("type","presence","count",active.get(room).size()));
    }
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message) throws Exception {
        UUID room=(UUID)session.getAttributes().get("room"),user=(UUID)session.getAttributes().get("user");
        JsonNode node=json.readTree(message.getPayload());String type=node.path("type").asText();
        if("edit".equals(type)) {
            try {
                var update=new RoomApi.UpdateCode(node.path("code").asText(),node.path("language").asText(),node.path("baseVersion").asLong(-1));
                var result=rooms.edit(room,user,update);
                broadcast(room,Map.of("type","code","room",result,"authorId",user.toString()));
            } catch(org.springframework.web.server.ResponseStatusException e) {
                send(session,Map.of("type","conflict","room",rooms.get(room,user),"message",e.getReason()==null?"Edit rejected":e.getReason()));
            }
        } else if("cursor".equals(type)) {
            int line=Math.max(1,Math.min(100000,node.path("line").asInt(1)));
            int column=Math.max(1,Math.min(100000,node.path("column").asInt(1)));
            for(var peer:active.getOrDefault(room,Set.of())) if(peer!=session) send(peer,Map.of("type","cursor","userId",user.toString(),"line",line,"column",column));
        }
    }
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status) throws Exception {
        log.debug("Room socket {} closed: {}",session.getId(),status);
        UUID room=(UUID)session.getAttributes().get("room");
        Set<WebSocketSession> set=active.get(room);if(set!=null) { set.remove(session);broadcast(room,Map.of("type","presence","count",set.size()));if(set.isEmpty()) active.remove(room,set); }
    }
    @Override public void handleTransportError(WebSocketSession session,Throwable error) {
        log.warn("Room socket {} transport error",session.getId(),error);
    }
    private void broadcast(UUID room,Object data) { for(var peer:active.getOrDefault(room,Set.of())) send(peer,data); }
    void publishSubmission(Submission submission) { broadcast(submission.roomId,Map.of("type","submission","submission",submission)); }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000)
    void publishDatabaseChanges() {
        for(UUID room:active.keySet()) try {
            for(Submission submission:submissions.findByRoomIdOrderByCreatedAtDesc(room)) {
                String previous=lastStatus.put(submission.id,submission.status);
                if(previous==null || !previous.equals(submission.status)) publishSubmission(submission);
            }
        } catch(Exception e) { log.warn("Could not poll submission updates for room {}",room,e); }
        if(active.isEmpty()) lastStatus.clear();
    }
    private void send(WebSocketSession session,Object data) { try { synchronized(session) { if(session.isOpen()) session.sendMessage(new TextMessage(json.writeValueAsString(data))); } } catch(Exception ignored) {} }
}
