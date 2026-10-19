package com.avrgaming.civcraft.modern.war;
import java.sql.*;
/** Pin legacy empty capital fields before ownership changes can reorder a civilization's towns. */
public final class CapitalStore {
    private CapitalStore() {}
    public static void seed(Connection db)throws SQLException {
        boolean column=false;try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info(civilizations)")){while(r.next())if(r.getString("name").equals("capital_town_id"))column=true;}
        if(!column)try(var s=db.createStatement()){s.executeUpdate("ALTER TABLE civilizations ADD COLUMN capital_town_id INTEGER");}
        String pending=com.avrgaming.civcraft.modern.foundation.FoundationCreationStore.installed(db)
                ? " AND NOT EXISTS(SELECT 1 FROM foundation_creation_plans p JOIN foundation_escrows e ON e.id=p.escrow_id WHERE p.created_civ=civilizations.id AND e.state='CREATING')" : "";
        try(var s=db.createStatement()) {
            s.executeUpdate("""
                UPDATE civilizations SET capital_town_id=COALESCE(
                    (SELECT b.town_id FROM structure_builds b JOIN towns t ON t.id=b.town_id
                     WHERE t.civ_id=civilizations.id AND b.structure_id='s_capitol'
                       AND b.status IN ('QUEUED','BUILDING','COMPLETE')
                       AND NOT EXISTS(SELECT 1 FROM town_conquests tc WHERE tc.town_id=t.id AND tc.mother_civ IS NOT NULL)
                     ORDER BY b.id LIMIT 1),
                    (SELECT MIN(t.id) FROM towns t WHERE t.civ_id=civilizations.id
                       AND NOT EXISTS(SELECT 1 FROM town_conquests tc WHERE tc.town_id=t.id AND tc.mother_civ IS NOT NULL)))
                WHERE capital_town_id IS NULL
                """+pending);
        }
    }
}
