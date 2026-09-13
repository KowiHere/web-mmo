package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the running maps and hands out references to them. This is the boundary
 * between Spring and the game: above it there are beans and requests, below it
 * there is a simulation that knows nothing about either.
 *
 * <p>Map threads are platform threads, not virtual ones - each is long-running
 * and CPU-bound between ticks, which is precisely the case virtual threads do
 * not help with.
 */
@Service
public class WorldService {

    private static final Logger log = LoggerFactory.getLogger(WorldService.class);

    private final MapDefLoader loader;
    private final ObjectMapper json;
    private final Map<String, MapRunner> runners = new LinkedHashMap<>();
    private final List<Thread> threads = new ArrayList<>();

    private String defaultMapId;

    public WorldService(MapDefLoader loader, ObjectMapper json) {
        this.loader = loader;
        this.json = json;
    }

    @PostConstruct
    void start() {
        for (MapDef def : loader.loadAll().values()) {
            MapRunner runner = new MapRunner(def, json);
            runners.put(def.id(), runner);
            Thread thread = new Thread(runner, "map-" + def.id());
            thread.setDaemon(false);
            threads.add(thread);
            thread.start();
            if (defaultMapId == null) {
                defaultMapId = def.id();
            }
        }
        log.info("World started with {} map(s); default is '{}'", runners.size(), defaultMapId);
    }

    @PreDestroy
    void stop() {
        runners.values().forEach(MapRunner::stop);
        for (Thread thread : threads) {
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public MapRunner map(String id) {
        MapRunner runner = runners.get(id);
        if (runner == null) {
            throw new IllegalArgumentException("No such map: " + id);
        }
        return runner;
    }

    public MapRunner defaultMap() {
        return map(defaultMapId);
    }
}
