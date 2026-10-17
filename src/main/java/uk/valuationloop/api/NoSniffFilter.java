package uk.valuationloop.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds {@code X-Content-Type-Options: nosniff} to every response, so a browser never guesses a JSON body is HTML.
 * A servlet filter wraps every request once, before any controller (Django: a tiny middleware).
 */
@Component
public class NoSniffFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        chain.doFilter(request, response);
    }
}
