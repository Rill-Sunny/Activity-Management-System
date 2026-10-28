package com.limitactivitysystem.identity.controller;

import com.limitactivitysystem.identity.dto.LoginRequest;
import com.limitactivitysystem.identity.dto.LoginResponse;
import com.limitactivitysystem.identity.dto.RegisterRequest;
import com.limitactivitysystem.identity.dto.RegisterResponse;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.identity.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        AppUser user = authService.register(request);
        return new RegisterResponse(user.getId(), user.getUsername());
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(authService.login(request));
    }
}
