package com.kowihere.mmo.persistence;

import com.kowihere.mmo.loop.ActorSnapshot;
import com.kowihere.mmo.loop.WorldPersistence;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Writes behind the tick, never inside it.
 *
 * <p>Map threads drop snapshots into a bounded queue and carry on; one writer
 * thread drains it into the database. This is the whole point of the split: a
 * slow write delays a save, not the world.
 *
 * <p>When the queue is full the snapshot is dropped rather than blocking the
 * caller. That loses at most one save interval of progress for one character;
 * blocking would cost every player on that map their next tick.
 */
@Component
public class QueuedWorldPersistence implements WorldPersistence {

    private static final Logger log = LoggerFactory.getLogger(QueuedWorldPersistence.class);

    private static final int CAPACITY = 4096;
    private static final int DRAIN_BATCH = 256;

    private final CharacterRepository characters;
    private final BlockingQueue<ActorSnapshot> pending = new ArrayBlockingQueue<>(CAPACITY);
    private final Thread writer;

    private volatile boolean running = true;

    public QueuedWorldPersistence(CharacterRepository characters) {
        this.characters = characters;
        this.writer = new Thread(this::drainForever, "persistence-writer");
        this.writer.start();
    }

    @Override
    public void save(ActorSnapshot snapshot) {
        if (!pending.offer(snapshot)) {
            log.warn("Persistence queue full; dropped a save for '{}'", snapshot.name());
        }
    }

    private void drainForever() {
        log.info("Persistence writer started");
        while (running || !pending.isEmpty()) {
            try {
                ActorSnapshot first = pending.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<ActorSnapshot> batch = new ArrayList<>();
                batch.add(first);
                pending.drainTo(batch, DRAIN_BATCH - 1);
                writeAll(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.info("Persistence writer stopped with {} snapshot(s) left", pending.size());
    }

    private void writeAll(List<ActorSnapshot> batch) {
        for (ActorSnapshot snapshot : batch) {
            try {
                characters.save(snapshot);
            } catch (RuntimeException e) {
                // One bad row must not take down the writer and with it every
                // other character's progress.
                log.error("Could not save '{}'", snapshot.name(), e);
            }
        }
    }

    /**
     * Lets the queue finish. The world has already been stopped by this point,
     * so what is left here is the final flush of everyone's last position.
     */
    @PreDestroy
    void shutdown() {
        running = false;
        try {
            writer.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
