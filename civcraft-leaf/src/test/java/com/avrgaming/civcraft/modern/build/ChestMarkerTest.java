package com.avrgaming.civcraft.modern.build;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ChestMarkerTest {
    @Test void parsesOnlyChestCommandsAndKeepsOriginalFacingConversion() {
        assertEquals(1,ChestMarker.parse(List.of("/chest","id:1","","")).orElseThrow().id());
        assertEquals(2,ChestMarker.parse(List.of(" /CHEST "," ID: 2 ")).orElseThrow().id());
        assertTrue(ChestMarker.parse(List.of("/sign","id:1")).isEmpty());assertTrue(ChestMarker.parse(List.of("/chest","id:bad")).isEmpty());
        assertEquals("south",ChestMarker.facing((byte)0));assertEquals("west",ChestMarker.facing((byte)4));assertEquals("north",ChestMarker.facing((byte)8));assertEquals("east",ChestMarker.facing((byte)12));
    }
    @Test void readsChestMarkersWithoutLoadingAllTemplateBlocksAndRejectsBadCoordinates()throws Exception {
        var file=java.nio.file.Files.createTempFile("chest-markers-",".def");
        try {
            java.nio.file.Files.writeString(file,"2;2;2\n0:0:0,63:0,/chest,id:1,,\n1:0:0,63:8,/sign,id:2,,\n");
            assertEquals(1,new LegacyDefTemplateLoader().readChestMarkers(file).size());
            java.nio.file.Files.writeString(file,"2;2;2\n3:0:0,63:0,/chest,id:1,,\n");
            assertThrows(java.io.IOException.class,()->new LegacyDefTemplateLoader().readChestMarkers(file));
        }finally{java.nio.file.Files.deleteIfExists(file);}
    }
    @Test void recordsOnlyConvertedChestsAndCascadesMetadataWithBuildDeletion()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            db.createStatement().execute("PRAGMA foreign_keys=ON");db.createStatement().executeUpdate("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY)");db.createStatement().executeUpdate("INSERT INTO structure_builds VALUES(1)");
            StructureChestStore.install(db);db.setAutoCommit(false);
            var blocks=List.of(new BlockPlacement("world",1,2,3,"CHEST",List.of("/chest","id:1")),new BlockPlacement("world",2,2,3,"CHEST",List.of("/chest","id:1")),new BlockPlacement("world",3,2,3,"OAK_SIGN",List.of("/chest","id:2")));
            StructureChestStore.record(db,1,blocks);db.commit();StructureChestStore.record(db,1,blocks);db.commit();
            try(var r=db.createStatement().executeQuery("SELECT COUNT(*) FROM structure_chests")){r.next();assertEquals(2,r.getInt(1));}
            db.createStatement().executeUpdate("DELETE FROM structure_builds WHERE id=1");db.commit();
            try(var r=db.createStatement().executeQuery("SELECT COUNT(*) FROM structure_chests")){r.next();assertEquals(0,r.getInt(1));}
        }
    }
}
