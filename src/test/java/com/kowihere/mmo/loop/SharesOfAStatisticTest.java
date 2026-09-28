package com.kowihere.mmo.loop;

import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.world.BlessingDef;
import com.kowihere.mmo.world.BlessingLine;
import com.kowihere.mmo.world.BlessingStat;
import com.kowihere.mmo.world.ItemDef;
import com.kowihere.mmo.world.ItemSlot;
import com.kowihere.mmo.world.Rarity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A line written as a percentage, counted by hand.
 *
 * <p>Everything else about blessings is tested through a running map, where the
 * claim is that no second place computes anything. This one is arithmetic, and
 * arithmetic deserves numbers somebody can check: what a share is a share
 * <em>of</em>, which way it rounds, and how much of the penalty for dying is
 * counted twice.
 */
class SharesOfAStatisticTest {

    private static final ItemDef PLATE = new ItemDef("plyta", "Płyta", ItemSlot.CHEST, 1,
            new Attributes(0, 0, 0), 0, 20, null, null, 10);

    private static BlessingDef share(BlessingStat stat, int percent) {
        return new BlessingDef("probny-udzial", "Próbny udział", Rarity.COMMON, 5, 1,
                Map.of(stat, BlessingLine.percent(percent)));
    }

    private static Actor character(Attributes attributes) {
        Actor actor = new Actor(1, "Ala", "ala", 1L, 5, 5, 1);
        actor.attributes = attributes;
        return actor;
    }

    @Test
    void aShareOfArmourIsAShareOfTheArmourYouAreWearing() {
        // The whole point of the order: flat first, then the percentage on the
        // sum of it. Taken off the attributes alone, a tenth of armour would be
        // a tenth of the one point a body is worth and nothing off the plate.
        Actor ala = character(new Attributes(30, 10, 10));
        ItemStack plate = new ItemStack("p-1", PLATE);
        ala.inventory.add(plate);
        ala.inventory.wear(plate);
        assertThat(ala.armor()).isEqualTo(21);

        ala.blessings.lay(share(BlessingStat.ARMOR, -10));

        assertThat(ala.armor()).isEqualTo(19);
    }

    @Test
    void whatIsLostIsRoundedDown() {
        // Towards the player, and deliberately. A penalty nobody chose that
        // bites harder than it promises is how a lesson turns into a grudge -
        // and it means the first few levels, where a tenth is not yet a whole
        // point, lose nothing at all.
        Actor ala = character(new Attributes(15, 5, 5));

        ala.blessings.lay(share(BlessingStat.STRENGTH, -10));

        assertThat(ala.totalAttributes().strength())
                .as("a tenth of fifteen is one and a half, and one is what it costs")
                .isEqualTo(14);

        Actor beginner = character(Attributes.FRESH);
        beginner.blessings.lay(share(BlessingStat.STRENGTH, -10));
        assertThat(beginner.totalAttributes().strength())
                .as("and a tenth of five is nothing yet")
                .isEqualTo(5);
    }

    @Test
    void aShareOfAttackIsCountedOnTopOfWhatStrengthAlreadyLost() {
        // Not an accident, and worth a number: -10% strength has already taken
        // attack down once, because attack grows out of strength. A line on
        // attack takes it down again. For the penalty after dying that is the
        // intention - it is meant to reach the sword as well as the arm - but
        // it means the attack actually lost is half again the figure on the
        // line.
        Actor ala = character(new Attributes(30, 10, 10));
        assertThat(ala.attack()).isEqualTo(32);

        ala.blessings.lay(new BlessingDef("probna-slabosc", "Próbna słabość", Rarity.COMMON, 5, 1,
                Map.of(BlessingStat.STRENGTH, BlessingLine.percent(-10),
                        BlessingStat.ATTACK, BlessingLine.percent(-10))));

        assertThat(ala.totalAttributes().strength()).isEqualTo(27);
        assertThat(ala.attack())
                .as("29 from strength, then a tenth of that off again")
                .isEqualTo(27);
    }

    @Test
    void flatPointsAndSharesLiveTogether() {
        Actor ala = character(new Attributes(30, 10, 10));

        ala.blessings.lay(new BlessingDef("probna-mieszanka", "Próbna mieszanka", Rarity.COMMON,
                5, 1, Map.of(BlessingStat.STRENGTH, BlessingLine.flat(10))));
        ala.blessings.lay(share(BlessingStat.STRENGTH, -50));

        assertThat(ala.totalAttributes().strength())
                .as("forty first, and then half of forty")
                .isEqualTo(20);
    }

    @Test
    void aShareCanTakeEverythingAndStops() {
        // Nothing in this game may go negative: every formula below would obey
        // it. The same rule the flat lines already had.
        Actor ala = character(new Attributes(30, 10, 10));

        ala.blessings.lay(share(BlessingStat.STRENGTH, -100));
        assertThat(ala.totalAttributes().strength()).isZero();
        assertThat(ala.attack()).isPositive();

        // And a curse worth more than the character has stops at nothing too,
        // rather than coming out the other side as a number every formula
        // below would then quietly obey.
        Actor bela = character(new Attributes(30, 10, 10));
        bela.blessings.lay(new BlessingDef("probna-ruina", "Próbna ruina", Rarity.COMMON, 5, 1,
                Map.of(BlessingStat.ARMOR, BlessingLine.flat(-50))));
        assertThat(bela.armor()).isZero();
    }
}
