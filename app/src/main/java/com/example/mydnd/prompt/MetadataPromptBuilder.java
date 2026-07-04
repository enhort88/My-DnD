package com.example.mydnd.prompt;

public class MetadataPromptBuilder {

    public String buildPrompt(
            String playerText
    ) {
        String action =
                playerText == null
                        ? ""
                        : playerText.trim();


        StringBuilder prompt =
                new StringBuilder();


        prompt.append(
                "Analyze only ACTION.\n"
        );

        prompt.append(
                "Report the item state immediately after the action.\n"
        );


        prompt.append(
                "\nReturn exactly one of these forms:\n"
        );

        prompt.append(
                "{\"type\":\"NONE\"}\n"
        );

        prompt.append(
                "{\"type\":\"ITEM\",\"holder\":\"PLAYER\",\"name\":\"exact item name\"}\n"
        );

        prompt.append(
                "{\"type\":\"ITEM\",\"holder\":\"WORLD\",\"name\":\"exact item name\"}\n"
        );


        prompt.append(
                "\nPLAYER means the concrete item is in the player's possession after ACTION.\n"
        );

        prompt.append(
                "WORLD means the concrete item is outside the player's possession after ACTION.\n"
        );

        prompt.append(
                "NONE means ACTION causes no concrete item state change.\n"
        );

        prompt.append(
                "Copy the item name exactly from ACTION. Never translate it.\n"
        );


        prompt.append(
                "\nACTION:\n"
        );

        prompt.append(
                action
        );


        prompt.append(
                "\n\nANSWER:\n"
        );


        return prompt.toString();
    }
}