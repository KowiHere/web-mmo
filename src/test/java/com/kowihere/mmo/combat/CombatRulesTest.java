package com.kowihere.mmo.combat;

import com.kowihere.mmo.world.MobDef;
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
    void statisticsFollowTheLevelAndNothingElse() {
        assertThat(CombatRules.maxHpForLevel(1)).isEqualTo(65);
        assertThat(CombatRules.maxHpForLevel(5)).isEqualTo(125);
        assertThat(CombatRules.attackForLevel(1)).isEqualTo(7);
        assertThat(CombatRules.armorForLevel(1)).isEqualTo(2);

        for (int level = 1; level < 50; level++) {
            assertThat(CombatRules.maxHpForLevel(level + 1))
                    .isGreaterThan(CombatRules.maxHpForLevel(level));
        }
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
        // the length of a fight.
        CombatRules average = rollingExactly(0.5);
        int hp = CombatRules.maxHpForLevel(1);
        int attack = CombatRules.attackForLevel(1);
        int armour = CombatRules.armorForLevel(1);

        int rounds = (int) Math.ceil(mob.hp() / (double) average.damage(attack, mob.armor()));
        return rounds * average.damage(mob.attack(), armour) < hp;
    }

    @Test
    void fleeingSucceedsOrFailsExactlyAtTheStatedChance() {
        assertThat(rollingExactly(CombatRules.FLEE_CHANCE - 0.01).escapes()).isTrue();
        assertThat(rollingExactly(CombatRules.FLEE_CHANCE).escapes()).isFalse();
    }

    @Test
    void beingNewlyDeadHalvesWhatYouBringToTheNextFight() {
        assertThat(CombatRules.weakened(20)).isEqualTo(10);
        assertThat(CombatRules.weakened(1))
                .as("a penalty should never reduce anything to nothing")
                .isEqualTo(1);
    }
}
