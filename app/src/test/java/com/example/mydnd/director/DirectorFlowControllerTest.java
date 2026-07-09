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
        return "call:director_action{type:\"INV_ADD\",name:\""
                + name + "\",value:\"\",details:\"picked up\"}";
    }
}
