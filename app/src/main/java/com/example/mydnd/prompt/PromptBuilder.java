package com.example.mydnd.prompt;

import com.example.mydnd.game.CampaignPromptState;
import com.example.mydnd.game.GameEvent;
import com.example.mydnd.director.DirectorMode;
import com.example.mydnd.director.DirectorPromptState;
import com.example.mydnd.director.DirectorToolSpec;
import com.example.mydnd.memory.MemoryContext;

import java.util.Collections;
import java.util.List;

public class PromptBuilder {

    private static final int MAX_SUMMARY_CHARS = 250;
    private static final int MAX_RECENT_EVENTS_CHARS = 350;
    private static final int MAX_RELEVANT_FACTS_CHARS = 450;

    private static final int MAX_WORLD_CHARS = 220;
    private static final int MAX_CHARACTER_CHARS = 180;
    private static final int MAX_WORLD_CHANGES_CHARS = 220;
    private static final int MAX_SITUATIONS_CHARS = 180;
    private static final int MAX_NPCS_CHARS = 180;
    private static final int MAX_SCENE_CHARS = 360;


    public String buildPrompt(
            String playerText,
            MemoryContext memoryContext
    ) {
        return buildPrompt(
                playerText,
                memoryContext,
                Collections.emptyList(),
                ""
        );
    }


    public String buildPrompt(
            String playerText,
            MemoryContext memoryContext,
            List<String> inventory,
            String inventoryUpdate
    ) {
        return buildPromptInternal(
                playerText,
                memoryContext,
                inventory,
                inventoryUpdate,
                false,
                CampaignPromptState.empty(),
                ""
        );
    }


    public String buildInventoryToolAwarePrompt(
            String playerText,
            MemoryContext memoryContext,
            List<String> inventoryBefore
    ) {
        return buildInventoryToolAwarePrompt(
                playerText,
                memoryContext,
                inventoryBefore,
                CampaignPromptState.empty()
        );
    }


    public String buildInventoryToolAwarePrompt(
            String playerText,
            MemoryContext memoryContext,
            List<String> inventoryBefore,
            CampaignPromptState campaignState
    ) {
        return buildInventoryToolAwarePrompt(
                playerText,
                memoryContext,
                inventoryBefore,
                campaignState,
                "NONE"
        );
    }


    public String buildInventoryToolAwarePrompt(
            String playerText,
            MemoryContext memoryContext,
            List<String> inventoryBefore,
            CampaignPromptState campaignState,
            String actionHint
    ) {
        return buildPromptInternal(
                playerText,
                memoryContext,
                inventoryBefore,
                "",
                true,
                campaignState == null
                        ? CampaignPromptState.empty()
                        : campaignState,
                actionHint
        );
    }


