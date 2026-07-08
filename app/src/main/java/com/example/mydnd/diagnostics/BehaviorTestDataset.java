package com.example.mydnd.diagnostics;

import com.example.mydnd.director.DirectorMode;
import com.example.mydnd.director.DirectorPromptState;
import com.example.mydnd.game.CampaignPromptState;
import com.example.mydnd.game.GameEvent;
import com.example.mydnd.memory.MemoryContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Small hand-written behaviour dataset for repeatable on-device Director checks.
 * It is deliberately independent from production campaign rows and never writes Room.
 */
public final class BehaviorTestDataset {

    private BehaviorTestDataset() {
    }

    public static CampaignPromptState campaignState() {
        return new CampaignPromptState(
                "Каменная дорога у старой заставы. Рядом стоит Страж. В стороне виден старый деревянный мост над оврагом.",
                "Небольшой мрачный фэнтезийный край с лесом, заставой и старым мостом.",
                "Тестовый герой, человек, авантюрист; HP 10/10; СИЛ 10; ЛОВ 10; ИНТ 10; ХАР 10.",
                "",
                "",
                "Страж стоит рядом и следит за дорогой."
        );
    }

    public static List<BehaviorTestCase> create() {
        List<BehaviorTestCase> cases = new ArrayList<>();
        MemoryContext emptyMemory = emptyMemory();
        DirectorPromptState base = baseState(false, false);

        cases.add(new BehaviorTestCase(
                "OBSERVE",
                DirectorMode.PLAYER_ACTION,
                "Я осматриваюсь.",
                base,
                emptyMemory,
                Collections.emptyList(),
                Collections.emptyList(),
                true
        ));

        cases.add(new BehaviorTestCase(
                "INV_ADD",
                DirectorMode.PLAYER_ACTION,
                "Я беру с земли *камень* и кладу в карман.",
                base,
                emptyMemory,
                Collections.singletonList("INV_ADD"),
                Collections.emptyList(),
                false
        ));

        cases.add(new BehaviorTestCase(
                "INV_REMOVE",
                DirectorMode.PLAYER_ACTION,
                "Я выбрасываю *камень* из инвентаря.",
                baseState(true, false),
                emptyMemory,
                Collections.singletonList("INV_REMOVE"),
                Collections.emptyList(),
                false
        ));

        cases.add(new BehaviorTestCase(
                "DIRECT_HP",
                DirectorMode.PLAYER_ACTION,
                "На меня падает тяжёлый камень и наносит мне 3 урона.",
                base,
                emptyMemory,
                Collections.singletonList("HP"),
                Collections.emptyList(),
                false
        ));

        cases.add(new BehaviorTestCase(
                "CHECK_JUMP",
                DirectorMode.PLAYER_ACTION,
                "Я пытаюсь перепрыгнуть широкую пропасть. Нужна проверка.",
                base,
                emptyMemory,
                Collections.singletonList("CHECK"),
                Collections.emptyList(),
                false
        ));

        cases.add(new BehaviorTestCase(
                "CHECK_RESULT_FALL",
                DirectorMode.CHECK_RESULT,
                "Я пытаюсь перепрыгнуть широкую пропасть.",
                base,
                failedJumpMemory(),
                Collections.singletonList("HP"),
                Collections.emptyList(),
                false
        ));

        cases.add(new BehaviorTestCase(
                "NPC_INSULT",
                DirectorMode.PLAYER_ACTION,
                "Я смотрю на Стража и говорю: «Ты трус и бесполезный лжец».",
                base,
                emptyMemory,
                Collections.emptyList(),
                Arrays.asList("NPC_MEMORY", "NPC_STATUS"),
                false
        ));

        cases.add(new BehaviorTestCase(
                "COMPLEX_TRADE",
                DirectorMode.PLAYER_ACTION,
                "Я отдаю Стражу *Меч*, получаю от него 10 монет и называю его честным человеком.",
                baseState(false, true),
                emptyMemory,
                Arrays.asList("INV_REMOVE", "MONEY"),
                Arrays.asList("NPC_MEMORY", "NPC_STATUS"),
                false
        ));

        cases.add(new BehaviorTestCase(
                "WORLD_BRIDGE",
                DirectorMode.PLAYER_ACTION,
                "Я поджигаю старый деревянный мост и жду, пока он начнет разрушаться.",
                worldState(),
                emptyMemory,
                Collections.emptyList(),
                Arrays.asList("WORLD_UPDATE", "WORLD_ADD", "EFFECT_ADD"),
                false
        ));

        return Collections.unmodifiableList(cases);
    }

    private static MemoryContext emptyMemory() {
        return new MemoryContext(
                "",
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private static MemoryContext failedJumpMemory() {
        List<GameEvent> events = new ArrayList<>();
        events.add(GameEvent.player("Я пытаюсь перепрыгнуть широкую пропасть."));
        events.add(GameEvent.system("Результат проверки ЛОВКОСТЬ: d20=3 против СЛ 15 — ПРОВАЛ."));
        return new MemoryContext("", events, Collections.emptyList());
    }

    private static DirectorPromptState baseState(boolean hasStone, boolean hasSword) {
        List<String> inventory = new ArrayList<>();
        inventory.add("Фляга");
        if (hasStone) {
            inventory.add("Камень");
        }
        if (hasSword) {
            inventory.add("Меч");
        }

        return DirectorPromptState.builder()
                .location("Старая застава")
                .health("10/10")
                .money("0")
                .inventory(inventory)
                .activeNpcs(Collections.singletonList(
                        "Страж | ACTIVE | HP 10/10 | Старая застава | Спокойно наблюдает за дорогой."
                ))
                .hint("NONE")
                .build();
    }

    private static DirectorPromptState worldState() {
        return DirectorPromptState.builder()
                .location("Старый мост")
                .health("10/10")
                .money("0")
                .inventory(Collections.singletonList("Фляга"))
                .activeNpcs(Collections.singletonList(
                        "Страж | ACTIVE | HP 10/10 | Старая застава"
                ))
                .worldEvents(Collections.singletonList(
                        "Старый деревянный мост | ACTIVE | Пересекает глубокий овраг."
                ))
                .hint("NONE")
                .build();
    }
}
