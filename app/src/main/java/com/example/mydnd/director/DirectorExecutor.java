package com.example.mydnd.director;

import java.util.Objects;

/**
 * The Director: parse/validate/execute boundary.
 * LLM-driven structural mutations and visible state plaques pass through this class.
 */
public final class DirectorExecutor {

    private final DirectorStore store;
    private final DirectorActionValidator validator;

    public DirectorExecutor(DirectorStore store) {
        this(store, new DirectorActionValidator());
    }

    public DirectorExecutor(
            DirectorStore store,
            DirectorActionValidator validator
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public DirectorResult reject(
            long campaignId,
            DirectorAction action,
            String code,
            String stateAfter
    ) {
        DirectorResult rejected = DirectorResult.rejected(
                action,
                code,
                stateAfter
        );
        safeLog(campaignId, action, rejected);
        return rejected;
    }

    public DirectorResult execute(long campaignId, DirectorAction action) {
        DirectorValidation validation = validator.validate(action);
        if (!validation.isValid()) {
            DirectorResult rejected = DirectorResult.rejected(
                    action,
                    validation.getCode(),
                    ""
            );
            safeLog(campaignId, action, rejected);
            return rejected;
        }

        if (action.getType() == DirectorActionType.NO_CHANGE) {
            DirectorResult noChange = DirectorResult.noChange(action);
            safeLog(campaignId, action, noChange);
            return noChange;
        }

        DirectorStoreResult storeResult;
        try {
            storeResult = store.apply(campaignId, action);
        } catch (RuntimeException error) {
            DirectorResult rejected = DirectorResult.rejected(
                    action,
                    "STORE_ERROR",
                    ""
            );
            safeLog(campaignId, action, rejected);
            return rejected;
        }

        if (storeResult == null) {
            DirectorResult rejected = DirectorResult.rejected(
                    action,
                    "STORE_RESULT_NULL",
                    ""
            );
            safeLog(campaignId, action, rejected);
            return rejected;
        }

        DirectorResult result = storeResult.isApplied()
                ? DirectorResult.applied(
                        action,
                        storeResult.getCode(),
                        storeResult.getStateAfter(),
                        storeResult.getStateChangeId()
                )
                : DirectorResult.rejected(
                        action,
                        storeResult.getCode(),
                        storeResult.getStateAfter()
                );

        safeLog(campaignId, action, result);
        return result;
    }

    private void safeLog(
            long campaignId,
            DirectorAction action,
            DirectorResult result
    ) {
        try {
            store.log(campaignId, action, result);
        } catch (RuntimeException ignored) {
            // Audit must never corrupt the already-decided game-state result.
        }
    }
}
