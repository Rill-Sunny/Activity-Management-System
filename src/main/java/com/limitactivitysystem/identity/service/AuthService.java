package com.limitactivitysystem.identity.service;

import com.limitactivitysystem.identity.dto.LoginRequest;
import com.limitactivitysystem.identity.dto.LoginResponse;
import com.limitactivitysystem.identity.dto.RegisterRequest;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.JwtTokenService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

@Service
public class AuthService {

    private final AppUserMapper appUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public AuthService(AppUserMapper appUserMapper, PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService) {
        this.appUserMapper = appUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    public AppUser register(RegisterRequest request) {
        if (request.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must not exceed 72 UTF-8 bytes");
        }

        if (appUserMapper.findByUsername(request.getUsername()) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }

        AppUser user = new AppUser();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole("USER");
        user.setStatus("ACTIVE");
        try {
            appUserMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            // The unique constraint also covers simultaneous registrations of the same username.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        return user;
    }

    public LoginResponse login(LoginRequest request) {
        if (request.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must not exceed 72 UTF-8 bytes");
        }
        AppUser user = appUserMapper.findByUsername(request.getUsername());
        if (user == null || !"ACTIVE".equals(user.getStatus())
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return jwtTokenService.issueAccessToken(user);
    }
}
