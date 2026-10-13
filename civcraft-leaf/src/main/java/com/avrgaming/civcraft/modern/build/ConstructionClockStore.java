package com.avrgaming.civcraft.modern.build;
import java.sql.*;
import java.util.*;

public final class ConstructionClockStore {
    private ConstructionClockStore() {}
    private static final String KEY="construction_last_live_ms";
    public static void checkpoint(Connection db,long now)throws SQLException {
        try(var s=db.prepareStatement("INSERT INTO civcraft_meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){s.setString(1,KEY);s.setString(2,Long.toString(now));s.executeUpdate();}
    }
    public static int resume(Connection db,long now)throws SQLException {
        Long previous=null;try(var s=db.prepareStatement("SELECT value FROM civcraft_meta WHERE key=?")){s.setString(1,KEY);try(var r=s.executeQuery()){if(r.next())try{previous=Long.parseLong(r.getString(1));}catch(NumberFormatException invalid){throw new SQLException("Invalid construction checkpoint",invalid);}}}
        int count=0;
        if(previous!=null) {
            record Shift(long id,long start,long finish) {}
            var rows=new ArrayList<Shift>();
            try {
                long delta=Math.subtractExact(now,previous);
                try(var s=db.createStatement();var r=s.executeQuery("SELECT id,started_at,finish_at FROM structure_builds WHERE status IN ('QUEUED','BUILDING') AND started_at IS NOT NULL AND finish_at IS NOT NULL")) {
                    while(r.next())rows.add(new Shift(r.getLong(1),Math.addExact(r.getLong(2),delta),Math.addExact(r.getLong(3),delta)));
                }
            }catch(ArithmeticException overflow){throw new SQLException("Construction timestamp overflow",overflow);}
            try(var s=db.prepareStatement("UPDATE structure_builds SET started_at=?,finish_at=? WHERE id=?")){for(var row:rows){s.setLong(1,row.start());s.setLong(2,row.finish());s.setLong(3,row.id());s.addBatch();}s.executeBatch();}
            count=rows.size();
        }
        checkpoint(db,now);return count;
    }
}
