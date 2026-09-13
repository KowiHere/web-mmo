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
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientMessage(
        String type,
        String name,
        String token,
        Long since,
        Integer x,
        Integer y,
        String text
) {
}
