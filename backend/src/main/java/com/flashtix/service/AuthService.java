
package com.flashtix.service;

import com.flashtix.dto.AuthResponse;
import com.flashtix.dto.LoginRequest;
import com.flashtix.dto.RegisterRequest;
import com.flashtix.entity.User;
import com.flashtix.repository.UserRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;

    private final String issuer;
    private final long expirationMinutes;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            @Value("${flashtix.jwt.issuer}") String issuer,
            @Value("${flashtix.jwt.expiration-minutes}") long expirationMinutes) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
        this.expirationMinutes = expirationMinutes;
    }

    // -------------------------------------------
    // REGISTER
    // -------------------------------------------

    public AuthResponse register(RegisterRequest request) {

        String normalizedEmail = normalizeEmail(
                request.email());

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Email is already registered");
        }

        String passwordHash = passwordEncoder.encode(
                request.password());

        User user = new User(
                request.fullName().trim(),
                normalizedEmail,
                passwordHash);

        try {
            // Flush so a concurrent duplicate-email insert
            // is detected before returning success.
            userRepository.saveAndFlush(user);

        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Email is already registered",
                    ex);
        }

        return createAuthResponse(user);
    }

    // -------------------------------------------
    // LOGIN
    // -------------------------------------------

    public AuthResponse login(LoginRequest request) {

        String normalizedEmail = normalizeEmail(
                request.email());

        User user = userRepository
                .findByEmail(normalizedEmail)
                .orElseThrow(() -> invalidCredentials());

        if (!user.isEnabled()
                || !passwordEncoder.matches(
                        request.password(),
                        user.getPasswordHash())) {

            throw invalidCredentials();
        }

        return createAuthResponse(user);
    }

    // -------------------------------------------
    // CURRENT USER
    // -------------------------------------------

    public User getCurrentUser(UUID userId) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "User account not found"));

        if (!user.isEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "User account disabled");
        }

        return user;
    }

    // -------------------------------------------
    // JWT GENERATION
    // -------------------------------------------

    private AuthResponse createAuthResponse(User user) {

        Instant issuedAt = Instant.now();

        Instant expiresAt = issuedAt.plus(
                expirationMinutes,
                ChronoUnit.MINUTES);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("email", user.getEmail())
                .claim("role", user.getRole())
                .build();

        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .build();

        String token = jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new AuthResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                token,
                "Bearer",
                expirationMinutes * 60);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Invalid email or password");
    }
}
