package com.abel.ledger.api.controller;

import com.abel.ledger.api.dto.request.LoginRequest;
import com.abel.ledger.api.dto.response.ErrorResponse;
import com.abel.ledger.api.dto.response.LoginResponse;
import com.abel.ledger.domain.user.User;
import com.abel.ledger.repository.UserRepository;
import com.abel.ledger.security.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Login and JWT issuance")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public AuthController(
            AuthenticationManager authenticationManager, UserRepository userRepository, JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and obtain a JWT",
            description = "Exchanges a username/password for a signed JWT. Send the token back as "
                    + "'Authorization: Bearer <token>' on subsequent requests.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Authenticated",
                content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unknown username or invalid password",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException e) {
            ErrorResponse body = new ErrorResponse(
                    Instant.now(),
                    HttpStatus.UNAUTHORIZED.value(),
                    HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                    "Invalid username or password",
                    "/api/v1/auth/login");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }

        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalStateException(
                        "Authentication succeeded but user '" + request.username() + "' could not be reloaded"));
        JwtService.IssuedToken issued = jwtService.generateToken(user.getUsername(), user.getRole());
        return ResponseEntity.ok(new LoginResponse(issued.token(), issued.expiresAt()));
    }
}
