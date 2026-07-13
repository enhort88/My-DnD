package com.example.mydnd.director;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Verifies that per-turn guardrails are purely structural (action type/count
 * based) and never depend on scanning player text or action details for
 * language-specific keywords. The same assertions must hold no matter what
 * language the details/playerText are written in.
 */
public class DirectorFlowControllerTest {

    private static final class RecordingStore implements DirectorStore {
        @Override
        public DirectorStoreResult apply(long campaignId, DirectorAction action) {
            return DirectorStoreResult.applied("OK", "");
        }
    }

    @Test
    public void secondHealthChangeInSameTurnIsRejectedRegardlessOfLanguage() {
        DirectorFlowController controller = new DirectorFlowController(
                new RecordingStore(),
                result -> { }
        );

        // German player text; no English/Russian damage keywords anywhere.
        controller.startTurn(
                DirectorMode.PLAYER_ACTION,
                "NONE",
                "Ich stolpere über eine Wurzel."
        );

        String firstResponse = controller.onToolCall(
                1L,
                hpCall("-2", "Sturz über eine Wurzel.")
        );
        assertTrue(
                "first HP change must apply",
                firstResponse.contains("APPLIED")
        );

        String secondResponse = controller.onToolCall(
                1L,
                hpCall("-1", "Noch ein Sturz.")
        );
        assertTrue(
                "second HP change in the same turn must be rejected structurally",
                secondResponse.contains("HP_ALREADY_APPLIED_THIS_TURN")
        );
    }

    @Test
    public void secondMoneyChangeInSameTurnIsRejectedRegardlessOfLanguage() {
        DirectorFlowController controller = new DirectorFlowController(
                new RecordingStore(),
                result -> { }
        );

        controller.startTurn(
                DirectorMode.PLAYER_ACTION,
                "NONE",
                "Je trouve une bourse pleine de pièces."
        );

        String firstResponse = controller.onToolCall(
                1L,
                moneyCall("+10", "Bourse trouvée sur la route.")
        );
        assertTrue(
                "first MONEY change must apply",
                firstResponse.contains("APPLIED")
        );

        String secondResponse = controller.onToolCall(
                1L,
                moneyCall("+5", "Une autre bourse, comme par magie.")
        );
        assertTrue(
                "second MONEY change in the same turn must be rejected structurally",
                secondResponse.contains("MONEY_ALREADY_APPLIED_THIS_TURN")
        );
    }

    @Test
    public void sameFactAppliedAsDifferentTypeInSameTurnIsRejected() {
        DirectorFlowController controller = new DirectorFlowController(
                new RecordingStore(),
                result -> { }
        );

        controller.startTurn(
                DirectorMode.PLAYER_ACTION,
                "NONE",
                "Ich schaue mich um, sehe ich eine Stadt?"
        );

        String firstResponse = controller.onToolCall(
                1L,
                invAddCall("Stadt", "Du siehst in der Ferne die Umrisse einer Stadt.")
        );
        assertTrue(
                "first observation, encoded as INV_ADD, must apply",
                firstResponse.contains("APPLIED")
        );

        String secondResponse = controller.onToolCall(
                1L,
                locationCall("Stadt", "Du siehst in der Ferne die Umrisse einer Stadt.")
        );
        assertTrue(
                "the same fact re-encoded as a different action type must be rejected",
                secondResponse.contains("DUPLICATE_FACT_DIFFERENT_TYPE")
        );
        assertTrue(
                "the rejection must force the turn to end instead of allowing more retries",
                secondResponse.contains("DONE_ONLY")
        );
    }

    @Test
    public void distinctActionsInSameTurnStillBothApply() {
        DirectorFlowController controller = new DirectorFlowController(
                new RecordingStore(),
                result -> { }
        );

        controller.startTurn(
                DirectorMode.PLAYER_ACTION,
                "NONE",
                "Ich schaue mich um und hebe einen Stein auf."
        );

        String firstResponse = controller.onToolCall(
                1L,
                invAddCall("Stein", "Du hebst einen Stein vom Boden auf.")
        );
        assertTrue(
                "picking up the rock must apply",
                firstResponse.contains("APPLIED")
        );

        String secondResponse = controller.onToolCall(
                1L,
                locationCall("Lichtung", "Du siehst eine Lichtung vor dir.")
        );
        assertTrue(
                "a genuinely different fact (different name and details) must still apply",
                secondResponse.contains("APPLIED")
        );
    }

    @Test
    public void explicitAddHintCapsTurnToTheHintedItemOnlyForAnyLanguage() {
        DirectorFlowController controller = new DirectorFlowController(
                new RecordingStore(),
                result -> { }
        );

        controller.startTurn(
                DirectorMode.PLAYER_ACTION,
                "EXPLICIT_ADD: *Stein*",
                "Ich nehme den Stein."
        );

        String firstResponse = controller.onToolCall(
                1L,
                invAddCall("Stein")
        );
        assertTrue(
                "hinted inventory action must apply",
                firstResponse.contains("APPLIED")
        );

        String secondResponse = controller.onToolCall(
                1L,
                moneyCall("+1", "Beim Aufheben klimpert etwas Kleingeld.")
        );
        assertTrue(
                "any second applied action after a single-item hint must be rejected",
                secondResponse.contains("ACTION_HINT_REQUIRES_DONE")
        );
    }

    private static String hpCall(String value, String details) {
        return "call:director_action{type:\"HP\",name:\"PLAYER\",value:\""
                + value + "\",details:\"" + details + "\"}";
    }

    private static String moneyCall(String value, String details) {
        return "call:director_action{type:\"MONEY\",name:\"PLAYER\",value:\""
                + value + "\",details:\"" + details + "\"}";
    }

    private static String invAddCall(String name) {
        return invAddCall(name, "picked up");
    }

    private static String invAddCall(String name, String details) {
        return "call:director_action{type:\"INV_ADD\",name:\""
                + name + "\",value:\"\",details:\"" + details + "\"}";
    }

    private static String locationCall(String name, String details) {
        return "call:director_action{type:\"LOCATION\",name:\""
                + name + "\",value:\"\",details:\"" + details + "\"}";
    }
}
