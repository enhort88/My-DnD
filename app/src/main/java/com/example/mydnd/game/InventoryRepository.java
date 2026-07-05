package com.example.mydnd.game;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.dao.InventoryItemDao;
import com.example.mydnd.db.entity.InventoryItemEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class InventoryRepository {

    public static final String TOOL_ADD_ITEM =
            "add_item_to_inventory";

    public static final String TOOL_REMOVE_ITEM =
            "remove_item_from_inventory";

    private final InventoryItemDao inventoryItemDao;


    public InventoryRepository(
            AppDatabase database
    ) {
        inventoryItemDao =
                database.inventoryItemDao();
    }


    public List<String> getItemNames(
            long campaignId
    ) {
        List<InventoryItemEntity> entities =
                inventoryItemDao.getItemsForCampaign(
                        campaignId
                );

        if (entities == null
                || entities.isEmpty()) {

            return Collections.emptyList();
        }

        List<String> names =
                new ArrayList<>(
                        entities.size()
                );

        for (InventoryItemEntity entity : entities) {
            if (entity == null
                    || entity.name == null
                    || entity.name.trim().isEmpty()) {

                continue;
            }

            names.add(
                    entity.name.trim()
            );
        }

        return names;
    }


    public ApplyResult applyToolCall(
            long campaignId,
            String functionName,
            String itemName
    ) {
        String safeFunctionName =
                functionName == null
                        ? ""
                        : functionName.trim();

        String safeItemName =
                normalizeDisplayName(
                        itemName
                );

        if (campaignId <= 0L) {
            return ApplyResult.rejected(
                    "INVALID_CAMPAIGN",
                    safeItemName
            );
        }

        if (safeItemName.isEmpty()) {
            return ApplyResult.rejected(
                    "EMPTY_ITEM_NAME",
                    safeItemName
            );
        }

        if (TOOL_ADD_ITEM.equals(
                safeFunctionName
        )) {
            return addItem(
                    campaignId,
                    safeItemName
            );
        }

        if (TOOL_REMOVE_ITEM.equals(
                safeFunctionName
        )) {
            return removeItem(
                    campaignId,
                    safeItemName
            );
        }

        return ApplyResult.rejected(
                "UNKNOWN_TOOL",
                safeItemName
        );
    }


    private ApplyResult addItem(
            long campaignId,
            String itemName
    ) {
        String nameKey =
                normalizeNameKey(
                        itemName
                );

        InventoryItemEntity existing =
                inventoryItemDao.findByNameKey(
                        campaignId,
                        nameKey
                );

        if (existing != null) {
            return ApplyResult.rejected(
                    "ALREADY_IN_INVENTORY",
                    existing.name
            );
        }

        long now =
                System.currentTimeMillis();

        InventoryItemEntity entity =
                new InventoryItemEntity();

        entity.campaignId =
                campaignId;

        entity.name =
                itemName;

        entity.nameKey =
                nameKey;

        entity.createdAt =
                now;

        entity.updatedAt =
                now;

        long itemId =
                inventoryItemDao.insert(
                        entity
                );

        if (itemId <= 0L) {
            return ApplyResult.rejected(
                    "INSERT_REJECTED",
                    itemName
            );
        }

        return ApplyResult.applied(
                "ITEM_ADDED",
                itemName
        );
    }


    private ApplyResult removeItem(
            long campaignId,
            String itemName
    ) {
        String nameKey =
                normalizeNameKey(
                        itemName
                );

        InventoryItemEntity existing =
                inventoryItemDao.findByNameKey(
                        campaignId,
                        nameKey
                );

        if (existing == null) {
            return ApplyResult.rejected(
                    "ITEM_NOT_IN_INVENTORY",
                    itemName
            );
        }

        int deletedRows =
                inventoryItemDao.deleteById(
                        existing.id
                );

        if (deletedRows != 1) {
            return ApplyResult.rejected(
                    "DELETE_FAILED",
                    existing.name
            );
        }

        return ApplyResult.applied(
                "ITEM_REMOVED",
                existing.name
        );
    }


    private String normalizeDisplayName(
            String itemName
    ) {
        if (itemName == null) {
            return "";
        }

        return itemName
                .trim()
                .replaceAll(
                        "\\s+",
                        " "
                );
    }


    private String normalizeNameKey(
            String itemName
    ) {
        return normalizeDisplayName(
                itemName
        ).toLowerCase(
                Locale.ROOT
        );
    }


    public static class ApplyResult {

        private final boolean applied;
        private final String code;
        private final String itemName;


        private ApplyResult(
                boolean applied,
                String code,
                String itemName
        ) {
            this.applied = applied;
            this.code = code;
            this.itemName = itemName;
        }


        public static ApplyResult applied(
                String code,
                String itemName
        ) {
            return new ApplyResult(
                    true,
                    code,
                    itemName
            );
        }


        public static ApplyResult rejected(
                String code,
                String itemName
        ) {
            return new ApplyResult(
                    false,
                    code,
                    itemName
            );
        }


        public boolean isApplied() {
            return applied;
        }


        public String getCode() {
            return code;
        }


        public String getItemName() {
            return itemName;
        }
    }
}
