package com.example.mydnd.director;

/**
 * Boundary between the Director and the canonical persistent state (the "Room").
 * A Room-backed implementation dispatches validated actions to DAO/repositories.
 */
public interface DirectorStore {

    DirectorStoreResult apply(long campaignId, DirectorAction action);

    default void log(long campaignId, DirectorAction action, DirectorResult result) {
        // Optional audit hook. A Room implementation should persist all APPLIED and REJECTED actions.
    }
}
