package com.avrgaming.civcraft.modern.wonder;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WonderBonusesTest {
    final WonderBonuses bonuses=WonderBonuses.load(new java.io.File("src/main/resources/civcraft/data/buffs.yml"));
    Connection database()throws Exception {var db=DriverManager.getConnection("jdbc:sqlite::memory:");try(var s=db.createStatement()){s.execute("CREATE TABLE towns(id INTEGER,civ_id INTEGER)");s.execute("INSERT INTO towns VALUES(1,1),(2,1),(3,2)");s.execute("CREATE TABLE structure_builds(id INTEGER,town_id INTEGER,structure_id TEXT,status TEXT)");s.execute("INSERT INTO structure_builds VALUES(1,1,'w_greatlibrary','COMPLETE'),(2,1,'w_hanginggardens','COMPLETE'),(3,2,'w_mother_tree','COMPLETE'),(4,1,'w_pyramid','COMPLETE')");}return db;}
    @Test void configuredBonusesHaveOriginalCivilizationAndTownScopes()throws Exception {try(var db=database()){var a=bonuses.town(db,1);var b=bonuses.town(db,2);assertEquals(.1,a.science());assertEquals(.75,a.growth());assertEquals(.5,a.culture());assertEquals(.25,a.cottageFood());assertEquals(1.1,a.cottageIncome());assertEquals(.75,b.growth());assertEquals(1,b.cottageFood());assertEquals(1,b.cottageIncome());assertEquals(WonderBonuses.Values.NONE,bonuses.town(db,3));}}
    @Test void IncompleteDestroyedAndDuplicateWondersDoNotCreateExtraBonuses()throws Exception {try(var db=database()){db.createStatement().execute("INSERT INTO structure_builds VALUES(5,2,'w_greatlibrary','COMPLETE')");assertEquals(.1,bonuses.town(db,1).science());db.createStatement().execute("UPDATE structure_builds SET status='QUEUED' WHERE structure_id='w_greatlibrary'");assertEquals(0,bonuses.town(db,1).science());db.createStatement().execute("UPDATE structure_builds SET status='WAR_DESTROYED'");assertEquals(WonderBonuses.Values.NONE,bonuses.town(db,1));}}
    @Test void TownTransferImmediatelyMovesTheEffectsToTheNewOwner()throws Exception {try(var db=database()){db.createStatement().execute("UPDATE towns SET civ_id=2 WHERE id=1");assertEquals(0,bonuses.town(db,2).science());assertEquals(.5,bonuses.town(db,2).growth());assertEquals(.1,bonuses.town(db,3).science());assertEquals(.25,bonuses.town(db,3).growth());assertEquals(1,bonuses.town(db,3).cottageIncome());}}
    @Test void cottageIncomeTruncatesPyramidBeforeTaxationAsOriginalIntegerCompoundAssignments(){assertEquals(9,WonderBonuses.cottageOutput(7.4,1.1,1.3));assertEquals(10,WonderBonuses.cottageOutput(7.6,1.1,1.3));assertThrows(IllegalArgumentException.class,()->WonderBonuses.cottageOutput(Double.POSITIVE_INFINITY,1.1,1));}
    @Test void pyramidFoodFactorFeedsARealConfiguredCottageAndRetainsTheMinimumOneItem()throws Exception {
        try(var db=database()) {
            var levels=new com.avrgaming.civcraft.modern.town.ConsumptionCatalog(new java.io.File("src/main/resources/civcraft/data/structures.yml")).levels("ti_cottage");
            var level=levels.get(levels.keySet().stream().max(Integer::compare).orElseThrow());var food=new HashMap<Integer,Long>();
            double factor=bonuses.town(db,1).cottageFood();level.food().forEach((id,amount)->food.put(id,(long)com.avrgaming.civcraft.modern.town.ConsumptionRules.amount(amount,factor)));
            assertFalse(com.avrgaming.civcraft.modern.town.ConsumptionPlan.meal(level.food(),food,1,List.of()).enough());
            var meal=com.avrgaming.civcraft.modern.town.ConsumptionPlan.meal(level.food(),food,bonuses.town(db,1).cottageFood(),List.of());assertTrue(meal.enough());assertTrue(meal.remaining().isEmpty());
            assertEquals(1,com.avrgaming.civcraft.modern.town.ConsumptionRules.amount(1,bonuses.town(db,1).cottageFood()));
        }
    }
}
