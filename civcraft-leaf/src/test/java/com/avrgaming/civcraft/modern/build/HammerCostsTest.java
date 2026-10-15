package com.avrgaming.civcraft.modern.build;
import java.io.File;
import java.sql.*;
import java.util.List;
import com.avrgaming.civcraft.modern.trade.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HammerCostsTest {
    final File folder=new File("src/main/resources/civcraft/data");
    final HammerCosts costs=new HammerCosts(folder);
    Connection db()throws SQLException {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");
        try(var s=db.createStatement()) {
            s.execute("CREATE TABLE towns(id INTEGER,civ_id INTEGER)");s.execute("INSERT INTO towns VALUES(1,1),(2,1),(3,2)");
            s.execute("CREATE TABLE structure_builds(id INTEGER,town_id INTEGER,structure_id TEXT,status TEXT,max_hitpoints INTEGER)");
            s.execute("INSERT INTO structure_builds VALUES(1,1,'w_mother_tree','COMPLETE',100)");
            s.execute("CREATE TABLE building_combat_health(build_id INTEGER,hitpoints INTEGER)");
        }return db;
    }
    @Test void rushFromDifferentGoodsDoesNotStackAndCombinesAdditivelyWithMotherTree()throws Exception {
        var effects=new GoodieEffects(new TradeGoodsCatalog(new File(folder,"goods.yml")),new File(folder,"buffs.yml"));
        double rush=effects.calculate(List.of("good_coffee","good_guarana")).value("buff_rush");assertEquals(.15,rush);
        try(var db=db()){assertEquals(650,costs.cost(db,2,"ti_mine",1000,rush),1e-9);assertEquals(850,costs.cost(db,3,"ti_mine",1000,rush),1e-9);}
    }
    @Test void motherTreeOnlyReducesTileImprovementsAndOnlyWhileAliveAndComplete()throws Exception {
        try(var db=db()) {
            assertEquals(800,costs.cost(db,2,"ti_mine",1000,0));assertEquals(1000,costs.cost(db,2,"s_store",1000,0));assertEquals(1000,costs.cost(db,2,"w_pyramid",1000,0));
            db.createStatement().execute("INSERT INTO building_combat_health VALUES(1,0)");assertEquals(1000,costs.cost(db,2,"ti_mine",1000,0));
            db.createStatement().execute("DELETE FROM building_combat_health");db.createStatement().execute("UPDATE structure_builds SET status='BUILDING'");assertEquals(1000,costs.cost(db,2,"ti_mine",1000,0));
        }
    }
    @Test void recalculationUsesOriginalCostSoRemovingRushRestoresItWithoutRepeatedDiscount()throws Exception {
        try(var db=db()) {
            assertEquals(850,costs.cost(db,3,"s_store",1000,.15));assertEquals(850,costs.cost(db,3,"s_store",1000,.15));assertEquals(1000,costs.cost(db,3,"s_store",1000,0));
            db.createStatement().execute("UPDATE towns SET civ_id=1 WHERE id=3");assertEquals(650,costs.cost(db,3,"ti_mine",1000,.15),1e-9);
        }
    }
    @Test void invalidReductionsCannotTurnConstructionIntoANegativeCost() {
        assertThrows(IllegalArgumentException.class,()->HammerCosts.apply(1000,.9,.2));assertThrows(IllegalArgumentException.class,()->HammerCosts.apply(Double.NaN,0,0));assertThrows(IllegalArgumentException.class,()->HammerCosts.apply(1000,-.1,0));
        assertEquals(0,HammerCosts.apply(1000,1,0));
    }
}
