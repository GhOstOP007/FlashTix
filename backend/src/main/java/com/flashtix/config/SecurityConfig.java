
package com.flashtix.config;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder(12);
        }

        @Bean
        public SecurityFilterChain securityFilterChain(
                        HttpSecurity http,
                        Environment environment) throws Exception {

                boolean devMode = environment.acceptsProfiles(
                                Profiles.of("dev"));

                http
                                // Stateless REST API using bearer tokens.
                                .csrf(csrf -> csrf.disable())

                                .sessionManagement(session -> session.sessionCreationPolicy(
                                                SessionCreationPolicy.STATELESS))

                                .authorizeHttpRequests(auth -> {

                                        // ========================================
                                        // 1. INTERNAL ERROR DISPATCHES
                                        // ========================================

                                        // Allow Spring Boot to render the original
                                        // error status rather than masking it
                                        // with a security-generated 403.
                                        auth.dispatcherTypeMatchers(
                                                        DispatcherType.ERROR).permitAll();

                                        // ========================================
                                        // 2. PUBLIC AUTHENTICATION
                                        // ========================================

                                        auth.requestMatchers(
                                                        HttpMethod.POST,
                                                        "/api/auth/register",
                                                        "/api/auth/login").permitAll();

                                        // ========================================
                                        // 3. PUBLIC EVENT DISCOVERY
                                        // ========================================

                                        auth.requestMatchers(
                                                        HttpMethod.GET,
                                                        "/api/health",
                                                        "/api/events",
                                                        "/api/events/**",
                                                        "/api/shows",
                                                        "/api/shows/**",
                                                        "/api/venues",
                                                        "/api/venues/**").permitAll();

                                        // ========================================
                                        // 4. AUTHENTICATED USER PROFILE
                                        // ========================================

                                        auth.requestMatchers(
                                                        "/api/auth/me").authenticated();

                                        // ========================================
                                        // 5. AUTHENTICATED RESERVATIONS
                                        // ========================================

                                        auth.requestMatchers(
                                                        "/api/reservations",
                                                        "/api/reservations/**").authenticated();

                                        // ========================================
                                        // 6. DEVELOPMENT-ONLY ENDPOINTS
                                        // ========================================

                                        if (devMode) {

                                                // Local development data creation.
                                                // Never permit these anonymously
                                                // in a public deployment.
                                                auth.requestMatchers(
                                                                HttpMethod.POST,
                                                                "/api/events",
                                                                "/api/venues",
                                                                "/api/shows").permitAll();

                                                // Mock payment order APIs.
                                                auth.requestMatchers(
                                                                "/api/payments/orders",
                                                                "/api/payments/orders/**",
                                                                "/api/dev/payments/**").authenticated();

                                                // Razorpay Test Mode:
                                                // create orders, read orders,
                                                // reconcile, and fulfil.
                                                auth.requestMatchers(
                                                                "/api/dev/razorpay/orders",
                                                                "/api/dev/razorpay/orders/**").authenticated();

                                                // Razorpay webhook authentication
                                                // happens through HMAC signature
                                                // verification inside the service.
                                                auth.requestMatchers(
                                                                HttpMethod.POST,
                                                                "/api/webhooks/razorpay").permitAll();
                                        }

                                        // ========================================
                                        // 7. DEFAULT DENY
                                        // ========================================

                                        // All other requests are rejected.
                                        auth.anyRequest().denyAll();
                                })

                                // Validate JWT signatures, issuer and expiry.
                                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

                return http.build();
        }
}
