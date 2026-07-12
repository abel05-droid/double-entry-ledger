package com.abel.ledger.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security for the REST API. No sessions, no CSRF (there are
 * no cookies to protect against CSRF for — auth is a bearer token the
 * client attaches itself), no {@code httpBasic}/{@code formLogin}: the only
 * way in is {@code POST /api/v1/auth/login} plus a {@code Bearer} token on
 * every subsequent request.
 *
 * <p>Authorization is enforced here, at the servlet filter level, and
 * nowhere else — {@code PostingService}, {@code BalanceService}, and
 * {@code LedgerEventPublisher} have no dependency on Spring Security and no
 * awareness that roles exist.
 *
 * <p>{@code @EnableWebSecurity} is explicit here rather than relying on
 * Spring Boot's implicit auto-configuration, because that auto-import
 * ({@code WebSecurityEnablerConfiguration}) is gated behind
 * {@code @ConditionalOnWebApplication(type = SERVLET)}. Tests such as
 * {@code PostingServiceConcurrencyTest} boot a
 * {@code @SpringBootTest(webEnvironment = NONE)} context — component
 * scanning still finds and instantiates every {@code @RestController},
 * including {@code AuthController}, which needs an
 * {@code AuthenticationManager} bean regardless — so the beans this class
 * provides must be available in any context type, not just a servlet one.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService) {
        return new JwtAuthenticationFilter(jwtService);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/api/v1/auth/login",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/journal-entries")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
