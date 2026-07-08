package com.example.mydnd.changeset;

import com.example.mydnd.game.CampaignPromptState;
import com.example.mydnd.game.GameEvent;
import com.example.mydnd.memory.MemoryContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Compact prompts for the narrative-first pipeline. */
public final class ChangeSetPromptBuilder {

    private static final int MAX_SCENE = 240;
    private static final int MAX_WORLD = 120;
    private static final int MAX_CHARACTER = 140;
    private static final int MAX_SUMMARY = 140;
    private static final int MAX_RECENT = 180;
    private static final int MAX_FACTS = 180;

    public String buildPlayerTurn(
            String playerAction,
            MemoryContext memory,
            ChangeSetPromptState state,
            CampaignPromptState campaign
    ) {
        ChangeSetPromptState safeState = state == null
                ? ChangeSetPromptState.empty()
                : state;
        CampaignPromptState safeCampaign = campaign == null
                ? CampaignPromptState.empty()
                : campaign;

        StringBuilder prompt = new StringBuilder();
        prompt.append("SYSTEM:\n")
                .append("Ты мастер RPG. Первый ответ — только CHECK при реальном риске с значимым последствием провала, иначе DONE. ")
                .append("После DONE сразу продолжи сцену 2-4 атмосферными предложениями. Не решай за игрока и не придумывай механические последствия без причины.");

        prompt.append("\n\nCURRENT_SCENE:\n")
                .append(limitStart(safeCampaign.getCurrentScene(), MAX_SCENE));

        appendOptional(prompt, "WORLD", limitStart(safeCampaign.getWorld(), MAX_WORLD));
        appendOptional(prompt, "CHARACTER", limitStart(safeCampaign.getCharacter(), MAX_CHARACTER));

        String summary = memory != null && memory.hasSummary()
                ? limitStart(memory.getLatestSummary(), MAX_SUMMARY)
                : "";
        String recent = memory == null
                ? ""
                : limitEnd(recent(memory.getRecentEvents()), MAX_RECENT);
        String facts = memory == null
                ? ""
                : limitStart(facts(memory.getRelevantFacts()), MAX_FACTS);

        appendOptional(prompt, "SUMMARY", summary);
        appendOptional(prompt, "RECENT", recent);
        appendOptional(prompt, "FACTS", facts);

        appendState(prompt, safeState, playerAction);

        prompt.append("\n\nPLAYER_ACTION:\n")
                .append(safe(playerAction));
        return prompt.toString();
    }

    public String buildResolvedCheckTurn(
            ChangeSetPromptState state,
            CampaignPromptState campaign,
            String attribute,
            int dc,
            String reason,
            int roll,
            boolean success
    ) {
        ChangeSetPromptState safeState = state == null
                ? ChangeSetPromptState.empty()
                : state;
        CampaignPromptState safeCampaign = campaign == null
                ? CampaignPromptState.empty()
                : campaign;

        StringBuilder prompt = new StringBuilder();
        prompt.append("SYSTEM:\n")
                .append("Ты мастер RPG. Проверка уже решена: строго соблюдай OUTCOME. ")
                .append("Опиши непосредственный результат 2-4 атмосферными предложениями. Последствия должны прямо вытекать из действия и броска; не пиши технический отчёт.");

        prompt.append("\n\nCURRENT_SCENE:\n")
                .append(limitStart(safeCampaign.getCurrentScene(), MAX_SCENE));
        appendOptional(prompt, "CHARACTER", limitStart(safeCampaign.getCharacter(), MAX_CHARACTER));
        appendState(prompt, safeState, reason);

        prompt.append("\n\nCHECK_RESULT:")
                .append("\nSTAT=").append(safe(attribute))
                .append("\nDC=").append(clamp(dc, 5, 25))
                .append("\nROLL=").append(clamp(roll, -99, 99))
                .append("\nOUTCOME=").append(success ? "SUCCESS" : "FAILURE")
                .append("\nREASON=").append(limitStart(reason, 180));
        return prompt.toString();
    }

