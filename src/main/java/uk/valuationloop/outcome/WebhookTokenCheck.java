package uk.valuationloop.outcome;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * POST /outcomes/webhook only accepts a call whose X-Webhook-Token header equals
 * valuationloop.outcome.webhook-token; anything else is 401 before the body is even read or validated.
 * <p>
 * Demo stand-in: production gives each CRM its own secret and checks an HMAC signature over the body, which also
 * binds the leadRef to the agent whose CRM signed it (so one agent's CRM cannot record another agent's instruction).
 * <p>
 * A HandlerInterceptor runs before the controller method (Django: a decorator on the view); preHandle throwing
 * an exception ends the request there, and {@link uk.valuationloop.api.ApiErrors} turns it into the JSON 401.
 */
@Configuration
public class WebhookTokenCheck implements HandlerInterceptor, WebMvcConfigurer {

    static final String HEADER = "X-Webhook-Token";

    private final byte[] expected;

    public WebhookTokenCheck(@Value("${valuationloop.outcome.webhook-token}") String token) {
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/outcomes/webhook");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String given = request.getHeader(HEADER);
        // MessageDigest.isEqual compares in constant time, so response timing does not reveal how much matched
        if (given == null || !MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
            throw new Unauthorized();
        }
        return true;
    }

    /** 401 (mapped in {@link uk.valuationloop.api.ApiErrors}). */
    public static class Unauthorized extends RuntimeException {
        public Unauthorized() { super("missing or wrong " + HEADER); }
    }
}
