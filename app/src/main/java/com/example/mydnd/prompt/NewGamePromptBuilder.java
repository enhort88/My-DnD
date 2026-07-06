package com.example.mydnd.prompt;

import com.example.mydnd.db.entity.CharacterStartingItemEntity;
import com.example.mydnd.db.entity.WorldRaceEntity;
import com.example.mydnd.game.setup.CharacterData;
import com.example.mydnd.game.setup.WorldData;

public class NewGamePromptBuilder {

    public String buildWorldPrompt(String userRequest) {
        String system =
                "Создай компактный мир для локальной RPG. "
                        + "Верни только один валидный JSON без Markdown и пояснений. "
                        + "Поля: name, genre, description, rules, races. "
                        + "description: 3-5 коротких предложений, максимум 700 символов. "
                        + "rules: одна короткая строка, максимум 350 символов. "
                        + "races: 2-5 объектов с полями name и description; "
                        + "description каждой расы — одно короткое предложение. "
                        + "Расы — обычные жители мира, не ограничение для будущего героя. "
                        + "Все значения на русском. "
                        + "Не используй переносы строк внутри строк JSON. "
                        + "Обязательно полностью закрой массивы, строки и сам JSON символом }.";

        String user =
                "ПОЖЕЛАНИЕ:\n"
                        + safe(userRequest);

        return wrap(system, user);
    }

    public String buildCharacterPrompt(
            WorldData worldData,
            String userRequest
    ) {
        StringBuilder races = new StringBuilder();

        for (WorldRaceEntity race : worldData.getRaces()) {
            if (races.length() > 0) {
                races.append("; ");
            }

            races.append(race.name);
        }

        String system =
                "Создай героя для локальной RPG. "
                        + "Верни только один валидный JSON без Markdown и пояснений. "
                        + "Поля: name, race, className, age, description, background, personality, startingItems. "
                        + "description: 1-2 коротких предложения. "
                        + "background: 3-5 коротких предложений. "
                        + "personality: одна короткая строка. "
                        + "startingItems: 2-5 конкретных предметов. "
                        + "Герой обычно подходит миру, но пользователь может попросить чужака или попаданца. "
                        + "Не добавляй современные вещи, если мир их не допускает. "
                        + "Все значения на русском. "
                        + "Обязательно полностью закрой JSON символом }.";

        String user =
                "МИР: " + worldData.getWorld().name + "\n"
                        + "ЖАНР: " + worldData.getWorld().genre + "\n"
                        + "ОПИСАНИЕ: " + limit(worldData.getWorld().description, 900) + "\n"
                        + "ПРАВИЛА: " + limit(worldData.getWorld().rules, 450) + "\n"
                        + "РАСЫ: " + races + "\n\n"
                        + "ПОЖЕЛАНИЕ:\n"
                        + safe(userRequest);

        return wrap(system, user);
    }

    public String buildSituationPrompt(
            WorldData worldData,
            CharacterData characterData,
            String userRequest
    ) {
        StringBuilder items = new StringBuilder();

        for (CharacterStartingItemEntity item : characterData.getStartingItems()) {
            if (items.length() > 0) {
                items.append("; ");
            }

            items.append(item.name);
        }

        String system =
                "Создай стартовую ситуацию для RPG. "
                        + "Верни только один валидный JSON без Markdown и пояснений. "
                        + "Поля: title, stateSummary, npcs. "
                        + "stateSummary: 4-6 коротких предложений; это стартовая сцена и текущее состояние. "
                        + "npcs: 0-3 объекта с полями name, description, stateSummary. "
                        + "NPC должны присутствовать или немедленно влиять на сцену. "
                        + "stateSummary NPC кратко хранит отношение, цель или важное состояние. "
                        + "Не решай за героя. Все значения на русском. "
                        + "Обязательно полностью закрой JSON символом }.";

        String user =
                "МИР: " + worldData.getWorld().name + "\n"
                        + "ЖАНР: " + worldData.getWorld().genre + "\n"
                        + "ПРАВИЛА: " + limit(worldData.getWorld().rules, 450) + "\n"
                        + "ГЕРОЙ: " + characterData.getCharacter().name
                        + ", " + characterData.getCharacter().race
                        + ", " + characterData.getCharacter().className + "\n"
                        + "ХАРАКТЕР: " + limit(characterData.getCharacter().personality, 350) + "\n"
                        + "ПРЕДЫСТОРИЯ: " + limit(characterData.getCharacter().background, 700) + "\n"
                        + "ВЕЩИ: " + items + "\n\n"
                        + "ПОЖЕЛАНИЕ К СТАРТУ:\n"
                        + safe(userRequest);

        return wrap(system, user);
    }

    private String wrap(
            String system,
            String user
    ) {
        return "<|turn>system\n"
                + system
                + "<turn|>\n"
                + "<|turn>user\n"
                + user
                + "<turn|>\n"
                + "<|turn>model\n";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(
            String value,
            int maxChars
    ) {
        String safeValue = safe(value);

        if (safeValue.length() <= maxChars) {
            return safeValue;
        }

        return safeValue.substring(0, maxChars).trim();
    }
}
