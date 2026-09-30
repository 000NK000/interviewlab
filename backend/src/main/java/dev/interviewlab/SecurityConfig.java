package dev.interviewlab;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration class SecurityConfig {
    static final String USER_KEY="interviewlab_user";
    @org.springframework.beans.factory.annotation.Value("${app.origin:http://localhost:15173}") private String appOrigin;
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
          .headers(h -> h.frameOptions(f -> f.sameOrigin()))
          .authorizeHttpRequests(a -> a
             .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
             .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/logout", "/actuator/health").permitAll()
             .requestMatchers("/api/**", "/ws/**").authenticated()
             .anyRequest().permitAll())
          .addFilterBefore(new SessionFilter(appOrigin), org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    static UUID user(HttpServletRequest request) {
        Object id=request.getSession(false)==null?null:request.getSession(false).getAttribute(USER_KEY);
        if (!(id instanceof UUID uuid)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        return uuid;
    }
    static class SessionFilter extends OncePerRequestFilter {
        private final String appOrigin;
        SessionFilter(String appOrigin) { this.appOrigin=appOrigin; }
        @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            String origin=req.getHeader("Origin");
            if (origin!=null && !origin.equals(appOrigin) && !origin.equals(req.getScheme()+"://"+req.getServerName()+(req.getServerPort()==80||req.getServerPort()==443?"":":"+req.getServerPort()))) {
                res.sendError(403,"Cross-origin request rejected"); return;
            }
            Object id=req.getSession(false)==null?null:req.getSession(false).getAttribute(USER_KEY);
            if (id instanceof UUID uuid) {
                var auth=new UsernamePasswordAuthenticationToken(uuid,null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            try { chain.doFilter(req,res); } finally { SecurityContextHolder.clearContext(); }
        }
    }
}
