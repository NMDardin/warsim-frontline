package com.warsim.frontline.api.objective;

import static org.junit.jupiter.api.Assertions.*;

import com.warsim.frontline.api.roster.TeamSide;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObjectiveModelTest {
    @Test void validObjectiveId() {
        assertEquals("alpha-1", new ObjectiveId("alpha-1").value());
    }

    @Test void objectiveIdRejectsUppercase() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectiveId("Alpha"));
    }

    @Test void objectiveIdRejectsLongValue() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectiveId("a".repeat(33)));
    }

    @Test void objectiveIdRejectsSpaces() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectiveId("alpha point"));
    }

    @Test void regionIncludesHorizontalBoundary() {
        ObjectiveRegion region = new ObjectiveRegion("world", 0, 64, 0, 8, 6);
        assertTrue(region.contains("world", 8, 64, 0));
    }

    @Test void regionRejectsOutsideRadius() {
        ObjectiveRegion region = new ObjectiveRegion("world", 0, 64, 0, 8, 6);
        assertFalse(region.contains("world", 8.01, 64, 0));
    }

    @Test void regionRejectsOutsideVerticalRange() {
        ObjectiveRegion region = new ObjectiveRegion("world", 0, 64, 0, 8, 6);
        assertFalse(region.contains("world", 0, 70.01, 0));
    }

    @Test void regionRejectsDifferentWorld() {
        ObjectiveRegion region = new ObjectiveRegion("world", 0, 64, 0, 8, 6);
        assertFalse(region.contains("world_nether", 0, 64, 0));
    }

    @Test void regionRejectsNonFiniteCoordinate() {
        assertThrows(IllegalArgumentException.class,
            () -> new ObjectiveRegion("world", Double.NaN, 64, 0, 8, 6));
    }

    @Test void captureRulesValidateBaseSeconds() {
        assertThrows(IllegalArgumentException.class,
            () -> new ObjectiveCaptureRules(4, 4, .5, EmptyBehavior.RETURN_TO_OWNER,
                ContestedBehavior.FREEZE));
    }

    @Test void captureRulesValidateEffectivePlayers() {
        assertThrows(IllegalArgumentException.class,
            () -> new ObjectiveCaptureRules(30, 0, .5, EmptyBehavior.RETURN_TO_OWNER,
                ContestedBehavior.FREEZE));
    }

    @Test void captureRulesValidateAdditionalRate() {
        assertThrows(IllegalArgumentException.class,
            () -> new ObjectiveCaptureRules(30, 4, 2.1, EmptyBehavior.RETURN_TO_OWNER,
                ContestedBehavior.FREEZE));
    }

    @Test void configurationRequiresPointWhenEnabled() {
        assertThrows(IllegalArgumentException.class,
            () -> new ObjectiveConfiguration(true, 5, List.of()));
    }

    @Test void configurationValidatesScanInterval() {
        assertThrows(IllegalArgumentException.class,
            () -> new ObjectiveConfiguration(true, 21, List.of(definition())));
    }

    @Test void presenceRequiresTeam() {
        assertThrows(NullPointerException.class,
            () -> new ObjectivePlayerPresence(java.util.UUID.randomUUID(), null, "world", 0, 64, 0));
    }

    @Test void defaultDefinitionStartsOwnedByDefenders() {
        assertEquals(ObjectiveOwner.DEFENDERS, definition().initialOwner());
    }

    @Test void regionAcceptsVerticalBoundary() {
        ObjectiveRegion region = new ObjectiveRegion("world", 0, 64, 0, 8, 6);
        assertTrue(region.contains("world", 0, 70, 0));
    }

    @Test void regionRejectsRadiusBelowOne() {
        assertThrows(IllegalArgumentException.class,
            () -> new ObjectiveRegion("world", 0, 64, 0, .9, 6));
    }

    @Test void regionRejectsVerticalAboveSixtyFour() {
        assertThrows(IllegalArgumentException.class,
            () -> new ObjectiveRegion("world", 0, 64, 0, 8, 65));
    }

    @Test void displayNameCannotBeBlank() {
        ObjectiveDefinition definition = definition();
        assertThrows(IllegalArgumentException.class, () -> new ObjectiveDefinition(
            definition.objectiveId(), " ", definition.region(), definition.initialOwner(),
            false, definition.captureRules(), definition.rewards()));
    }

    @Test void rewardCannotExceedOneThousand() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectiveRewards(1001, 0));
    }

    @Test void multiplierUsesNetPlayerCap() {
        ObjectiveCaptureRules rules = new ObjectiveCaptureRules(
            30, 4, .5, EmptyBehavior.RETURN_TO_OWNER, ContestedBehavior.FREEZE);
        assertEquals(2.5, rules.multiplier(100));
    }

    @Test void disabledConfigurationMayBeEmpty() {
        assertFalse(ObjectiveConfiguration.disabled().enabled());
    }

    @Test void configurationRejectsMoreThanFivePoints() {
        List<ObjectiveDefinition> definitions = java.util.stream.IntStream.range(0, 6)
            .mapToObj(index -> {
                ObjectiveDefinition source = definition();
                return new ObjectiveDefinition(new ObjectiveId("point_" + index),
                    source.displayName(), source.region(), source.initialOwner(), false,
                    source.captureRules(), source.rewards());
            }).toList();
        assertThrows(IllegalArgumentException.class,
            () -> new ObjectiveConfiguration(true, 5, definitions));
    }

    @Test void duplicateObjectiveIdsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new ObjectiveConfiguration(true, 5, List.of(definition(), definition())));
    }

    @Test void ownerMapsTeamSide() {
        assertEquals(TeamSide.ATTACKERS, ObjectiveOwner.ATTACKERS.teamSide());
        assertNull(ObjectiveOwner.NEUTRAL.teamSide());
    }

    private static ObjectiveDefinition definition() {
        return new ObjectiveDefinition(
            new ObjectiveId("alpha"), "A点",
            new ObjectiveRegion("world", .5, 64, .5, 8, 6),
            ObjectiveOwner.DEFENDERS, false,
            new ObjectiveCaptureRules(30, 4, .5, EmptyBehavior.RETURN_TO_OWNER,
                ContestedBehavior.FREEZE),
            new ObjectiveRewards(50, 0)
        );
    }
}
