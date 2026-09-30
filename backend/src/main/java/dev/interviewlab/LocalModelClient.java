package dev.interviewlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component class LocalModelClient {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ObjectMapper json;
    private final URI base;
    private final String model;
    private final Semaphore oneGeneration=new Semaphore(1,true);
    LocalModelClient(ObjectMapper json,@Value("${app.ai.url:http://127.0.0.1:11434}") String url,@Value("${app.ai.model:qwen3.5:9b-q8_0}") String model) {
        this.json=json;this.base=URI.create(url.endsWith("/")?url:url+"/");this.model=model;
    }
    String model() { return model; }
    record Status(boolean available,String model,List<String> installedModels) {}
    Status status() {
        try {
            HttpRequest request=HttpRequest.newBuilder(base.resolve("api/tags")).timeout(Duration.ofSeconds(4)).GET().build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()!=200) return new Status(false,model,List.of());
            List<String> names=new ArrayList<>();
            for(JsonNode item:json.readTree(response.body()).path("models")) names.add(item.path("name").asText());
            return new Status(names.contains(model),model,names);
        } catch(Exception e) { return new Status(false,model,List.of()); }
    }
    JsonNode chat(String roleInstructions,String task,Map<String,Object> schema) {
        if(!oneGeneration.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Local model is busy; retry shortly");
        try {
            Map<String,Object> payload=Map.of(
                "model",model,"stream",false,"think",false,"format",schema,
                "options",Map.of("temperature",0.2,"num_predict",750,"num_ctx",8192),
                "messages",List.of(Map.of("role","system","content",roleInstructions),Map.of("role","user","content",task))
            );
            HttpRequest request=HttpRequest.newBuilder(base.resolve("api/chat"))
                .timeout(Duration.ofSeconds(180)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload),StandardCharsets.UTF_8)).build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()!=200) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Local model is unavailable or missing");
            String answer=json.readTree(response.body()).path("message").path("content").asText();
            JsonNode structured=json.readTree(answer);
            if(!structured.isObject()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Local model returned invalid JSON");
            return structured;
        } catch(ResponseStatusException e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Local model request interrupted"); }
        catch(Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Local model request failed"); }
        finally { oneGeneration.release(); }
    }
}
