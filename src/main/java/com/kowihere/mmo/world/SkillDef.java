package com.kowihere.mmo.world;

/**
 * One skill, as content rather than code.
 *
 * <p>There is a single shape of effect — a multiplier on the attack, a number of
 * blows, and optionally how much armour those blows pass through — rather than
 * one kind per skill. Three skills built from the same three numbers differ from
 * each other for reasons a player can read off the description; three separate
 * effect types would be one skill wearing three names, and every new idea would
 * mean new code rather than a new file.
 *
 * @param classId      the only class that may learn it, or null when anyone may
 * @param cost         energy per use; zero means it is passive and never "used"
 * @param power        multiplier on the wielder's attack, per blow
 * @param blows        how many times it strikes
 * @param armorIgnored what fraction of the target's armour it passes through,
 *                     or a negative number to use whatever the class does
 * @param energyPerRank what each rank adds to charging, for the one skill whose
 *                      whole purpose is that
 */
public record SkillDef(
        String id,
        String name,
        String description,
        String classId,
        int cost,
        int maxRank,
        double power,
        int blows,
        double armorIgnored,
        int energyPerRank
) {

    /** A skill with no cost strikes nothing: it works simply by being known. */
    public boolean isPassive() {
        return cost == 0;
    }

    public boolean availableTo(String otherClassId) {
        return classId == null || classId.equals(otherClassId);
    }

    /** Whether this overrides the class's own armour piercing. */
    public boolean overridesArmorIgnored() {
        return armorIgnored >= 0;
    }
}
