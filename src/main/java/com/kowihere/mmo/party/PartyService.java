package com.kowihere.mmo.party;

import com.kowihere.mmo.loop.Client;
import com.kowihere.mmo.loop.PartyBoard;
import com.kowihere.mmo.loop.PlayerNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Every party in the world, and the only thing allowed to change one.
 *
 * <p>A party is the first thing in this game that is <em>not</em> world state:
 * five people can be standing on three maps, and each map has one writer that
 * must never wait for another. So parties live here instead, beside the world
 * rather than inside it, and the rules are:
 *
 * <ul>
 *   <li><strong>Changing a party</strong> - inviting, accepting, leaving,
 *       removing, handing over - happens on a network thread or on the
 *       heartbeat, never in a tick, so plain {@code synchronized} is the whole
 *       of the locking.</li>
 *   <li><strong>Map threads only publish.</strong> {@link #publish} is a put
 *       into a concurrent map: one writer per character, many readers, no lock.
 *       That is the same shape as the routing table in {@code WorldService} -
 *       a signpost, not a world.</li>
 *   <li><strong>Nobody pushes frames from a tick.</strong> The heartbeat builds
 *       them, which is why a party spread over three maps updates at the same
 *       rate as one standing together.</li>
 * </ul>
 *
 * <p>None of it is written down. A party is a conversation rather than a
 * possession: it survives a logout exactly as far as a conversation with an NPC
 * does, which is not at all.
 */
@Service
public class PartyService implements PartyBoard {

    private static final Logger log = LoggerFactory.getLogger(PartyService.class);

    /** How long an offer of a seat stands before it is forgotten. */
    static final long INVITE_MS = 60_000;

    /**
     * How long a party keeps a seat for somebody the world has stopped talking
     * about. The same half minute the world itself holds a disconnected
     * character for, so a dropped connection does not break up a hunt.
     */
    static final long SILENCE_MS = 30_000;

    /** At most this many characters in one message, as on a map. */
    static final int MAX_CHAT_LENGTH = 200;

    /** One line a second is a conversation; faster than that is a script. */
    static final long CHAT_COOLDOWN_MS = 800;

    private final LongSupplier clock;

    /** Guarded by {@code this}. */
    private final Map<Long, Party> parties = new LinkedHashMap<>();
    private final Map<String, Long> partyOf = new LinkedHashMap<>();
    private final Map<String, Invitation> invitations = new LinkedHashMap<>();
    private final Map<String, Long> lastSaid = new LinkedHashMap<>();
    private long nextPartyId = 1;

    /**
     * Written by map threads, read by everybody. Never guarded: one map owns a
     * character, so one thread writes each entry, and a reader that catches the
     * previous instant simply draws a health bar a tick out of date.
     */
    private final Map<String, Vitals> vitals = new ConcurrentHashMap<>();

    /** The socket each character is playing on. Written when they enter and leave the world. */
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    public PartyService() {
        this(System::currentTimeMillis);
    }

    /**
     * @param clock handed in so that expiry and silence can be tested by moving
     *              time rather than by waiting for it - the same reason combat
     *              takes its randomness as an argument
     */
    public PartyService(LongSupplier clock) {
        this.clock = clock;
    }

    // ---- the world tells us how people are doing -------------------------

    @Override
    public void publish(String nameKey, String name, int level, int hp, int maxHp,
                        String mapId, String mapName, int x, int y, boolean online) {
        vitals.put(nameKey, new Vitals(name, level, hp, maxHp, mapId, mapName, x, y,
                online, clock.getAsLong()));
    }

    /** A socket that has just entered the world with this character. */
    public void joined(String nameKey, Client client) {
        clients.put(nameKey, client);
    }

    /**
     * A socket that has gone. The seat stays: the character is still standing in
     * the world for the next half minute, and a party that broke up over a
     * flicker of wifi would be worse than no party at all.
     */
    public void disconnected(String nameKey) {
        clients.remove(nameKey);
        Vitals last = vitals.get(nameKey);
        if (last != null) {
            vitals.put(nameKey, new Vitals(last.name(), last.level(), last.hp(), last.maxHp(),
                    last.mapId(), last.mapName(), last.x(), last.y(), false, last.at()));
        }
    }

    // ---- what a party is, to whoever asks --------------------------------

    public synchronized Party partyOf(String nameKey) {
        Long id = partyOf.get(nameKey);
        return id == null ? null : parties.get(id);
    }

    public Vitals vitalsOf(String nameKey) {
        return vitals.get(nameKey);
    }

    synchronized Invitation invitationTo(String nameKey) {
        return invitations.get(nameKey);
    }

    // ---- and the six things anybody can do to one ------------------------

    /**
     * Offers somebody a seat.
     *
     * @return why not, or null when the offer stands
     */
    public synchronized String invite(String fromKey, String toName) {
        String toKey = PlayerNames.key(toName);
        if (toKey.equals(fromKey)) {
            return "Sam ze sobą drużyny nie założysz.";
        }
        Vitals them = vitals.get(toKey);
        if (them == null || !them.online()) {
            return "Nie ma tu nikogo takiego.";
        }
        Party party = partyOf(fromKey);
        if (party != null && !party.isLedBy(fromKey)) {
            return "Zaprasza tylko przywódca drużyny.";
        }
        if (party != null && party.isFull()) {
            return "Drużyna jest pełna.";
        }
        if (partyOf(toKey) != null) {
            return them.name() + " jest już w drużynie.";
        }
        Invitation waiting = invitations.get(toKey);
        if (waiting != null && !waiting.hasExpired(clock.getAsLong())) {
            return them.name() + " ma już zaproszenie.";
        }
        if (party == null) {
            // A party of one, made the moment it is needed. Inviting is the only
            // way one ever starts, so there is nothing else to press first.
            party = new Party(nextPartyId++, 1, fromKey, List.of(memberFor(fromKey)));
            parties.put(party.id(), party);
            partyOf.put(fromKey, party.id());
        }
        invitations.put(toKey, new Invitation(party.id(), fromKey, nameOf(fromKey),
                clock.getAsLong() + INVITE_MS));
        return null;
    }

    /** @return why not, or null when they are in */
    public synchronized String accept(String nameKey) {
        Invitation invitation = invitations.remove(nameKey);
        if (invitation == null || invitation.hasExpired(clock.getAsLong())) {
            return "To zaproszenie już nie jest aktualne.";
        }
        if (partyOf(nameKey) != null) {
            return "Jesteś już w drużynie.";
        }
        Party party = parties.get(invitation.partyId());
        if (party == null) {
            return "Tej drużyny już nie ma.";
        }
        if (party.isFull()) {
            return "Drużyna zdążyła się zapełnić.";
        }
        replace(party.with(memberFor(nameKey)));
        partyOf.put(nameKey, party.id());
        return null;
    }

    public synchronized void decline(String nameKey) {
        invitations.remove(nameKey);
    }

    /** Leaving is the one thing nobody needs permission for. */
    public synchronized void leave(String nameKey) {
        Party party = partyOf(nameKey);
        if (party == null) {
            return;
        }
        partyOf.remove(nameKey);
        Party without = party.without(nameKey);
        if (without.members().size() <= 1) {
            // One person is not a party. Dissolving it is what stops the world
            // filling up with parties of one nobody can see or join.
            without.members().forEach(member -> partyOf.remove(member.nameKey()));
            parties.remove(party.id());
            return;
        }
        replace(without);
    }

    /** @return why not, or null when they are out */
    public synchronized String remove(String byKey, String name) {
        Party party = partyOf(byKey);
        if (party == null || !party.isLedBy(byKey)) {
            return "Wyrzuca tylko przywódca drużyny.";
        }
        String targetKey = PlayerNames.key(name);
        if (targetKey.equals(byKey)) {
            return "Siebie się nie wyrzuca - można wyjść.";
        }
        if (!party.has(targetKey)) {
            return "Nie ma takiego w drużynie.";
        }
        leave(targetKey);
        return null;
    }

    /** @return why not, or null when the party has a new leader */
    public synchronized String handOver(String fromKey, String name) {
        Party party = partyOf(fromKey);
        if (party == null || !party.isLedBy(fromKey)) {
            return "Przywództwo oddaje tylko przywódca.";
        }
        String targetKey = PlayerNames.key(name);
        if (!party.has(targetKey)) {
            return "Nie ma takiego w drużynie.";
        }
        replace(party.ledBy(targetKey));
        return null;
    }

    /** @return why not, or null when everybody heard it */
    public synchronized String say(String nameKey, String text) {
        Party party = partyOf(nameKey);
        if (party == null) {
            return "Nie jesteś w drużynie.";
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        long now = clock.getAsLong();
        Long said = lastSaid.get(nameKey);
        if (said != null && now - said < CHAT_COOLDOWN_MS) {
            return "Nie tak szybko.";
        }
        lastSaid.put(nameKey, now);
        String line = text.strip();
        if (line.length() > MAX_CHAT_LENGTH) {
            line = line.substring(0, MAX_CHAT_LENGTH);
        }
        heard.add(new Said(party.id(), nameOf(nameKey), line));
        return null;
    }

    /** What has been said since anybody last asked. Drained by the heartbeat. */
    private final List<Said> heard = new ArrayList<>();

    record Said(long partyId, String from, String text) {
    }

    synchronized List<Said> drainSaid() {
        if (heard.isEmpty()) {
            return List.of();
        }
        List<Said> all = List.copyOf(heard);
        heard.clear();
        return all;
    }

    // ---- and the thing nobody does: forgetting the silent ----------------

    /**
     * Drops anybody the world has stopped talking about.
     *
     * <p>This is how a character leaves a party by leaving the world, and it is
     * deliberately not the map's job: a tick that changed a party would be a
     * tick taking a lock held by somebody else's socket. Instead the map simply
     * stops publishing, and half a minute later this notices.
     */
    synchronized void forgetTheSilent() {
        long now = clock.getAsLong();
        for (String nameKey : List.copyOf(partyOf.keySet())) {
            Vitals last = vitals.get(nameKey);
            if (last == null || now - last.at() > SILENCE_MS) {
                log.debug("Dropping '{}' from their party: the world stopped talking about them",
                        nameKey);
                leave(nameKey);
                vitals.remove(nameKey);
            }
        }
        invitations.entrySet().removeIf(entry -> entry.getValue().hasExpired(now));
    }

    /** Everything the heartbeat needs, copied under the lock and read outside it. */
    synchronized Snapshot snapshot() {
        return new Snapshot(List.copyOf(parties.values()), Map.copyOf(invitations));
    }

    record Snapshot(List<Party> parties, Map<String, Invitation> invitations) {
    }

    synchronized Client clientOf(String nameKey) {
        return clients.get(nameKey);
    }

    private Member memberFor(String nameKey) {
        return new Member(nameKey, nameOf(nameKey));
    }

    private String nameOf(String nameKey) {
        Vitals known = vitals.get(nameKey);
        return known == null ? nameKey : known.name();
    }

    private void replace(Party party) {
        parties.put(party.id(), party);
        party.members().forEach(member -> partyOf.put(member.nameKey(), party.id()));
    }
}
