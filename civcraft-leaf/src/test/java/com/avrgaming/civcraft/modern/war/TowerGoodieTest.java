package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.io.File;
import java.util.*;
import com.avrgaming.civcraft.modern.trade.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TowerGoodieTest {
    final GoodieEffects effects=new GoodieEffects(new TradeGoodsCatalog(new File("src/main/resources/civcraft/data/goods.yml")),new File("src/main/resources/civcraft/data/buffs.yml"));
    Connection db()throws SQLException {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");
        try(var s=db.createStatement()) {
            s.execute("CREATE TABLE towns(id INTEGER,civ_id INTEGER)");s.execute("INSERT INTO towns VALUES(1,1),(2,1),(3,2)");
            s.execute("CREATE TABLE structure_builds(id INTEGER,town_id INTEGER,structure_id TEXT,status TEXT,max_hitpoints INTEGER)");
            s.execute("INSERT INTO structure_builds VALUES(1,1,'s_arrowtower','COMPLETE',100),(2,3,'ti_trade_outpost','COMPLETE',100),(3,1,'s_townhall','COMPLETE',100),(4,2,'w_chichen_itza','BUILDING',100),(5,3,'ti_trade_outpost','COMPLETE',100)");
            s.execute("CREATE TABLE goodie_frames(frame_key TEXT,build_id INTEGER,is_source INTEGER)");s.execute("INSERT INTO goodie_frames VALUES('3:0',3,0),('3:1',3,0)");
            s.execute("CREATE TABLE bonus_goodies(origin_build INTEGER,good_id TEXT,holder_kind TEXT,holder_id TEXT,host_town INTEGER)");s.execute("INSERT INTO bonus_goodies VALUES(2,'good_limestone','FRAME','3:0',1)");
            s.execute("CREATE TABLE goodie_transfers(origin_build INTEGER,status TEXT)");
        }
        TowerHealthStore.install(db);TowerHealthStore.configure(db,.5);db.setAutoCommit(false);TowerHealthStore.configureBarricade(db,effects.goodsForBuff("buff_barricade"));db.commit();return db;
    }
    @Test void allFourLegacyTowersRequireChichenBeforeBarricadeApplies()throws Exception {
        try(var db=db()) {
            assertEquals(100,TowerHealthStore.maximum(db,1,100));db.createStatement().execute("UPDATE structure_builds SET status='COMPLETE' WHERE id=4");
            for(String type:TowerHealthStore.TYPES){db.createStatement().execute("UPDATE structure_builds SET structure_id='"+type+"' WHERE id=1");assertEquals(200,TowerHealthStore.maximum(db,1,100));}
            db.createStatement().execute("UPDATE structure_builds SET structure_id='s_arrowship' WHERE id=1");assertEquals(100,TowerHealthStore.maximum(db,1,100));
        }
    }
    @Test void barricadeIsLocalAndTransferLocksAndRuinsSuppressItsEffect()throws Exception {
        try(var db=db()) {
            db.createStatement().execute("UPDATE structure_builds SET status='COMPLETE' WHERE id=4");assertEquals(200,TowerHealthStore.maximum(db,1,100));
            db.createStatement().execute("INSERT INTO goodie_transfers VALUES(2,'PREPARED')");assertEquals(150,TowerHealthStore.maximum(db,1,100));
            db.createStatement().execute("UPDATE goodie_transfers SET status='COMPLETE'");assertEquals(200,TowerHealthStore.maximum(db,1,100));
            db.createStatement().execute("UPDATE structure_builds SET status='WAR_DESTROYED' WHERE id=2");assertEquals(150,TowerHealthStore.maximum(db,1,100));
            db.createStatement().execute("UPDATE structure_builds SET status='COMPLETE' WHERE id=2");db.createStatement().execute("UPDATE bonus_goodies SET host_town=2");assertEquals(150,TowerHealthStore.maximum(db,1,100));
        }
    }
    @Test void repeatedLimestoneDoesNotStackAndLosingCivilizationWonderRemovesBothBonuses()throws Exception {
        try(var db=db()) {
            db.createStatement().execute("UPDATE structure_builds SET status='COMPLETE' WHERE id=4");db.createStatement().execute("INSERT INTO bonus_goodies VALUES(5,'good_limestone','FRAME','3:1',1)");
            assertEquals(200,TowerHealthStore.maximum(db,1,100));db.createStatement().execute("UPDATE towns SET civ_id=2 WHERE id=2");assertEquals(100,TowerHealthStore.maximum(db,1,100));
        }
    }
    @Test void oilAppliesOnceToProjectileDamageWithOriginalIntegerTruncation() {
        double bonus=effects.calculate(List.of("good_oil","good_oil")).value("buff_fire_bomb");
        assertEquals(.5,bonus);assertEquals(10,TowerCombat.arrowDamage(7,bonus));assertEquals(7,TowerCombat.arrowDamage(7,0));
        assertThrows(IllegalArgumentException.class,()->TowerCombat.arrowDamage(7,Double.NaN));assertThrows(IllegalArgumentException.class,()->TowerCombat.arrowDamage(Integer.MAX_VALUE,1));
    }
}
