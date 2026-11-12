
package com.flashtix.controller;

import com.flashtix.dto.AuthResponse;
import com.flashtix.dto.LoginRequest;
import com.flashtix.dto.RegisterRequest;
import com.flashtix.entity.User;
import com.flashtix.service.AuthService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(
            @Valid @RequestBody RegisterRequest request) {

        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(
            @Valid @RequestBody LoginRequest request) {

        return authService.login(request);
    }

    @GetMapping("/me")
    public CurrentUserResponse currentUser(
            @AuthenticationPrincipal Jwt jwt) {

        UUID userId = UUID.fromString(
                jwt.getSubject());

        User user = authService.getCurrentUser(userId);

        return new CurrentUserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole());
    }

    public record CurrentUserResponse(
            UUID userId,
            String fullName,
            String email,
            String role) {
    }
}
