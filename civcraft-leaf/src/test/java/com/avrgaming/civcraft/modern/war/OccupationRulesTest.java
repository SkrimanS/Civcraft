package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OccupationRulesTest {
    @Test void captureKeepsTaxWithTheVictorAndDiscardsHalfOfNetIncome() {
        assertEquals(new OccupationRules.Income(90,10,0),OccupationRules.income(100,.1,false,.5));
        assertEquals(new OccupationRules.Income(45,10,45),OccupationRules.income(100,.1,true,.5));
        assertEquals(new OccupationRules.Income(0,100,0),OccupationRules.income(100,1,true,.5));
        assertEquals(new OccupationRules.Income(90,10,0),OccupationRules.income(100,.1,true,1));
        assertEquals(new OccupationRules.Income(0,10,90),OccupationRules.income(100,.1,true,0));
    }
    @Test void invalidRatesAndNegativeIncomeCannotMintCoins() {
        long maximum=Long.MAX_VALUE-2;
        assertEquals(new OccupationRules.Income(0,maximum,0),OccupationRules.income(maximum,1,true,.5));
        for(double rate:new double[]{-.1,1.1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->OccupationRules.income(100,.1,true,rate));
        assertThrows(IllegalArgumentException.class,()->OccupationRules.income(-1,.1,false,.5));
        assertThrows(IllegalArgumentException.class,()->OccupationRules.income(100,1.1,false,.5));
    }
    @Test void capturedTownsUseMotherGovernmentOnlyDuringVictorsAnarchy() {
        assertEquals("gov_monarchy",OccupationRules.government("gov_monarchy","gov_democracy",true));
        assertEquals("gov_democracy",OccupationRules.government("gov_anarchy","gov_democracy",true));
        assertEquals("gov_tribalism",OccupationRules.government("gov_anarchy","gov_anarchy",true));
        assertEquals("gov_anarchy",OccupationRules.government("gov_anarchy","gov_monarchy",false));
    }
    @Test void motherlandGovernmentWorksEvenWhileTheMotherCivilizationIsDormant()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:");var s=db.createStatement()) {
            s.execute("CREATE TABLE civilizations(id INTEGER,government TEXT)");s.execute("INSERT INTO civilizations VALUES(1,'gov_anarchy'),(2,'gov_monarchy')");
            s.execute("CREATE TABLE towns(id INTEGER,civ_id INTEGER)");s.execute("INSERT INTO towns VALUES(1,1)");s.execute("CREATE TABLE town_conquests(town_id INTEGER,mother_civ INTEGER)");
            assertFalse(OccupationRules.captured(db,1));assertEquals("gov_anarchy",OccupationRules.government(db,1));
            s.execute("INSERT INTO town_conquests VALUES(1,2)");assertTrue(OccupationRules.captured(db,1));assertEquals("gov_monarchy",OccupationRules.government(db,1));
            s.execute("UPDATE town_conquests SET mother_civ=NULL");assertFalse(OccupationRules.captured(db,1));assertEquals("gov_anarchy",OccupationRules.government(db,1));
        }
    }
    @Test void zeroIncomeIsHarmlessAndPositiveIncomePaysExistingDebtFirst()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:");var s=db.createStatement()) {
            s.execute("CREATE TABLE towns(id INTEGER PRIMARY KEY,coins INTEGER,debt INTEGER)");s.execute("INSERT INTO towns VALUES(1,0,30)");
            s.execute("CREATE TABLE civilizations(id INTEGER PRIMARY KEY,coins INTEGER,debt INTEGER)");s.execute("INSERT INTO civilizations VALUES(1,0,5)");
            com.avrgaming.civcraft.modern.town.UpkeepStore.install(db);
            OccupationRules.credit(db,1,1,new OccupationRules.Income(0,0,0));
            OccupationRules.credit(db,1,1,OccupationRules.income(100,.1,true,.5));
            try(var r=s.executeQuery("SELECT coins,debt FROM towns")){r.next();assertEquals(15,r.getLong(1));assertEquals(0,r.getLong(2));}
            try(var r=s.executeQuery("SELECT coins,debt FROM civilizations")){r.next();assertEquals(5,r.getLong(1));assertEquals(0,r.getLong(2));}
        }
    }
}
