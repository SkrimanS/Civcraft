package com.avrgaming.civcraft.modern.trade;

import java.io.File;
import java.sql.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GoodieEffectsTest {
    @TempDir Path directory;
    final TradeGoodsCatalog goods=new TradeGoodsCatalog(new File("src/main/resources/civcraft/data/goods.yml"));
    final GoodieEffects effects=new GoodieEffects(goods,new File("src/main/resources/civcraft/data/buffs.yml"));
    @Test void distinctAndRepeatedGoodsRespectEachBuffsStackingRule() {
        var value=effects.calculate(List.of("good_pelts","good_ivory","good_pelts","good_silver","good_silver"));
        assertEquals(.45,value.value("buff_monopoly"),1e-9);
        assertEquals(.25,value.culture(),1e-9);
        assertEquals(.1,value.value("buff_barter"),1e-9);
        assertEquals(11,value.happiness());
        assertEquals(2,value.count("good_pelts"));
    }
    @Test void constructionIsFlatAndScienceAndGrowthAreAdditiveRates() {
        var value=effects.calculate(List.of("good_limestone","good_Marble","good_papyrus","good_incense","good_horses","good_herbs"));
        assertEquals(200,value.value("buff_construction"));assertEquals(.2,value.value("buff_innovation"),1e-9);
        assertEquals(.4,value.value("buff_year_of_plenty"),1e-9);assertEquals(.5,value.value("buff_barricade"));
    }
    @Test void allOriginalGoodsResolveAndEmptyTownHasNoEffects() {
        assertEquals(28,goods.list().size());
        for(var good:goods.list())assertTrue(effects.calculate(List.of(good.id())).happiness()>=1);
        assertEquals(GoodieEffects.Values.NONE,effects.calculate(List.of()));
    }
    void schema(Connection db)throws Exception {
        try(var s=db.createStatement()) {
            s.execute("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER)");s.execute("INSERT INTO towns VALUES(1,1),(2,2),(3,2)");
            s.execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,town_id INTEGER,structure_id TEXT,status TEXT)");
            s.execute("INSERT INTO structure_builds VALUES(1,1,'ti_trade_outpost','COMPLETE'),(2,2,'s_townhall','COMPLETE'),(3,3,'s_capitol','COMPLETE')");
            s.execute("CREATE TABLE goodie_frames(frame_key TEXT PRIMARY KEY,build_id INTEGER,is_source INTEGER)");
            s.execute("INSERT INTO goodie_frames VALUES('1:-1',1,1),('2:0',2,0),('3:0',3,0)");
            s.execute("CREATE TABLE bonus_goodies(origin_build INTEGER,good_id TEXT,holder_kind TEXT,holder_id TEXT,host_town INTEGER)");
            s.execute("CREATE TABLE goodie_transfers(origin_build INTEGER,status TEXT)");
            s.execute("INSERT INTO bonus_goodies VALUES(1,'good_silver','SOURCE','1',NULL)");
        }
    }
    @Test void medicineInAnyTownBenefitsItsCivilizationOnceAndFollowsTownOwnership()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);db.createStatement().execute("UPDATE bonus_goodies SET good_id='good_herbs',holder_kind='FRAME',holder_id='2:0',host_town=2");
            assertEquals(50,effects.medicine(db,2));assertEquals(0,effects.medicine(db,1));
            assertEquals(10,com.avrgaming.civcraft.modern.war.WarRespawnStore.delaySeconds(30,30,1,false,10,effects.medicine(db,2)));
            assertEquals(610,com.avrgaming.civcraft.modern.war.WarRespawnStore.delaySeconds(30,30,1,true,10,effects.medicine(db,2)));
            assertEquals(1,com.avrgaming.civcraft.modern.war.WarRespawnStore.delaySeconds(30,30,0,false,10,effects.medicine(db,2)));
            db.createStatement().execute("INSERT INTO structure_builds VALUES(4,1,'ti_trade_outpost','COMPLETE')");db.createStatement().execute("INSERT INTO bonus_goodies VALUES(4,'good_guarana','FRAME','3:0',3)");assertEquals(50,effects.medicine(db,2));
            db.createStatement().execute("UPDATE towns SET civ_id=1 WHERE id IN (2,3)");assertEquals(0,effects.medicine(db,2));assertEquals(50,effects.medicine(db,1));
            db.createStatement().execute("UPDATE bonus_goodies SET holder_kind='PLAYER'");assertEquals(0,effects.medicine(db,1));
        }
    }
    @Test void unfinishedTransferCannotKeepProvidingCityBonusesWhileItsActorIsOffline()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);db.createStatement().execute("UPDATE bonus_goodies SET holder_kind='FRAME',holder_id='2:0',host_town=2");
            assertEquals(4,effects.town(db,2).happiness());
            db.createStatement().execute("INSERT INTO goodie_transfers VALUES(1,'PREPARED')");assertEquals(GoodieEffects.Values.NONE,effects.town(db,2));
            db.createStatement().execute("UPDATE goodie_transfers SET status='COMPLETE'");assertEquals(4,effects.town(db,2).happiness());
        }
    }
    @Test void onlyMatchingCompleteHallFramesApplyAndRemovalSurvivesReopen()throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("effects.sqlite");
        try(var db=DriverManager.getConnection(url)) {
            schema(db);assertEquals(GoodieEffects.Values.NONE,effects.town(db,2));
            db.createStatement().execute("UPDATE bonus_goodies SET holder_kind='FRAME',holder_id='2:0',host_town=2");
            assertEquals(4,effects.town(db,2).happiness());assertEquals(GoodieEffects.Values.NONE,effects.town(db,1));
            db.createStatement().execute("UPDATE bonus_goodies SET holder_id='3:0'");assertEquals(GoodieEffects.Values.NONE,effects.town(db,2));
            db.createStatement().execute("UPDATE bonus_goodies SET holder_id='2:0'");
            db.createStatement().execute("UPDATE structure_builds SET status='WAR_DESTROYED' WHERE id=2");assertEquals(GoodieEffects.Values.NONE,effects.town(db,2));
            db.createStatement().execute("UPDATE structure_builds SET status='COMPLETE' WHERE id=2");
        }
        try(var db=DriverManager.getConnection(url)) {
            assertEquals(4,effects.town(db,2).happiness());
            db.createStatement().execute("UPDATE bonus_goodies SET holder_kind='PLAYER',holder_id='carrier',host_town=NULL");
            assertEquals(GoodieEffects.Values.NONE,effects.town(db,2));
            db.createStatement().execute("UPDATE bonus_goodies SET holder_kind='FRAME',holder_id='1:-1',host_town=1");
            assertEquals(GoodieEffects.Values.NONE,effects.town(db,1));
            db.createStatement().execute("UPDATE bonus_goodies SET holder_kind='FRAME',holder_id='2:0',host_town=2");
            db.createStatement().execute("UPDATE structure_builds SET status='WAR_DESTROY_PENDING' WHERE id=1");
            assertEquals(GoodieEffects.Values.NONE,effects.town(db,2));
        }
    }
}
