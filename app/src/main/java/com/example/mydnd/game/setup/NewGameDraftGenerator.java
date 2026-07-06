package com.example.mydnd.game.setup;

import com.example.mydnd.draft.CharacterDraft;
import com.example.mydnd.draft.DraftJsonParser;
import com.example.mydnd.draft.StartingSituationDraft;
import com.example.mydnd.draft.WorldDraft;
import com.example.mydnd.llm.GenerationProfile;
import com.example.mydnd.llm.LlmCallback;
import com.example.mydnd.llm.LlmModelManager;
import com.example.mydnd.llm.ModelRole;
import com.example.mydnd.prompt.NewGamePromptBuilder;

public class NewGameDraftGenerator {

    private final LlmModelManager modelManager;
    private final NewGamePromptBuilder promptBuilder = new NewGamePromptBuilder();
    private final DraftJsonParser parser = new DraftJsonParser();

    public NewGameDraftGenerator(LlmModelManager modelManager) {
        this.modelManager = modelManager;
    }

    public void generateWorld(
            String userRequest,
            Listener<WorldDraft> listener
    ) {
        String prompt = promptBuilder.buildWorldPrompt(userRequest);

        generate(
                prompt,
                GenerationProfile.worldDraft(),
                raw -> parser.parseWorld(raw),
                listener
        );
    }

    public void generateCharacter(
            WorldData worldData,
            String userRequest,
            Listener<CharacterDraft> listener
    ) {
        String prompt = promptBuilder.buildCharacterPrompt(
                worldData,
                userRequest
        );

        generate(
                prompt,
                GenerationProfile.characterDraft(),
                raw -> parser.parseCharacter(raw),
                listener
        );
    }

    public void generateSituation(
            WorldData worldData,
            CharacterData characterData,
            String userRequest,
            Listener<StartingSituationDraft> listener
    ) {
        String prompt = promptBuilder.buildSituationPrompt(
                worldData,
                characterData,
                userRequest
        );

        generate(
                prompt,
                GenerationProfile.situationDraft(),
                raw -> parser.parseSituation(raw),
                listener
        );
    }

    private <T> void generate(
            String prompt,
            GenerationProfile profile,
            Parser<T> parser,
            Listener<T> listener
    ) {
        modelManager.generate(
                ModelRole.MASTER,
                prompt,
                profile,
                new LlmCallback() {

                    @Override
                    public void onToken(String token) {
                        // Draft UI обновляем только после полного JSON.
                    }

                    @Override
                    public void onComplete(String fullText) {
                        try {
                            listener.onSuccess(
                                    parser.parse(fullText),
                                    fullText
                            );

                        } catch (Throwable throwable) {
                            listener.onError(throwable, fullText);
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        listener.onError(throwable, "");
                    }
                }
        );
    }

    private interface Parser<T> {
        T parse(String rawText) throws Exception;
    }

    public interface Listener<T> {
        void onSuccess(T draft, String rawText);

        void onError(Throwable throwable, String rawText);
    }
}
