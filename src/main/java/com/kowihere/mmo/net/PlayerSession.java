package com.kowihere.mmo.net;

import com.kowihere.mmo.loop.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges one WebSocket to the map thread.
 *
 * <p>The map thread must never block on socket I/O - one player on hotel wifi
 * would otherwise stall the tick for everyone on the map. So sends go into a
 * bounded queue that a virtual thread drains. When that queue fills up the
 * client is not keeping up with the world, and the right answer is to drop it
 * rather than to buffer without limit.
 */
final class PlayerSession implements Client {

    private static final Logger log = LoggerFactory.getLogger(PlayerSession.class);

    private static final int OUTBOUND_CAPACITY = 256;

    /**
     * Inbound frames allowed per second. A player clicking and typing as fast as
     * a human can manage stays far below this; anything above it is a script,
     * and scripts are what turn one socket into everyone's lag.
     */
    private static final int INBOUND_FRAMES_PER_SECOND = 30;

    /**
     * Deliberately a fresh instance rather than a literal, so it can be matched
     * by identity and never collides with a frame that happens to say the same.
     */
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String POISON = new String("__poison__");

    private final WebSocketSession session;
    private final BlockingQueue<String> outbound = new ArrayBlockingQueue<>(OUTBOUND_CAPACITY);
    private final AtomicBoolean closing = new AtomicBoolean();
    private long windowStartedAt = System.nanoTime();
    private int framesThisWindow;

    PlayerSession(WebSocketSession session) {
        this.session = session;
        Thread.ofVirtual().name("ws-writer-" + session.getId()).start(this::pump);
    }

    /**
     * The single writer for this socket. Spring's {@link WebSocketSession} does
     * not tolerate concurrent sends, and funnelling every frame through here is
     * what makes that a non-issue.
     */
    private void pump() {
        try {
            while (true) {
                String frame = outbound.take();
                //noinspection StringEquality - identity check is the point
                if (frame == POISON) {
                    return;
                }
                session.sendMessage(new TextMessage(frame));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.debug("Write failed for {}: {}", describe(), e.toString());
        } finally {
            closeQuietly();
        }
    }

    /**
     * Counts one inbound frame against this socket's budget.
     *
     * <p>Called only from the container thread that reads this socket, which is
     * the single reader Spring guarantees - so the counters need no locking.
     *
     * @return false when the socket has exceeded its share and should be dropped
     */
    boolean allowInboundFrame() {
        long now = System.nanoTime();
        if (now - windowStartedAt >= 1_000_000_000L) {
            windowStartedAt = now;
            framesThisWindow = 0;
        }
        return ++framesThisWindow <= INBOUND_FRAMES_PER_SECOND;
    }

    @Override
    public void send(String json) {
        if (closing.get()) {
            return;
        }
        if (!outbound.offer(json)) {
            log.info("Dropping {}: cannot keep up with the world", describe());
            disconnect("Connection too slow");
        }
    }

    @Override
    public void disconnect(String reason) {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        log.debug("Disconnecting {}: {}", describe(), reason);
        outbound.clear();
        outbound.offer(POISON);
    }

    private void closeQuietly() {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        } catch (IOException e) {
            log.trace("Close failed for {}", describe(), e);
        }
    }

    @Override
    public String describe() {
        return "session " + session.getId();
    }
}