    private void appendState(
            StringBuilder prompt,
            ChangeSetPromptState state,
            String sourceText
    ) {
        prompt.append("\n\nSTATE:")
                .append("\nHP=").append(empty(state.getHealth()))
                .append("\nMONEY=").append(empty(state.getMoney()))
                .append("\nLOC=").append(empty(state.getLocation()));

        appendRefs(prompt, "INV", state.getInventory());
        appendRefs(prompt, "NPC", state.getNpcs());
        appendRefs(prompt, "QUEST", state.getQuests());
        appendRefs(prompt, "WORLD_EVENT", state.getWorldEvents());
        appendRefs(prompt, "ABILITY", state.getAbilities());
        appendRefs(prompt, "EFFECT", state.getEffects());

        List<String> eligible = eligibleAliases(state, sourceText);
        prompt.append("\nELIGIBLE_REFS=")
                .append(eligible.isEmpty() ? "NONE" : String.join(",", eligible));
    }

    private List<String> eligibleAliases(
            ChangeSetPromptState state,
            String sourceText
    ) {
        String source = safe(sourceText).toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        collectEligible(result, state.getInventory(), source);
        collectEligible(result, state.getNpcs(), source);
        collectEligible(result, state.getQuests(), source);
        collectEligible(result, state.getWorldEvents(), source);
        collectEligible(result, state.getAbilities(), source);
        collectEligible(result, state.getEffects(), source);
        return result;
    }

    private void collectEligible(
            List<String> target,
            List<ChangeSetPromptState.Ref> refs,
            String sourceLower
    ) {
        if (refs == null || refs.isEmpty() || sourceLower.isEmpty()) {
            return;
        }
        for (ChangeSetPromptState.Ref ref : refs) {
            if (ref == null || ref.getName().isEmpty()) {
                continue;
            }
            if (sourceLower.contains(ref.getName().toLowerCase(Locale.ROOT))) {
                target.add(ref.getAlias());
            }
        }
    }

    private void appendRefs(
            StringBuilder prompt,
            String title,
            List<ChangeSetPromptState.Ref> refs
    ) {
        prompt.append("\n").append(title).append(":");
        if (refs == null || refs.isEmpty()) {
            prompt.append(" NONE");
            return;
        }
        for (ChangeSetPromptState.Ref ref : refs) {
            if (ref == null || ref.getAlias().isEmpty() || ref.getName().isEmpty()) {
                continue;
            }
            prompt.append("\n").append(ref.toPromptLine());
        }
    }

    private String recent(List<GameEvent> events) {
        if (events == null || events.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (GameEvent event : events) {
            if (event == null || !event.isIncludeInPrompt()) {
                continue;
            }
            String text = safe(event.getText());
            if (text.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append("\n");
            }
            if (event.getSpeaker() == GameEvent.Speaker.PLAYER) {
                result.append("P: ");
            } else if (event.getSpeaker() == GameEvent.Speaker.MASTER) {
                result.append("GM: ");
            } else {
                result.append("S: ");
            }
            result.append(text);
        }
        return result.toString();
    }

    private String facts(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            String clean = safe(value);
            if (clean.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append("\n");
            }
            result.append(clean);
        }
        return result.toString();
    }

    private void appendOptional(StringBuilder prompt, String title, String value) {
        String clean = safe(value);
        if (!clean.isEmpty()) {
            prompt.append("\n\n").append(title).append(":\n").append(clean);
        }
    }

    private String limitStart(String value, int max) {
        String clean = safe(value);
        if (clean.length() <= max) {
            return clean;
        }
        return clean.substring(0, max).trim();
    }

    private String limitEnd(String value, int max) {
        String clean = safe(value);
        if (clean.length() <= max) {
            return clean;
        }
        return clean.substring(clean.length() - max).trim();
    }

    private String empty(String value) {
        String clean = safe(value);
        return clean.isEmpty() ? "NONE" : clean;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
