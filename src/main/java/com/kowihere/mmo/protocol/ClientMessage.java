package com.kowihere.mmo.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Everything the client may send, flattened into one record because the set is
 * small and the alternative — polymorphic deserialisation — hands an untrusted
 * peer control over which type gets instantiated.
 *
 * <p>Every field is a request, never an instruction: the server decides what
 * actually happens. {@code x}/{@code y} are a destination to path towards, not
 * a position to teleport to.
 *
 * <p>Notably absent: any claim about who the sender is. Identity is settled at
 * the handshake, so there is nothing here for a client to lie about.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientMessage(
        String type,
        Long since,
        Integer x,
        Integer y,
        String text,
        Integer targetId,
        String itemId,
        String slot,
        String attribute
) {
}
