package dev.interviewlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/auth") class AuthApi {
    private final UserRepo users; private final PasswordEncoder encoder;
    AuthApi(UserRepo users, PasswordEncoder encoder) { this.users=users; this.encoder=encoder; }
    record Credentials(@Email @NotBlank String email, @NotBlank String password, String displayName) {}
    record PublicUser(UUID id, String email, String displayName) { PublicUser(AppUser u) { this(u.id,u.email,u.displayName); } }
    @PostMapping("/register") PublicUser register(@Valid @RequestBody Credentials body, HttpServletRequest req) {
        String email=body.email().trim().toLowerCase(Locale.ROOT);
        if (body.password().length()<8 || body.password().length()>128 || body.displayName()==null || body.displayName().isBlank() || body.displayName().length()>80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a name and an 8-128 character password");
        if (users.findByEmail(email).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Email already registered");
        AppUser u=users.save(new AppUser(email,body.displayName().trim(),encoder.encode(body.password())));
        loginSession(req,u.id); return new PublicUser(u);
    }
    @PostMapping("/login") PublicUser login(@Valid @RequestBody Credentials body, HttpServletRequest req) {
        AppUser u=users.findByEmail(body.email().trim().toLowerCase(Locale.ROOT)).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials"));
        if (!encoder.matches(body.password(),u.passwordHash)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");
        loginSession(req,u.id); return new PublicUser(u);
    }
    private void loginSession(HttpServletRequest req, UUID id) { HttpSession session=req.getSession(true); req.changeSessionId(); session.setAttribute(SecurityConfig.USER_KEY,id); session.setMaxInactiveInterval(7*24*3600); }
    @GetMapping("/me") PublicUser me(HttpServletRequest req) { return new PublicUser(users.findById(SecurityConfig.user(req)).orElseThrow()); }
    @PostMapping("/logout") void logout(HttpServletRequest req) { if(req.getSession(false)!=null) req.getSession(false).invalidate(); }
}

@RestController @RequestMapping("/api") class RoomApi {
    private final RoomService rooms; private final JudgeService judge; private final ObjectMapper json;
    RoomApi(RoomService rooms, JudgeService judge, ObjectMapper json) { this.rooms=rooms; this.judge=judge; this.json=json; }
    record NewRoom(@NotBlank String title, @NotBlank String prompt, @Min(10) @Max(240) int durationMinutes, String problemId) {
        NewRoom(String title,String prompt,int durationMinutes) { this(title,prompt,durationMinutes,null); }
    }
    record Join(String inviteToken) {}
    record UpdateCode(String code, String language, long baseVersion) {}
    record NewFeedback(@Min(1) @Max(5) int score, @NotBlank String notes) {}
    record TestCase(String input, String expected) {}
    record RunRequest(List<TestCase> cases) {}
    @GetMapping("/rooms") Object list(HttpServletRequest req) { return rooms.list(SecurityConfig.user(req)); }
    @GetMapping("/problems") Object problems(HttpServletRequest req) { SecurityConfig.user(req);return ProblemBank.publicSpecs(); }
    @PostMapping("/rooms") Object create(@Valid @RequestBody NewRoom body, HttpServletRequest req) { return rooms.create(SecurityConfig.user(req),body); }
    @GetMapping("/rooms/{id}") Object room(@PathVariable UUID id, HttpServletRequest req) { return rooms.get(id,SecurityConfig.user(req)); }
    @PostMapping("/rooms/{id}/join") Object join(@PathVariable UUID id,@RequestBody Join body,HttpServletRequest req) { return rooms.join(id,SecurityConfig.user(req),body.inviteToken()); }
    @PostMapping("/rooms/{id}/invitation") Object invitation(@PathVariable UUID id,HttpServletRequest req) { return Map.of("inviteToken",rooms.refreshInvitation(id,SecurityConfig.user(req))); }
    @PostMapping("/rooms/{id}/start") Object start(@PathVariable UUID id,HttpServletRequest req) { return rooms.transition(id,SecurityConfig.user(req),"LIVE"); }
    @PostMapping("/rooms/{id}/end") Object end(@PathVariable UUID id,HttpServletRequest req) { return rooms.transition(id,SecurityConfig.user(req),"ENDED"); }
    @PutMapping("/rooms/{id}/code") Object edit(@PathVariable UUID id,@RequestBody UpdateCode body,HttpServletRequest req) { return rooms.edit(id,SecurityConfig.user(req),body); }
    @GetMapping("/rooms/{id}/revisions") Object revisions(@PathVariable UUID id,HttpServletRequest req) { return rooms.revisions(id,SecurityConfig.user(req)); }
    @GetMapping("/rooms/{id}/feedback") Object feedback(@PathVariable UUID id,HttpServletRequest req) { return rooms.feedback(id,SecurityConfig.user(req)); }
    @PutMapping("/rooms/{id}/feedback") Object feedback(@PathVariable UUID id,@Valid @RequestBody NewFeedback body,HttpServletRequest req) { return rooms.saveFeedback(id,SecurityConfig.user(req),body); }
    @PostMapping("/rooms/{id}/submissions") Object run(@PathVariable UUID id,@RequestBody RunRequest body,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req) { return judge.submit(id,SecurityConfig.user(req),body.cases(),key); }
    @GetMapping("/rooms/{id}/submissions") Object submissions(@PathVariable UUID id,HttpServletRequest req) { return judge.list(id,SecurityConfig.user(req)); }
    @GetMapping("/submissions/{id}") Object submission(@PathVariable UUID id,HttpServletRequest req) { return judge.get(id,SecurityConfig.user(req)); }
}

@RestControllerAdvice class Errors {
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    ResponseEntity<?> invalid(org.springframework.web.bind.MethodArgumentNotValidException e) { return ResponseEntity.badRequest().body(Map.of("error","Invalid input")); }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<?> invalidJson() { return ResponseEntity.badRequest().body(Map.of("error","Invalid JSON")); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",e.getReason()==null?"Request failed":e.getReason())); }
}
