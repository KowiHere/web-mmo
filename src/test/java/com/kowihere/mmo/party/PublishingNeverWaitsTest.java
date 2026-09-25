package com.kowihere.mmo.party;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The promise that makes all of this safe: a map thread never waits on a party.
 *
 * <p>Changing a party takes the register's lock, and those changes arrive on
 * network threads. If publishing health took the same lock, one player pressing
 * "invite" could hold up a whole map's tick - which is precisely the kind of
 * cross-thread wait this world is built to not have.
 */
class PublishingNeverWaitsTest {

    @Test
    void aMapCanPublishWhileSomebodyElseIsChangingAParty() throws Exception {
        PartyService parties = new PartyService();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);

        Thread hog = new Thread(() -> {
            synchronized (parties) {
                holding.countDown();
                try {
                    letGo.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "holds-the-register");
        hog.setDaemon(true);
        hog.start();
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch published = new CountDownLatch(1);
        Thread map = new Thread(() -> {
            parties.publish("ala", "Ala", 1, 10, 10, "starter", "Polana", 1, 1, true);
            published.countDown();
        }, "pretend-map");
        map.setDaemon(true);
        map.start();

        assertThat(published.await(2, TimeUnit.SECONDS))
                .as("publishing waited for a lock held by somebody pressing a button")
                .isTrue();
        letGo.countDown();
        assertThat(parties.vitalsOf("ala").mapName()).isEqualTo("Polana");
    }
}
