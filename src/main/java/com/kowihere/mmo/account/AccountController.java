package com.kowihere.mmo.account;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Registration and login. REST rather than WebSocket on purpose: none of this
 * touches the running world, so none of it belongs in the command queue.
 */
@RestController
@RequestMapping("/api")
class AccountController {

    private final AccountService accounts;
    private final SessionCookie cookie;

    AccountController(AccountService accounts, SessionCookie cookie) {
        this.accounts = accounts;
        this.cookie = cookie;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegisterRequest(String login, String password, String characterName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LoginRequest(String login, String password) {
    }

    @PostMapping("/register")
    ResponseEntity<Map<String, String>> register(@RequestBody RegisterRequest request) {
        String token = accounts.register(request.login(), request.password(), request.characterName());
        return ResponseEntity.ok()
                .headers(SessionCookie.header(cookie.set(token)))
                .body(Map.of("login", request.login().strip()));
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest request) {
        String token = accounts.login(request.login(), request.password());
        return ResponseEntity.ok()
                .headers(SessionCookie.header(cookie.set(token)))
                .body(Map.of("login", request.login().strip()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        SessionCookie.read(request).ifPresent(accounts::logout);
        return ResponseEntity.noContent()
                .headers(SessionCookie.header(cookie.clear()))
                .build();
    }
}
