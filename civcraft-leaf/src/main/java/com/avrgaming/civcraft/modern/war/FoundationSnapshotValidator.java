package com.avrgaming.civcraft.modern.war;

import java.util.*;
import java.util.function.ToIntFunction;
import org.bukkit.*;

/** Captures Bukkit state on the server thread; scans only immutable snapshots off-thread. */
public final class FoundationSnapshotValidator {
    private FoundationSnapshotValidator() {}
    public record Column(int x,int z) {}
    public record Cell(int x,int y,int z) {}
    public record Result(boolean valid,int y,long reinforcement,int columns,int depth) {
        public String message(){return valid?"Опора валидна.":"Недостаточная опора на высоте "+y+": "+reinforcement+"/"+columns+" (нужно "+Math.round(HallValidationRules.requirement(depth)*100)+"%).";}
    }
    public record Snapshot(List<Column> columns,int startY,int minY,Map<Long,ChunkSnapshot> chunks) {
        public Result validate(){return scan(columns,startY,minY,cell->HallValidationRules.reinforcement(chunks.get(key(Math.floorDiv(cell.x(),16),Math.floorDiv(cell.z(),16))).getBlockType(Math.floorMod(cell.x(),16),cell.y(),Math.floorMod(cell.z(),16)).name()));}
    }
    private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    public static Snapshot capture(World world,List<Column> columns,int originY) {
        var chunks=new HashMap<Long,ChunkSnapshot>();
        for(var column:columns){int x=Math.floorDiv(column.x(),16),z=Math.floorDiv(column.z(),16);chunks.computeIfAbsent(key(x,z),ignored->world.getChunkAt(x,z).getChunkSnapshot());}
        return new Snapshot(List.copyOf(columns),originY-1,world.getMinHeight(),Map.copyOf(chunks));
    }
    public static Result scan(List<Column> columns,int startY,int minY,ToIntFunction<Cell> reinforcement) {
        int depth=0;for(int y=startY;y>minY;y--){depth++;long value=0;for(var column:columns)value+=reinforcement.applyAsInt(new Cell(column.x(),y,column.z()));if(!HallValidationRules.layerValid(value,columns.size(),depth))return new Result(false,y,value,columns.size(),depth);}
        return new Result(true,minY,0,columns.size(),depth);
    }
}
