package com.avrgaming.civcraft.modern.town;
import java.sql.*;
import java.util.*;
/** Cottages share active town granaries; a temple consumes its own group-one chests. */
public final class ConsumptionSources {
    private ConsumptionSources() {}
    public static List<ConsumptionContainers.Position> positions(Connection db,long build,long town,String type)throws SQLException {
        boolean cottage=type.equals("ti_cottage");
        if(!cottage && !type.equals("s_temple") && !type.equals("ti_mine"))throw new IllegalArgumentException("Неподдерживаемый источник еды");
        var result=new ArrayList<ConsumptionContainers.Position>();
        String owner=cottage?"b.town_id=? AND b.structure_id='s_granary'":"b.id=?";
        int group=type.equals("ti_mine")?0:1;
        try(var s=db.prepareStatement("SELECT DISTINCT c.world,c.x,c.y,c.z FROM structure_chests c JOIN structure_builds b ON b.id=c.build_id WHERE c.chest_id="+group+" AND b.status='COMPLETE' AND "+owner+" ORDER BY c.world,c.x,c.y,c.z")) {
            s.setLong(1,cottage?town:build);
            try(var r=s.executeQuery()){while(r.next())result.add(new ConsumptionContainers.Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)));}
        }
        return List.copyOf(result);
    }
}
