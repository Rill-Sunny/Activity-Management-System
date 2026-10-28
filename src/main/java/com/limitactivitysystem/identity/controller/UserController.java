package com.limitactivitysystem.identity.controller;

import com.limitactivitysystem.identity.dto.CurrentUserResponse;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    private final CurrentUserProvider currentUserProvider;

    public UserController(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/me")
    public CurrentUserResponse currentUser() {
        AppUser user = currentUserProvider.getCurrentActiveUser();
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getRole(), user.getStatus());
    }
}
