package com.avrgaming.civcraft.modern.build;
import java.sql.*;
import java.util.*;
public final class StructureChestStore {
    private StructureChestStore() {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS structure_chests(world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,build_id INTEGER NOT NULL,chest_id INTEGER NOT NULL,PRIMARY KEY(world,x,y,z),FOREIGN KEY(build_id) REFERENCES structure_builds(id) ON DELETE CASCADE)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS structure_chest_build_id ON structure_chests(build_id,chest_id)");
        }
    }
    public static void record(Connection db,long build,Collection<BlockPlacement> blocks)throws SQLException {
        try(var s=db.prepareStatement("INSERT OR REPLACE INTO structure_chests VALUES(?,?,?,?,?,?)")) {
            for(var block:blocks) {
                var marker=ChestMarker.parse(block.signLines()).orElse(null);
                if(marker==null || !block.material().equals("CHEST"))continue;
                s.setString(1,block.world());s.setInt(2,block.x());s.setInt(3,block.y());s.setInt(4,block.z());s.setLong(5,build);s.setInt(6,marker.id());s.addBatch();
            }
            s.executeBatch();
        }
    }
}
