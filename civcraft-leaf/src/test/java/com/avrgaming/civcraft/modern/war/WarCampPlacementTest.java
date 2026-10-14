package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WarCampPlacementTest {
    final WarCampStoreTest fixture=new WarCampStoreTest();
    Connection database()throws Exception {
        var db=fixture.database();try(var s=db.createStatement()) {
            s.execute("ALTER TABLE towns ADD COLUMN mayor_uuid TEXT");s.execute("UPDATE towns SET mayor_uuid='"+fixture.f.owner+"' WHERE id=2");
            s.execute("CREATE TABLE town_assistants(town_id INTEGER,player_uuid TEXT)");s.execute("CREATE TABLE structure_chunks(world TEXT,chunk_x INTEGER,chunk_z INTEGER,build_id INTEGER)");
            s.execute("CREATE TABLE town_claims(world TEXT,chunk_x INTEGER,chunk_z INTEGER,town_id INTEGER,civ_id INTEGER,owner_uuid TEXT,purchase_value INTEGER DEFAULT 0)");
        }com.avrgaming.civcraft.modern.town.PlotStore.installClaims(db);db.commit();return db;
    }
    @Test void existingStructureProtectionIsCheckedInTheWholeVolumeAndCorrectWorld()throws Exception {
        try(var db=database()) {
            var origin=new WarCampPlacementRules.Origin(0,80,0,"south");assertThrows(IllegalArgumentException.class,()->WarCampPlacement.verifyRegion(db,fixture.f.owner,"world",origin,2,2,2));
            assertDoesNotThrow(()->WarCampPlacement.verifyRegion(db,fixture.f.owner,"other",origin,2,2,2));
            db.createStatement().execute("UPDATE structure_builds SET status='DEMOLISHED'");db.commit();assertDoesNotThrow(()->WarCampPlacement.verifyRegion(db,fixture.f.owner,"world",origin,2,2,2));
        }
    }
    @Test void allTouchedClaimChunksNeedDestroyPermissionWithoutOperatorShortcuts()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("INSERT INTO town_claims(world,chunk_x,chunk_z,town_id,civ_id) VALUES('world',2,2,2,2)");db.commit();var origin=new WarCampPlacementRules.Origin(32,80,32,"south");
            assertDoesNotThrow(()->WarCampPlacement.verifyRegion(db,fixture.f.owner,"world",origin,32,21,32));assertThrows(IllegalArgumentException.class,()->WarCampPlacement.verifyRegion(db,fixture.f.attacker,"world",origin,32,21,32));
            db.createStatement().execute("UPDATE town_claims SET permit_destroy=0");db.commit();assertThrows(IllegalArgumentException.class,()->WarCampPlacement.verifyRegion(db,fixture.f.owner,"world",origin,32,21,32));
            db.createStatement().execute("UPDATE town_claims SET permit_destroy=4");db.commit();assertDoesNotThrow(()->WarCampPlacement.verifyRegion(db,fixture.f.attacker,"world",origin,32,21,32));
        }
    }
    @Test void farmsAndWallsPreventOverlapEvenWhereTheirPhysicalBlockIsAir()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("UPDATE structure_builds SET structure_id='ti_farm'");db.createStatement().execute("INSERT INTO structure_chunks VALUES('world',2,2,1)");db.commit();var origin=new WarCampPlacementRules.Origin(32,80,32,"south");assertThrows(IllegalArgumentException.class,()->WarCampPlacement.verifyRegion(db,fixture.f.owner,"world",origin,32,21,32));
            db.createStatement().execute("UPDATE structure_builds SET structure_id='ti_fortifiedwall'");db.commit();assertThrows(IllegalArgumentException.class,()->WarCampPlacement.verifyRegion(db,fixture.f.owner,"world",origin,32,21,32));
        }
    }
}
