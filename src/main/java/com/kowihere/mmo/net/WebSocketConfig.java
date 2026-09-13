package com.kowihere.mmo.net;

import org.springframework.beans.factory.annotation.Value;
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
    private final String allowedOrigins;

    public WebSocketConfig(GameWebSocketHandler handler,
                           @Value("${game.allowed-origins:http://localhost:8080}") String allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
                .setAllowedOrigins(allowedOrigins.split(","));
    }
}
