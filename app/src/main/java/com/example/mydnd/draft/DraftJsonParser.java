package com.example.mydnd.draft;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class DraftJsonParser {

    public WorldDraft parseWorld(String rawText) throws JSONException {
        JSONObject json = new JSONObject(extractJsonObject(rawText));

        List<WorldDraft.RaceDraft> races = new ArrayList<>();
        JSONArray raceArray = json.optJSONArray("races");

        if (raceArray != null) {
            for (int i = 0; i < raceArray.length(); i++) {
                JSONObject race = raceArray.optJSONObject(i);

                if (race == null) {
                    continue;
                }

                String name = race.optString("name", "").trim();

                if (name.isEmpty()) {
                    continue;
                }

                races.add(
                        new WorldDraft.RaceDraft(
                                name,
                                race.optString("description", "")
                        )
                );
            }
        }

        WorldDraft draft = new WorldDraft(
                json.optString("name", ""),
                json.optString("genre", ""),
                json.optString("description", ""),
                json.optString("rules", ""),
                races
        );

        require(!draft.getName().isEmpty(), "В ответе нет названия мира");
        require(!draft.getDescription().isEmpty(), "В ответе нет описания мира");
        require(!draft.getRaces().isEmpty(), "В ответе нет рас мира");

        return draft;
    }

    public CharacterDraft parseCharacter(String rawText) throws JSONException {
        JSONObject json = new JSONObject(extractJsonObject(rawText));

        List<String> items = new ArrayList<>();
        JSONArray itemArray = json.optJSONArray("startingItems");

        if (itemArray != null) {
            for (int i = 0; i < itemArray.length(); i++) {
                String item = itemArray.optString(i, "").trim();

                if (!item.isEmpty()) {
                    items.add(item);
                }
            }
        }

        CharacterDraft draft = new CharacterDraft(
                json.optString("name", ""),
                json.optString("race", ""),
                json.optString("className", ""),
                json.optString("age", ""),
                json.optString("description", ""),
                json.optString("background", ""),
                json.optString("personality", ""),
                items
        );

        require(!draft.getName().isEmpty(), "В ответе нет имени героя");
        require(!draft.getRace().isEmpty(), "В ответе нет расы героя");
        require(!draft.getClassName().isEmpty(), "В ответе нет класса героя");

        return draft;
    }

    public StartingSituationDraft parseSituation(String rawText) throws JSONException {
        JSONObject json = new JSONObject(extractJsonObject(rawText));

        List<StartingSituationDraft.NpcDraft> npcs = new ArrayList<>();
        JSONArray npcArray = json.optJSONArray("npcs");

        if (npcArray != null) {
            for (int i = 0; i < npcArray.length(); i++) {
                JSONObject npc = npcArray.optJSONObject(i);

                if (npc == null) {
                    continue;
                }

                String name = npc.optString("name", "").trim();

                if (name.isEmpty()) {
                    continue;
                }

                npcs.add(
                        new StartingSituationDraft.NpcDraft(
                                name,
                                npc.optString("description", ""),
                                npc.optString("stateSummary", "")
                        )
                );
            }
        }

        StartingSituationDraft draft = new StartingSituationDraft(
                json.optString("title", ""),
                json.optString("stateSummary", ""),
                npcs
        );

        require(!draft.getTitle().isEmpty(), "В ответе нет названия ситуации");
        require(!draft.getStateSummary().isEmpty(), "В ответе нет стартовой сцены");

        return draft;
    }

    private String extractJsonObject(String rawText) throws JSONException {
        if (rawText == null) {
            throw new JSONException("Пустой ответ модели");
        }

        String value = rawText.trim();
        int start = value.indexOf('{');

        if (start < 0) {
            throw new JSONException("Модель не вернула JSON-объект");
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }

                if (c == '\\') {
                    escaped = true;
                    continue;
                }

                if (c == '"') {
                    inString = false;
                }

                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == '{') {
                depth++;
                continue;
            }

            if (c == '}') {
                depth--;

                if (depth == 0) {
                    return value.substring(start, i + 1);
                }
            }
        }

        throw new JSONException("JSON-объект не закрыт");
    }

    private void require(
            boolean condition,
            String message
    ) throws JSONException {
        if (!condition) {
            throw new JSONException(message);
        }
    }
}
