package com.avrgaming.civcraft.modern.wonder;
import java.sql.*;
import java.util.*;
import com.avrgaming.civcraft.modern.town.UpkeepStore;
import com.avrgaming.civcraft.modern.war.OccupationRules;
public final class NotreDameIncomeStore {
    private NotreDameIncomeStore() {}
    public static void install(Connection db)throws SQLException {try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS wonder_daily_splits(day TEXT NOT NULL,build_id INTEGER NOT NULL,town_coins INTEGER NOT NULL,civ_coins INTEGER NOT NULL,discarded INTEGER NOT NULL,PRIMARY KEY(day,build_id))");}}
    public static List<WonderIncomeStore.Credit> plan(Connection db,double rate)throws SQLException {
        record Source(long build,long town,long civ,boolean complete) {}
        var sources=new ArrayList<Source>();try(var s=db.createStatement();var r=s.executeQuery("SELECT b.id,t.id,t.civ_id,b.status='COMPLETE' FROM structure_builds b JOIN towns t ON t.id=b.town_id WHERE b.structure_id='w_notre_dame' AND b.status IN ('QUEUED','BUILDING','COMPLETE') AND "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"towns","t.id")+" AND "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"structure_builds","b.id")+" ORDER BY b.id")){while(r.next())sources.add(new Source(r.getLong(1),r.getLong(2),r.getLong(3),r.getBoolean(4)));}
        if(sources.isEmpty())return List.of();
        long towns;try(var s=db.createStatement();var r=s.executeQuery("""
            SELECT COUNT(*) FROM towns t JOIN civilizations c ON c.id=t.civ_id
            WHERE NOT EXISTS(SELECT 1 FROM administrative_civilizations a WHERE a.civ_id=c.id)
              AND NOT EXISTS(SELECT 1 FROM diplomacy_relations d WHERE d.status='WAR' AND (d.first_civ=c.id OR d.second_civ=c.id))
            """+" AND "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"towns","t.id")+" AND "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"civilizations","c.id"))){r.next();towns=r.getLong(1);}
        var result=new ArrayList<WonderIncomeStore.Credit>();for(var source:sources)result.add(new WonderIncomeStore.Credit(source.build(),source.town(),source.civ(),"w_notre_dame",towns,source.complete()?WonderEconomy.coins(rate,towns):0));return result;
    }
    public static void deposit(Connection db,String day,WonderIncomeStore.Credit credit,double capturedPenalty)throws SQLException {
        double tax;try(var s=db.prepareStatement("SELECT income_tax_rate FROM civilizations WHERE id=?")){s.setLong(1,credit.civ());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Notre Dame owner missing");tax=r.getDouble(1);}}
        var income=OccupationRules.income(credit.coins(),tax,OccupationRules.captured(db,credit.town()),capturedPenalty);
        if(income.town()>0)UpkeepStore.deposit(db,"towns",credit.town(),income.town());if(income.civilization()>0)com.avrgaming.civcraft.modern.research.CivilizationScienceStore.creditTax(db,credit.civ(),income.civilization());
        try(var s=db.prepareStatement("INSERT INTO wonder_daily_splits VALUES(?,?,?,?,?)")){s.setString(1,day);s.setLong(2,credit.build());s.setLong(3,income.town());s.setLong(4,income.civilization());s.setLong(5,income.discarded());s.executeUpdate();}
    }
}
