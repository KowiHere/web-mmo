package com.kowihere.mmo.combat;

import java.util.Locale;

/**
 * What a blow is made of, beyond force.
 *
 * <p>An ordinary blow is answered by armour. These are not: each one is
 * answered by a resistance of its own, and each leaves something behind on
 * whoever it lands on. That second half is the point - five words for the same
 * damage with different colours would be five words for nothing.
 *
 * <p>A closed list, like every other thing content may name. What each of them
 * is <em>worth</em> - how long it lasts, how hard it bites - lives in
 * {@link CombatRules}, with the rest of the numbers that decide a fight.
 */
public enum Element {

    /** Burns hard and briefly, on whatever the blow was worth. */
    FIRE("fire", "ognia", "płonie", "przestaje płonąć", Fed.BLOW),

    /** Takes the quickness out of somebody: no slipping away, no second blow. */
    FROST("frost", "lodu", "jest wychłodzony", "rozgrzewa się", Fed.NOTHING),

    /** Costs turns rather than health, which is worse when the fight is even. */
    SHOCK("shock", "prądu", "jest porażony", "przestaje drętwieć", Fed.NOTHING),

    /**
     * Feeds on the body rather than on the blow, so it is the one thing in this
     * game that troubles a big creature more than a small one.
     */
    POISON("poison", "trucizny", "jest zatruty", "zwalcza truciznę", Fed.BODY),

    /** Bleeds on the blow, and faster than mending can keep up with. */
    BLEED("bleed", "krwi", "krwawi", "tamuje krew", Fed.BLOW);

    /** What each round of an affliction is taken out of. */
    public enum Fed { BLOW, BODY, NOTHING }

    private final String key;
    private final String label;
    private final String whileItLasts;
    private final String whenItPasses;
    private final Fed fed;

    Element(String key, String label, String whileItLasts, String whenItPasses, Fed fed) {
        this.key = key;
        this.label = label;
        this.whileItLasts = whileItLasts;
        this.whenItPasses = whenItPasses;
        this.fed = fed;
    }

    /** What content writes. */
    public String key() {
        return key;
    }

    /** What a tooltip says after a number: "12 obrażeń ognia". */
    public String label() {
        return label;
    }

    /** What the fight says when it takes hold: "Ala płonie". */
    public String whileItLasts() {
        return whileItLasts;
    }

    /** And when it lets go. */
    public String whenItPasses() {
        return whenItPasses;
    }

    public Fed fed() {
        return fed;
    }

    /** Whether being afflicted with this costs health every round. */
    public boolean burns() {
        return fed != Fed.NOTHING;
    }

    /** @return the element, or null when content names one that does not exist */
    public static Element parse(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim();
        for (Element element : values()) {
            if (element.key.equalsIgnoreCase(cleaned) || element.name().equalsIgnoreCase(cleaned)) {
                return element;
            }
        }
        return null;
    }

    public static String known() {
        StringBuilder all = new StringBuilder();
        for (Element element : values()) {
            all.append(all.isEmpty() ? "" : ", ").append(element.key);
        }
        return all.toString().toLowerCase(Locale.ROOT);
    }
}
