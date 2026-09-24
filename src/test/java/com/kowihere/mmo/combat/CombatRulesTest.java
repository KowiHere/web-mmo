package com.kowihere.mmo.combat;

import com.kowihere.mmo.world.ClassDef;
import com.kowihere.mmo.world.ClassDefLoader;
import com.kowihere.mmo.world.MobDef;
import com.kowihere.mmo.world.SkillDef;
import com.kowihere.mmo.world.SkillDefLoader;
import com.kowihere.mmo.world.MobDefLoader;
import com.kowihere.mmo.world.MobTier;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The numbers behind a fight, tested on their own.
 *
 * <p>The randomness is handed in, so every one of these is an exact assertion
 * rather than "run it a hundred times and hope". A combat formula nobody can
 * pin down is a combat formula nobody can balance.
 */
class CombatRulesTest {

    /** Always rolls the same fraction, so a "random" swing becomes arithmetic. */
    private static CombatRules rollingExactly(double fraction) {
        return new CombatRules(new Random() {
            @Override
            public double nextDouble() {
                return fraction;
            }
        });
    }

    @Test
    void armourReducesDamageByAPercentageRatherThanSubtracting() {
        // Subtracting armour leaves only two outcomes - armour that does nothing
        // and armour that stops everything - with nothing in between to tune.
        CombatRules rules = rollingExactly(0.5); // the middle of the swing: exactly 1.0x

        int unarmoured = rules.damage(100, 0);
        int lightly = rules.damage(100, 20);
        int heavily = rules.damage(100, 180);

        assertThat(unarmoured).isEqualTo(100);
        assertThat(lightly).isEqualTo(50);   // 20 armour halves it
        assertThat(heavily).isEqualTo(10);   // heavy armour still lets a tenth through
    }

    @Test
    void moreArmourAlwaysHelpsButNeverCompletely() {
        CombatRules rules = rollingExactly(0.5);

        int previous = Integer.MAX_VALUE;
        for (int armour = 0; armour <= 500; armour += 25) {
            int dealt = rules.damage(50, armour);
            assertThat(dealt).as("armour %d", armour).isLessThanOrEqualTo(previous).isPositive();
            previous = dealt;
        }
    }

    @Test
    void aBlowAlwaysLandsForSomething() {
        // Otherwise two well-armoured actors could trade nothing for ever, and
        // the fight would never end.
        CombatRules weakest = rollingExactly(0.0);

        assertThat(weakest.damage(1, 10_000)).isEqualTo(1);
    }

    @Test
    void theSwingSpansTheRangeItPromises() {
        assertThat(rollingExactly(0.0).damage(100, 0)).isEqualTo(85);
        assertThat(rollingExactly(1.0).damage(100, 0)).isEqualTo(115);
    }

    @Test
    void aFreshCharacterIsExactlyAsStrongAsItWasBeforeAttributesExisted() {
        // Statistics used to come straight from the level. They now come from
        // attributes, and this pins the join: a brand new character has the same
        // 65 health and 7 attack it always had. Without that, every creature on
        // the starter map would silently need rebalancing again.
        Attributes fresh = Attributes.FRESH;

        assertThat(fresh.maxHp()).isEqualTo(65);
        assertThat(fresh.attack()).isEqualTo(7);
    }

    @Test
    void theExperienceCurveOnlyEverRises() {
        long previous = -1;
        for (int level = 1; level <= 60; level++) {
            long needed = CombatRules.xpForLevel(level);
            assertThat(needed).as("threshold for level %d", level).isGreaterThan(previous);
            previous = needed;
        }
    }

    @Test
    void experienceAndLevelAgreeWithEachOther() {
        for (int level = 1; level <= 40; level++) {
            long threshold = CombatRules.xpForLevel(level);

            assertThat(CombatRules.levelForXp(threshold))
                    .as("exactly at the threshold for %d", level)
                    .isEqualTo(level);
            assertThat(CombatRules.levelForXp(threshold - 1))
                    .as("one short of %d", level)
                    .isEqualTo(Math.max(1, level - 1));
        }
    }

