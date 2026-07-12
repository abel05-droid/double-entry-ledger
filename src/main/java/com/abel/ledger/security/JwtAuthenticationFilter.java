package com.abel.ledger.security;

import com.abel.ledger.domain.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates the {@link SecurityContextHolder} from a {@code Bearer} JWT, if
 * one is present and valid. On any failure to parse or verify the token —
 * malformed, expired, wrong signature — this filter leaves the request
 * unauthenticated and continues the chain rather than rejecting it
 * directly; whether that matters is left entirely to
 * {@code authorizeHttpRequests}, which is what turns "reached a protected
 * endpoint anonymously" into the 401 that {@link RestAuthenticationEntryPoint}
 * writes. This filter never logs a token or any part of one.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                Claims claims = jwtService.parseClaims(token);
                authenticate(claims);
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("Rejected JWT on {} {}: {}", request.getMethod(), request.getRequestURI(),
                        e.getClass().getSimpleName());
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(Claims claims) {
        String username = claims.getSubject();
        Role role = jwtService.extractRole(claims);
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        var authToken = new UsernamePasswordAuthenticationToken(username, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
