package com.kowihere.mmo.loop;

import com.kowihere.mmo.combat.Element;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What is burning, freezing, numbing, poisoning or bleeding one actor.
 *
 * <p>The other half of a blessing, and deliberately not the same class. A
 * blessing is bought, lasts minutes of play, survives logging out and is
 * written down; an affliction is inflicted, lasts rounds, and is gone the
 * moment the fight is - like the energy a skill is paid for with, which is why
 * neither of them ever reaches the database.
 *
 * <p>Mutable and unsynchronised: one map thread owns it, as with everything
 * else under {@code loop}.
 */
final class Ailments {

    /** One affliction and what is left of it. */
    static final class Fit {
        final Element element;
        int roundsLeft;
        /** Health taken each round; nought for the two that cost something else. */
        int perRound;

        Fit(Element element, int roundsLeft, int perRound) {
            this.element = element;
            this.roundsLeft = roundsLeft;
            this.perRound = perRound;
        }
    }

    private final Map<Element, Fit> fits = new EnumMap<>(Element.class);

    boolean any() {
        return !fits.isEmpty();
    }

    boolean has(Element element) {
        return fits.containsKey(element);
    }

    /**
     * Takes hold, or takes hold again.
     *
     * <p>Refreshed rather than stacked, as blessings are - five blows of the
     * same brand are five times as long alight and never five times as hot. The
     * harder of the two bites wins, so a weak blow cannot water down a burn
     * that a hard one started.
     */
    void afflict(Element element, int rounds, int perRound) {
        Fit already = fits.get(element);
        if (already == null) {
            fits.put(element, new Fit(element, rounds, perRound));
            return;
        }
        already.roundsLeft = Math.max(already.roundsLeft, rounds);
        already.perRound = Math.max(already.perRound, perRound);
    }

    /**
     * Counts every affliction down by one round.
     *
     * @return the ones that have let go, so the world can say so
     */
    List<Element> wearOff() {
        List<Element> gone = new ArrayList<>();
        for (Fit fit : List.copyOf(fits.values())) {
            fit.roundsLeft--;
            if (fit.roundsLeft <= 0) {
                fits.remove(fit.element);
                gone.add(fit.element);
            }
        }
        return gone;
    }

    List<Fit> all() {
        return List.copyOf(fits.values());
    }

    /** Everything ends with the fight, including this. */
    void clear() {
        fits.clear();
    }
}
