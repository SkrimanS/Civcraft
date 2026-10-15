package com.avrgaming.civcraft.modern.trade;

import java.io.File;
import java.sql.*;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TradeMarkerStoreTest {
    @TempDir Path directory;
    @Test void savedPlanAndPendingMarkerSurviveReopenWithoutReplacingChoicesOrDuplicatingColumn()throws Exception {
        var catalog=new TradeGoodsCatalog(new File("src/main/resources/civcraft/data/goods.yml"));
        String url="jdbc:sqlite:"+directory.resolve("goods.sqlite");long id;
        try(var db=DriverManager.getConnection(url)) {
            db.createStatement().execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,status TEXT)");
            TradeMarkerStore.install(db);db.setAutoCommit(false);
            var chunk=new TradeGoodGeneration.Chunk(-2,3);var pick=new TradeGoodGeneration.Pick(chunk,"good_pelts","good_crabs");
            assertTrue(TradeGenerationStore.initialize(db,"world",123,Map.of(chunk,pick)));
            TradeGenerationStore.request(db,"world",-2,3);TradeGenerationStore.request(db,"world",-2,3);TradeGenerationStore.request(db,"world",0,0);
            var job=TradeMarkerStore.prepare(db,catalog,"good_crabs","world",-24,63,56,1);id=job.deposit();db.commit();
            assertEquals(4,TradeMarkerStore.positions(job).size());
        }
        try(var db=DriverManager.getConnection(url)) {
            db.setAutoCommit(false);assertFalse(TradeGenerationStore.initialize(db,"world",999,Map.of()));
            assertEquals("good_pelts",TradeGenerationStore.pick(db,"world",-2,3).land());
            assertEquals(java.util.List.of(new TradeGoodGeneration.Chunk(-2,3)),TradeGenerationStore.requests(db,"world"));
            var job=TradeMarkerStore.jobs(db,true).getFirst();assertEquals(id,job.deposit());assertEquals(1,job.face());
            assertEquals(new TradeMarkerStore.Position("world",-23,65,56),TradeMarkerStore.positions(job).getLast());
            assertNull(TradeMarkerStore.prepare(db,catalog,"good_pelts","world",-24,200,56,0));
            TradeMarkerStore.complete(db,id);db.rollback();assertEquals(1,TradeMarkerStore.jobs(db,true).size());
            TradeMarkerStore.complete(db,id);db.commit();assertTrue(TradeMarkerStore.jobs(db,true).isEmpty());
            assertEquals(job,TradeMarkerStore.completed(db,id));
        }
    }
}
