package com.avrgaming.civcraft.modern.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Legacy wonder race: parallel construction, one completed winner. */
public final class WonderUniqueness {
    private WonderUniqueness() {}

    public static void install(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX IF EXISTS idx_unique_active_wonder");
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_active_wonder
                    ON structure_builds(lower(structure_id))
                    WHERE lower(structure_id) GLOB 'w_*'
                    AND lower(structure_id) NOT IN ('w_colosseum', 'w_battledome')
                    AND status IN ('COMPLETE', 'DEMOLISH_PENDING')
                    """);
        }
    }

    /** Called inside the completion transaction; a losing build receives one refund. */
    public static boolean rollbackLosingBuild(Connection db, long buildId) throws SQLException {
        try (var query = db.prepareStatement("""
                SELECT loser.town_id, loser.cost FROM structure_builds loser
                WHERE loser.id=? AND loser.status IN ('QUEUED','BUILDING')
                AND lower(loser.structure_id) GLOB 'w_*'
                AND lower(loser.structure_id) NOT IN ('w_colosseum','w_battledome')
                AND EXISTS(SELECT 1 FROM structure_builds winner
                    WHERE lower(winner.structure_id)=lower(loser.structure_id)
                    AND winner.id<>loser.id AND winner.status IN ('COMPLETE','DEMOLISH_PENDING'))
                """)) {
            query.setLong(1,buildId);
            long town,refund;
            try(var result=query.executeQuery()) {
                if(!result.next())return false;
                town=result.getLong(1);refund=result.getLong(2);
            }
            try(var update=db.prepareStatement("UPDATE structure_builds SET status='ROLLBACK_PENDING' WHERE id=?")) {
                update.setLong(1,buildId);update.executeUpdate();
            }
            try(var update=db.prepareStatement("UPDATE towns SET coins=coins+? WHERE id=?")) {
                update.setLong(1,refund);update.setLong(2,town);
                if(update.executeUpdate()!=1)throw new SQLException("Missing town for losing wonder #"+buildId);
            }
            return true;
        }
    }
}