    @Test
    void aFreshCharacterIsLevelOne() {
        assertThat(CombatRules.levelForXp(0)).isEqualTo(1);
        assertThat(CombatRules.levelForXp(-5)).isEqualTo(1); // nonsense in, sane out
    }

    @Test
    void anEliteIsWorthFarMoreThanACommonCreatureOfTheSameLevel() {
        Map<String, MobDef> mobs = new MobDefLoader().loadAll();
        MobDef wolf = mobs.get("wilk");
        MobDef alpha = mobs.get("wilczyca");

        assertThat(wolf.tier()).isEqualTo(MobTier.MOB);
        assertThat(alpha.tier()).isEqualTo(MobTier.ELITE);
        assertThat(CombatRules.xpReward(alpha))
                .as("an elite should be worth chasing")
                .isGreaterThan(CombatRules.xpReward(wolf) * 4);
    }

    @Test
    void everyClassCanBeatTheStarterCreaturesAndNoneCanBeatTheElite() {
        // The most important test in the file. Three classes now fight with
        // three different attributes against the same creatures, so a change
        // that suits one of them can quietly ruin another - and the only way
        // anybody would find out is by playing that class for an hour.
        Map<String, MobDef> mobs = new MobDefLoader().loadAll();

        for (ClassDef characterClass : new ClassDefLoader().loadAll().values()) {
            assertThat(survives(characterClass, mobs.get("dzik")))
                    .as("%s cannot beat a boar", characterClass.name())
                    .isTrue();
            assertThat(survives(characterClass, mobs.get("wilk")))
                    .as("%s cannot beat a wolf", characterClass.name())
                    .isTrue();
            assertThat(survives(characterClass, mobs.get("wilczyca")))
                    .as("%s beats an elite at level one, which makes the tier meaningless",
                            characterClass.name())
                    .isFalse();
        }
    }

    @Test
    void noClassCanBeatAnEliteEvenUsingItsSkillEveryTimeItCanAffordIt() {
        // Skills only ever help, so the checks above still hold with them. What
        // they could break is the other half: an elite that stops being a wall.
        // This is the place where this milestone most easily ruins the last one,
        // so it is worth asking directly rather than assuming.
        Map<String, MobDef> mobs = new MobDefLoader().loadAll();
        Map<String, SkillDef> skills = new SkillDefLoader().loadAll();
        MobDef elite = mobs.get("wilczyca");

        for (ClassDef characterClass : new ClassDefLoader().loadAll().values()) {
            SkillDef signature = signatureOf(skills, characterClass);
            assertThat(survivesUsing(characterClass, elite, signature))
                    .as("%s beats an elite at level one with %s, which makes the tier meaningless",
                            characterClass.name(), signature.name())
                    .isFalse();
        }
    }

    private static SkillDef signatureOf(Map<String, SkillDef> skills, ClassDef characterClass) {
        return skills.values().stream()
                .filter(skill -> characterClass.id().equals(skill.classId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        characterClass.name() + " has no skill of its own"));
    }

    /** Whether a level-one character of this class outlasts this creature. */
    private static boolean survives(ClassDef characterClass, MobDef mob) {
        return survivesUsing(characterClass, mob, null);
    }

