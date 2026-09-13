package com.kowihere.mmo.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.loop.Command;
import com.kowihere.mmo.loop.MapRunner;
import com.kowihere.mmo.loop.PlayerNames;
import com.kowihere.mmo.loop.SavedCharacter;
import com.kowihere.mmo.loop.WorldService;
import com.kowihere.mmo.persistence.CharacterRepository;
import com.kowihere.mmo.protocol.ClientMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Translates socket frames into commands and nothing more. There is no game
 * logic here on purpose: this class runs on container threads, and anything it
 * decided would be a decision made outside the map's single-writer thread.
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);

    private static final String SESSION_KEY = "playerSession";
    private static final int MAX_FRAME_CHARS = 4_096;

    private final WorldService world;
    private final ObjectMapper json;
    private final CharacterRepository characters;

    public GameWebSocketHandler(WorldService world, ObjectMapper json, CharacterRepository characters) {
        this.world = world;
        this.json = json;
        this.characters = characters;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().put(SESSION_KEY, new PlayerSession(session));
        log.debug("Socket open: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage frame) {
        PlayerSession client = client(session);
        if (client == null) {
            return;
        }
        if (!client.allowInboundFrame()) {
            log.info("Dropping {}: sending faster than a player can act", session.getId());
            client.disconnect("Too many messages");
            return;
        }

        String payload = frame.getPayload();
        if (payload.length() > MAX_FRAME_CHARS) {
            client.disconnect("Frame too large");
            return;
        }

        ClientMessage message;
        try {
            message = json.readValue(payload, ClientMessage.class);
        } catch (Exception e) {
            log.debug("Malformed frame from {}: {}", session.getId(), e.toString());
            client.disconnect("Malformed message");
            return;
        }
        if (message.type() == null) {
            return;
        }

        MapRunner map = world.defaultMap();
        switch (message.type()) {
            case "hello" -> {
                // The lookup happens here, on a container thread, precisely so
                // that the map thread never waits on a database.
                String name = PlayerNames.sanitise(message.name());
                SavedCharacter saved = characters.find(PlayerNames.key(name)).orElse(null);
                map.submit(new Command.Join(client, name, message.token(),
                        message.since() == null ? 0L : message.since(), saved));
            }
            case "move" -> {
                if (message.x() != null && message.y() != null) {
                    map.submit(new Command.MoveTo(client, message.x(), message.y()));
                }
            }
            case "chat" -> map.submit(new Command.Chat(client, message.text()));
            default -> log.debug("Unknown message type '{}' from {}", message.type(), session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        PlayerSession client = client(session);
        if (client != null) {
            world.defaultMap().submit(new Command.Detach(client));
            client.disconnect("Socket closed");
        }
        log.debug("Socket closed: {} ({})", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Transport error on {}: {}", session.getId(), exception.toString());
    }

    private static PlayerSession client(WebSocketSession session) {
        return (PlayerSession) session.getAttributes().get(SESSION_KEY);
    }
}
