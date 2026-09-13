package com.kowihere.mmo.net;

import com.kowihere.mmo.account.AccountService;
import com.kowihere.mmo.account.SessionCookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;

/**
 * Decides whose socket this is, before the socket exists.
 *
 * <p>Identity used to arrive in the first message, which meant a connection was
 * open and anonymous for a moment and anyone could claim any character by name.
 * Checking here removes that window entirely: a socket either belongs to a
 * known account and one of its own characters, or it is never created.
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    static final String ACCOUNT_ID = "accountId";
    static final String CHARACTER_KEY = "characterKey";

    private static final Logger log = LoggerFactory.getLogger(AuthHandshakeInterceptor.class);

    private final AccountService accounts;

    public AuthHandshakeInterceptor(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        Optional<Long> accountId = sessionToken(request).flatMap(accounts::accountFor);
        if (accountId.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String characterKey = queryParam(request, "character");
        // Being logged in is not the same as being entitled to this character.
        // Missing that distinction is how one account ends up playing another's.
        if (!accounts.owns(accountId.get(), characterKey)) {
            log.warn("Account {} tried to enter the world as '{}', which is not theirs",
                    accountId.get(), characterKey);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(ACCOUNT_ID, accountId.get());
        attributes.put(CHARACTER_KEY, characterKey);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
    }

    private static Optional<String> sessionToken(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servlet) {
            return SessionCookie.read(servlet.getServletRequest());
        }
        return Optional.empty();
    }

    private static String queryParam(ServerHttpRequest request, String name) {
        return UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst(name);
    }
}