    /**
     * The same fight, optionally with the class throwing its skill the moment it
     * can pay for it - which is the strongest a level-one character can be.
     */
    private static boolean survivesUsing(ClassDef characterClass, MobDef mob, SkillDef skill) {
        CombatRules average = rollingExactly(0.5);
        Attributes start = characterClass.startingAttributes();
        int health = start.maxHp() + characterClass.hpBonus();
        int attack = characterClass.attackFrom(start);
        int incoming = average.damage(mob.attack(), start.armor());

        int mobHealth = mob.hp();
        int energy = 0;
        int rounds = 0;
        while (mobHealth > 0 && rounds < 10_000) {
            rounds++;
            // One rank of regeneration is all a level-one character could have,
            // and only if it spent its single point there rather than on the
            // skill it is about to use - so the base rate is the honest figure.
            energy = Energy.charged(energy, 0);

            if (skill != null && energy >= skill.cost()) {
                energy -= skill.cost();
                double ignored = skill.overridesArmorIgnored()
                        ? skill.armorIgnored()
                        : characterClass.armorIgnored();
                int perBlow = average.damage((int) Math.round(attack * skill.power()),
                        mob.armor(), ignored);
                mobHealth -= perBlow * skill.blows();
            } else {
                mobHealth -= average.damage(attack, mob.armor(), characterClass.armorIgnored());
            }
        }
        return rounds * incoming < health;
    }

    @Test
    void aFreshCharacterCanBeatTheStarterCreaturesAndNotTheElite() {
        // The map is called the Beginners' Glade. A level-one character that
        // loses to everything on it is not a difficulty setting, it is a bug -
        // and one that only shows up by playing, which is the worst way to find
        // it. The arithmetic is here so it shows up in a second instead.
        Map<String, MobDef> mobs = new MobDefLoader().loadAll();

        assertThat(roundsSurvivedAgainst(mobs.get("dzik")))
                .as("a boar is the first thing anybody kills")
                .isTrue();
        assertThat(roundsSurvivedAgainst(mobs.get("wilk")))
                .as("a wolf should be a real fight that a healthy character still wins")
                .isTrue();
        assertThat(roundsSurvivedAgainst(mobs.get("wilczyca")))
                .as("an elite is a wall at level one, and should stay one")
                .isFalse();
    }

    /** Whether a level-one character at full health outlasts this creature. */
    private static boolean roundsSurvivedAgainst(MobDef mob) {
        // The average swing, since neither side is luckier than the other over
        // the length of a fight. Bare-handed and with nothing spent: the worst
        // shape a character is ever in is the one the starter map has to suit.
        CombatRules average = rollingExactly(0.5);
        Attributes fresh = Attributes.FRESH;

        int rounds = (int) Math.ceil(mob.hp() / (double) average.damage(fresh.attack(), mob.armor()));
        return rounds * average.damage(mob.attack(), fresh.armor()) < fresh.maxHp();
    }

    @Test
    void fleeingSucceedsOrFailsExactlyAtTheStatedChance() {
        assertThat(rollingExactly(CombatRules.FLEE_CHANCE - 0.01).escapes()).isTrue();
        assertThat(rollingExactly(CombatRules.FLEE_CHANCE).escapes()).isFalse();
    }

    @Test
    void beingKilledCostsLongerTheFurtherAlongYouAre() {
        // This replaced the halved attack and armour a death used to carry.
        // One event should be paid for once, and lying there for a while is
        // both the clearer price and the one a player can plan around.
        assertThat(CombatRules.wakeSeconds(5))
                .isGreaterThan(CombatRules.wakeSeconds(1))
                .isEqualTo(CombatRules.WAKE_SECONDS_PER_LEVEL * 5);
    }

    @Test
    void butNeverLongerThanAnybodyWouldSitThrough() {
        // The other end of "rising with the level" is a quarter of an hour
        // looking at a screen, and no lesson is worth that.
        assertThat(CombatRules.wakeSeconds(500)).isEqualTo(CombatRules.WAKE_SECONDS_MAX);
    }

    @Test
    void nonsenseInDoesNotProduceNonsenseOut() {
        assertThat(CombatRules.wakeSeconds(0)).isEqualTo(CombatRules.WAKE_SECONDS_PER_LEVEL);
        assertThat(CombatRules.wakeSeconds(-4)).isPositive();
    }
}
