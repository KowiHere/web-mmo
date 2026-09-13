package com.kowihere.mmo.net;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The allowed-origin list is the only guard on the game socket, because a
 * WebSocket handshake is not covered by the browser's same-origin policy. These
 * tests cover the ways a hand-edited configuration string can quietly weaken it.
 */
class WebSocketConfigTest {

    @Test
    void keepsASingleOrigin() {
        assertThat(WebSocketConfig.parseOrigins("http://localhost:8080"))
                .containsExactly("http://localhost:8080");
    }

    @Test
    void trimsWhitespaceAroundEntries() {
        // Without trimming, " http://host" never matches any real Origin header
        // and the player is locked out with no clue why.
        assertThat(WebSocketConfig.parseOrigins("http://localhost:8080, http://box:8080"))
                .containsExactly("http://localhost:8080", "http://box:8080");
    }

    @Test
    void dropsEmptyEntriesFromStrayCommas() {
        // Spring treats an empty origin as a pattern, so a trailing comma could
        // turn the guard off entirely.
        assertThat(WebSocketConfig.parseOrigins("http://localhost:8080,,  ,"))
                .containsExactly("http://localhost:8080");
    }

    @Test
    void yieldsNothingForBlankConfiguration() {
        assertThat(WebSocketConfig.parseOrigins("   ")).isEmpty();
    }
}
