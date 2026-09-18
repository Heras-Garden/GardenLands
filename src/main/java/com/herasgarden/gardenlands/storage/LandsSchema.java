package com.herasgarden.gardenlands.storage;

import com.herasgarden.gardencore.api.storage.GardenStorage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class LandsSchema {
    private LandsSchema() {
    }

    public static void ensure(GardenStorage storage) throws SQLException {
        try (Connection connection = storage.connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gl_citizenships ("
                    + "player_uuid VARCHAR(36) PRIMARY KEY,"
                    + "territory_claim_uuid VARCHAR(36) NOT NULL,"
                    + "joined_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gl_citizenships_territory "
                    + "ON gl_citizenships (territory_claim_uuid, joined_at)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gl_territory_glyphs ("
                    + "territory_claim_uuid VARCHAR(36) PRIMARY KEY,"
                    + "codepoint INTEGER NOT NULL UNIQUE,"
                    + "banner_fingerprint VARCHAR(128) NULL,"
                    + "updated_at BIGINT NOT NULL)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gl_container_permissions ("
                    + "world_uuid VARCHAR(36) NOT NULL,"
                    + "x INTEGER NOT NULL,"
                    + "y INTEGER NOT NULL,"
                    + "z INTEGER NOT NULL,"
                    + "claim_uuid VARCHAR(36) NOT NULL,"
                    + "open_value VARCHAR(16) NOT NULL DEFAULT 'INHERIT',"
                    + "insert_value VARCHAR(16) NOT NULL DEFAULT 'INHERIT',"
                    + "take_value VARCHAR(16) NOT NULL DEFAULT 'INHERIT',"
                    + "break_value VARCHAR(16) NOT NULL DEFAULT 'INHERIT',"
                    + "updated_by VARCHAR(36) NOT NULL,"
                    + "updated_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (world_uuid, x, y, z))");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gl_door_permissions ("
                    + "world_uuid VARCHAR(36) NOT NULL,"
                    + "x INTEGER NOT NULL,"
                    + "y INTEGER NOT NULL,"
                    + "z INTEGER NOT NULL,"
                    + "claim_uuid VARCHAR(36) NOT NULL,"
                    + "use_value VARCHAR(16) NOT NULL DEFAULT 'INHERIT',"
                    + "break_value VARCHAR(16) NOT NULL DEFAULT 'INHERIT',"
                    + "updated_by VARCHAR(36) NOT NULL,"
                    + "updated_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (world_uuid, x, y, z))");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gl_land_purchase_offers ("
                    + "offer_uuid VARCHAR(36) PRIMARY KEY,"
                    + "target_claim_uuid VARCHAR(36) NOT NULL,"
                    + "buyer_uuid VARCHAR(36) NOT NULL,"
                    + "seller_uuid VARCHAR(36) NOT NULL,"
                    + "amount BIGINT NOT NULL,"
                    + "order_uuid VARCHAR(36) NOT NULL,"
                    + "status VARCHAR(24) NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "updated_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gl_land_offer_seller "
                    + "ON gl_land_purchase_offers (seller_uuid, status, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gl_land_offer_target "
                    + "ON gl_land_purchase_offers (target_claim_uuid, status, created_at)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gl_property_rentals ("
                    + "rental_uuid VARCHAR(36) PRIMARY KEY,"
                    + "property_uuid VARCHAR(36) NOT NULL,"
                    + "claim_uuid VARCHAR(36) NOT NULL,"
                    + "owner_uuid VARCHAR(36) NOT NULL,"
                    + "renter_uuid VARCHAR(36) NULL,"
                    + "price BIGINT NOT NULL,"
                    + "duration_minutes BIGINT NOT NULL,"
                    + "order_uuid VARCHAR(36) NULL,"
                    + "status VARCHAR(24) NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "started_at BIGINT NULL,"
                    + "expires_at BIGINT NULL,"
                    + "updated_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gl_rental_claim "
                    + "ON gl_property_rentals (claim_uuid, status, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gl_rental_renter "
                    + "ON gl_property_rentals (renter_uuid, status, expires_at)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gl_property_rental_terms ("
                    + "rental_uuid VARCHAR(36) PRIMARY KEY,"
                    + "pricing_mode VARCHAR(24) NOT NULL,"
                    + "unit_price BIGINT NOT NULL,"
                    + "unit_minutes BIGINT NOT NULL,"
                    + "max_units INTEGER NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
        }
    }
}
