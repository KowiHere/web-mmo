package com.kowihere.mmo.loop;

/**
 * The map thread's view of a connected player: something it can push a frame
 * at, and nothing else. Keeping the transport behind this interface is what
 * stops WebSocket concerns from leaking into game logic — and lets the loop be
 * tested without a network.
 */
public interface Client {

    /** Hand a serialised frame to the client. Must not block the caller: the caller is the tick. */
    void send(String json);

    /** Drop the connection. The loop calls this when a client cannot keep up or misbehaves. */
    void disconnect(String reason);

    /** Label for logs only — never an identity the game trusts. */
    String describe();
}
