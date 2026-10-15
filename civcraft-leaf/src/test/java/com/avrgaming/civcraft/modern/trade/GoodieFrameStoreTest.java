package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.build.LegacyBlock;
import java.util.*;
import java.sql.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GoodieFrameStoreTest {
    @TempDir Path directory;
    @Test void originalMarkersHaveEightHallSlotsAndEastFacingOutpostFrame() {
        var hall=new LegacyBlock(14,2,9,68,(byte)3,List.of("/itemframe","id:7","",""));
        assertEquals(new GoodieFrameMarker(7,14,2,9,"SOUTH",false),GoodieFrameMarker.parse("s_capitol",hall).orElseThrow());
        assertTrue(GoodieFrameMarker.parse("s_arrowship",hall).isEmpty());
        assertTrue(GoodieFrameMarker.parse("s_townhall",new LegacyBlock(0,0,0,68,(byte)3,List.of("/itemframe","id:8"))).isEmpty());
        var source=new LegacyBlock(1,1,3,63,(byte)12,List.of("/tradeoutpost","","",""));
        assertEquals(new GoodieFrameMarker(-1,2,2,3,"EAST",true),GoodieFrameMarker.parse("ti_fishing_boat",source).orElseThrow());
    }
    @Test void registryPersistsEntityIdentityAndRejectsOverlappingOrMovedFrames()throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("frames.sqlite");var marker=new GoodieFrameMarker(0,1,2,3,"NORTH",false);UUID entity=UUID.randomUUID();
        try(var db=DriverManager.getConnection(url)) {
            db.createStatement().execute("PRAGMA foreign_keys=ON");db.createStatement().execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY)");db.createStatement().execute("INSERT INTO structure_builds VALUES(1),(2)");
            GoodieFrameStore.install(db);
            var first=GoodieFrameStore.register(db,1,"world",100,70,200,marker);assertEquals(101,first.x());
            GoodieFrameStore.entity(db,first.key(),entity);
        }
        try(var db=DriverManager.getConnection(url)) {
            db.createStatement().execute("PRAGMA foreign_keys=ON");
            var existing=GoodieFrameStore.register(db,1,"world",100,70,200,marker);assertEquals(entity.toString(),existing.entity());assertEquals(1,GoodieFrameStore.building(db,1).size());
            assertThrows(IllegalArgumentException.class,()->GoodieFrameStore.register(db,2,"world",100,70,200,marker));
            assertThrows(IllegalArgumentException.class,()->GoodieFrameStore.register(db,1,"world",200,70,200,marker));
            db.createStatement().execute("DELETE FROM structure_builds WHERE id=1");assertTrue(GoodieFrameStore.building(db,1).isEmpty());
        }
    }
}