    /**
     * Production prompt for the universal Director cycle.
     * Canonical state is deliberately compact and comes from Room, not narrative parsing.
     */
    public String buildDirectorToolAwarePrompt(
            String playerText,
            MemoryContext memoryContext,
            DirectorPromptState directorState,
            CampaignPromptState campaignState
    ) {
        DirectorPromptState state = directorState == null
                ? DirectorPromptState.empty()
                : directorState;
        CampaignPromptState campaign = campaignState == null
                ? CampaignPromptState.empty()
                : campaignState;

        String summary = memoryContext.hasSummary()
                ? limitFromStart(memoryContext.getLatestSummary(), MAX_SUMMARY_CHARS)
                : "";
        String recentEvents = limitFromEnd(
                buildRecentEvents(memoryContext),
                MAX_RECENT_EVENTS_CHARS
        );
        String relevantFacts = limitFromStart(
                buildRelevantFacts(memoryContext),
                MAX_RELEVANT_FACTS_CHARS
        );

        StringBuilder prompt = new StringBuilder();
        prompt.append("SYSTEM:");
        prompt.append(DirectorToolSpec.compactRules(DirectorMode.PLAYER_ACTION));
        prompt.append(DirectorToolSpec.declaration());

        prompt.append("\n\nCURRENT_SCENE:\n");
        prompt.append(limitFromStart(campaign.getCurrentScene(), MAX_SCENE_CHARS));

        appendOptionalBlock(
                prompt,
                "WORLD",
                limitFromStart(campaign.getWorld(), MAX_WORLD_CHARS)
        );
        appendOptionalBlock(
                prompt,
                "CHARACTER",
                limitFromStart(campaign.getCharacter(), MAX_CHARACTER_CHARS)
        );
        appendOptionalBlock(
                prompt,
                "ACTIVE_SITUATIONS",
                limitFromStart(campaign.getActiveSituations(), MAX_SITUATIONS_CHARS)
        );

        if (!summary.isEmpty()) {
            prompt.append("\n\nSUMMARY:\n").append(summary);
        }
        if (!recentEvents.isEmpty()) {
            prompt.append("\n\nRECENT_EVENTS:\n").append(recentEvents);
        }
        if (!relevantFacts.isEmpty()) {
            prompt.append("\n\nRELEVANT_FACTS:\n").append(relevantFacts);
        }

        prompt.append("\n\nSTATE BEFORE (REFERENCE ONLY, NOT TASKS):");
        appendStateLine(prompt, "LOCATION", state.getLocation());
        appendStateLine(prompt, "HP", state.getHealth());
        appendStateLine(prompt, "MONEY", state.getMoney());
        appendStateList(prompt, "INVENTORY", state.getInventory());
        appendStateList(prompt, "NPCS", state.getActiveNpcs());
        appendStateList(prompt, "QUESTS", state.getActiveQuests());
        appendStateList(prompt, "WORLD_EVENTS", state.getWorldEvents());
        appendStateList(prompt, "ABILITIES", state.getAbilities());
        appendStateList(prompt, "EFFECTS", state.getEffects());
        appendStateLine(prompt, "ACTION_HINT", state.getHint());

        prompt.append("\n\nPLAYER_ACTION:\n");
        prompt.append(playerText == null ? "" : playerText.trim());
        return prompt.toString();
    }

    private void appendStateLine(StringBuilder prompt, String title, String value) {
        String safe = value == null ? "" : value.trim();
        prompt.append("\n").append(title).append(": ")
                .append(safe.isEmpty() ? "NONE" : safe);
    }

