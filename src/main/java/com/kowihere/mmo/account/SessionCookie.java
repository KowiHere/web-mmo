package com.kowihere.mmo.account;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * The session cookie, in one place so its flags cannot drift apart between the
 * place that sets it and the place that clears it.
 */
@Component
public class SessionCookie {

    public static final String NAME = "mmo_session";

    private static final Duration LIFETIME = Duration.ofDays(30);

    private final boolean secure;

    /**
     * @param secure must stay false for plain http://localhost. A cookie marked
     *               Secure is silently dropped by the browser over http, and the
     *               symptom is a login that "does nothing" with no error at all.
     *               Turn it on the moment the game is served over HTTPS.
     */
    public SessionCookie(@Value("${game.session.secure-cookie:false}") boolean secure) {
        this.secure = secure;
    }

    public String set(String token) {
        return build(token, LIFETIME).toString();
    }

    public String clear() {
        return build("", Duration.ZERO).toString();
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)        // script on the page cannot read it, so an XSS cannot steal it
                .sameSite("Strict")    // it is never sent from another site's page
                .secure(secure)
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    public static Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    public static HttpHeaders header(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, cookie);
        return headers;
    }
}
