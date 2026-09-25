package com.kowihere.mmo.party;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.loop.Client;
import com.kowihere.mmo.protocol.ServerMessages;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

/**
 * Draws every party, twice a second, from outside every map.
 *
 * <p>A party panel has to show five health bars belonging to as many as five
 * different maps, and no map thread is allowed to read another one's actors.
 * So nobody pushes these frames from a tick: this does, on its own thread,
 * reading the health each map publishes and the register of who is with whom.
 *
 * <p>It is also where a party notices that somebody has gone. A map that stops
 * publishing says everything that needs saying, and this is the one place
 * allowed to act on the silence - because it may take the register's lock, and
 * a tick may not.
 */
@Component
public class PartyHeartbeat implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(PartyHeartbeat.class);

    /** Often enough for a health bar to be worth looking at, rarely enough to be free. */
    private static final long BEAT_MS = 500;

    private final PartyService parties;
    private final ObjectMapper json;

    /** Who was sent a party last beat, so that leaving is told exactly once. */
    private final Set<String> hadAParty = new HashSet<>();
    private final Set<String> wasAsked = new HashSet<>();

    private volatile boolean running = true;
    private Thread thread;

    public PartyHeartbeat(PartyService parties, ObjectMapper json) {
        this.parties = parties;
        this.json = json;
    }

    @PostConstruct
    void start() {
        thread = new Thread(this, "party-heartbeat");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        while (running) {
            try {
                beat();
            } catch (RuntimeException e) {
                // One bad frame must not stop every party in the world.
                log.error("Party heartbeat failed", e);
            }
            LockSupport.parkNanos(BEAT_MS * 1_000_000L);
        }
    }

    /** Package-private so a test can beat once instead of waiting half a second. */
    void beat() {
        parties.forgetTheSilent();
        sayWhatWasSaid();

        PartyService.Snapshot snapshot = parties.snapshot();
        Set<String> inAParty = new HashSet<>();
        for (Party party : snapshot.parties()) {
            List<ServerMessages.PartyMemberDto> seats = new ArrayList<>(party.members().size());
            for (Member member : party.members()) {
                Vitals vitals = parties.vitalsOf(member.nameKey());
                seats.add(new ServerMessages.PartyMemberDto(
                        member.name(),
                        vitals == null ? 1 : vitals.level(),
                        vitals == null ? 0 : vitals.hp(),
                        vitals == null ? 0 : vitals.maxHp(),
                        vitals == null ? "?" : vitals.mapName(),
                        vitals != null && vitals.online(),
                        party.isLedBy(member.nameKey()),
                        false));
            }
            for (Member member : party.members()) {
                inAParty.add(member.nameKey());
                // One frame per member, because each of them is "you" in a
                // different row, and a client that had to work out which row is
                // its own would be a second place deciding who you are.
                List<ServerMessages.PartyMemberDto> mine = new ArrayList<>(seats.size());
                for (int i = 0; i < seats.size(); i++) {
                    ServerMessages.PartyMemberDto seat = seats.get(i);
                    boolean you = party.members().get(i).nameKey().equals(member.nameKey());
                    mine.add(new ServerMessages.PartyMemberDto(seat.name(), seat.level(),
                            seat.hp(), seat.maxHp(), seat.mapName(), seat.online(),
                            seat.leader(), you));
                }
                send(member.nameKey(), new ServerMessages.PartyDto(party.version(), mine));
            }
        }

        // Anybody who had one a moment ago and does not now is told once, so
        // that a panel never lingers over a party that has broken up.
        for (String nameKey : List.copyOf(hadAParty)) {
            if (!inAParty.contains(nameKey)) {
                send(nameKey, ServerMessages.PartyDto.none());
            }
        }
        hadAParty.clear();
        hadAParty.addAll(inAParty);

        offerSeats(snapshot);
    }

    private void sayWhatWasSaid() {
        for (PartyService.Said said : parties.drainSaid()) {
            Party party = parties.snapshot().parties().stream()
                    .filter(candidate -> candidate.id() == said.partyId())
                    .findFirst().orElse(null);
            if (party == null) {
                continue; // it broke up between the saying and the hearing
            }
            for (Member member : party.members()) {
                send(member.nameKey(), new ServerMessages.PartyChatDto(said.from(), said.text()));
            }
        }
    }

    private void offerSeats(PartyService.Snapshot snapshot) {
        long now = System.currentTimeMillis();
        Set<String> asked = new HashSet<>();
        snapshot.invitations().forEach((nameKey, invitation) -> {
            asked.add(nameKey);
            Party party = snapshot.parties().stream()
                    .filter(candidate -> candidate.id() == invitation.partyId())
                    .findFirst().orElse(null);
            send(nameKey, new ServerMessages.PartyInviteDto(invitation.fromName(),
                    party == null ? 1 : party.members().size(),
                    Math.max(0, invitation.expiresAt() - now)));
        });
        for (String nameKey : List.copyOf(wasAsked)) {
            if (!asked.contains(nameKey)) {
                send(nameKey, ServerMessages.PartyInviteDto.withdrawn());
            }
        }
        wasAsked.clear();
        wasAsked.addAll(asked);
    }

    private void send(String nameKey, Object frame) {
        Client client = parties.clientOf(nameKey);
        if (client == null) {
            return; // not playing right now; the seat is being held, not drawn
        }
        try {
            client.send(json.writeValueAsString(frame));
        } catch (Exception e) {
            log.warn("Could not serialise a party frame for '{}'", nameKey, e);
        }
    }
}
