package com.avrgaming.civcraft.modern.town;
import java.io.File;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TownLimitsStoreTest {
    final PlotStoreTest fixture=new PlotStoreTest();
    TownCatalog catalog(){return new TownCatalog(new File("src/main/resources/civcraft/data/town.yml"));}
    Connection database()throws SQLException {
        var db=fixture.database();
        try(var s=db.createStatement()) {
            s.executeUpdate("ALTER TABLE towns ADD COLUMN level INTEGER DEFAULT 1");s.executeUpdate("ALTER TABLE towns ADD COLUMN world TEXT DEFAULT 'world'");
            s.executeUpdate("ALTER TABLE towns ADD COLUMN x INTEGER DEFAULT 0");s.executeUpdate("ALTER TABLE towns ADD COLUMN z INTEGER DEFAULT 0");
            s.executeUpdate("ALTER TABLE town_claims ADD COLUMN claimed_by_uuid TEXT");s.executeUpdate("ALTER TABLE town_claims ADD COLUMN claimed_at INTEGER");
            s.executeUpdate("DELETE FROM town_claims");s.executeUpdate("UPDATE towns SET coins=2000");
            s.executeUpdate("CREATE TABLE culture_regions(world TEXT,chunk_x INTEGER,chunk_z INTEGER,town_id INTEGER)");
            s.executeUpdate("CREATE TABLE structure_builds(id INTEGER,town_id INTEGER,structure_id TEXT,status TEXT)");
            s.executeUpdate("CREATE TABLE structure_chunks(build_id INTEGER,world TEXT,chunk_x INTEGER,chunk_z INTEGER)");
            s.executeUpdate("INSERT INTO culture_regions VALUES('world',1,0,1),('world',2,0,1),('world',3,0,1)");
        }
        TownLimitsStore.install(db);TownLimitsStore.seed(db,0);db.commit();return db;
    }
    PlotStore.Key key(int x,int z){return new PlotStore.Key("world",x,z);}
    long number(Connection db,String sql)throws SQLException{return fixture.number(db,sql);}
    @Test void expansionUsesTownTreasuryAndRejectsDuplicateWithoutAnotherCharge()throws Exception {
        try(var db=database()) {
            assertEquals(500,TownLimitsStore.claim(db,1,fixture.mayor,key(1,0),catalog(),15,0));db.commit();
            assertEquals(1500,number(db,"SELECT coins FROM towns"));assertEquals(2000,fixture.wallet(db,fixture.mayor));
            assertThrows(IllegalArgumentException.class,()->TownLimitsStore.claim(db,1,fixture.mayor,key(1,0),catalog(),15,1));db.rollback();
            assertEquals(1500,number(db,"SELECT coins FROM towns"));
        }
    }
    @Test void requiresCardinalNeighborCivilizationCultureAndAuthority()throws Exception {
        try(var db=database()) {
            assertThrows(IllegalArgumentException.class,()->TownLimitsStore.claim(db,1,fixture.mayor,key(2,0),catalog(),15,0));db.rollback();
            assertThrows(IllegalArgumentException.class,()->TownLimitsStore.claim(db,1,fixture.mayor,key(0,1),catalog(),15,0));db.rollback();
            assertThrows(IllegalArgumentException.class,()->TownLimitsStore.claim(db,1,fixture.buyer,key(1,0),catalog(),15,0));db.rollback();
            assertEquals(2000,number(db,"SELECT coins FROM towns"));assertEquals(1,number(db,"SELECT COUNT(*) FROM town_claims"));
        }
    }
    @Test void inadequateTreasuryLimitAndForeignDistanceDoNotCreateLand()throws Exception {
        try(var db=database()) {
            db.createStatement().executeUpdate("UPDATE towns SET coins=499");db.commit();
            assertThrows(IllegalArgumentException.class,()->TownLimitsStore.claim(db,1,fixture.mayor,key(1,0),catalog(),15,0));db.rollback();
            assertEquals(499,number(db,"SELECT coins FROM towns"));
            db.createStatement().executeUpdate("UPDATE towns SET coins=2000");
            db.createStatement().executeUpdate("INSERT INTO town_claims(world,chunk_x,chunk_z,town_id,civ_id) VALUES('world',16,0,2,2)");db.commit();
            assertThrows(IllegalArgumentException.class,()->TownLimitsStore.claim(db,1,fixture.mayor,key(1,0),catalog(),15,0));db.rollback();
            assertEquals(2000,number(db,"SELECT coins FROM towns"));
        }
    }
    @Test void protectsHallLandAndAllowsPaidLandReleaseWithoutRefund()throws Exception {
        try(var db=database()) {
            assertThrows(IllegalArgumentException.class,()->TownLimitsStore.unclaim(db,1,fixture.mayor,key(0,0)));db.rollback();
            TownLimitsStore.claim(db,1,fixture.mayor,key(1,0),catalog(),15,0);db.commit();
            TownLimitsStore.unclaim(db,1,fixture.mayor,key(1,0));db.commit();
            assertEquals(1,number(db,"SELECT COUNT(*) FROM town_claims"));assertEquals(1500,number(db,"SELECT coins FROM towns"));
        }
    }
    @Test void hallFootprintIsFreeProtectedAndSeedDoesNotRepeat()throws Exception {
        try(var db=database()) {
            db.createStatement().executeUpdate("INSERT INTO structure_builds VALUES(1,1,'s_capitol','COMPLETE')");
            db.createStatement().executeUpdate("INSERT INTO structure_chunks VALUES(1,'world',0,0),(1,'world',1,0),(1,'world',0,1),(1,'world',1,1)");
            TownLimitsStore.seed(db,10);db.commit();TownLimitsStore.seed(db,11);db.commit();
            assertEquals(4,number(db,"SELECT COUNT(*) FROM town_claims"));assertEquals(4,number(db,"SELECT COUNT(*) FROM town_claims WHERE can_unclaim=0"));
            assertEquals(2000,number(db,"SELECT coins FROM towns"));
        }
    }
    @Test void exactPlotThresholdRaisesPriceAndTownCapacityStillApplies()throws Exception {
        var c=catalog();assertEquals(500,c.nextPlotCost(31));assertEquals(1000,c.nextPlotCost(32));assertEquals(2000,c.nextPlotCost(64));
        try(var db=database()) {
            for(int x=10;x<41;x++)db.createStatement().executeUpdate("INSERT INTO town_claims(world,chunk_x,chunk_z,town_id,civ_id) VALUES('world',"+x+",30,1,1)");db.commit();
            assertThrows(IllegalArgumentException.class,()->TownLimitsStore.claim(db,1,fixture.mayor,key(1,0),c,15,0));db.rollback();
            assertEquals(2000,number(db,"SELECT coins FROM towns"));
        }
    }
}
