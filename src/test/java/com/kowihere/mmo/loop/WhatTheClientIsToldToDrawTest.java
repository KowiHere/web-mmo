package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.world.ClassDefLoader;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.ItemDefLoader;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import com.kowihere.mmo.world.MobDefLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the client is handed to draw with.
 *
 * <p>Run against the shipped world on a real runner rather than a stub,
 * because the thing worth checking is the whole way through: content is read,
 * kept on the definition, written into the frame and sent. A test on the
 * loader alone would pass with the frame still carrying nothing.
 */
class WhatTheClientIsToldToDrawTest {

    private static final MapDef CLEARING = new MapDefLoader().loadAll().get("starter");
    private static final Content SHIPPED = new Content(new MobDefLoader().loadAll(),
            new ItemDefLoader().loadAll(), new ClassDefLoader().loadAll());

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 15_000;

    private MapRunner runner;
    private Thread thread;

    @BeforeEach
    void startMap() {
        runner = new MapRunner(CLEARING, JSON, WorldPersistence.NONE, SHIPPED, 20);
        thread = new Thread(runner, "test-drawing");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void theMapArrivesWithItsGroundAndItsLegend() throws Exception {
        JsonNode init = JSON.readTree(join());

        JsonNode ground = init.path("map").path("ground");
        assertThat(ground.size())
                .as("one row of ground per row of collision")
                .isEqualTo(init.path("map").path("collision").size());
        assertThat(init.path("map").path("groundLegend").path("t").asText())
                .as("and what each character means, so the client keeps no copy of the registry")
                .isEqualTo("trawa");
    }

    @Test
    void everyCreatureArrivesSayingHowItIsDrawn() throws Exception {
        JsonNode init = JSON.readTree(join());

        int creatures = 0;
        for (JsonNode actor : init.path("actors")) {
            if (!"MOB".equals(actor.path("kind").asText())) {
                continue;
            }
            creatures++;
            assertThat(actor.path("look").asText())
                    .as("'%s' arrived with no look", actor.path("name").asText())
                    .isNotEmpty();
        }
        assertThat(creatures).as("the clearing should have creatures on it").isPositive();
    }

    @Test
    void differentCreaturesArriveLookingDifferent() throws Exception {
        // The point of the whole milestone: before it, every creature of a tier
        // was drawn identically, so a wolf and a boar were the same circle.
        JsonNode init = JSON.readTree(join());

        List<String> looks = new ArrayList<>();
        for (JsonNode actor : init.path("actors")) {
            if ("MOB".equals(actor.path("kind").asText())) {
                looks.add(actor.path("look").asText());
            }
        }
        // Several wolves all look like wolves, which is right - what matters is
        // that a wolf and a boar do not, and before this milestone they did.
        assertThat(looks).hasSizeGreaterThan(1);
        assertThat(new java.util.HashSet<>(looks))
                .as("every creature on the clearing arrived looking the same: %s", looks)
                .hasSizeGreaterThan(1);
    }

    @Test
    void peopleArriveSayingItToo() throws Exception {
        JsonNode init = JSON.readTree(join());

        for (JsonNode actor : init.path("actors")) {
            if ("NPC".equals(actor.path("kind").asText())) {
                assertThat(actor.path("look").asText())
                        .as("'%s' arrived with no look", actor.path("name").asText())
                        .isNotEmpty();
            }
        }
    }

    // ------------------------------------------------------------------

    private String join() {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L,
                SavedCharacter.fresh("ala", "Ala", CLEARING.id(), CLEARING.spawnX(),
                        CLEARING.spawnY(), Direction.DOWN), 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client.frames().stream().filter(f -> f.contains("\"type\":\"init\""))
                .findFirst().orElseThrow();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class FakeClient implements Client {

        private final List<String> frames = new CopyOnWriteArrayList<>();

        @Override
        public void send(String json) {
            frames.add(json);
        }

        @Override
        public void disconnect(String reason) {
        }

        @Override
        public String describe() {
            return "fake client";
        }

        List<String> frames() {
            return new ArrayList<>(frames);
        }

        boolean await(Predicate<String> match) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (frames.stream().anyMatch(match)) {
                    return true;
                }
                sleep(20);
            }
            return false;
        }
    }
}
