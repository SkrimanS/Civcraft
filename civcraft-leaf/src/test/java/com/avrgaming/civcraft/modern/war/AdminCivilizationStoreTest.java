package com.avrgaming.civcraft.modern.war;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AdminCivilizationStoreTest {
    @Test void onlyAdministrativeActualCapitolRestrictsDistanceAndOperatorBypasses()throws Exception {
        var f=new WarCampStoreTest();try(var db=f.database()) {
            for(var column:java.util.List.of("x INTEGER","y INTEGER","z INTEGER","world TEXT DEFAULT 'world'"))db.createStatement().execute("ALTER TABLE structure_builds ADD COLUMN "+column);
            db.createStatement().execute("ALTER TABLE civilizations ADD COLUMN name TEXT");db.createStatement().execute("UPDATE civilizations SET name='AdminTest' WHERE id=2");db.createStatement().execute("ALTER TABLE civilizations ADD COLUMN capital_town_id INTEGER");db.createStatement().execute("UPDATE civilizations SET capital_town_id=2 WHERE id=2");
            db.createStatement().execute("UPDATE structure_builds SET structure_id='s_capitol',x=0,y=80,z=0 WHERE town_id=2");AdminCivilizationStore.install(db);db.commit();
            assertDoesNotThrow(()->AdminCivilizationStore.checkDistance(db,"world",0,80,0,1000,false));AdminCivilizationStore.set(db,"admintest",true);db.commit();
            assertThrows(IllegalArgumentException.class,()->AdminCivilizationStore.checkDistance(db,"world",0,80,0,1000,false));assertDoesNotThrow(()->AdminCivilizationStore.checkDistance(db,"world",0,80,0,1000,true));
            assertDoesNotThrow(()->AdminCivilizationStore.checkDistance(db,"world",1000,80,0,1000,false));assertDoesNotThrow(()->AdminCivilizationStore.checkDistance(db,"world",0,1080,0,1000,false));assertDoesNotThrow(()->AdminCivilizationStore.checkDistance(db,"other",0,80,0,1000,false));
            db.createStatement().execute("UPDATE structure_builds SET status='DEMOLISHED'");assertDoesNotThrow(()->AdminCivilizationStore.checkDistance(db,"world",0,80,0,1000,false));
            AdminCivilizationStore.set(db,"AdminTest",false);assertThrows(IllegalArgumentException.class,()->AdminCivilizationStore.set(db,"Missing",true));
        }
    }
}
