package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.mydnd.db.entity.InventoryItemEntity;

import java.util.List;

@Dao
public interface InventoryItemDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(
            InventoryItemEntity item
    );

    @Query(
            "SELECT * FROM inventory_items "
                    + "WHERE campaign_id = :campaignId "
                    + "ORDER BY name ASC"
    )
    List<InventoryItemEntity> getItemsForCampaign(
            long campaignId
    );

    @Query(
            "SELECT * FROM inventory_items "
                    + "WHERE campaign_id = :campaignId "
                    + "AND name_key = :nameKey "
                    + "LIMIT 1"
    )
    InventoryItemEntity findByNameKey(
            long campaignId,
            String nameKey
    );

    @Query(
            "DELETE FROM inventory_items "
                    + "WHERE id = :itemId"
    )
    int deleteById(
            long itemId
    );

    @Query("DELETE FROM inventory_items WHERE campaign_id = :campaignId")
    int deleteForCampaign(long campaignId);

}
