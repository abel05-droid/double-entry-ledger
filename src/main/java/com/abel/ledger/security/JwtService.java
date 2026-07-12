package com.abel.ledger.security;

import com.abel.ledger.domain.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies the HS256 JWTs this service uses for stateless
 * authentication. Claims are deliberately minimal: {@code sub} (username),
 * {@code role}, {@code iat}, {@code exp} — enough to authorize a request
 * without a database round-trip per call.
 *
 * <p>{@code jwt.secret} is hashed with SHA-256 before use as the signing
 * key, rather than used directly as raw key bytes. HS256 requires a key of
 * at least 256 bits; the default {@code change-me-for-local-dev} value (and
 * any other human-typed secret) is shorter than that, so using it verbatim
 * would make {@link Keys#hmacShaKeyFor} reject it at startup. Hashing
 * always yields exactly 32 bytes regardless of the configured secret's
 * length while still deriving deterministically from it, so two instances
 * configured with the same {@code jwt.secret} verify each other's tokens.
 */
@Service
public class JwtService {

    private static final String ROLE_CLAIM = "role";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(sha256(properties.secret()));
    }

    public IssuedToken generateToken(String username, Role role) {
        return generateToken(username, role, Instant.now());
    }

    /**
     * Package-visible so tests can mint an already-expired token (by
     * passing an {@code issuedAt} far enough in the past) using this
     * service's real signing key, without exposing that key outside this
     * package.
     */
    IssuedToken generateToken(String username, Role role, Instant issuedAt) {
        Instant expiry = issuedAt.plus(properties.expiration());
        String token = Jwts.builder()
                .subject(username)
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiry);
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    /**
     * Parses and verifies {@code token}, returning its claims.
     *
     * @throws JwtException if the token is malformed, has an invalid
     *     signature, or is expired
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Role extractRole(Claims claims) {
        return Role.valueOf(claims.get(ROLE_CLAIM, String.class));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
