package dev.interviewlab;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/ai") class AiApi {
    private final AiService ai;
    AiApi(AiService ai) { this.ai=ai; }
    record ProblemRequest(String difficulty,String topic) {}
    record HintRequest(String request) {}
    record CommunicationRequest(String text,String source) {}
    @GetMapping("/status") Object status() { return ai.status(); }
    @PostMapping("/problems") Object problem(@RequestBody ProblemRequest body) { return ai.selectProblem(body.difficulty(),body.topic()); }
    @GetMapping("/rooms/{id}/events") Object events(@PathVariable UUID id,HttpServletRequest req) { return ai.history(id,SecurityConfig.user(req)); }
    @PostMapping("/rooms/{id}/hints") Object hint(@PathVariable UUID id,@RequestBody HintRequest body,HttpServletRequest req) { return ai.hint(id,SecurityConfig.user(req),body.request()); }
    @PostMapping("/rooms/{id}/questions") Object question(@PathVariable UUID id,HttpServletRequest req) { return ai.question(id,SecurityConfig.user(req)); }
    @PostMapping("/rooms/{id}/review") Object review(@PathVariable UUID id,HttpServletRequest req) { return ai.review(id,SecurityConfig.user(req)); }
    @PostMapping("/rooms/{id}/communication") Object communication(@PathVariable UUID id,@RequestBody CommunicationRequest body,HttpServletRequest req) { return ai.communication(id,SecurityConfig.user(req),body.text(),body.source()); }
}
