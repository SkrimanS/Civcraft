package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;

/** A FarmChunk belongs to the farm's corner chunk, rather than every chunk in its footprint. */
public final class FarmPlots {
    private FarmPlots() {}
    public record Plot(long farm,String world,int x,int z) {}
    private record Key(int x,int z) {}
    private static final int[][] OFFSETS={{-1,0},{1,0},{0,-1},{0,1},{1,1},{-1,-1},{-1,1},{1,-1}};
    public static List<Plot> adjacent(Connection db,String world,int blockX,int blockZ)throws SQLException {
        int x=Math.floorDiv(blockX,16),z=Math.floorDiv(blockZ,16);var plots=new HashMap<Key,Plot>();
        try(var s=db.prepareStatement("SELECT id,x,z FROM structure_builds WHERE structure_id='ti_farm' AND status='COMPLETE' AND world=? AND x>=? AND x<? AND z>=? AND z<? ORDER BY id")) {
            s.setString(1,world);s.setLong(2,((long)x-1)*16);s.setLong(3,((long)x+2)*16);s.setLong(4,((long)z-1)*16);s.setLong(5,((long)z+2)*16);
            try(var r=s.executeQuery()){while(r.next()){int cx=Math.floorDiv(r.getInt(2),16),cz=Math.floorDiv(r.getInt(3),16);plots.put(new Key(cx,cz),new Plot(r.getLong(1),world,cx,cz));}}
        }
        var result=new ArrayList<Plot>();for(var offset:OFFSETS){var plot=plots.get(new Key(x+offset[0],z+offset[1]));if(plot!=null)result.add(plot);}return List.copyOf(result);
    }
}
