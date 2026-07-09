package com.example.mydnd.game;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class InventoryActionHintResolverTest {

    private final InventoryActionHintResolver resolver =
            new InventoryActionHintResolver();

    @Test
    public void starredItemNotOwnedRoutesToExplicitAdd() {
        InventoryActionHintResolver.Result result = resolver.resolve(
                "Я беру с земли *камень* и кладу в карман.",
                Collections.singletonList("Фляга")
        );

        assertEquals("EXPLICIT_ADD: *камень*", result.getPromptValue());
        assertEquals("EXPLICIT_ADD", result.getReason());
    }

    @Test
    public void starredItemAlreadyOwnedRoutesToExplicitRemove() {
        InventoryActionHintResolver.Result result = resolver.resolve(
                "Я выбрасываю *камень* из инвентаря.",
                Arrays.asList("Фляга", "Камень")
        );

        assertEquals("EXPLICIT_REMOVE: *Камень*", result.getPromptValue());
        assertEquals("EXPLICIT_REMOVE", result.getReason());
    }

    @Test
    public void englishStarredItemNotOwnedRoutesToExplicitAdd() {
        InventoryActionHintResolver.Result result = resolver.resolve(
                "I pick up *bronze coin* and put it in my inventory.",
                Collections.singletonList("Flask")
        );

        assertEquals("EXPLICIT_ADD: *bronze coin*", result.getPromptValue());
        assertEquals("EXPLICIT_ADD", result.getReason());
    }

    @Test
    public void englishStarredItemAlreadyOwnedRoutesToExplicitRemove() {
        InventoryActionHintResolver.Result result = resolver.resolve(
                "I throw away *bronze coin*.",
                Arrays.asList("Flask", "bronze coin")
        );

        assertEquals("EXPLICIT_REMOVE: *bronze coin*", result.getPromptValue());
        assertEquals("EXPLICIT_REMOVE", result.getReason());
    }

    @Test
    public void germanStarredItemNotOwnedRoutesToExplicitAdd() {
        InventoryActionHintResolver.Result result = resolver.resolve(
                "Ich nehme den *Stein* und stecke ihn ein.",
                Collections.singletonList("Flasche")
        );

        assertEquals("EXPLICIT_ADD: *Stein*", result.getPromptValue());
        assertEquals("EXPLICIT_ADD", result.getReason());
    }

    @Test
    public void unstarredTextNeverProducesHint() {
        InventoryActionHintResolver.Result result = resolver.resolve(
                "Я бросаю камень в воду.",
                Arrays.asList("Фляга", "Камень")
        );

        assertEquals("NONE", result.getPromptValue());
        assertEquals("NO_STARRED_ITEM", result.getReason());
    }

    @Test
    public void ambiguousDuplicateInventoryMatchProducesNoHint() {
        InventoryActionHintResolver.Result result = resolver.resolve(
                "Я выбрасываю *камень*.",
                Arrays.asList("Камень", "Камень")
        );

        assertEquals("NONE", result.getPromptValue());
        assertEquals("AMBIGUOUS_INVENTORY_MATCH", result.getReason());
    }

    @Test
    public void multipleStarredItemsProduceNoHint() {
        InventoryActionHintResolver.Result result = resolver.resolve(
                "Я меняю *меч* на *щит*.",
                Collections.singletonList("Меч")
        );

        assertEquals("NONE", result.getPromptValue());
        assertEquals("MULTIPLE_STARRED_ITEMS", result.getReason());
    }
}
