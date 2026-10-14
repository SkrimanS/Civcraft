package com.avrgaming.civcraft.modern.town;
import java.sql.*;
/** Completed mines contribute their current level's raw hammer bonus even while starving. */
public final class MineProductionStore {
    private MineProductionStore() {}
    public static double hammers(Connection db,long town,ConsumptionCatalog catalog)throws SQLException {
        double total=0;
        try(var s=db.prepareStatement("SELECT COALESCE(c.level,1) FROM structure_builds b LEFT JOIN building_consumption c ON c.build_id=b.id WHERE b.town_id=? AND b.structure_id='ti_mine' AND b.status='COMPLETE'")) {
            s.setLong(1,town);try(var r=s.executeQuery()){while(r.next()) {
                var level=catalog.levels("ti_mine").get(r.getInt(1));
                if(level==null)throw new SQLException("Unknown mine production level: "+r.getInt(1));
                total+=level.output();
            }}
        }
        return total;
    }
}
