package com.example.mydnd.prompt;

import java.util.Collections;
import java.util.List;

public class GemmaToolPromptBuilder {

    public String buildInventoryPrompt(
            String action,
            List<String> inventoryBefore
    ) {
        String safeAction =
                action == null
                        ? ""
                        : action.trim();

        List<String> safeInventory =
                inventoryBefore == null
                        ? Collections.emptyList()
                        : inventoryBefore;

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

        appendAddItemTool(
                prompt
        );

        appendRemoveItemTool(
                prompt
        );

        prompt.append("<turn|>\n");
        prompt.append("<|turn>user\n");
        prompt.append("INVENTORY BEFORE:\n");

        boolean hasInventoryItems =
                false;

        for (String itemName : safeInventory) {
            if (itemName == null
                    || itemName.trim().isEmpty()) {

                continue;
            }

            hasInventoryItems =
                    true;

            prompt.append("- ");
            prompt.append(
                    itemName.trim()
            );
            prompt.append('\n');
        }

        if (!hasInventoryItems) {
            prompt.append("(empty)\n");
        }

        prompt.append("\nACTION:\n");
        prompt.append(
                safeAction
        );
        prompt.append("<turn|>\n");
        prompt.append("<|turn>model\n");

        return prompt.toString();
    }


    private void appendAddItemTool(
            StringBuilder prompt
    ) {
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
    }


    private void appendRemoveItemTool(
            StringBuilder prompt
    ) {
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
    }
}
