package com.kowihere.mmo.net;

import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Raw WebSocket, not STOMP. STOMP adds a broker, subscriptions and a message
 * envelope that would only get in the way: this game has exactly one publisher
 * per map and a delta format of its own.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameWebSocketHandler handler;
    private final AuthHandshakeInterceptor auth;
    private final String allowedOrigins;

    public WebSocketConfig(GameWebSocketHandler handler, AuthHandshakeInterceptor auth,
                           @Value("${game.allowed-origins:http://localhost:8080}") String allowedOrigins) {
        this.handler = handler;
        this.auth = auth;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
                .addInterceptors(auth)
                .setAllowedOrigins(parseOrigins(allowedOrigins));
    }

    /**
     * A WebSocket handshake is NOT covered by the browser's same-origin policy,
     * so this list is the only thing stopping any page on the internet from
     * opening a game socket as one of your players. Never widen it to "*".
     *
     * <p>Blank entries are dropped rather than passed through: Spring treats an
     * empty origin as a pattern, and a stray trailing comma in configuration
     * would quietly turn the guard off.
     */
    static String[] parseOrigins(String configured) {
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }
}