    private void appendStateList(
            StringBuilder prompt,
            String title,
            List<String> values
    ) {
        prompt.append("\n").append(title).append(":");
        if (values == null || values.isEmpty()) {
            prompt.append(" NONE");
            return;
        }
        int count = 0;
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            prompt.append("\n- ").append(value.trim());
            count++;
        }
        if (count == 0) {
            prompt.append(" NONE");
        }
    }


    private String buildPromptInternal(
            String playerText,
            MemoryContext memoryContext,
            List<String> inventory,
            String inventoryUpdate,
            boolean toolAwareInventory,
            CampaignPromptState campaignState,
            String actionHint
    ) {
        String summary =
                memoryContext.hasSummary()
                        ? limitFromStart(
                                memoryContext.getLatestSummary(),
                                MAX_SUMMARY_CHARS
                        )
                        : "";

        String recentEvents =
                limitFromEnd(
                        buildRecentEvents(memoryContext),
                        MAX_RECENT_EVENTS_CHARS
                );

        String relevantFacts =
                limitFromStart(
                        buildRelevantFacts(memoryContext),
                        MAX_RELEVANT_FACTS_CHARS
                );

        List<String> safeInventory =
                inventory == null
                        ? Collections.emptyList()
                        : inventory;

        String safeInventoryUpdate =
                inventoryUpdate == null
                        ? ""
                        : inventoryUpdate.trim();

        String safePlayerText =
                playerText == null
                        ? ""
                        : playerText.trim();

        String safeActionHint =
                actionHint == null || actionHint.trim().isEmpty()
                        ? "NONE"
                        : actionHint.trim();

        StringBuilder prompt = new StringBuilder();

        prompt.append("SYSTEM:");

        if (toolAwareInventory) {
            appendCompactToolAwareSystem(prompt);
            appendInventoryTools(prompt);
        } else {
            appendCompactNarrativeSystem(prompt);
        }

        prompt.append("\n\nCURRENT_SCENE:\n");
        prompt.append(
                limitFromStart(
                        campaignState.getCurrentScene(),
                        MAX_SCENE_CHARS
                )
        );

        appendOptionalBlock(
                prompt,
                "WORLD",
                limitFromStart(
                        campaignState.getWorld(),
                        MAX_WORLD_CHARS
                )
        );

        appendOptionalBlock(
                prompt,
                "CHARACTER",
                limitFromStart(
                        campaignState.getCharacter(),
                        MAX_CHARACTER_CHARS
                )
        );

        appendOptionalBlock(
                prompt,
                "WORLD_CHANGES",
                limitFromStart(
                        campaignState.getWorldChanges(),
                        MAX_WORLD_CHANGES_CHARS
                )
        );

        appendOptionalBlock(
                prompt,
                "ACTIVE_SITUATIONS",
                limitFromStart(
                        campaignState.getActiveSituations(),
                        MAX_SITUATIONS_CHARS
                )
        );

        appendOptionalBlock(
                prompt,
                "ACTIVE_NPCS",
                limitFromStart(
                        campaignState.getActiveNpcs(),
                        MAX_NPCS_CHARS
                )
        );

        if (!summary.isEmpty()) {
            prompt.append("\n\nSUMMARY:\n");
            prompt.append(summary);
        }

        if (!recentEvents.isEmpty()) {
            prompt.append("\n\nRECENT_EVENTS:\n");
            prompt.append(recentEvents);
        }

        if (!relevantFacts.isEmpty()) {
            prompt.append("\n\nRELEVANT_FACTS:\n");
            prompt.append(relevantFacts);
        }

        prompt.append(
                toolAwareInventory
                        ? "\n\nINVENTORY BEFORE:"
                        : "\n\nINVENTORY:"
        );

        appendInventory(prompt, safeInventory);

        if (!toolAwareInventory
                && !safeInventoryUpdate.isEmpty()) {

            prompt.append("\n\nINVENTORY_UPDATE:\n");
            prompt.append(safeInventoryUpdate);
        }

        if (toolAwareInventory) {
            prompt.append("\n\nACTION_HINT:\n");
            prompt.append(safeActionHint);
        }

        prompt.append("\n\nPLAYER_ACTION:\n");
        prompt.append(safePlayerText);

        return prompt.toString();
    }


    private void appendCompactToolAwareSystem(
            StringBuilder prompt
    ) {
        prompt.append("\nТы мастер DnD.");
        prompt.append("\nПеред ответом определи итог инвентаря и вызови минимум один tool.");
        prompt.append("\nНесколько изменений — tools по одному; после каждого response при необходимости вызови следующий.");
        prompt.append("\nДля tool используй ACTION_HINT, INVENTORY BEFORE и PLAYER_ACTION.");
        prompt.append("\nACTION_HINT EXPLICIT_ADD/REMOVE — вызови соответствующий tool для *имени*; NONE — решай сам.");
        prompt.append("\nINVENTORY BEFORE — истина. Считай PLAYER_ACTION выполненным.");
        prompt.append("\nADD: предмета не было, после действия он у игрока.");
        prompt.append("\nREMOVE: предмет был, после действия его нет.");
        prompt.append("\nNO CHANGE: иначе.");
        prompt.append("\nУчитывай только итог всего действия.");
        prompt.append("\nREMOVE — точное имя из INVENTORY BEFORE.");
        prompt.append("\nADD — естественное имя из PLAYER_ACTION.");
        prompt.append("\nКогда изменения закончены, кратко продолжи сцену по-русски и не противоречь результатам.");
        prompt.append("\nПосле сцены при необходимости: [NPC_MEMORY|имя ACTIVE_NPC|GOOD/BAD/NEUTRAL|факт].");
        prompt.append("\nДля риска: [CHECK|STR/DEX/INT/CHA|5-25|причина]; до броска не описывай исход.");
        prompt.append("\nНе добавляй служебные строки без причины и не раскрывай числовое отношение NPC.");
        prompt.append("\nНе печатай другие служебные блоки и список инвентаря.");
        prompt.append("\nНе решай за игрока. Кубики не бросай.");
        prompt.append("\nАтмосферно, без лишнего пафоса.");
    }


    private void appendCompactNarrativeSystem(
            StringBuilder prompt
    ) {
        prompt.append("\nТы мастер мрачной RPG.");
        prompt.append("\nКратко продолжи сцену по-русски на один шаг.");
        prompt.append("\nINVENTORY — факт приложения, не противоречь ему.");
        prompt.append("\nЕсли есть INVENTORY_UPDATE, естественно отрази его.");
        prompt.append("\nНе решай за игрока. Кубики не бросай. Рискованное действие требует проверки.");
        prompt.append("\nМрачно, атмосферно, без лишнего пафоса.");
    }


    private void appendInventoryTools(
            StringBuilder prompt
    ) {
        prompt.append(
                "<|tool>declaration:add_item_to_inventory{"
                        + "description:<|\"|>Item entered inventory.<|\"|>,"
                        + "parameters:{properties:{name:{"
                        + "description:<|\"|>Item name.<|\"|>,"
                        + "type:<|\"|>STRING<|\"|>"
                        + "}},required:[<|\"|>name<|\"|>],type:<|\"|>OBJECT<|\"|>}"
                        + "}<tool|>"
        );

        prompt.append(
                "<|tool>declaration:remove_item_from_inventory{"
                        + "description:<|\"|>Item left inventory.<|\"|>,"
                        + "parameters:{properties:{name:{"
                        + "description:<|\"|>Item name.<|\"|>,"
                        + "type:<|\"|>STRING<|\"|>"
                        + "}},required:[<|\"|>name<|\"|>],type:<|\"|>OBJECT<|\"|>}"
                        + "}<tool|>"
        );

        prompt.append(
                "<|tool>declaration:no_inventory_change{"
                        + "description:<|\"|>Inventory unchanged.<|\"|>,"
                        + "parameters:{properties:{},required:[],type:<|\"|>OBJECT<|\"|>}"
                        + "}<tool|>"
        );
    }


    private void appendInventory(
            StringBuilder prompt,
            List<String> inventory
    ) {
        boolean hasItems = false;

        for (String itemName : inventory) {
            if (itemName == null
                    || itemName.trim().isEmpty()) {

                continue;
            }

            hasItems = true;
            prompt.append("\n- ");
            prompt.append(itemName.trim());
        }

        if (!hasItems) {
            prompt.append("\n(пусто)");
        }
    }


    private void appendOptionalBlock(
            StringBuilder prompt,
            String title,
            String value
    ) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }

        prompt.append("\n\n")
                .append(title)
                .append(":\n")
                .append(value.trim());
    }


    private String buildRecentEvents(
            MemoryContext memoryContext
    ) {
        StringBuilder builder = new StringBuilder();

        for (GameEvent event : memoryContext.getRecentEvents()) {
            if (event.getSpeaker() == GameEvent.Speaker.PLAYER) {
                builder.append("P: ");

            } else if (event.getSpeaker() == GameEvent.Speaker.MASTER) {
                builder.append("GM: ");

            } else if (event.getSpeaker() == GameEvent.Speaker.SYSTEM) {
                builder.append("SYS: ");

            } else {
                continue;
            }

            builder.append(event.getText().trim());
            builder.append('\n');
        }

        return builder.toString().trim();
    }


    private String buildRelevantFacts(
            MemoryContext memoryContext
    ) {
        StringBuilder builder = new StringBuilder();

        for (String fact : memoryContext.getRelevantFacts()) {
            if (fact == null
                    || fact.trim().isEmpty()) {

                continue;
            }

            builder.append("- ");
            builder.append(fact.trim());
            builder.append('\n');
        }

        return builder.toString().trim();
    }


    private String limitFromStart(
            String text,
            int maxChars
    ) {
        if (text == null) {
            return "";
        }

        String value = text.trim();

        if (value.length() <= maxChars) {
            return value;
        }

        return value
                .substring(0, maxChars)
                .trim();
    }


    private String limitFromEnd(
            String text,
            int maxChars
    ) {
        if (text == null) {
            return "";
        }

        String value = text.trim();

        if (value.length() <= maxChars) {
            return value;
        }

        return value
                .substring(value.length() - maxChars)
                .trim();
    }
}
