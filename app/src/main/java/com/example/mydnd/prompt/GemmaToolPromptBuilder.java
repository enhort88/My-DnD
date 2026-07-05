package com.example.mydnd.prompt;

public class GemmaToolPromptBuilder {

    public String buildRememberFactPrompt(
            String action
    ) {
        String safeAction =
                action == null
                        ? ""
                        : action.trim();

        /*
         * Временный PoC.
         * Реального InventoryRepository в проекте пока нет,
         * поэтому для одного диагностического теста считаем,
         * что до хода у персонажа уже есть костяной амулет.
         */
        String inventoryBefore =
                "(empty)";

        StringBuilder prompt =
                new StringBuilder();

        prompt.append("<|turn>system\n");

        prompt.append(
                "You are an inventory state router. "
                        + "INVENTORY BEFORE is authoritative. "
                        + "Treat ACTION as successfully completed. "
                        + "Compare item membership before and after the whole ACTION. "
                        + "If one concrete item was absent before and present after, call add_item_to_inventory. "
                        + "If one concrete item was present before and absent after, call remove_item_from_inventory. "
                        + "If inventory membership is unchanged, answer exactly NONE. "
                        + "Use only the final state after the complete ACTION, not an intermediate step. "
                        + "Call at most one tool. "
                        + "Copy the item name exactly from ACTION. Never translate it. "
                        + "Never narrate and never explain your choice."
        );

        prompt.append(
                "<|tool>declaration:add_item_to_inventory{"
                        + "description:<|\"|>Call when a concrete item is absent from INVENTORY BEFORE but present after the completed ACTION.<|\"|>,"
                        + "parameters:{"
                        + "properties:{"
                        + "name:{"
                        + "description:<|\"|>Exact item name copied from ACTION. Never translate.<|\"|>,"
                        + "type:<|\"|>STRING<|\"|>"
                        + "}"
                        + "},"
                        + "required:[<|\"|>name<|\"|>],"
                        + "type:<|\"|>OBJECT<|\"|>"
                        + "}"
                        + "}<tool|>"
        );

        prompt.append(
                "<|tool>declaration:remove_item_from_inventory{"
                        + "description:<|\"|>Call when a concrete item is present in INVENTORY BEFORE but absent after the completed ACTION.<|\"|>,"
                        + "parameters:{"
                        + "properties:{"
                        + "name:{"
                        + "description:<|\"|>Exact item name copied from ACTION. Never translate.<|\"|>,"
                        + "type:<|\"|>STRING<|\"|>"
                        + "}"
                        + "},"
                        + "required:[<|\"|>name<|\"|>],"
                        + "type:<|\"|>OBJECT<|\"|>"
                        + "}"
                        + "}<tool|>"
        );

        prompt.append("<turn|>\n");
        prompt.append("<|turn>user\n");
        prompt.append("INVENTORY BEFORE:\n");
        prompt.append(inventoryBefore);
        prompt.append("\n\nACTION:\n");
        prompt.append(safeAction);
        prompt.append("<turn|>\n");
        prompt.append("<|turn>model\n");

        return prompt.toString();
    }
}
