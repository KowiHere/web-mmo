package com.kowihere.mmo.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.loop.Command;
import com.kowihere.mmo.loop.MapRunner;
import com.kowihere.mmo.loop.SavedCharacter;
import com.kowihere.mmo.loop.WorldService;
import com.kowihere.mmo.persistence.CharacterRepository;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.protocol.ClientMessage;
import com.kowihere.mmo.world.ItemSlot;
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
        log.debug("Socket open for account {} as '{}'",
                session.getAttributes().get(AuthHandshakeInterceptor.ACCOUNT_ID),
                session.getAttributes().get(AuthHandshakeInterceptor.CHARACTER_KEY));
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

        if ("hello".equals(message.type())) {
            enterWorld(session, client, message);
            return;
        }

        // Where this socket's character actually is, not where the world
        // happens to begin. Sending everything to the starting map worked for
        // exactly as long as there was only one map to send it to.
        MapRunner map = world.mapOf(client);
        if (map == null) {
            return; // no character yet, or one between two maps
        }
        switch (message.type()) {
            case "move" -> {
                if (message.x() != null && message.y() != null) {
                    map.submit(new Command.MoveTo(client, message.x(), message.y()));
                }
            }
            case "chat" -> map.submit(new Command.Chat(client, message.text()));
            case "attack" -> {
                if (message.targetId() != null) {
                    map.submit(new Command.Attack(client, message.targetId()));
                }
            }
            case "flee" -> map.submit(new Command.Flee(client));
            case "equip" -> {
                if (message.itemId() != null) {
                    map.submit(new Command.Equip(client, message.itemId()));
                }
            }
            // Both of these parse to null when a client names something that
            // does not exist, and the map ignores a null. Nothing here decides
            // whether the action is allowed - that is the world's business.
            case "unequip" -> map.submit(new Command.Unequip(client, ItemSlot.parse(message.slot())));
            case "spend" -> map.submit(
                    new Command.Spend(client, Attributes.Attribute.parse(message.attribute())));
            case "use" -> {
                if (message.skillId() != null) {
                    map.submit(new Command.Use(client, message.skillId()));
                }
            }
            case "learn" -> {
                if (message.skillId() != null) {
                    map.submit(new Command.Learn(client, message.skillId()));
                }
            }
            case "talk" -> {
                if (message.npcId() != null) {
                    map.submit(new Command.Talk(client, message.npcId()));
                }
            }
            case "choose" -> {
                if (message.option() != null) {
                    map.submit(new Command.Choose(client, message.option()));
                }
            }
            case "endTalk" -> map.submit(new Command.StopTalking(client));
            case "buy" -> {
                if (message.itemId() != null) {
                    map.submit(new Command.Buy(client, message.itemId()));
                }
            }
            case "sell" -> {
                if (message.itemId() != null) {
                    map.submit(new Command.Sell(client, message.itemId()));
                }
            }
            default -> log.debug("Unknown message type '{}' from {}", message.type(), session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        PlayerSession client = client(session);
        if (client != null) {
            MapRunner map = world.mapOf(client);
            if (map != null) {
                // To the map they are actually on. Sent to the starting map, a
                // character standing anywhere else would be saved by a map that
                // has never heard of them - which is to say, not saved at all.
                map.submit(new Command.Detach(client));
            }
            world.forget(client);
            client.disconnect("Socket closed");
        }
        log.debug("Socket closed: {} ({})", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Transport error on {}: {}", session.getId(), exception.toString());
    }

    /**
     * Turns an authenticated socket into a character in the world.
     *
     * <p>The identity comes from the handshake attributes, never from the
     * message: the client has no say in who it is. The database lookup happens
     * here, on a container thread, precisely so the map thread never waits on a
     * query.
     */
    private void enterWorld(WebSocketSession session, PlayerSession client,
                            ClientMessage message) {
        Long accountId = (Long) session.getAttributes().get(AuthHandshakeInterceptor.ACCOUNT_ID);
        String characterKey = (String) session.getAttributes().get(AuthHandshakeInterceptor.CHARACTER_KEY);
        if (accountId == null || characterKey == null) {
            client.disconnect("Not authenticated");
            return;
        }

        SavedCharacter character = characters.find(characterKey).orElse(null);
        if (character == null) {
            // Deleted between the handshake and the first message.
            client.disconnect("Character no longer exists");
            return;
        }

        // The map the character was last on, which is the whole point of
        // storing it. Content can have dropped that map since, and the world
        // answers with the starting one rather than refusing to let them play.
        MapRunner map = world.mapOrStarting(character.mapId());
        world.nowOn(client, map);
        map.submit(new Command.Join(client, accountId, character,
                message.since() == null ? 0L : message.since()));
    }

    private static PlayerSession client(WebSocketSession session) {
        return (PlayerSession) session.getAttributes().get(SESSION_KEY);
    }
}
