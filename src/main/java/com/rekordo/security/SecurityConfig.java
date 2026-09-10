package com.rekordo.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * Endpoints reachable without an account.
     *
     * The metadata proxy is deliberately open: the app is local-first, and someone with no
     * account must still be able to search releases and scan barcodes. Abuse is bounded by
     * the per-IP rate limiter and the response cache in front of MusicBrainz, not by
     * authentication.
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/health",
            "/api/v1/metadata/**",
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            // Followed in whichever browser opened the mail, which is often not a signed-in
            // one. The token in the body is the whole proof.
            "/api/v1/auth/confirm-email",
            // Asked from the browser that landed on a dead link with no session (21d).
            "/api/v1/auth/confirm-email/request",
            // The undo in the notice to an old address. It has to work from a mailbox that
            // can no longer sign in, which is the whole point of it.
            "/api/v1/auth/email-change/cancel",
            // Only a public site key, and it is asked for before anybody could be signed in.
            "/api/v1/auth/challenge",
            "/api/v1/auth/providers",
            "/api/v1/auth/oauth/**",
            // Profiles are open for the same reason the metadata proxy is: a public shelf
            // that demands a login to read is not public, and somebody handed a handle
            // should be able to look before deciding the app is worth an account. Every
            // endpoint behind this still answers according to who the viewer turns out to
            // be -- open is not the same as unguarded.
            "/api/v1/profiles/**",
            // The bytes of a picture on a shelf somebody is allowed to see. Authorised per
            // request inside PhotoService against the owner's sharing settings, which a
            // path rule cannot express.
            "/api/v1/photos/*/content",
            // The profile picture, which is the one image in the app that is the same for
            // every viewer. It is account data rather than shelf data, so it renders on a
            // profile whose collection does not (27f) -- and the upload flow says so before
            // anybody commits to it. Setting and removing one is POST/DELETE on
            // /api/v1/avatar itself, which this pattern does not match and which stays
            // behind the token.
            "/api/v1/avatar/*",
            "/actuator/health/**",
            "/actuator/prometheus",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
    };

    private final JwtFilter jwtFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // Stateless token API: there is no session and no browser-submitted form,
                // so there is no CSRF vector. The refresh cookie is SameSite=Strict and is
                // only ever exchanged by an explicit POST from the app's own origin.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                // 401 with an empty body rather than a redirect or a WWW-Authenticate
                // challenge, which would make the browser show its own password prompt.
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
