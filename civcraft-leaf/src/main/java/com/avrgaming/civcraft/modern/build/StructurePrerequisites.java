package com.avrgaming.civcraft.modern.build;
import java.sql.*;
public final class StructurePrerequisites {
    private StructurePrerequisites() {}
    public static void require(Connection db,long town,String required)throws SQLException {
        require(db,town,required,required);
    }
    public static void require(Connection db,long town,String required,String name)throws SQLException {
        if(required==null||required.isBlank())return;
        try(var s=db.prepareStatement("SELECT 1 FROM structure_builds b LEFT JOIN building_combat_health h ON h.build_id=b.id WHERE b.town_id=? AND b.structure_id=? AND b.status='COMPLETE' AND (b.structure_id IN ('s_capitol','s_townhall') OR COALESCE(h.hitpoints,b.max_hitpoints)>0) LIMIT 1")){s.setLong(1,town);s.setString(2,required);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Для этой постройки требуется действующее здание в вашем городе: "+name+".");}}
    }
}
