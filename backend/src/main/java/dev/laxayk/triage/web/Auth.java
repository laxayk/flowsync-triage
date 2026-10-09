package dev.laxayk.triage.web;

import dev.laxayk.triage.domain.Rows.UserRow;
import dev.laxayk.triage.store.Store;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo-grade auth: two seeded users (an AGENT who works tickets, an APPROVER who may send replies and release held mail).
 * Passwords come from env vars and are stored as bcrypt hashes; sessions are opaque random tokens held in memory.
 * Production would swap this for SSO/OIDC; the role checks in the controllers stay the same.
 */
@Component
public class Auth {
    public record Session(String username, String role) {}

    private final Store store;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(8);
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final String agentPw, approverPw;

    public Auth(Store store, @Value("${app.auth.agent-password:agent123}") String agentPw,
                @Value("${app.auth.approver-password:approver123}") String approverPw) {
        this.store = store;
        this.agentPw = agentPw;
        this.approverPw = approverPw;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (store.countUsers() == 0) {
            store.insertUser("agent", encoder.encode(agentPw), "AGENT");
            store.insertUser("approver", encoder.encode(approverPw), "APPROVER");
        }
    }

    public Optional<String> login(String username, String password) {
        Optional<UserRow> u = store.findUser(username == null ? "" : username);
        if (u.isEmpty() || password == null || !encoder.matches(password, u.get().passwordHash())) return Optional.empty();
        byte[] b = new byte[24];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        sessions.put(token, new Session(u.get().username(), u.get().role()));
        return Optional.of(token);
    }

    public Session session(String token) { return token == null ? null : sessions.get(token); }

    public void logout(String token) { if (token != null) sessions.remove(token); }

    public static String tokenOf(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) return h.substring(7).trim();
        return req.getParameter("token"); // EventSource cannot set headers
    }

    @Configuration
    static class WebConfig implements WebMvcConfigurer {
        private final Auth auth;
        private final String origins;

        WebConfig(Auth auth, @Value("${app.cors-origins:http://localhost:4200}") String origins) {
            this.auth = auth;
            this.origins = origins;
        }

        @Override
        public void addInterceptors(InterceptorRegistry r) {
            r.addInterceptor(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object h) throws Exception {
                    if (req.getMethod().equals("OPTIONS")) return true;
                    Session s = auth.session(tokenOf(req));
                    if (s == null) {
                        res.setStatus(401);
                        res.setContentType("application/json");
                        res.getWriter().write("{\"error\":\"unauthorized\"}");
                        return false;
                    }
                    req.setAttribute("session", s);
                    return true;
                }
            }).addPathPatterns("/api/**").excludePathPatterns("/api/auth/login", "/api/health");
        }

        @Override
        public void addCorsMappings(CorsRegistry r) {
            r.addMapping("/api/**").allowedOrigins(origins.split(",")).allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                    .allowedHeaders("*");
        }
    }
}
